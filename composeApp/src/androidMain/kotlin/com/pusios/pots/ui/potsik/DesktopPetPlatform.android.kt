package com.pusios.pots.ui.potsik

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.pusios.pots.PotsAppContext
import com.pusios.pots.overlay.PotsOverlayService

actual fun isDesktopPetSupported(): Boolean = true

actual fun canDrawDesktopPetOverlay(): Boolean {
    val app = PotsAppContext.application ?: return false
    return runCatching { Settings.canDrawOverlays(app) }.getOrDefault(false)
}

actual fun openDesktopPetPermissionSettings() {
    val app = PotsAppContext.application ?: return
    val intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${app.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { app.startActivity(intent) }
}

actual fun syncDesktopPetService() {
    PotsAppContext.application?.let { PotsOverlayService.reconcile(it) }
}
