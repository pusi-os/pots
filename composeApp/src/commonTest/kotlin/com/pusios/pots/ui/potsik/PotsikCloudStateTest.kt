package com.pusios.pots.ui.potsik

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The double tap and the pill's small rules.
 *
 * The tap counter is the part with a real risk: counting it by hand exists so
 * that petting him answers at once, and the pair has to open the cloud without
 * turning three quick taps into two opens.
 */
class PotsikCloudStateTest {

    private val timeout = 300L

    @Test
    fun `a lone tap is a single tap`() {
        val counter = PotsikTapCounter(timeout)
        assertEquals(PotsikTap.Single, counter.onTap(1_000))
    }

    @Test
    fun `a second tap inside the window is a double tap`() {
        val counter = PotsikTapCounter(timeout)
        counter.onTap(1_000)
        assertEquals(PotsikTap.Double, counter.onTap(1_300))
    }

    @Test
    fun `a second tap after the window is a new single tap`() {
        val counter = PotsikTapCounter(timeout)
        counter.onTap(1_000)
        assertEquals(PotsikTap.Single, counter.onTap(1_301))
    }

    @Test
    fun `three quick taps are one double and one single`() {
        val counter = PotsikTapCounter(timeout)
        assertEquals(PotsikTap.Single, counter.onTap(1_000))
        assertEquals(PotsikTap.Double, counter.onTap(1_200))
        // The pair is spent: the third tap starts counting from scratch.
        assertEquals(PotsikTap.Single, counter.onTap(1_400))
    }

    @Test
    fun `the window comes from the platform, not from us`() {
        // A device with a 500 ms double-tap timeout accepts a slower pair.
        val slow = PotsikTapCounter(500)
        slow.onTap(1_000)
        assertEquals(PotsikTap.Double, slow.onTap(1_450))
    }

    @Test
    fun `an empty field sends nothing`() {
        assertFalse(potsikCloudSendEnabled(""))
        assertFalse(potsikCloudSendEnabled("   "))
        assertTrue(potsikCloudSendEnabled("что тут на экране"))
    }

    @Test
    fun `the character keeps the app's own phases`() {
        assertEquals(PotsikPhase.IDLE, potsikPetLook(null).phase)
        assertEquals(PotsikPhase.IDLE, potsikPetLook(PotsikCloudPhase.Menu).phase)
        assertEquals(PotsikPhase.IDLE, potsikPetLook(PotsikCloudPhase.TextInput).phase)

        val listening = potsikPetLook(PotsikCloudPhase.Listening)
        assertEquals(PotsikPhase.IDLE, listening.phase)
        assertTrue(listening.leaning, "listening tips his head")

        assertEquals(PotsikPhase.WORKING, potsikPetLook(PotsikCloudPhase.Thinking).phase)
        assertEquals(PotsikPhase.SPEAKING, potsikPetLook(PotsikCloudPhase.Speaking).phase)
        assertEquals(PotsikPhase.ERROR, potsikPetLook(PotsikCloudPhase.Error).phase)
    }

    @Test
    fun `only listening leans`() {
        for (phase in PotsikCloudPhase.entries - PotsikCloudPhase.Listening) {
            assertFalse(potsikPetLook(phase).leaning, "$phase must not lean")
        }
        assertFalse(potsikPetLook(null).leaning)
    }

    @Test
    fun `the draft survives the field being closed`() {
        PotsikCloudDraft.set("черновик")
        assertEquals("черновик", PotsikCloudDraft.text.value)
        PotsikCloudDraft.clear()
        assertEquals("", PotsikCloudDraft.text.value)
    }

    @Test
    fun `an answer to a voice question keeps what was typed`() {
        PotsikCloudDraft.set("недописанная фраза")
        PotsikCloudDraft.markSent("другой вопрос")
        assertFalse(PotsikCloudDraft.clearIfAnswerTo("вопрос голосом"))
        assertEquals("недописанная фраза", PotsikCloudDraft.text.value)
        PotsikCloudDraft.clear()
        PotsikCloudDraft.forgetSent()
    }

