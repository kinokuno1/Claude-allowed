package ch.tunio.app.engine

import android.content.Context
import android.os.Environment
import ch.tunio.app.data.Library
import ch.tunio.app.data.Source
import ch.tunio.app.data.StreamInfo
import ch.tunio.app.data.Track
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Where audio lives on the phone.
 * - cache: songs fetched for playback (auto-trimmed, may be deleted by Android)
 * - offline: songs you chose to download; kept until you remove them
 * Playback always plays a local file, so anything that has been played once replays instantly.
 */
object AudioStore {
    private const val CACHE_LIMIT = 600L * 1024 * 1024

    lateinit var offlineDir: File
        private set
    lateinit var cacheDir: File
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val inflight = mutableMapOf<String, Deferred<File>>()
    private val limiter = Semaphore(2)

    /** Track ids saved for offline listening. */
    val offline = MutableStateFlow<Set<String>>(emptySet())
    /** Track id -> download progress 0..1 (or -1 while still looking it up). */
    val progress = MutableStateFlow<Map<String, Float>>(emptyMap())

    fun init(context: Context) {
        offlineDir = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: File(context.filesDir, "music"), "offline").apply { mkdirs() }
        cacheDir = File(context.cacheDir, "audio").apply { mkdirs() }
        offline.value = offlineDir.listFiles()?.filter { it.isFile && !it.name.contains(".part") }?.map { it.name.substringBeforeLast('.') }?.toSet().orEmpty()
    }

    fun isOffline(id: String) = id in offline.value

    fun localFile(track: Track): File? =
        Engine.findAudio(offlineDir, track.id) ?: Engine.findAudio(cacheDir, track.id)?.also { it.setLastModified(System.currentTimeMillis()) }

    /** Returns a playable local file for [track], downloading it to the cache if needed. */
    suspend fun ensure(track: Track): File {
        localFile(track)?.let { return it }
        val d = lock.withLock {
            inflight[track.id] ?: scope.async { fetch(track) }.also { job ->
                inflight[track.id] = job
                job.invokeOnCompletion { scope.launch { lock.withLock { inflight.remove(track.id) } } }
            }
        }
        return d.await()
    }

    /** The URL yt-dlp should use for [track]; Spotify songs get matched to YouTube once (and remembered). */
    private suspend fun target(track: Track): String {
        (Library.track(track.id)?.downloadTarget ?: track.downloadTarget)?.let { return it }
        if (track.source != Source.SPOTIFY) error("Nothing to download for ${track.title}")
        val match = Engine.matchOnYouTube(track).url ?: error("No YouTube match for ${track.title}")
        Library.updateTrack((Library.track(track.id) ?: track).copy(matchedUrl = match))
        return match
    }

    private val streams = java.util.concurrent.ConcurrentHashMap<String, Pair<StreamInfo, Long>>()

    /**
     * A URL to stream [track] from right away, or null if it has to be downloaded first.
     * YouTube URLs expire after ~6 h, so they are reused for 3 h at most.
     */
    suspend fun stream(track: Track): StreamInfo? {
        if (track.source == Source.TIKTOK || track.source == Source.OTHER) return null
        streams[track.id]?.let { (info, at) -> if (System.currentTimeMillis() - at < 3 * 3600_000L) return info }
        setProgress(track.id, -1f)
        try {
            val info = Engine.streamInfo(target(track)) ?: return null
            streams[track.id] = info to System.currentTimeMillis()
            return info
        } finally {
            progress.update { it - track.id }
        }
    }

    private suspend fun fetch(track: Track): File {
        setProgress(track.id, -1f)
        try {
            val target = target(track)
            val file = Engine.downloadAudio(target, cacheDir, track.id) { p -> setProgress(track.id, (p / 100f).coerceIn(0f, 1f)) }
            trimCache()
            return file
        } finally {
            progress.update { it - track.id }
        }
    }

    private fun setProgress(id: String, p: Float) = progress.update { it + (id to p) }

    /** Keeps [track] on the phone for offline listening. */
    suspend fun download(track: Track) {
        Library.remember(track)
        val f = ensure(track)
        withContext(Dispatchers.IO) {
            if (f.parentFile != offlineDir) {
                val dest = File(offlineDir, f.name)
                f.copyTo(dest, overwrite = true)
                f.delete()
            }
        }
        offline.update { it + track.id }
    }

    /** Bulk offline downloads run 2 at a time, so the song you're playing never waits behind them. */
    fun downloadAll(tracks: List<Track>) {
        tracks.filterNot { isOffline(it.id) }.forEach { t -> scope.launch { limiter.withPermit { runCatching { download(t) } } } }
    }

    fun removeDownload(track: Track) {
        offlineDir.listFiles { f -> f.name.startsWith("${track.id}.") }?.forEach { it.delete() }
        offline.update { it - track.id }
    }

    private fun trimCache() {
        val files = cacheDir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= CACHE_LIMIT) break
            total -= f.length()
            f.delete()
        }
    }

    fun clearCache() = cacheDir.listFiles()?.forEach { it.delete() }

    fun sizeOf(dir: File): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L
}
