package ch.tunio.app.engine

import android.content.Context
import android.util.Log
import ch.tunio.app.data.Source
import ch.tunio.app.data.StreamInfo
import ch.tunio.app.data.Track
import ch.tunio.app.data.TrackList
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

sealed interface EngineStatus {
    data object Starting : EngineStatus
    data object Updating : EngineStatus
    data object Ready : EngineStatus
    data class Failed(val message: String) : EngineStatus
}

/** Thin wrapper around yt-dlp (via youtubedl-android). Every call is a separate yt-dlp process. */
object Engine {
    private const val TAG = "TunioEngine"
    private const val PREFS = "engine"
    private const val KEY_LAST_UPDATE = "last_update"
    private const val KEY_EXTRA_ARGS = "extra_args"

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var ready = CompletableDeferred<Unit>()
    private val active = AtomicInteger(0)

    val status = MutableStateFlow<EngineStatus>(EngineStatus.Starting)
    val version = MutableStateFlow<String?>(null)

    var extraArgs: String
        get() = app.getSharedPreferences(PREFS, 0).getString(KEY_EXTRA_ARGS, "").orEmpty()
        set(v) { app.getSharedPreferences(PREFS, 0).edit().putString(KEY_EXTRA_ARGS, v.trim()).apply() }

    fun start(context: Context) {
        app = context.applicationContext
        if (status.value == EngineStatus.Ready) return
        status.value = EngineStatus.Starting
        ready = CompletableDeferred()
        scope.launch {
            try {
                YoutubeDL.init(app)
                FFmpeg.init(app)
                version.value = YoutubeDL.versionName(app) ?: YoutubeDL.version(app)
                val last = app.getSharedPreferences(PREFS, 0).getLong(KEY_LAST_UPDATE, 0)
                // The yt-dlp shipped inside the APK ages fast; refresh it from GitHub once a day.
                if (System.currentTimeMillis() - last > 24L * 3600 * 1000) {
                    status.value = EngineStatus.Updating
                    withTimeoutOrNull(60_000) { runCatching { doUpdate() } }
                }
                status.value = EngineStatus.Ready
                ready.complete(Unit)
            } catch (e: Throwable) {
                Log.e(TAG, "init failed", e)
                status.value = EngineStatus.Failed(e.message ?: e.toString())
                ready.complete(Unit)
            }
        }
    }

