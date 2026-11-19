package com.pusios.pots.assistant

import com.pusios.pots.handsfree.HandsFreeStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The one-microphone rule: what the assist gesture does to the voice loop.
 *
 * Two failures this guards against, and both are silent: a second start
 * `cancel()`s a live recognition session and opens another on top of it (the
 * platform answers `ERROR_RECOGNIZER_BUSY`), and an invocation during speech
 * that does nothing at all breaks the owner's requirement that the gesture
 * leaves Потсик listening.
 */
class AssistInvocationActionTest {

    @Test
    fun `an idle loop is opened by the gesture`() {
        assertEquals(AssistInvocationAction.StartListening, assistInvocationAction(HandsFreeStatus.Idle))
    }

    @Test
    fun `an error state is a fresh attempt, not a live microphone`() {
        assertEquals(AssistInvocationAction.StartListening, assistInvocationAction(HandsFreeStatus.Error))
    }

    @Test
    fun `a listening loop keeps the microphone`() {
        assertEquals(AssistInvocationAction.AttachOnly, assistInvocationAction(HandsFreeStatus.Listening))
    }

    @Test
    fun `a thinking loop keeps the microphone, the question is already asked`() {
        assertEquals(AssistInvocationAction.AttachOnly, assistInvocationAction(HandsFreeStatus.Thinking))
    }

    @Test
    fun `a speaking loop is cut short so the person is heard`() {
        assertEquals(AssistInvocationAction.InterruptAndListen, assistInvocationAction(HandsFreeStatus.Speaking))
    }

    @Test
    fun `no-match keeps the microphone, because the loop reopens it itself`() {
        assertEquals(AssistInvocationAction.AttachOnly, assistInvocationAction(HandsFreeStatus.NoMatch))
    }
}
