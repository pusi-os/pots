package com.pusios.pots.ui.potsik

import androidx.compose.ui.unit.IntOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The two gestures the character's size and place are made of, as pure
 * numbers: the pinch that sets his size, and the rule that keeps the window
 * he lives in on the screen.
 *
 * These are the parts of the gesture handling that can be wrong without a
 * device in hand — a division by a zero span, a size that escapes its range,
 * a window dragged past an edge — so they are the parts kept out of the
 * platform code and tested here.
 */
class DesktopPetGestureTest {

    // ---- the pinch -----------------------------------------------------------

    @Test
    fun `spreading the fingers makes him bigger in proportion`() {
        assertEquals(200f, pinchedPotsikSize(startSizeDp = 100f, startSpanPx = 100f, spanPx = 200f), 0.01f)
    }

    @Test
    fun `pinching the fingers together makes him smaller in proportion`() {
        assertEquals(100f, pinchedPotsikSize(startSizeDp = 200f, startSpanPx = 200f, spanPx = 100f), 0.01f)
    }

    @Test
    fun `moving both fingers together changes nothing`() {
        // Only the distance between the fingers counts, so a pinch that also
        // slides across the screen keeps the size it had.
        val moved = pinchedPotsikSize(startSizeDp = 132f, startSpanPx = 90f, spanPx = 90f)
        assertEquals(132f, moved, 0.01f)
    }

    @Test
    fun `one finger is not a pinch and keeps the size`() {
        assertEquals(132f, pinchedPotsikSize(132f, startSpanPx = 0f, spanPx = 0f), 0.01f)
        assertEquals(132f, pinchedPotsikSize(132f, startSpanPx = 0f, spanPx = 300f), 0.01f)
    }

    @Test
    fun `a span that collapses to zero keeps the size instead of dividing by it`() {
        assertEquals(132f, pinchedPotsikSize(132f, startSpanPx = 80f, spanPx = 0f), 0.01f)
    }

    @Test
    fun `he never pinches smaller than the minimum or bigger than the maximum`() {
        assertEquals(
            DESKTOP_PET_MIN_SIZE_DP,
            pinchedPotsikSize(startSizeDp = 100f, startSpanPx = 1_000f, spanPx = 1f),
            0.01f,
        )
        assertEquals(
            DESKTOP_PET_MAX_SIZE_DP,
            pinchedPotsikSize(startSizeDp = 100f, startSpanPx = 1f, spanPx = 1_000f),
            0.01f,
        )
    }

    @Test
    fun `the size a pinch ends on is what the look keeps`() {
        assertEquals(1f, potsikSizeScale(132f), 0.0001f)
        assertEquals(1.5f, potsikSizeScale(198f), 0.0001f)
        // The look can never hold a size the window could not have.
        assertEquals(DESKTOP_PET_MAX_SIZE_DP / DESKTOP_PET_BASE_SIZE_DP, potsikSizeScale(9_000f), 0.0001f)
        assertEquals(DESKTOP_PET_MIN_SIZE_DP / DESKTOP_PET_BASE_SIZE_DP, potsikSizeScale(1f), 0.0001f)
    }

    // ---- the window on the screen -------------------------------------------

    @Test
    fun `a window dragged past the left or the top edge is pulled back`() {
        val offset = clampWindowOffset(
            x = -400,
            y = -250,
            windowWidth = 200,
            windowHeight = 300,
            screenWidth = 1_080,
            screenHeight = 2_400,
        )
        assertEquals(IntOffset(0, 0), offset)
    }

    @Test
    fun `a window dragged past the right or the bottom edge is pulled back`() {
        val offset = clampWindowOffset(
            x = 5_000,
            y = 9_000,
            windowWidth = 200,
            windowHeight = 300,
            screenWidth = 1_080,
            screenHeight = 2_400,
        )
        assertEquals(IntOffset(880, 2_100), offset)
    }

    @Test
    fun `a window already inside the screen is left alone`() {
        val offset = clampWindowOffset(
            x = 120,
            y = 800,
            windowWidth = 200,
            windowHeight = 300,
            screenWidth = 1_080,
            screenHeight = 2_400,
        )
        assertEquals(IntOffset(120, 800), offset)
    }

    @Test
    fun `a character bigger than the screen sits in the corner rather than off it`() {
        val offset = clampWindowOffset(
            x = 400,
            y = 400,
            windowWidth = 1_200,
            windowHeight = 1_300,
            screenWidth = 1_080,
            screenHeight = 2_400,
        )
        assertEquals(IntOffset(0, 400), offset)
    }

    // ---- the carry -----------------------------------------------------------

    @Test
    fun `a drag in progress is the carry, and it is the hands-up animation`() {
        val effect = potsikDragEffect(dragging = true)
        assertEquals(PotsikDragEffect.CARRY, effect)
        assertEquals(PotsikAction.IDLE_HANDS_UP, effect.animation)
    }

    @Test
    fun `letting go is a release with no animation of its own`() {
        val effect = potsikDragEffect(dragging = false)
        assertEquals(PotsikDragEffect.RELEASE, effect)
        assertNull(effect.animation, "letting go hands him back to the idle loop")
    }
}
