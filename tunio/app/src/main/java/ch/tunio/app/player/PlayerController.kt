package ch.tunio.app.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import ch.tunio.app.data.Library
import ch.tunio.app.data.Track
import ch.tunio.app.engine.AudioStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

enum class RepeatMode { OFF, ALL, ONE }

/** Owns the play queue. ExoPlayer only ever holds the current song as a local file. */
object PlayerController {
    lateinit var exo: ExoPlayer
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loadJob: Job? = null
    private var prefetchJob: Job? = null
    private var originalQueue: List<Track> = emptyList()
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
        exo = ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying.value = playing
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) next(auto = true)
            }

            override fun onPlayerError(error: PlaybackException) {
                // A cached file can be corrupt (e.g. app killed mid-download): drop it and move on.
                current?.let { t -> AudioStore.localFile(t)?.takeIf { it.parentFile == AudioStore.cacheDir }?.delete() }
                messages.tryEmit("Playback error: ${error.errorCodeName}")
                next(auto = true)
            }
        })
    }

    fun play(tracks: List<Track>, start: Int = 0, shuffled: Boolean = shuffle.value) {
        if (tracks.isEmpty()) return
        originalQueue = tracks
        shuffle.value = shuffled
        val first = tracks[start.coerceIn(tracks.indices)]
        queue.value = if (shuffled) listOf(first) + (tracks - first).shuffled() else tracks
        index.value = if (shuffled) 0 else start.coerceIn(tracks.indices)
        failuresInARow = 0
        load()
    }

    fun playNext(track: Track) {
        if (current == null) return play(listOf(track))
        val q = queue.value.toMutableList()
        q.add(index.value + 1, track)
        queue.value = q
        messages.tryEmit("Plays next: ${track.title}")
    }

    fun addToQueue(track: Track) {
        if (current == null) return play(listOf(track))
        queue.value = queue.value + track
        messages.tryEmit("Added to queue: ${track.title}")
    }

    fun removeFromQueue(i: Int) {
        if (i == index.value || i !in queue.value.indices) return
        queue.value = queue.value.toMutableList().also { it.removeAt(i) }
        if (i < index.value) index.value -= 1
    }

    fun jumpTo(i: Int) {
        if (i !in queue.value.indices) return
        index.value = i
        load()
    }

    fun togglePlay() {
        if (current == null) return
        if (preparing.value) return
        if (exo.playbackState == Player.STATE_IDLE || exo.mediaItemCount == 0) return load()
        if (exo.playbackState == Player.STATE_ENDED) exo.seekTo(0)
        if (exo.isPlaying) exo.pause() else exo.play()
    }

    fun seekTo(ms: Long) = exo.seekTo(ms)

    fun next(auto: Boolean = false) {
        val q = queue.value
        if (q.isEmpty()) return
        when {
            auto && repeat.value == RepeatMode.ONE -> { exo.seekTo(0); exo.play() }
            index.value + 1 < q.size -> { index.value += 1; load() }
            repeat.value == RepeatMode.ALL -> { index.value = 0; load() }
            auto -> { exo.pause(); exo.seekTo(0) }
            else -> Unit
        }
    }

    fun previous() {
        if (exo.currentPosition > 3000 || index.value <= 0) {
            exo.seekTo(0)
            return
        }
        index.value -= 1
        load()
    }

    fun toggleShuffle() {
        val cur = current
        val on = !shuffle.value
        shuffle.value = on
        if (cur == null) return
        if (on) {
            originalQueue = queue.value
            val rest = queue.value.drop(index.value + 1).shuffled()
            queue.value = queue.value.take(index.value + 1) + rest
        } else {
            val i = originalQueue.indexOfFirst { it.id == cur.id }
            if (i >= 0) {
                queue.value = originalQueue
                index.value = i
            }
        }
    }

    fun cycleRepeat() {
        repeat.value = when (repeat.value) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
    }

    private fun load() {
        val track = current ?: return
        val myIndex = index.value
        loadJob?.cancel()
        exo.stop()
        exo.clearMediaItems()
        preparing.value = true
        Library.addRecent(track)
        loadJob = scope.launch {
            try {
                val file = AudioStore.ensure(track)
                if (index.value != myIndex || current?.id != track.id) return@launch
                val item = MediaItem.Builder()
                    .setMediaId(track.id)
                    .setUri(Uri.fromFile(file))
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(track.title)
                            .setArtist(track.artist)
                            .setArtworkUri(track.thumbnail?.let { Uri.parse(it) })
                            .build(),
                    )
                    .build()
                exo.setMediaItem(item)
                exo.prepare()
                exo.play()
                preparing.value = false
                failuresInARow = 0
                prefetch(myIndex + 1)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                preparing.value = false
                failuresInARow++
                messages.tryEmit("Couldn't play \"${track.title}\": ${shortError(e)}")
                if (failuresInARow < 3 && index.value + 1 < queue.value.size) next()
            }
        }
    }

    /** Fetch the next song in the background so the transition is instant. */
    private fun prefetch(i: Int) {
        val t = queue.value.getOrNull(i) ?: return
        prefetchJob?.cancel()
        prefetchJob = CoroutineScope(Dispatchers.IO).launch { runCatching { AudioStore.ensure(t) } }
    }
}

fun shortError(e: Throwable): String {
    val msg = e.message ?: e.javaClass.simpleName
    val line = msg.lines().lastOrNull { it.contains("ERROR", ignoreCase = false) } ?: msg.lines().firstOrNull { it.isNotBlank() } ?: msg
    return line.removePrefix("ERROR: ").take(180)
}
