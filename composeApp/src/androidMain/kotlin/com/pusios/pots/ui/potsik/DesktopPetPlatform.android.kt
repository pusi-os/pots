package com.pusios.pots.ui.potsik

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.pusios.pots.PotsAppContext
import com.pusios.pots.overlay.PotsOverlayService

/**
 * The oldest Android the mode is offered on.
 *
 * Android 13 (API 33), not 32: 12L is formally "newer than 12" but it is the
 * same branch, and the owner's rule is that a mode the old system cannot do
 * is not shimmed. One constant, so moving the floor is a one-line change.
 */
const val DESKTOP_PET_MIN_SDK = 33

/** The version the settings row names when the device is too old. */
private const val DESKTOP_PET_MIN_LABEL = "13"

actual fun desktopPetAvailability(): DesktopPetAvailability = if (Build.VERSION.SDK_INT >= DESKTOP_PET_MIN_SDK) {
    DesktopPetAvailability.Available
} else {
    DesktopPetAvailability.NeedsNewerAndroid(DESKTOP_PET_MIN_LABEL)
}

actual fun canDrawDesktopPetOverlay(): Boolean {
    val app = PotsAppContext.application ?: return false
    return runCatching { Settings.canDrawOverlays(app) }.getOrDefault(false)
}

actual fun openDesktopPetPermissionSettings() {
    ifDesktopPetAvailable(desktopPetAvailability()) {
        val app = PotsAppContext.application ?: return@ifDesktopPetAvailable
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${app.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { app.startActivity(intent) }
    }
}

actual fun syncDesktopPetService() {
    ifDesktopPetAvailable(desktopPetAvailability()) {
        PotsAppContext.application?.let { PotsOverlayService.reconcile(it) }
    }
}
