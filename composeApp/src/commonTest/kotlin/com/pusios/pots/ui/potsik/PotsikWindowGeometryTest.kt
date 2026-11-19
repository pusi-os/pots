package com.pusios.pots.ui.potsik

import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The window the character stands in, and the arithmetic that keeps him in
 * place while the window changes size.
 *
 * The owner's report was «облачка обрезаются с правой стороны границей
 * прямоугольной зоны»: the window used to be exactly his width, so the speech
 * bubble — which is drawn *inside* the same window — was cut by its edge, and
 * the pop made it 12 % wider still. These tests pin the two numbers the window
 * is made of (his arms and the bubble), prove the bubble fits at the peak of
 * its pop, and prove he does not slide sideways when a pinch changes the
 * window's width.
 */
class PotsikWindowGeometryTest {

    /** A 1080 × 2400 screen at density 2 — the numbers below stay readable in dp. */
    private val density = 2f
    private val screenWidth = 1_080
    private val screenHeight = 2_400

    private fun px(dp: Float): Int = (dp * density).roundToInt()

    private fun geometry(characterDp: Float, screenWidthPx: Int = screenWidth): PotsikWindowGeometry =
        potsikWindowGeometry(
            characterPx = px(characterDp),
            density = density,
            bubbleRoomDp = DESKTOP_PET_BUBBLE_ROOM_DP,
            screenWidthPx = screenWidthPx,
        )

    // ---- the size of the window ---------------------------------------------

    @Test
    fun `the smallest characters get a bubble-wide window and the biggest gets arms-wide`() {
        // 191 dp is the bubble's number: it is wider than 1.15 · 72 dp and
        // 1.15 · 132 dp, so those two sizes are the bubble's width exactly.
        assertEquals(382, geometry(72f).widthPx, "72 dp of character is a 191 dp window, got ${geometry(72f).widthPx}")
        assertEquals(382, geometry(132f).widthPx, "132 dp of character is a 191 dp window, got ${geometry(132f).widthPx}")
        // 1.15 · 320 dp = 368 dp of arms, and the bubble is narrower than that.
        assertEquals(736, geometry(320f).widthPx, "320 dp of character is a 368 dp window, got ${geometry(320f).widthPx}")
    }

    @Test
    fun `the height is the character plus the bubble's band, whatever his width is`() {
        assertEquals(336, geometry(72f).heightPx, "72 + 96 dp, got ${geometry(72f).heightPx}")
        assertEquals(456, geometry(132f).heightPx, "132 + 96 dp, got ${geometry(132f).heightPx}")
        assertEquals(832, geometry(320f).heightPx, "320 + 96 dp, got ${geometry(320f).heightPx}")
    }

    @Test
    fun `the window is never narrower than the character in it`() {
        // A screen narrower than he is: the window keeps his width and hangs
        // over the edge, which `FLAG_LAYOUT_NO_LIMITS` allows — a window
        // narrower than the body would clip the body itself.
        val narrow = geometry(320f, screenWidthPx = 500)
        assertEquals(
            px(320f),
            narrow.widthPx,
            "a 500 px screen must not shrink the window below the 640 px character, got ${narrow.widthPx}",
        )
        listOf(72f, 132f, 320f).forEach { characterDp ->
            val window = geometry(characterDp)
            assertTrue(
                window.widthPx >= px(characterDp),
                "the window (${window.widthPx} px) is narrower than the $characterDp dp character",
            )
        }
    }

    @Test
    fun `the window never grows past the screen it stands on`() {
        val window = potsikWindowGeometry(
            characterPx = px(132f),
            density = density,
            bubbleRoomDp = DESKTOP_PET_BUBBLE_ROOM_DP,
            screenWidthPx = 300,
        )
        assertEquals(300, window.widthPx, "a 300 px screen caps the 382 px window, got ${window.widthPx}")
    }

    // ---- the bubble inside it ------------------------------------------------

