package ch.tunio.app.engine

import android.content.Context
import android.os.Environment
import ch.tunio.app.data.Library
import ch.tunio.app.data.Source
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

    private suspend fun fetch(track: Track): File = limiter.withPermit {
        setProgress(track.id, -1f)
        try {
            var target = track.downloadTarget
            if (target == null && track.source == Source.SPOTIFY) {
                val match = Engine.matchOnYouTube(track)
                target = match.url
                Library.updateTrack((Library.track(track.id) ?: track).copy(matchedUrl = target))
            }
            requireNotNull(target) { "Nothing to download for ${track.title}" }
            val file = Engine.downloadAudio(target, cacheDir, track.id) { p -> setProgress(track.id, (p / 100f).coerceIn(0f, 1f)) }
            trimCache()
            file
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

    fun downloadAll(tracks: List<Track>) {
        tracks.filterNot { isOffline(it.id) }.forEach { t -> scope.launch { runCatching { download(t) } } }
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
