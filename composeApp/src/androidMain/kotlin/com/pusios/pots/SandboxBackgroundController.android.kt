package com.pusios.pots

import android.annotation.SuppressLint
import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.pusios.pots.data.AppSettings
import org.koin.java.KoinJavaComponent.inject

actual fun createSandboxBackgroundController(): SandboxBackgroundController = AndroidSandboxBackgroundController()

/**
 * The Android side of the sandbox's background mode.
 *
 * Nothing here is cached: [refresh] is the only read path, so an exemption the
 * user revoked in system settings shows up the moment they come back.
 */
class AndroidSandboxBackgroundController : SandboxBackgroundController {

    private val context: Context by inject(Context::class.java)
    private val appSettings: AppSettings by inject(AppSettings::class.java)

    override fun refresh(): SandboxBackgroundState {
        val enabled = appSettings.isSandboxBackgroundEnabled()
        // Re-applying on every resume is what brings the mode back after the app
        // was closed, and what recovers it after an OEM killer got to it.
        if (enabled) startService() else stopService()
        return SandboxBackgroundState(
            enabled = enabled,
            batteryExempt = isBatteryExempt(),
            notificationsVisible = areNotificationsEnabled(),
            supported = true,
        )
    }

    override fun setEnabled(enabled: Boolean) {
        appSettings.setSandboxBackgroundEnabled(enabled)
        if (enabled) startService() else stopService()
    }

    private fun startService() {
        try {
            context.startForegroundService(Intent(context, SandboxBackgroundService::class.java))
        } catch (_: ForegroundServiceStartNotAllowedException) {
            // Only allowed while the app is visible; the next resume retries.
        }
    }

    private fun stopService() {
        context.stopService(Intent(context, SandboxBackgroundService::class.java))
    }

    private fun isBatteryExempt(): Boolean = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    private fun areNotificationsEnabled(): Boolean = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    @SuppressLint("BatteryLife") // the request is deliberate, and the user can refuse it
    override fun requestBatteryExemption() {
        val request = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        )
        // Some ROMs have removed the dialog; the list still exists there.
        if (!launch(request)) openBatterySettings()
    }

    override fun openBatterySettings() {
        launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    override fun openAutostartSettings() {
        // Only the two vendors the UI names. Both are plain settings screens — the
        // app asks the user to change a setting, it does not try to survive the kill.
        val vendor = when {
            Build.MANUFACTURER.equals("xiaomi", ignoreCase = true) ->
                ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")

            Build.MANUFACTURER.equals("samsung", ignoreCase = true) ->
                ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")

            else -> null
        }
        if (vendor != null && launch(Intent().setComponent(vendor))) return
        // Fallback that exists on every ROM: this app's own system page.
        launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
    }

    private fun launch(intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}
