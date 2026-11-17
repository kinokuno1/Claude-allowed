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
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import ch.tunio.app.data.Library
import ch.tunio.app.data.Track
import ch.tunio.app.engine.AudioStore
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

    fun init(context: Context) {
        val resolving = ResolvingDataSource.Factory(DefaultDataSource.Factory(context), ResolvingDataSource.Resolver { spec -> resolve(spec) })
        val noRetryForFetch = object : DefaultLoadErrorHandlingPolicy() {
            override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
                if (generateSequence(info.exception as Throwable?) { it.cause }.any { it is FetchException }) C.TIME_UNSET
                else super.getRetryDelayMsFor(info)
        }
        exo = ExoPlayer.Builder(context)
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
            }

            override fun onPlayerError(error: PlaybackException) {
                val fetch = generateSequence(error as Throwable?) { it.cause }.firstOrNull { it is FetchException }
                val t = current
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
        val file = try {
            runBlocking { AudioStore.ensure(track) }
        } catch (e: InterruptedException) {
            throw java.io.InterruptedIOException("Cancelled")
        } catch (e: Throwable) {
            throw FetchException(shortError(e), e)
        }
        return spec.withUri(Uri.fromFile(file))
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