    private fun doUpdate(): String {
        val result = YoutubeDL.updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE)
        app.getSharedPreferences(PREFS, 0).edit().putLong(KEY_LAST_UPDATE, System.currentTimeMillis()).apply()
        version.value = YoutubeDL.versionName(app) ?: YoutubeDL.version(app)
        return if (result == YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE) "yt-dlp is already up to date" else "yt-dlp updated to ${version.value}"
    }

    /** Manual update from Settings. Waits for running yt-dlp processes to finish first. */
    suspend fun update(): String = withContext(Dispatchers.IO) {
        ready.await()
        status.value = EngineStatus.Updating
        try {
            while (active.get() > 0) delay(300)
            doUpdate()
        } finally {
            status.value = EngineStatus.Ready
        }
    }

    private fun splitArgs(s: String): List<String> =
        Regex("\"([^\"]*)\"|'([^']*)'|(\\S+)").findAll(s).map { m ->
            m.groupValues[1].ifEmpty { m.groupValues[2].ifEmpty { m.groupValues[3] } }
        }.toList()

    private suspend fun run(request: YoutubeDLRequest, onProgress: ((Float) -> Unit)? = null): YoutubeDLResponse {
        ready.await()
        (status.value as? EngineStatus.Failed)?.let { throw IllegalStateException("Engine failed to start: ${it.message}") }
        while (status.value == EngineStatus.Updating) delay(300)
        // youtubedl-android disables yt-dlp's cache unless a dir is given. The cache holds YouTube's
        // pre-processed player code; without it every song re-downloads (~2 MB) and re-runs it in QuickJS.
        if (!request.hasOption("--cache-dir")) request.addOption("--cache-dir", File(app.filesDir, "ytdlp-cache").absolutePath)
        extraArgs.takeIf { it.isNotBlank() }?.let { request.addCommands(splitArgs(it)) }
        val pid = UUID.randomUUID().toString()
        val callback: (Float, Long, String) -> Unit = { progress, _, _ -> onProgress?.invoke(progress) }
        return coroutineScope {
            val job = async(Dispatchers.IO) {
                active.incrementAndGet()
                try {
                    YoutubeDL.execute(request, pid, callback)
                } finally {
                    active.decrementAndGet()
                }
            }
            try {
                job.await()
            } catch (e: CancellationException) {
                YoutubeDL.destroyProcessById(pid)
                throw e
            }
        }
    }

    suspend fun search(source: Source, query: String, count: Int = 25): List<Track> {
        val prefix = when (source) {
            Source.SOUNDCLOUD -> "scsearch$count:"
            else -> "ytsearch$count:"
        }
        val req = YoutubeDLRequest(prefix + query)
            .addOption("--flat-playlist")
            .addOption("-J")
        val out = run(req).out
        return Parsing.parseResult(out, if (source == Source.SOUNDCLOUD) Source.SOUNDCLOUD else Source.YOUTUBE).tracks
    }

    /** Turns any supported link (YouTube/SoundCloud/TikTok/Spotify, single or playlist) into tracks. */
    suspend fun resolveLink(url: String): TrackList {
        if (Spotify.isSpotify(url) || SpotifyJson.trackIds(url).isNotEmpty()) return Spotify.resolveAny(url)
        val req = YoutubeDLRequest(url)
            .addOption("--flat-playlist")
            .addOption("-J")
        val result = Parsing.parseResult(run(req).out, Parsing.sourceOf(url))
        return if (result.sourceUrl == null) result.copy(sourceUrl = url) else result
    }

    /**
     * Songs similar to [track], like Spotify's autoplay: YouTube's own "Mix" for that song
     * (https://www.youtube.com/watch?v=ID&list=RDID). Non-YouTube songs are first looked up on YouTube.
     */
    suspend fun similar(track: Track, exclude: Collection<Track>, max: Int = 10): List<Track> {
        val id = Parsing.youtubeId(track)
            ?: Parsing.youtubeId(search(Source.YOUTUBE, track.searchQuery, count = 1).firstOrNull() ?: return emptyList())
            ?: return emptyList()
        val req = YoutubeDLRequest("https://www.youtube.com/watch?v=$id&list=RD$id")
            .addOption("--flat-playlist")
            .addOption("-J")
            .addOption("--playlist-items", "1:40")
        val mix = Parsing.parseResult(run(req).out, Source.YOUTUBE).tracks
        return Parsing.pickSimilar(mix, exclude + track, max)
    }

    /** Finds the YouTube upload that best matches a Spotify track. */
    suspend fun matchOnYouTube(track: Track): Track {
        val candidates = search(Source.YOUTUBE, track.searchQuery + " audio", count = 6)
        return Matching.pick(candidates, track) ?: error("No YouTube match for ${track.searchQuery}")
    }

    /**
     * Asks yt-dlp only *where* the best audio is (no download), so playback can start right away.
     * Returns null when the audio is not a plain HTTP file (e.g. HLS-only SoundCloud tracks).
     */
    suspend fun streamInfo(target: String): StreamInfo? {
        val req = YoutubeDLRequest(target)
            .addOption("-f", "ba/b")
            .addOption("-j")
            .addOption("--no-playlist")
            .addOption("--playlist-items", "1")
        return Parsing.streamInfo(run(req).out)
    }

    /** Downloads the best audio of [target] into [dir] as `<key>.<ext>` and returns the file. */
    suspend fun downloadAudio(target: String, dir: File, key: String, onProgress: (Float) -> Unit): File {
        dir.mkdirs()
        val req = YoutubeDLRequest(target)
            .addOption("-f", "bestaudio/best")
            .addOption("-x")
            .addOption("--no-playlist")
            .addOption("--playlist-items", "1")
            .addOption("--no-mtime")
            .addOption("--no-continue")
            .addOption("-o", File(dir, "$key.%(ext)s").absolutePath)
        try {
            run(req, onProgress)
        } catch (e: Throwable) {
            cleanup(dir, key)
            throw e
        }
        return findAudio(dir, key) ?: throw IllegalStateException("yt-dlp finished but no audio file was written")
    }

    private val partialExt = setOf("part", "ytdl", "temp", "tmp", "webp", "jpg", "png", "json")

    fun findAudio(dir: File, key: String): File? =
        dir.listFiles { f -> f.isFile && f.name.startsWith("$key.") && f.extension.lowercase() !in partialExt && !f.name.contains(".part") }
            ?.maxByOrNull { it.length() }

    private fun cleanup(dir: File, key: String) {
        dir.listFiles { f -> f.name.startsWith("$key.") }?.forEach { it.delete() }
    }
}
