package com.pusios.pots

import androidx.compose.runtime.Immutable

/**
 * What the sandbox's background mode looks like right now.
 *
 * Every field but [enabled] is read from the device, never remembered: the
 * battery-optimisation exemption and the notification permission can both be
 * taken away while the app runs, and a cached "granted" would then be a lie.
 */
@Immutable
data class SandboxBackgroundState(
    /** The user's toggle. The mode runs whenever it is on — warnings never gate it. */
    val enabled: Boolean = false,
    /** `PowerManager.isIgnoringBatteryOptimizations(packageName)`. */
    val batteryExempt: Boolean = false,
    /** `POST_NOTIFICATIONS` granted. Only affects whether the notification is visible. */
    val notificationsVisible: Boolean = true,
    /** False on platforms that have no background mode at all. */
    val supported: Boolean = false,
) {
    /** On exactly when the toggle is on; an OEM may still kill the process. */
    val working: Boolean get() = supported && enabled

    /** Battery optimisation can freeze or kill the sandbox — worth saying, not gating. */
    val batteryWarning: Boolean get() = working && !batteryExempt

    /** Without the notification permission the service still runs, it just cannot show status. */
    val notificationHidden: Boolean get() = working && !notificationsVisible
}

/**
 * The sandbox's background mode: the toggle, the battery-optimisation exemption,
 * and the keep-alive service behind them.
 */
interface SandboxBackgroundController {
    /**
     * Reads the live state and re-applies the user's toggle.
     *
     * Called on every return to the screen, not once at creation: that is the
     * only moment the app knows the user came back from the system settings, and
     * re-applying is also what brings the mode back after the app was closed
     * (a reboot does not, see docs/features/sandbox.md).
     */
    fun refresh(): SandboxBackgroundState

    /** Persists the toggle and starts or stops the keep-alive service. */
    fun setEnabled(enabled: Boolean)

    /** Shows the system dialog that asks to exempt the app from battery optimisation. */
    fun requestBatteryExemption()

    /** Opens the battery-optimisation list, for a refusal the dialog cannot undo. */
    fun openBatterySettings()

    /** Opens the vendor's autostart screen when it exists, else this app's system page. */
    fun openAutostartSettings()
}

/** Background mode is Android-only; every other target gets this. */
class NoOpSandboxBackgroundController : SandboxBackgroundController {
    override fun refresh(): SandboxBackgroundState = SandboxBackgroundState()

    override fun setEnabled(enabled: Boolean) {}

    override fun requestBatteryExemption() {}

    override fun openBatterySettings() {}

    override fun openAutostartSettings() {}
}

expect fun createSandboxBackgroundController(): SandboxBackgroundController
