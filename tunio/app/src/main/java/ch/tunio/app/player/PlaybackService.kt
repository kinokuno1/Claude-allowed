package ch.tunio.app.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import ch.tunio.app.ui.MainActivity

/**
 * Keeps music playing in the background and shows the lock-screen / notification controls.
 * Next/previous buttons are routed to [PlayerController]'s own queue.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val intent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, QueuePlayer(PlayerController.exo))
            .setSessionActivity(intent)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!PlayerController.exo.playWhenReady) stopSelf()
    }

    override fun onDestroy() {
        session?.release()
        session = null
        super.onDestroy()
    }
}

private class QueuePlayer(player: Player) : ForwardingPlayer(player) {
    private val extra = intArrayOf(
        Player.COMMAND_SEEK_TO_NEXT,
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_PREVIOUS,
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
    )

    override fun getAvailableCommands(): Player.Commands =
        super.getAvailableCommands().buildUpon().addAll(*extra).build()

    override fun isCommandAvailable(command: Int): Boolean = command in extra || super.isCommandAvailable(command)

    override fun hasNextMediaItem(): Boolean = true
    override fun hasPreviousMediaItem(): Boolean = true
    override fun seekToNext() = PlayerController.next()
    override fun seekToNextMediaItem() = PlayerController.next()
    override fun seekToPrevious() = PlayerController.previous()
    override fun seekToPreviousMediaItem() = PlayerController.previous()
    override fun play() {
        if (PlayerController.exo.mediaItemCount == 0) PlayerController.togglePlay() else super.play()
    }
}
