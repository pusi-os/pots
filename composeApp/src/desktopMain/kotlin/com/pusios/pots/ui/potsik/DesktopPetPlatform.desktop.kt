package com.pusios.pots.ui.potsik

// Desktop windows have no "above every other app" surface: the toggle is
// hidden and the mode is never on.

actual fun desktopPetAvailability(): DesktopPetAvailability = DesktopPetAvailability.NotOnThisPlatform

actual fun canDrawDesktopPetOverlay(): Boolean = false

actual fun openDesktopPetPermissionSettings() = Unit

actual fun syncDesktopPetService() = Unit
