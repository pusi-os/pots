package com.pusios.pots.ui.potsik

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The handshake between the system overlay window and the in-app character:
 * one flag, flipped only by whoever owns the window.
 */
class DesktopPetStateTest {

    @Test
    fun `setShowing flips the state the in-app overlay reads`() {
        DesktopPetWindow.setShowing(false)
        assertFalse(DesktopPetWindow.showing.value, "the window starts hidden")

        DesktopPetWindow.setShowing(true)
        assertTrue(DesktopPetWindow.showing.value, "the service reports the window is up")

        DesktopPetWindow.setShowing(false)
        assertFalse(DesktopPetWindow.showing.value, "and reports it coming back down")
    }
}
