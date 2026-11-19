package com.pusios.pots.ui.potsik

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The tempo the floating window keeps, and when its clock is allowed to stop.
 *
 * The owner's report was «анимация притормаживает, как будто мало кадров в
 * секунду»: the window parked on the in-app breathing roll, four to eight
 * frames a second, which samples an 1100 ms breath so few times that the
 * breathing reads as a staircase. These tests pin the two numbers that fix it
 * and prove the window's number reaches the clock that actually ticks him —
 * not just the constructor.
 */
class PotsikWindowTempoTest {

    private fun engine(idleFrameMs: Long? = null) = PotsikEngine(PotsikSettings(), idleFrameMs = idleFrameMs, isRussian = { false })

    @Test
    fun `the window keeps 30 frames a second and the app keeps the breathing roll`() {
        assertEquals(33L, WINDOW_IDLE_FRAME_MS, "33 ms is the window's tempo: ~30 fps")
        assertEquals(33L, potsikIdleFrameMs(overlayWindow = true), "the window must ask for 30 fps")
        assertNull(potsikIdleFrameMs(overlayWindow = false), "in-app the engine keeps its breathing roll")
    }

    @Test
    fun `the window tempo reaches the parked clock - a steady 33 ms a wake, thirty a second`() {
        // The engine the overlay builds, driven exactly as the overlay drives
        // it: sleep to the deadline, one frame, tick, sleep again.
        val engine = engine(potsikIdleFrameMs(overlayWindow = true))
        var now = 0L
        engine.tick(now)
        var wakes = 0
        while (now < 1_000L) {
            val next = engine.nextEventAt(now)
            assertNotNull(next, "at rest the window must always have a deadline to wake on")
            assertEquals(33L, next - now, "the window's wake is a steady 33 ms, got ${next - now}")
            now = next
            engine.tick(now)
            assertTrue(++wakes < 100, "the window's clock must not spin")
        }
        assertTrue(
            wakes >= 30,
            "a second of the window's clock is ~30 wakes; the old breathing roll gave 4–8, got $wakes",
        )
    }

    @Test
    fun `the in-app surface keeps the old breathing roll`() {
        val engine = engine(idleFrameMs = potsikIdleFrameMs(overlayWindow = false))
        engine.tick(1_000L)
        repeat(16) {
            val next = engine.nextEventAt(1_000L)
            assertNotNull(next)
            val delta = next - 1_000L
            assertTrue(
                delta in PotsikEngine.BREATHE_MIN_MS..PotsikEngine.BREATHE_MAX_MS,
                "inside the app the breathing tick stays 125..250 ms out, got $delta",
            )
        }
    }

    @Test
    fun `a lit screen keeps the engine's deadline and a dark screen sleeps outright`() {
        assertEquals(
            33L,
            potsikParkSleepMs(screenInteractive = true, nextEventAt = 1_033L, now = 1_000L),
            "a lit screen pauses between the window's own frames, it does not park",
        )
        assertNull(
            potsikParkSleepMs(screenInteractive = false, nextEventAt = 1_033L, now = 1_000L),
            "a dark screen is the one case where nothing of him is visible: park outright",
        )
        assertNull(
            potsikParkSleepMs(screenInteractive = true, nextEventAt = null, now = 1_000L),
            "nothing scheduled is a wait for a signal, whatever the screen does",
        )
        assertNull(
            potsikParkSleepMs(screenInteractive = false, nextEventAt = null, now = 1_000L),
        )
        assertEquals(
            1L,
            potsikParkSleepMs(screenInteractive = true, nextEventAt = 900L, now = 1_000L),
            "a deadline already behind is a 1 ms sleep, never a zero-delay spin",
        )
    }

    @Test
    fun `the screen flag starts lit, follows the platform and comes back`() {
        assertTrue(
            PotsikScreen.interactive.value,
            "a surface that never reports the screen must never leave him parked",
        )
        PotsikScreen.setInteractive(false)
        assertFalse(PotsikScreen.interactive.value, "screen off must park him")
        PotsikScreen.setInteractive(true)
        assertTrue(PotsikScreen.interactive.value, "the screen coming back must unpark him")
    }
}