    @Test
    fun `the answer to the typed question clears the field`() {
        PotsikCloudDraft.set("что тут на экране")
        PotsikCloudDraft.markSent("что тут на экране")
        assertTrue(PotsikCloudDraft.clearIfAnswerTo("что тут на экране"))
        assertEquals("", PotsikCloudDraft.text.value)
    }

    @Test
    fun `a question abandoned before its answer does not clear the field`() {
        PotsikCloudDraft.set("черновик")
        PotsikCloudDraft.markSent("тот же текст")
        PotsikCloudDraft.forgetSent()
        assertFalse(PotsikCloudDraft.clearIfAnswerTo("тот же текст"))
        assertEquals("черновик", PotsikCloudDraft.text.value)
        PotsikCloudDraft.clear()
    }

    @Test
    fun `the cloud takes input in the phases that wait for it`() {
        // The two surfaces that exist for input, and the error pill, whose
        // [Повторить] is a new turn of the same flow.
        assertTrue(potsikCloudInputsEnabled(PotsikCloudPhase.Menu))
        assertTrue(potsikCloudInputsEnabled(PotsikCloudPhase.TextInput))
        assertTrue(potsikCloudInputsEnabled(PotsikCloudPhase.Error))
    }

    @Test
    fun `a turn in progress takes no new input`() {
        // The bug this rule exists for: a capture started from a busy phase
        // cancels the answer nobody asked to end, silently.
        assertFalse(potsikCloudInputsEnabled(PotsikCloudPhase.Thinking))
        assertFalse(potsikCloudInputsEnabled(PotsikCloudPhase.Speaking))
        // The microphone has one owner, and the consent dialog is modal.
        assertFalse(potsikCloudInputsEnabled(PotsikCloudPhase.Listening))
        assertFalse(potsikCloudInputsEnabled(PotsikCloudPhase.AwaitingConsent))
        // A frame in hand is waiting for its own question, not for another.
        assertFalse(potsikCloudInputsEnabled(PotsikCloudPhase.Shot))
    }

    @Test
    fun `every phase has an answer, so a new one cannot default to enabled`() {
        // Exhaustive on purpose: a phase added later must be decided here.
        for (phase in PotsikCloudPhase.entries) {
            val expected = phase == PotsikCloudPhase.Menu ||
                phase == PotsikCloudPhase.TextInput ||
                phase == PotsikCloudPhase.Error
            assertEquals(expected, potsikCloudInputsEnabled(phase), "$phase")
        }
    }

    @Test
    fun `opening the field keeps the frame in hand`() {
        val question = PotsikCloudQuestion(
            asker = PotsikCloudAsker(CoroutineScope(Job()), repository = null),
            // Never reached here: the frame is only looked at, not sent.
            toFile = { null },
        )
        question.attach(PotsikShot(width = 6, height = 4, png = byteArrayOf(1, 2, 3)))

        val state = question.textInputState()

        assertEquals(PotsikCloudPhase.TextInput, state.phase)
        assertTrue(state.shotAttached, "«Текст» after a shot must keep the shot")
        assertNotNull(question.shot.value, "the frame is still owed to the question")
    }

    @Test
    fun `the field grows by the preview row when a frame rides with it`() {
        val plain = potsikCloudWindowSize(PotsikCloudState(phase = PotsikCloudPhase.TextInput))
        val withShot = potsikCloudWindowSize(
            PotsikCloudState(phase = PotsikCloudPhase.TextInput, shotAttached = true),
        )
        // The capsule's bounds are the content's bounds: a preview the window
        // did not grow for is a preview clipped by its own edge.
        assertEquals(POTSIK_CLOUD_INPUT_SHOT_ROW_DP, withShot.heightDp - plain.heightDp)
        assertEquals(plain.widthDp, withShot.widthDp)
    }
}
