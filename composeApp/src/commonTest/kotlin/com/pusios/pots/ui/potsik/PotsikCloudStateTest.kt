package com.pusios.pots.ui.potsik

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun `only the speaking pill interrupts`() {
        assertTrue(potsikCloudPillInterrupts(PotsikCloudPhase.Speaking))
        for (phase in PotsikCloudPhase.entries - PotsikCloudPhase.Speaking) {
            assertFalse(potsikCloudPillInterrupts(phase), "$phase must not interrupt")
        }
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
}
