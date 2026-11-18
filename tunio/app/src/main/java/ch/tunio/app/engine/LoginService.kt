package ch.tunio.app.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Runs only while you log in to Spotify. Android (Samsung especially) freezes apps in the background, so while
 * the browser was in front Tunio couldn't answer Spotify's redirect to 127.0.0.1. A foreground service with a
 * small notification keeps Tunio awake until the login finishes, then it stops itself.
 */
class LoginService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Spotify login", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("Waiting for Spotify login…")
            .setContentText("Finish logging in in the browser, then come back to Tunio.")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "spotify_login"
        private const val NOTIFICATION_ID = 7

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, LoginService::class.java))

        fun stop(context: Context) {
            context.stopService(Intent(context, LoginService::class.java))
        }
    }
}
