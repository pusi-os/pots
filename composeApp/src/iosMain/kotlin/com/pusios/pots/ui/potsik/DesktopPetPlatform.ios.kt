package com.pusios.pots.ui.potsik

// iOS has no system overlay window for a companion character: the toggle is
// hidden and the mode is never on.

actual fun isDesktopPetSupported(): Boolean = false

actual fun canDrawDesktopPetOverlay(): Boolean = false

actual fun openDesktopPetPermissionSettings() = Unit

actual fun syncDesktopPetService() = Unit