    @Test
    fun `every character size leaves the bubble the 150 dp of text it was measured for`() {
        // The bubble's padding is a fixed 20 px and the pop scales it to 1.12,
        // so the window's floor is (150 + 20) · 1.12 = 190.4 dp: 191 dp of
        // window is 150 dp of text at density 1 and more on every real screen.
        // 150 dp is the number the 313 lines were measured against: 90 % of
        // them fit two rows, all of them three.
        listOf(72f, 132f, 320f).forEach { characterDp ->
            val window = geometry(characterDp).widthPx.toFloat()
            val limit = potsikBubbleTextLimit(windowWidthPx = window, wantedPx = window)
            assertTrue(
                limit >= 150f * density,
                "at $characterDp dp the bubble may only ask for $limit px of text, it needs ${150f * density} px",
            )
        }
        // Density 1 is the tightest screen there is, and the one the floor was
        // chosen for: at 2× every size clears the bar twice over, so the claim
        // is checked where it actually bites.
        val atDensityOne = potsikWindowGeometry(
            characterPx = 144,
            density = 1f,
            bubbleRoomDp = DESKTOP_PET_BUBBLE_ROOM_DP,
            screenWidthPx = screenWidth,
        ).widthPx.toFloat()
        val limit = potsikBubbleTextLimit(windowWidthPx = atDensityOne, wantedPx = atDensityOne)
        assertTrue(
            limit >= 150f,
            "at density 1 the $atDensityOne px window leaves only $limit px of text, it needs 150",
        )
    }

    @Test
    fun `the bubble and the peak of its pop stay inside the window`() {
        // The widest text box the window allows, drawn centred — the case the
        // owner saw cut on the right.
        listOf(72f, 132f, 320f).forEach { characterDp ->
            val window = geometry(characterDp).widthPx.toFloat()
            val limit = potsikBubbleTextLimit(windowWidthPx = window, wantedPx = window)
            val bubbleWidth = limit + 20f
            val left = potsikBubbleLeft(windowWidthPx = window, bubbleWidthPx = bubbleWidth, centreX = window / 2f)
            val growth = (POTSIK_BUBBLE_POP_PEAK - 1f) * bubbleWidth / 2f
            assertTrue(
                left - growth >= -0.5f,
                "at $characterDp dp the popped bubble starts at ${left - growth} px, outside the $window px window",
            )
            assertTrue(
                left + bubbleWidth + growth <= window + 0.5f,
                "at $characterDp dp the popped bubble ends at ${left + bubbleWidth + growth} px, past the $window px window",
            )
        }
    }

    @Test
    fun `a bubble wider than its window is pinned to the left edge and not cut on the right`() {
        // The last guard, kept although the text limit above makes it
        // unreachable: a bubble wider than the window would otherwise be
        // centred and lose its right side — the shape of the owner's bug.
        val left = potsikBubbleLeft(windowWidthPx = 200f, bubbleWidthPx = 300f, centreX = 100f)
        assertEquals(0f, left, "a bubble wider than its window starts at the left edge, got $left")
    }

    // ---- he does not jump when the window changes width ----------------------

    @Test
    fun `a pinch from the smallest to the largest keeps his middle on the screen`() {
        val small = geometry(72f)
        val large = geometry(320f)
        val windowX = 300
        val before = potsikCharacterCxOnScreen(windowX = windowX, windowWidthPx = small.widthPx)
        val after = potsikCharacterCxOnScreen(
            windowX = potsikWindowXForCentre(characterCxOnScreen = before, windowWidthPx = large.widthPx),
            windowWidthPx = large.widthPx,
        )
        assertEquals(before, after, "his middle must stay at $before px, it moved to $after px")
        assertEquals(
            windowX - (large.widthPx - small.widthPx) / 2,
            potsikWindowXForCentre(characterCxOnScreen = before, windowWidthPx = large.widthPx),
            "the window's left edge must take half the growth back",
        )
    }

    @Test
    fun `every pinch step keeps his middle where it was, to the pixel`() {
        // Frame by frame through a pinch: 72 → 320 dp in 24 steps, each one
        // resizing the window around the middle he has at that moment.
        var geometry = geometry(72f)
        var windowX = 240
        val middle = potsikCharacterCxOnScreen(windowX = windowX, windowWidthPx = geometry.widthPx)
        for (step in 1..24) {
            val characterDp = 72f + (320f - 72f) * step / 24f
            val next = geometry(characterDp)
            windowX = potsikWindowXForCentre(characterCxOnScreen = middle, windowWidthPx = next.widthPx)
            geometry = next
            val now = potsikCharacterCxOnScreen(windowX = windowX, windowWidthPx = geometry.widthPx)
            assertEquals(middle, now, "step $step ($characterDp dp) moved his middle from $middle to $now px")
        }
    }

    // ---- where the window may sit -------------------------------------------

