package com.pusios.pots

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import com.pusios.pots.data.AppSettings
import com.pusios.pots.shared.R
import org.koin.android.ext.android.inject

/**
 * Keeps the app process alive while the sandbox has to work in the background.
 *
 * Its own service rather than [DaemonService]: turning the sandbox off must not
 * stop the task daemon, and stopping the daemon must not take the sandbox with
 * it. The type is `specialUse` (see the manifest) because this is a keep-alive
 * for a local Linux sandbox, not a sync — `dataSync` is capped at six hours a
 * day on Android 15+ and would end the session mid-day.
 */
class SandboxBackgroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "pots_sandbox_background"
        private const val NOTIFICATION_ID = 9003

        /** The notification's only action: end the mode and remember that it ended. */
        const val ACTION_STOP = "com.pusios.pots.action.STOP_SANDBOX_BACKGROUND"
    }

    private val appSettings: AppSettings by inject()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // The toggle is the user's, so it is what gets flipped: the card then
            // reads "off" instead of promising a mode the user just stopped.
            appSettings.setSandboxBackgroundEnabled(false)
            stopSelf()
            return START_NOT_STICKY
        }

        val foreground = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                // specialUse arrived in API 34; below it a plain foreground service
                // has no type to declare and nothing caps its run time.
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        }.isSuccess
        if (!foreground) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Sticky so a system kill during the day brings the keep-alive back; a
        // reboot does not (see docs/features/sandbox.md — the mode comes back the
        // next time the app is opened, which is when the card re-applies it).
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.sandbox_background_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.sandbox_background_channel_description)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val contentIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingContent = PendingIntent.getActivity(
            this,
            0,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = Intent(this, SandboxBackgroundService::class.java).setAction(ACTION_STOP)
        val pendingStop = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.sandbox_background_notification_text))
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentIntent(pendingContent)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.stat_notify_sync_noanim),
                    getString(R.string.sandbox_background_stop),
                    pendingStop,
                ).build(),
            )
            .setOngoing(true)
            .build()
    }
}
