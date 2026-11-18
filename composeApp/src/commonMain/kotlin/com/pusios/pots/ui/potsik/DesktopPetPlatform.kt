package com.pusios.pots.ui.potsik

/*
 * The platform side of the «Поверх окон» mode.
 *
 * Only Android can put a window above other apps; everywhere else the
 * toggle is hidden and the calls do nothing, so the rest of the app never
 * has to ask which platform it is on.
 */

/**
 * What the platform can do about a window above other apps.
 *
 * Two different questions, deliberately kept apart: whether the platform has
 * such a window at all (iOS, desktop and the web do not — the row is hidden
 * there, as it always was), and whether the *version* of Android is new
 * enough. The owner's rule for Android: a feature the old system cannot do
 * is not shimmed — the row stays visible, disabled, and says from which
 * version the mode works.
 */
sealed class DesktopPetAvailability {

    /** Everything is there: the toggle is live. */
    data object Available : DesktopPetAvailability()

    /** No such window on this platform — the row is not shown at all. */
    data object NotOnThisPlatform : DesktopPetAvailability()

    /** Android, but older than the minimum — the row is shown, disabled, with the reason. */
    data class NeedsNewerAndroid(val minimumOsLabel: String) : DesktopPetAvailability()
}

/** What this platform, on this device, offers. The single source of truth. */
expect fun desktopPetAvailability(): DesktopPetAvailability

/**
 * The settings row as it should be drawn.
 *
 * Pure, so the three branches are testable without an Android device: hidden
 * where there is no overlay at all, visible but dead on an Android too old
 * for it, live only when both the platform and the system agree.
 *
 * [checked] is the *effective* state, not the saved wish: with the mode on
 * but the permission missing the switch shows off, and the note next to it
 * explains why. Showing it on would claim a character is standing on the
 * desktop when none is.
 */
data class DesktopPetRow(
    val visible: Boolean,
    val enabled: Boolean,
    val checked: Boolean,
    /** Non-null when the row is disabled because the OS is too old. */
    val minimumOsLabel: String? = null,
)

/** The row for what the platform offers and what the user last asked for. */
fun desktopPetRow(
    availability: DesktopPetAvailability,
    savedEnabled: Boolean,
    permissionGranted: Boolean,
): DesktopPetRow = when (availability) {
    DesktopPetAvailability.NotOnThisPlatform -> DesktopPetRow(visible = false, enabled = false, checked = false)

    is DesktopPetAvailability.NeedsNewerAndroid -> DesktopPetRow(
        visible = true,
        enabled = false,
        checked = false,
        minimumOsLabel = availability.minimumOsLabel,
    )

    DesktopPetAvailability.Available -> DesktopPetRow(
        visible = true,
        enabled = true,
        checked = savedEnabled && permissionGranted,
    )
}

/**
 * Runs an overlay action only where the overlay actually exists.
 *
 * The row is already disabled on an Android too old for the mode, so this is
 * the belt to its braces: no call to the system's permission screen and no
 * attempt to start the service on a system that cannot show the window.
 */
inline fun ifDesktopPetAvailable(availability: DesktopPetAvailability, action: () -> Unit) {
    if (availability == DesktopPetAvailability.Available) action()
}

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
