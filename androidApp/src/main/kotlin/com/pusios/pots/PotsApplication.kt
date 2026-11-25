package com.pusios.pots

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.pusios.pots.adb.AdbMasterSwitch
import com.pusios.pots.adb.adbModule
import com.pusios.pots.data.TaskScheduler
import com.pusios.pots.overlay.PotsAppForeground
import com.pusios.pots.overlay.PotsOverlayService
import com.pusios.pots.sandbox.sandboxModule
import com.pusios.pots.shared.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class PotsApplication : Application() {
    companion object {
        lateinit var instance: PotsApplication
            private set
    }

    private val taskScheduler: TaskScheduler by inject()

    private val adbMasterSwitch: AdbMasterSwitch by inject()

    override fun onCreate() {
        super.onCreate()
        com.pusios.pots.PotsAppContext.application = this
        startKoin {
            androidContext(this@PotsApplication)
            modules(appModule, sandboxModule, adbModule)
        }
        // The pet's notification channel is made here, up front, never inside
        // onStartCommand: a channel built late is exactly how a foreground
        // service gets killed with ForegroundServiceDidNotStartInTime.
        ensureOverlayChannel()
        // Switching the ADB tools off must also drop the sockets they opened; the tools
        // themselves vanish from the agent's set on the next request.
        adbMasterSwitch.start(CoroutineScope(SupervisorJob() + Dispatchers.Default))
        // Track app foreground state so the scheduler only pushes a heartbeat notification
        // when the in-app banner isn't visible. ViewModel lifecycle is the wrong signal —
        // it survives backgrounding and only clears on Activity destruction.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                taskScheduler.appInForeground = true
                // The PixelTag rule: the pet window exists only while the app
                // is away. onStart is also the one place a foreground service
                // may legally be started (Android 12+ forbids it from the
                // background), so the reconcile that can start the pet's
                // service lives here, and the flip below only shows/hides.
                PotsAppForeground.set(true)
                PotsOverlayService.onAppForeground()
                PotsOverlayService.reconcile(this@PotsApplication)
            }
            override fun onStop(owner: LifecycleOwner) {
                taskScheduler.appInForeground = false
                PotsAppForeground.set(false)
                PotsOverlayService.onAppForeground()
            }
        })
    }

    private fun ensureOverlayChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                PotsOverlayService.CHANNEL,
                getString(R.string.potsik_overlay_channel_name),
                PotsOverlayService.CHANNEL_IMPORTANCE,
            ),
        )
    }
}
