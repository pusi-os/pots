package com.pusios.pots

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.pusios.pots.data.TaskScheduler
import com.pusios.pots.shared.R
import org.koin.android.ext.android.inject

class DaemonService : Service() {

    companion object {
        private const val CHANNEL_ID = "pots_daemon_channel"
        private const val NOTIFICATION_ID = 9001
    }

    private val taskScheduler: TaskScheduler by inject()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val foreground = runCatching {
            // The type has to be passed explicitly from API 34; specialUse itself
            // was added in 34, so older versions get the plain call. dataSync is
            // not an option here: it is capped at six hours a day on Android 15+,
            // and the daemon is meant to be up all day.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        }.isSuccess
        if (!foreground) {
            stopSelf()
            return
        }
        // The scheduler owns its own long-lived scope; this foreground service's job is
        // to keep the app process alive so that scope keeps running. START_STICKY (below)
        // asks the OS to re-create us if we're killed, which will re-trigger onCreate and
        // call start() again — idempotent no-op if the loop is already running.
        taskScheduler.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.daemon_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.daemon_channel_description)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val builder = Notification.Builder(this, CHANNEL_ID)
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return builder
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.daemon_notification_text))
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
