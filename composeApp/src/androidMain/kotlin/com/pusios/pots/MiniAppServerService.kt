package com.pusios.pots

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.pusios.pots.data.miniapp.MiniAppServer
import com.pusios.pots.shared.R
import org.koin.android.ext.android.inject

/** Foreground service that keeps the loopback mini-app server alive. */
class MiniAppServerService : Service() {

    companion object {
        private const val CHANNEL_ID = "pots_mini_app_server"
        private const val NOTIFICATION_ID = 9002
    }

    private val server: MiniAppServer by inject()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) {
            stopSelf()
            return
        }
        server.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        server.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.mini_app_server_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.mini_app_server_channel_description)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.mini_app_server_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
