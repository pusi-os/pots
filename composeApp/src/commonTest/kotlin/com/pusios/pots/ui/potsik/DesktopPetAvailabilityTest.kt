package com.pusios.pots.ui.potsik

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the platform offers, and what the settings row may therefore do.
 *
 * The owner's rule for Android: a mode the old system cannot do is not
 * shimmed. On a system below the minimum the row stays visible and dead with
 * the version in the text; on a platform without such a window at all it is
 * hidden, as it always was. These are the three branches, with a fake
 * availability instead of a device.
 */
class DesktopPetAvailabilityTest {

    @Test
    fun `a platform without an overlay window hides the row`() {
        val row = desktopPetRow(
            availability = DesktopPetAvailability.NotOnThisPlatform,
            savedEnabled = true,
            permissionGranted = true,
        )
        assertFalse(row.visible)
        assertFalse(row.enabled)
        assertFalse(row.checked)
    }

    @Test
    fun `an Android below the minimum shows a dead row with the version in it`() {
        val row = desktopPetRow(
            availability = DesktopPetAvailability.NeedsNewerAndroid("13"),
            savedEnabled = true,
            permissionGranted = true,
        )
        assertTrue(row.visible)
        assertFalse(row.enabled, "the switch does not work on a system that cannot do the mode")
        assertFalse(row.checked, "and it never reads as on")
        assertEquals("13", row.minimumOsLabel)
    }

    @Test
    fun `an available platform with the permission shows the saved state`() {
        val row = desktopPetRow(
            availability = DesktopPetAvailability.Available,
            savedEnabled = true,
            permissionGranted = true,
        )
        assertTrue(row.visible)
        assertTrue(row.enabled)
        assertTrue(row.checked)
        assertNull(row.minimumOsLabel)
    }

    @Test
    fun `the mode saved on without the permission does not read as on`() {
        val row = desktopPetRow(
            availability = DesktopPetAvailability.Available,
            savedEnabled = true,
            permissionGranted = false,
        )
        assertTrue(row.visible)
        assertTrue(row.enabled, "the switch still works: tapping it is how the permission is asked for")
        assertFalse(row.checked, "a switch that claimed to be on would promise a character that is not there")
    }

    @Test
    fun `the overlay's own actions do nothing where the overlay cannot exist`() {
        var opened = 0
        var synced = 0
        val openSettings = { opened++ }
        val syncService = { synced++ }

        ifDesktopPetAvailable(DesktopPetAvailability.NotOnThisPlatform) { openSettings() }
        ifDesktopPetAvailable(DesktopPetAvailability.NeedsNewerAndroid("13")) { openSettings() }
        ifDesktopPetAvailable(DesktopPetAvailability.NeedsNewerAndroid("13")) { syncService() }

        assertEquals(0, opened, "the system's permission screen is not opened on a system that cannot use it")
        assertEquals(0, synced, "and the service is never started there")
    }

    @Test
    fun `a saved mode on an old system never starts the service`() {
        // The flag survives from a newer phone or an older build; the gate is
        // what stops it from turning into a service call on this one.
        var synced = 0
        ifDesktopPetAvailable(DesktopPetAvailability.NeedsNewerAndroid("13")) { synced++ }
        assertEquals(0, synced)
    }

    @Test
    fun `on a platform that has the mode the actions run`() {
        var opened = 0
        var synced = 0
        ifDesktopPetAvailable(DesktopPetAvailability.Available) { opened++ }
        ifDesktopPetAvailable(DesktopPetAvailability.Available) { synced++ }
        assertEquals(1, opened)
        assertEquals(1, synced)
    }
}
