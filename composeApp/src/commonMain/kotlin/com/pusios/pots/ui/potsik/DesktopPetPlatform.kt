package com.pusios.pots.ui.potsik

/*
 * The platform side of the «Поверх окон» mode.
 *
 * Only Android can put a window above other apps; everywhere else the
 * toggle is hidden and the calls do nothing, so the rest of the app never
 * has to ask which platform it is on.
 */

/** Whether the platform can show the character above other apps at all. */
expect fun isDesktopPetSupported(): Boolean

/**
 * Whether the system has granted the "display over other apps" permission
 * (Android's `Settings.canDrawOverlays`). Always false where there is no
 * such permission.
 */
expect fun canDrawDesktopPetOverlay(): Boolean

/**
 * Opens the system screen where the overlay permission is granted — the
 * "Открыть настройки" button of the settings row.
 */
expect fun openDesktopPetPermissionSettings()

/**
 * Brings the desktop-pet service in line with the saved flag: starts it
 * when the mode is on and the permission is granted, stops it otherwise.
 * A no-op where there is no such service.
 */
expect fun syncDesktopPetService()
