package com.pusios.pots.assistant

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ROLE_ASSISTANT exists from API 29 and cannot be requested in code
 * (`requestable=false` in AOSP). The settings card has to reflect that
 * instead of showing a request dialog that silently closes.
 *
 * Production change that would fail each test:
 * - drop the API 29 gate → old devices get a dead toggle
 * - treat "available but not held" as already assigned → the button lies
 * - invent a requestable-true path → the user never reaches system settings
 */
class AssistantAvailabilityTest {

    @Test
    fun `below API 29 the role is not on this Android`() {
        assertEquals(
            AssistantAvailability.NeedsNewerAndroid("10"),
            assistantAvailability(sdkInt = 28, roleAvailable = false, roleHeld = false),
        )
    }

    @Test
    fun `API 29 with the role missing is still not on this Android`() {
        // Some OEM builds hide ROLE_ASSISTANT even on 10+.
        assertEquals(
            AssistantAvailability.NotOnThisPlatform,
            assistantAvailability(sdkInt = 29, roleAvailable = false, roleHeld = false),
        )
    }

    @Test
    fun `the role can be assigned but is not held yet`() {
        val status = assistantAvailability(sdkInt = 29, roleAvailable = true, roleHeld = false)
        assertEquals(AssistantAvailability.Available(isHeld = false), status)
        assertFalse(status.held)
        assertTrue(status.canOpenDefaultApps)
    }

    @Test
    fun `Pots is already the default assistant`() {
        val status = assistantAvailability(sdkInt = 36, roleAvailable = true, roleHeld = true)
        assertEquals(AssistantAvailability.Available(isHeld = true), status)
        assertTrue(status.held)
        assertTrue(status.canOpenDefaultApps)
    }

    @Test
    fun `non-Android has no assistant role at all`() {
        assertEquals(
            AssistantAvailability.NotOnThisPlatform,
            assistantAvailability(sdkInt = null, roleAvailable = false, roleHeld = false),
        )
    }
}
