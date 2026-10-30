package com.pusios.pots

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.pusios.pots.data.TaskScheduler
import com.pusios.pots.sandbox.sandboxModule
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class PotsApplication : Application() {
    companion object {
        lateinit var instance: PotsApplication
            private set
    }

    private val taskScheduler: TaskScheduler by inject()

    override fun onCreate() {
        super.onCreate()
        com.pusios.pots.PotsAppContext.application = this
        startKoin {
            androidContext(this@PotsApplication)
            modules(appModule, sandboxModule)
        }
        // Track app foreground state so the scheduler only pushes a heartbeat notification
        // when the in-app banner isn't visible. ViewModel lifecycle is the wrong signal —
        // it survives backgrounding and only clears on Activity destruction.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                taskScheduler.appInForeground = true
            }
            override fun onStop(owner: LifecycleOwner) {
                taskScheduler.appInForeground = false
            }
        })
    }
}
