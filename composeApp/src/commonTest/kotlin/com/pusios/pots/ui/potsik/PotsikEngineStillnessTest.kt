package com.pusios.pots.ui.potsik

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The stillness contract of the engine.
 *
 * The frame clock the screens run the character on is only allowed to turn
 * while [PotsikEngine.isAnimating] says something is actually moving. These
 * tests pin down what "moving" means, because a clock that never parks is
 * what kept the whole window redrawing on a screen nobody touched.
 */
class PotsikEngineStillnessTest {

    private fun engine(settings: PotsikSettings = PotsikSettings()) = PotsikEngine(settings) { false }

    /** The box the character occupies, in the same units the hit test uses. */
    private val box = PotsikBox(cx = 100f, headTopY = 100f, scale = 2f)

    @Test
    fun `at rest he is not animating`() {
        val engine = engine()
        engine.tick(1_000L)
        assertFalse(engine.isAnimating(1_000L), "a fresh character with nothing going on must be still")
    }

    @Test
    fun `a petting starts the animation and it ends with the mood and the line`() {
        val engine = engine()
        engine.noteTapAt(100f, 80f, box)
        engine.onPetted(1_000L)
        assertTrue(engine.isAnimating(1_000L), "a petting must wake him up")
        // The bubble lives for the tempo'd minimum, so a moment later he is
        // still talking — and long after everything has expired he is still.
        assertTrue(engine.isAnimating(2_000L))
        engine.tick(60_000L)
        assertFalse(engine.isAnimating(60_000L), "once the mood and the line are over he must be still again")
    }

    @Test
    fun `the working phase keeps him animating and idle lets him rest`() {
        val engine = engine()
        engine.setPhase(PotsikPhase.WORKING, null, 0L)
        assertTrue(engine.isAnimating(0L), "a running request is a playlist of animations")

        engine.setPhase(PotsikPhase.IDLE, null, 100_000L)
        engine.tick(100_000L)
        assertFalse(engine.isAnimating(100_000L), "back to idle with nothing said he must be still")
    }

    @Test
    fun `a named animation from the agent keeps him animating until it is over`() {
        val engine = engine()
        engine.playAction(PotsikAction.IDLE_DANCE, durationMs = 2_600L, now = 1_000L)
        assertTrue(engine.isAnimating(1_000L))
        engine.tick(10_000L)
        assertFalse(engine.isAnimating(10_000L))
    }

    @Test
    fun `a line said on its own keeps him animating until it goes away`() {
        val engine = engine()
        engine.say("Something to say.", ms = 3_000L, now = 1_000L)
        assertTrue(engine.isAnimating(1_000L), "the mouth flaps while the line is up")
        assertFalse(engine.isAnimating(100_000L), "an expired line must not keep the clock turning")
    }

    @Test
    fun `he stays still when the chatter is switched off`() {
        val engine = engine(settings = PotsikSettings(banterEnabled = false))
        engine.tick(1_000L)
        assertFalse(engine.isAnimating(1_000L))
    }

    @Test
    fun `starting from the outside sends the motion signal a parked clock waits on`() {
        val engine = engine()
        val before = engine.motion.value
        engine.onPetted(1_000L)
        assertNotEquals(before, engine.motion.value, "a petting must wake a parked frame clock")

        val beforePhase = engine.motion.value
        engine.setPhase(PotsikPhase.WORKING, null, 2_000L)
        assertNotEquals(beforePhase, engine.motion.value, "a phase change must wake a parked frame clock")
    }

    @Test
    fun `a clock woken only by its deadlines still reaches the idle roll`() {
        val engine = engine()
        var now = 0L
        engine.tick(now)
        // The parked clock exactly as the overlay runs it: sleep to the next
        // deadline, one frame, tick, sleep again. No touches, no motion signal.
        var wakes = 0
        while (engine.bubble(now) == null) {
            val next = engine.nextEventAt(now)
            assertNotNull(next, "at rest the engine must always have a deadline to wake on")
            now = next
            engine.tick(now)
            assertTrue(++wakes < 1_000, "the deadline loop must reach the roll, not spin for good")
        }
        assertNotNull(
            engine.bubble(now),
            "the roll must produce a line when only deadlines wake the clock",
        )
        val earliest = engine.tempo(9_000L)
        assertTrue(
            now in earliest..earliest + 13_000L + PotsikEngine.BREATHE_MAX_MS,
            "the first roll is scheduled $earliest..${earliest + 13_000} ms out, fired at $now",
        )
    }

    @Test
    fun `the next deadline is the earliest of them all, not the first one stored`() {
        val engine = engine()
        engine.tick(1_000L) // the idle roll is scheduled first: tempo(9 s) + 0..13 s out
        engine.playMood(PotsikMood.WAVE, durationMs = 1_200L, now = 1_000L)
        engine.say("Something to say.", ms = 5_000L, now = 1_000L)
        val moodEnd = 1_000L + 1_200L
        val bubbleEnd = 1_000L + engine.tempo(5_000L)
        assertEquals(
            moodEnd,
            engine.nextEventAt(1_000L),
            "the mood ends before the line and before the roll, so it is the next deadline",
        )
        // The mood expires; the line is now the earliest — still before the roll.
        engine.tick(moodEnd + 1)
        assertEquals(bubbleEnd, engine.nextEventAt(moodEnd + 1))
    }

    @Test
    fun `at rest the breathing tick is his nearest deadline`() {
        val engine = engine()
        engine.tick(1_000L)
        assertFalse(engine.isAnimating(1_000L), "guard: this test is about a character at rest")
        // At rest the deadline is never null and never further than the
        // breathing tick — that is what keeps him alive on a quiet screen.
        // The roll itself stays scheduled behind it (the roll window is
        // pinned by the deadline-loop test above).
        repeat(32) {
            val next = engine.nextEventAt(1_000L)
            assertNotNull(next, "at rest he must never sleep for good — that is how the banter died")
            val delta = next - 1_000L
            assertTrue(
                delta in PotsikEngine.BREATHE_MIN_MS..PotsikEngine.BREATHE_MAX_MS,
                "the breathing tick comes 125..250 ms out, got $delta",
            )
        }
    }
}
