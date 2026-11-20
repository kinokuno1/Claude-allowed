package ch.tunio.app.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import ch.tunio.app.data.Library
import ch.tunio.app.data.Track
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import ch.tunio.app.engine.AudioStore
import ch.tunio.app.engine.Engine
import ch.tunio.app.engine.HomeServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

enum class RepeatMode { OFF, ALL, ONE }

/** Thrown on ExoPlayer's loading thread when a song can't be fetched; never retried. */
class FetchException(message: String, cause: Throwable?) : IOException(message, cause)

/**
 * The queue lives in ExoPlayer as a normal playlist. Each item has a `tunio://track/<id>` URI that a
 * [ResolvingDataSource] turns into a local file, downloading it first if needed. That keeps ExoPlayer
 * "buffering" (not stopped) while the next song is fetched, so background playback keeps running.
 */
@UnstableApi
object PlayerController {
    lateinit var exo: ExoPlayer
        private set

    private val known = ConcurrentHashMap<String, Track>()
    private var unshuffled: List<Track>? = null
    private var failuresInARow = 0

    val queue = MutableStateFlow<List<Track>>(emptyList())
    val index = MutableStateFlow(-1)
    val preparing = MutableStateFlow(false)
    val isPlaying = MutableStateFlow(false)
    val shuffle = MutableStateFlow(false)
    val repeat = MutableStateFlow(RepeatMode.OFF)
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    val current: Track? get() = queue.value.getOrNull(index.value)

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var autoplayJob: Job? = null
    private var preloadJob: Job? = null

    /** Autoplay: when 3 or fewer songs are left and you're on Wi-Fi, add similar songs (setting, default on). */
    val autoplay = MutableStateFlow(true)

    fun setAutoplay(on: Boolean) {
        autoplay.value = on
        app.getSharedPreferences("player", 0).edit().putBoolean("autoplay", on).apply()
        if (on) onQueuePosition()
    }

