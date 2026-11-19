package com.pusios.pots

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The background mode's rules, exercised through a fake device: the toggle is
 * the only gate, and both things Android can take away are reported honestly on
 * the next read rather than remembered from the last one.
 */
class SandboxBackgroundStateTest {

    private class FakeDevice(
        var batteryExempt: Boolean = false,
        var notificationsVisible: Boolean = true,
    ) : SandboxBackgroundController {
        var modeOn = false
        var serviceRunning = false

        override fun refresh(): SandboxBackgroundState = SandboxBackgroundState(
            enabled = modeOn,
            batteryExempt = batteryExempt,
            notificationsVisible = notificationsVisible,
            supported = true,
        )

        override fun setEnabled(enabled: Boolean) {
            modeOn = enabled
            serviceRunning = enabled
        }

        override fun requestBatteryExemption() {
            // The user tapped Allow in the system dialog.
            batteryExempt = true
        }

        override fun openBatterySettings() {}

        override fun openAutostartSettings() {}
    }

    @Test
    fun enablingWithoutTheExemptionWarnsButStillRuns() {
        val device = FakeDevice(batteryExempt = false)

        device.setEnabled(true)
        val state = device.refresh()

        assertTrue(state.working, "the mode is the toggle, not the exemption")
        assertTrue(device.serviceRunning, "the service starts anyway")
        assertTrue(state.batteryWarning, "the risk is stated")
    }

    @Test
    fun grantingTheExemptionClearsTheWarning() {
        val device = FakeDevice(batteryExempt = false)
        device.setEnabled(true)

        device.requestBatteryExemption()

        assertFalse(device.refresh().batteryWarning)
        assertTrue(device.refresh().working)
    }

    @Test
    fun aRevokedExemptionIsReportedOnTheNextRead() {
        val device = FakeDevice(batteryExempt = true)
        device.setEnabled(true)
        assertFalse(device.refresh().batteryWarning)

        // The user takes the exemption away in system settings.
        device.batteryExempt = false

        assertTrue(device.refresh().batteryWarning, "nothing may cache a granted exemption")
    }

    @Test
    fun hiddenNotificationsDoNotBlockTheMode() {
        val device = FakeDevice(batteryExempt = true, notificationsVisible = false)

        device.setEnabled(true)
        val state = device.refresh()

        assertTrue(state.working)
        assertTrue(device.serviceRunning)
        assertTrue(state.notificationHidden, "the invisibility is stated instead of blocking")
    }

    @Test
    fun notificationsComingBackClearsTheHint() {
        val device = FakeDevice(batteryExempt = true, notificationsVisible = false)
        device.setEnabled(true)
        assertTrue(device.refresh().notificationHidden)

        device.notificationsVisible = true

        assertFalse(device.refresh().notificationHidden)
    }

    @Test
    fun switchingTheModeOffClearsEveryWarning() {
        val device = FakeDevice(batteryExempt = false, notificationsVisible = false)
        device.setEnabled(true)
        assertTrue(device.refresh().batteryWarning)

        device.setEnabled(false)
        val state = device.refresh()

        assertFalse(state.working)
        assertFalse(state.batteryWarning)
        assertFalse(state.notificationHidden)
        assertFalse(device.serviceRunning, "the service is stopped with the toggle")
    }

    @Test
    fun platformsWithoutABackgroundModeNeverReportItWorking() {
        val state = SandboxBackgroundState(enabled = true, batteryExempt = false, supported = false)

        assertFalse(state.working)
        assertFalse(state.batteryWarning)
        assertEquals(false, state.notificationHidden)
    }
}
