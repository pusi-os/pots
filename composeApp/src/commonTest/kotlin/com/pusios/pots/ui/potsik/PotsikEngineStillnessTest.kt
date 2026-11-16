package com.pusios.pots.ui.potsik

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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
}