    /** Wi-Fi / Ethernet (not mobile data, not a metered hotspot). */
    private fun onWifi(): Boolean {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
            (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
    }

    /**
     * Called whenever the current song changes. On Wi-Fi:
     *  - if 3 or fewer songs are left, fetch similar songs (YouTube Mix of the current one) and queue them;
     *  - preload the next 2 songs in the background so they start instantly (and survive losing Wi-Fi).
     */
    private fun onQueuePosition() {
        if (exo.mediaItemCount == 0 || !onWifi()) return
        val idx = exo.currentMediaItemIndex
        val remaining = exo.mediaItemCount - idx - 1
        val seed = current ?: return
        if (autoplay.value && remaining <= 3 && repeat.value == RepeatMode.OFF && autoplayJob?.isActive != true) {
            autoplayJob = scope.launch {
                val queued = queue.value
                val recent = Library.tracks(Library.data.value.recent.take(30))
                val similar = runCatching { Engine.similar(seed, queued + recent) }.getOrDefault(emptyList())
                    .filter { t -> queue.value.none { it.id == t.id } }
                if (similar.isNotEmpty() && exo.mediaItemCount > 0) {
                    exo.addMediaItems(similar.map(::item))
                    messages.tryEmit("Autoplay: added ${similar.size} similar songs")
                    preloadNext()
                }
            }
        }
        preloadNext()
    }

    private fun preloadNext() {
        if (preloadJob?.isActive == true) return
        val upcoming = queue.value.drop(exo.currentMediaItemIndex + 1).take(2)
            .filter { AudioStore.localFile(it) == null && !StreamCache.isFullyCached(it.id) }
        if (upcoming.isEmpty()) return
        preloadJob = scope.launch(Dispatchers.IO) {
            for (t in upcoming) runCatching { AudioStore.ensure(t) }
        }
    }

    fun init(context: Context) {
        app = context.applicationContext
        autoplay.value = app.getSharedPreferences("player", 0).getBoolean("autoplay", true)
        StreamCache.init(context)
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        val local = DefaultDataSource.Factory(context, http)
        val cached = CacheDataSource.Factory()
            .setCache(StreamCache.cache)
            .setUpstreamDataSourceFactory(http)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val router = DataSource.Factory { RouterDataSource(local.createDataSource(), cached.createDataSource()) }
        val resolving = ResolvingDataSource.Factory(router, ResolvingDataSource.Resolver { spec -> resolve(spec) })
        val noRetryForFetch = object : DefaultLoadErrorHandlingPolicy() {
            override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
                if (generateSequence(info.exception as Throwable?) { it.cause }.any { it is FetchException }) C.TIME_UNSET
                else super.getRetryDelayMsFor(info)
        }
        // Start playing after 0.5 s of audio instead of ExoPlayer's default 2.5 s.
        val quickStart = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 60_000, 500, 1_500)
            .build()
        exo = ExoPlayer.Builder(context)
            .setLoadControl(quickStart)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolving).setLoadErrorHandlingPolicy(noRetryForFetch))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying.value = playing
                if (playing) failuresInARow = 0
            }

            override fun onPlaybackStateChanged(state: Int) = refreshState()
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = refreshState()
            override fun onTimelineChanged(timeline: Timeline, reason: Int) = syncQueue()

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                syncQueue()
                current?.let { Library.addRecent(it) }
                onQueuePosition()
            }

            override fun onPlayerError(error: PlaybackException) {
                val fetch = generateSequence(error as Throwable?) { it.cause }.firstOrNull { it is FetchException }
                val t = current
                if (fetch == null && t != null && AudioStore.servedByHome.remove(t.id)) {
                    // The home server stopped answering (e.g. left home Wi-Fi): play this song with the phone instead.
                    HomeServer.markDown()
                    exo.prepare()
                    exo.play()
                    return
                }
                if (fetch == null && t != null) {
                    // Probably a broken cached file (e.g. app killed mid-download): delete it so it is fetched again.
                    AudioStore.localFile(t)?.takeIf { it.parentFile == AudioStore.cacheDir }?.delete()
                }
                messages.tryEmit("Couldn't play \"${t?.title ?: "song"}\": ${fetch?.message ?: error.errorCodeName}")
                failuresInARow++
                if (failuresInARow < 3 && exo.hasNextMediaItem()) {
                    exo.seekToNextMediaItem()
                    exo.prepare()
                    exo.play()
                }
                refreshState()
            }
        })
    }

    /** Runs on ExoPlayer's loading thread: turns tunio://track/<id> into a local file (blocking). */
    private fun resolve(spec: DataSpec): DataSpec {
        val uri = spec.uri
        if (uri.scheme != "tunio") return spec
        val id = uri.lastPathSegment ?: throw FetchException("Bad song id", null)
        val track = known[id] ?: Library.track(id) ?: throw FetchException("Unknown song", null)
        // 1. Downloaded (offline) or previously downloaded file.
        AudioStore.localFile(track)?.let { return spec.withUri(Uri.fromFile(it)) }
        // 2. Already fully streamed once: play from the stream cache, no network or yt-dlp.
        if (StreamCache.isFullyCached(track.id)) {
            return spec.buildUpon().setUri(Uri.parse("https://cached.invalid/${track.id}")).setKey(track.id).build()
        }
        try {
            // 3. Ask yt-dlp for the audio URL and start streaming immediately (cached while it plays).
            val stream = runBlocking { AudioStore.stream(track) }
            if (stream != null) {
                return spec.buildUpon()
                    .setUri(Uri.parse(stream.url))
                    .setKey(stream.cacheKey ?: track.id)
                    .setHttpRequestHeaders(stream.headers + spec.httpRequestHeaders)
                    .build()
            }
            // 4. Not streamable (TikTok, HLS-only SoundCloud): download the whole file first.
            return spec.withUri(Uri.fromFile(runBlocking { AudioStore.ensure(track) }))
        } catch (e: InterruptedException) {
            throw java.io.InterruptedIOException("Cancelled")
        } catch (e: Throwable) {
            throw FetchException(shortError(e), e)
        }
    }

    private fun refreshState() {
        preparing.value = exo.playWhenReady && exo.playbackState == Player.STATE_BUFFERING
    }

    private fun syncQueue() {
        queue.value = (0 until exo.mediaItemCount).mapNotNull { known[exo.getMediaItemAt(it).mediaId] }
        index.value = if (exo.mediaItemCount == 0) -1 else exo.currentMediaItemIndex
        refreshState()
    }

    private fun item(t: Track): MediaItem {
        known[t.id] = t
        return MediaItem.Builder()
            .setMediaId(t.id)
            .setUri(Uri.parse("tunio://track/${t.id}"))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(t.title)
                    .setArtist(t.artist)
                    .setArtworkUri(t.thumbnail?.let { Uri.parse(it) })
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .build(),
            )
            .build()
    }

    fun play(tracks: List<Track>, start: Int = 0, shuffled: Boolean = shuffle.value) {
        if (tracks.isEmpty()) return
        val s = start.coerceIn(tracks.indices)
        shuffle.value = shuffled
        val list: List<Track>
        val startIndex: Int
        if (shuffled) {
            unshuffled = tracks
            val first = tracks[s]
            list = listOf(first) + tracks.filterIndexed { i, _ -> i != s }.shuffled()
            startIndex = 0
        } else {
            unshuffled = null
            list = tracks
            startIndex = s
        }
        failuresInARow = 0
        exo.setMediaItems(list.map(::item), startIndex, 0L)
        exo.prepare()
        exo.play()
    }

    fun playNext(track: Track) {
        if (exo.mediaItemCount == 0) return play(listOf(track))
        exo.addMediaItem(exo.currentMediaItemIndex + 1, item(track))
        messages.tryEmit("Plays next: ${track.title}")
    }

    fun addToQueue(track: Track) {
        if (exo.mediaItemCount == 0) return play(listOf(track))
        exo.addMediaItem(item(track))
        messages.tryEmit("Added to queue: ${track.title}")
    }

    fun removeFromQueue(i: Int) {
        if (i == exo.currentMediaItemIndex || i !in 0 until exo.mediaItemCount) return
        exo.removeMediaItem(i)
    }

    fun jumpTo(i: Int) {
        if (i !in 0 until exo.mediaItemCount) return
        exo.seekToDefaultPosition(i)
        if (exo.playbackState == Player.STATE_IDLE) exo.prepare()
        exo.play()
    }

    fun togglePlay() {
        if (exo.mediaItemCount == 0) return
        when {
            exo.playbackState == Player.STATE_IDLE -> { exo.prepare(); exo.play() }
            exo.playbackState == Player.STATE_ENDED -> { exo.seekToDefaultPosition(0); exo.play() }
            exo.playWhenReady -> exo.pause()
            else -> exo.play()
        }
    }

    fun seekTo(ms: Long) = exo.seekTo(ms)

    fun next() {
        if (exo.hasNextMediaItem()) exo.seekToNextMediaItem()
        else if (exo.mediaItemCount > 0) exo.seekToDefaultPosition(0)
        if (exo.playbackState == Player.STATE_IDLE) exo.prepare()
        exo.play()
    }

    fun previous() {
        if (exo.currentPosition > 3000 || !exo.hasPreviousMediaItem()) exo.seekTo(0)
        else exo.seekToPreviousMediaItem()
        if (exo.playbackState == Player.STATE_IDLE) exo.prepare()
    }

    /** Shuffles the songs after the current one (or restores the original order). */
    fun toggleShuffle() {
        val on = !shuffle.value
        shuffle.value = on
        val n = exo.mediaItemCount
        if (n < 2) return
        val cur = exo.currentMediaItemIndex
        val items = (0 until n).map { exo.getMediaItemAt(it) }
        val newOrder: List<MediaItem>
        val newIndex: Int
        if (on) {
            unshuffled = items.mapNotNull { known[it.mediaId] }
            newOrder = items.take(cur + 1) + items.drop(cur + 1).shuffled()
            newIndex = cur
        } else {
            val original = unshuffled?.map(::item) ?: return
            val curId = items[cur].mediaId
            newIndex = original.indexOfFirst { it.mediaId == curId }.takeIf { it >= 0 } ?: return
            newOrder = original
        }
        // Replace everything except the playing item so playback isn't interrupted.
        exo.removeMediaItems(cur + 1, n)
        exo.removeMediaItems(0, cur)
        exo.addMediaItems(0, newOrder.subList(0, newIndex))
        exo.addMediaItems(newOrder.subList(newIndex + 1, newOrder.size))
    }

    fun cycleRepeat() {
        val next = when (repeat.value) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        repeat.value = next
        exo.repeatMode = when (next) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        }
    }
}

fun shortError(e: Throwable): String {
    val msg = e.message ?: e.javaClass.simpleName
    val line = msg.lines().lastOrNull { it.contains("ERROR") } ?: msg.lines().firstOrNull { it.isNotBlank() } ?: msg
    return line.removePrefix("ERROR: ").take(180)
}
