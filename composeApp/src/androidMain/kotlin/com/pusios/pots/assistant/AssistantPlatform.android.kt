package com.pusios.pots.assistant

import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.pusios.pots.PotsAppContext

actual fun currentAssistantAvailability(): AssistantAvailability {
    val sdk = Build.VERSION.SDK_INT
    if (sdk < ASSISTANT_MIN_SDK) {
        return assistantAvailability(sdk, roleAvailable = false, roleHeld = false)
    }
    val app = PotsAppContext.application
        ?: return assistantAvailability(sdk, roleAvailable = false, roleHeld = false)
    val roles = app.getSystemService(RoleManager::class.java)
        ?: return assistantAvailability(sdk, roleAvailable = false, roleHeld = false)
    val available = runCatching { roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT) }.getOrDefault(false)
    val held = available && runCatching { roles.isRoleHeld(RoleManager.ROLE_ASSISTANT) }.getOrDefault(false)
    return assistantAvailability(sdk, available, held)
}

/**
 * The role decides: held → the assist snapshot brings the screen's text; not
 * held → the screenshot flow is the only way to look at the screen.
 */
actual fun currentScreenContextAvailability(): ScreenContextAvailability = screenContextFor(currentAssistantAvailability())

actual fun openDefaultAppsSettings() {
    val app = PotsAppContext.application ?: return
    val primary = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val fallback = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { app.startActivity(primary) }.isFailure) {
        runCatching { app.startActivity(fallback) }
    }
}