    @Test
    fun `the bubble stays whole at either edge and he stops half a bubble short of it`() {
        // The owner's check №4: dragged into a corner, the line and the capsule
        // are not cut. The bubble is drawn centred on him and its tail points
        // at his head, so the only way to keep it whole is to keep *him* half a
        // bubble in from the edge — his body therefore stops short, and the
        // window's empty strip hangs over the edge (FLAG_LAYOUT_NO_LIMITS).
        listOf(72f, 132f, 320f).forEach { characterDp ->
            val geometry = geometry(characterDp)
            val characterPx = px(characterDp)
            val bubbleWidth = potsikBubbleWidthFor(geometry.widthPx.toFloat())

            for (dragX in listOf(-5_000, 5_000)) {
                val clamped = clampPotsikWindowOffset(
                    x = dragX,
                    y = 800,
                    characterPx = characterPx,
                    windowWidth = geometry.widthPx,
                    windowHeight = geometry.heightPx,
                    screenWidth = screenWidth,
                    screenHeight = screenHeight,
                )
                val centre = clamped.x + geometry.widthPx / 2
                val bubbleLeft = centre - bubbleWidth / 2f
                val bubbleRight = centre + bubbleWidth / 2f
                assertTrue(
                    bubbleLeft >= -0.5f,
                    "at $characterDp dp dragged to $dragX the bubble starts at $bubbleLeft px, off the left edge",
                )
                assertTrue(
                    bubbleRight <= screenWidth + 0.5f,
                    "at $characterDp dp dragged to $dragX the bubble ends at $bubbleRight px, off the $screenWidth px screen",
                )
                // Flush: no dead strip between the bubble and the edge either —
                // he gets as close as a whole line allows and no closer.
                assertTrue(
                    bubbleLeft <= 0.5f || bubbleRight >= screenWidth - 0.5f,
                    "at $characterDp dp dragged to $dragX the bubble sits at [$bubbleLeft, $bubbleRight], not at the edge",
                )

                val bodyLeft = centre - characterPx / 2
                assertTrue(bodyLeft >= 0, "at $characterDp dp his body starts at $bodyLeft px, off the screen")
                assertTrue(
                    clamped.x + geometry.widthPx > screenWidth || clamped.x < 0,
                    "at $characterDp dp the window must hang over the edge, it sits at ${clamped.x}",
                )
            }
        }
    }

    @Test
    fun `at the default size his middle stops 85 dp from the edge, not 36`() {
        // The documented number, so a change to the bubble's width or the
        // clamp shows up as a diff and not as a surprise on the phone: a
        // 382 px window holds a 341 px bubble, so the 264 px body stops 39 px
        // (19.5 dp) short of the edge.
        val geometry = geometry(132f)
        val clamped = clampPotsikWindowOffset(
            x = 5_000,
            y = 800,
            characterPx = px(132f),
            windowWidth = geometry.widthPx,
            windowHeight = geometry.heightPx,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
        )
        val centre = clamped.x + geometry.widthPx / 2
        assertEquals(
            screenWidth - 171,
            centre,
            "his middle must stop 171 px from the right edge, it stops at ${screenWidth - centre} px from it",
        )
    }

    @Test
    fun `he reaches the top and the bottom edge too`() {
        val geometry = geometry(132f)
        val characterPx = px(132f)

        val bottom = clampPotsikWindowOffset(
            x = 0,
            y = 9_000,
            characterPx = characterPx,
            windowWidth = geometry.widthPx,
            windowHeight = geometry.heightPx,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
        )
        assertEquals(
            screenHeight,
            bottom.y + geometry.heightPx,
            "his feet stand on the bottom edge, they stop at ${bottom.y + geometry.heightPx} px",
        )

        val top = clampPotsikWindowOffset(
            x = 0,
            y = -9_000,
            characterPx = characterPx,
            windowWidth = geometry.widthPx,
            windowHeight = geometry.heightPx,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
        )
        assertEquals(
            0,
            top.y + geometry.heightPx - characterPx,
            "his head reaches the top edge, it starts at ${top.y + geometry.heightPx - characterPx} px",
        )
    }

    @Test
    fun `a window already on the screen is left where it is`() {
        val geometry = geometry(132f)
        val offset = clampPotsikWindowOffset(
            x = 400,
            y = 800,
            characterPx = px(132f),
            windowWidth = geometry.widthPx,
            windowHeight = geometry.heightPx,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
        )
        assertEquals(IntOffset(400, 800), offset)
    }

    @Test
    fun `a character bigger than the screen is centred instead of pinned to a corner`() {
        // A bubble wider than the screen leaves no position where it fits: the
        // bounds meet in the middle, so he is centred rather than pushed off
        // one edge with a negative x.
        val offset = clampPotsikWindowOffset(
            x = 400,
            y = 400,
            characterPx = 1_200,
            windowWidth = 1_380,
            windowHeight = 1_600,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
        )
        assertEquals(
            screenWidth / 2,
            offset.x + 1_380 / 2,
            "a character wider than the screen is centred on it, he sits at ${offset.x + 1_380 / 2} px",
        )
    }
}
