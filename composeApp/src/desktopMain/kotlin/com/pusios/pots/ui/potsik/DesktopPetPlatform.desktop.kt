package com.pusios.pots.ui.potsik

// Desktop windows have no "above every other app" surface: the toggle is
// hidden and the mode is never on.

actual fun isDesktopPetSupported(): Boolean = false

actual fun canDrawDesktopPetOverlay(): Boolean = false

actual fun openDesktopPetPermissionSettings() = Unit

actual fun syncDesktopPetService() = Unit
