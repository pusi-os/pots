package com.pusios.pots.data.miniapp

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import com.pusios.pots.MiniAppServerService
import org.koin.java.KoinJavaComponent.inject

actual fun createMiniAppServerController(): MiniAppServerController = AndroidMiniAppServerController()

private class AndroidMiniAppServerController : MiniAppServerController {
    private val context: Context by inject(Context::class.java)

    override fun startBackground() {
        try {
            context.startForegroundService(Intent(context, MiniAppServerService::class.java))
        } catch (_: ForegroundServiceStartNotAllowedException) {
            // Android 12+ refuses a foreground start from the background; the
            // in-process server keeps working until the user opens the app again.
        }
    }

    override fun stopBackground() {
        context.stopService(Intent(context, MiniAppServerService::class.java))
    }
}
