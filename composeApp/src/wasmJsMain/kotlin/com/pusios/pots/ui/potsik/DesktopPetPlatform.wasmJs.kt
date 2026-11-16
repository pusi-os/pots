package com.pusios.pots.ui.potsik

// The browser has no window above other apps: the toggle is hidden and the
// mode is never on.

actual fun isDesktopPetSupported(): Boolean = false

actual fun canDrawDesktopPetOverlay(): Boolean = false

actual fun openDesktopPetPermissionSettings() = Unit

actual fun syncDesktopPetService() = Unit
