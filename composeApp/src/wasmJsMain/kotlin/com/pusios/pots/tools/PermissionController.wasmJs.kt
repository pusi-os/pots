package com.pusios.pots.tools

import androidx.compose.runtime.Composable

internal actual fun platformHasPermission(permission: AppPermission): Boolean = when (permission) {
    // Not implemented on web.
    AppPermission.CALENDAR,
    AppPermission.POST_NOTIFICATIONS,
    AppPermission.READ_SMS,
    AppPermission.SEND_SMS,
    -> false

    // The browser doesn't gate local network access as an app permission.
    AppPermission.LOCAL_NETWORK -> true

    // Not implemented on the web.
    AppPermission.RECORD_AUDIO,
    AppPermission.READ_MEDIA_IMAGES,
    -> false
}

internal actual fun platformCanRequest(permission: AppPermission): Boolean = false

internal actual fun platformOpenAppSettings() {
    // No app permission settings screen on web.
}

@Composable
actual fun SetupPermissionHandler(controller: PermissionController) = Unit
