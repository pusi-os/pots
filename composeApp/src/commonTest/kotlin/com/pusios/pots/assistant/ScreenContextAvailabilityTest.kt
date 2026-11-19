package com.pusios.pots.assistant

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The mechanism behind the screen's text is chosen by the role, not by a flag:
 * held → the assist snapshot, not held → the screenshot flow, elsewhere →
 * nothing. Getting this wrong is what makes the cloud promise a context it
 * cannot have.
 */
class ScreenContextAvailabilityTest {

    @Test
    fun `holding the assistant role gives the assist snapshot`() {
        assertEquals(
            ScreenContextAvailability.AssistSnapshot,
            screenContextFor(AssistantAvailability.Available(isHeld = true)),
        )
    }

    @Test
    fun `an assignable but unheld role leaves only the screenshot`() {
        assertEquals(
            ScreenContextAvailability.Screenshot,
            screenContextFor(AssistantAvailability.Available(isHeld = false)),
        )
    }

    @Test
    fun `an Android without the role still has the screenshot`() {
        assertEquals(
            ScreenContextAvailability.Screenshot,
            screenContextFor(AssistantAvailability.NeedsNewerAndroid("10")),
        )
    }

    @Test
    fun `a platform without an assistant has no screen context`() {
        assertEquals(
            ScreenContextAvailability.NotOnThisPlatform,
            screenContextFor(AssistantAvailability.NotOnThisPlatform),
        )
    }
}
