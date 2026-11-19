package com.pusios.pots.ui.potsik

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Where the capsule goes.
 *
 * Every branch of [potsikCloudPlacement] is checked without a device: the
 * arithmetic is the whole feature (a capsule half off the screen is a bug the
 * owner would find on his phone and nobody could reproduce), so the numbers
 * are asserted, not eyeballed.
 */
class PotsikCloudPlacementTest {

    private val density = 2f

    /** 176×72 dp at 2×. */
    private val cloudWidth = 352
    private val cloudHeight = 144

    private val margin = 16 // 8 dp
    private val aboveGap = 16 // 8 dp
    private val sideGap = 16 // 8 dp

    /** A 300 px character with the 96 dp bubble band above him: the window is 492 px tall. */
    private val characterPx = 300
    private val bubbleBand = 192
    private val petHeight = characterPx + bubbleBand
    private val petWidth = 300

    private val screenWidth = 1080
    private val screenHeight = 2400

    private fun place(
        petX: Int,
        petY: Int,
        screenWidth: Int = this.screenWidth,
        screenHeight: Int = this.screenHeight,
        petWidth: Int = this.petWidth,
        petHeight: Int = this.petHeight,
        characterPx: Int = this.characterPx,
    ) = potsikCloudPlacement(
        petX = petX,
        petY = petY,
        petWidth = petWidth,
        petHeight = petHeight,
        characterPx = characterPx,
        screenWidth = screenWidth,
        screenHeight = screenHeight,
        density = density,
        cloudWidthPx = cloudWidth,
        cloudHeightPx = cloudHeight,
    )

    @Test
    fun `over his head when there is room`() {
        val placement = place(petX = 400, petY = 1000)
        val headTop = 1000 + (petHeight - characterPx)
        assertEquals(PotsikCloudSide.Above, placement.side)
        // Centred on his head: window centre == head centre.
        assertEquals(400 + petWidth / 2, placement.x + cloudWidth / 2)
        // Its bottom sits the gap above the top of his head.
        assertEquals(headTop - aboveGap, placement.y + cloudHeight)
    }

    @Test
    fun `to the right when the head is too close to the top`() {
        // Dragged up past the edge — FLAG_LAYOUT_NO_LIMITS allows it, and the
        // capsule must still open somewhere sensible.
        val placement = place(petX = 300, petY = -100)
        assertEquals(PotsikCloudSide.Right, placement.side)
        // The gap is measured from his shoulder — the window is his width here.
        val bodyRight = 300 + (petWidth - characterPx) / 2 + characterPx
        assertEquals(bodyRight + sideGap, placement.x)
        // Vertically centred on his head.
        val headTop = -100 + (petHeight - characterPx)
        assertEquals(headTop + characterPx / 2, placement.y + cloudHeight / 2)
    }

    @Test
    fun `to the left when the right side is taken`() {
        val placement = place(petX = 700, petY = -100)
        assertEquals(PotsikCloudSide.Left, placement.side)
        val bodyLeft = 700 + (petWidth - characterPx) / 2
        assertEquals(bodyLeft - sideGap - cloudWidth, placement.x)
    }

    @Test
    fun `the side branches count from his body, not from the window's edge`() {
        // The window is wider than he is (the bubble's room), so its edges sit
        // (W − C) / 2 away from him: measuring the 8 dp gap from them hung the
        // capsule 67 dp off his shoulder at the smallest size.
        val window = 382 // a 191 dp window at 2× around a 300 px body
        val bodyLeft = 300 + (window - characterPx) / 2
        val bodyRight = bodyLeft + characterPx

        val right = place(petX = 300, petY = -100, petWidth = window)
        assertEquals(PotsikCloudSide.Right, right.side)
        assertEquals(bodyRight + sideGap, right.x)
        assertTrue(
            right.x < 300 + window + sideGap,
            "the gap must not be measured from the window's edge: x=${right.x}",
        )

        // The same on the left: 700 px from the left, the right side is taken.
        val left = place(petX = 700, petY = -100, petWidth = window)
        assertEquals(PotsikCloudSide.Left, left.side)
        assertEquals(700 + (window - characterPx) / 2 - sideGap - cloudWidth, left.x)
        assertTrue(
            left.x > 700 - sideGap - cloudWidth,
            "the capsule must not start at the window's edge: x=${left.x}",
        )
    }

    @Test
    fun `back over his head when neither side fits`() {
        // A narrow screen and a character in the middle: nowhere to go but up.
        val placement = place(petX = 60, petY = -100, screenWidth = 380)
        assertEquals(PotsikCloudSide.Above, placement.side)
        // Clamped into the visible area rather than off the screen.
        assertEquals(margin, placement.x)
        assertTrue(placement.y >= margin)
    }

    @Test
    fun `stays on the screen at every edge`() {
        val corners = listOf(
            0 to 0,
            screenWidth - petWidth to 0,
            0 to screenHeight - petHeight,
            screenWidth - petWidth to screenHeight - petHeight,
            // And past them: he can be dragged over the edge.
            -petWidth / 2 to -200,
            screenWidth to screenHeight,
        )
        for ((x, y) in corners) {
            val placement = place(petX = x, petY = y)
            assertTrue(
                placement.x >= margin && placement.x + cloudWidth <= screenWidth - margin,
                "x=${placement.x} with the character at ($x, $y)",
            )
            assertTrue(
                placement.y >= margin && placement.y + cloudHeight <= screenHeight - margin,
                "y=${placement.y} with the character at ($x, $y)",
            )
        }
    }

    @Test
    fun `a screen narrower than the capsule still puts it at the left margin`() {
        // 170 dp wide: the capsule (176 dp) does not fit at all.
        val placement = place(petX = 20, petY = 400, screenWidth = 340)
        assertEquals(margin, placement.x)
    }

    @Test
    fun `the menu window is the capsule plus its tail`() {
        assertEquals(
            PotsikCloudSize(POTSIK_CLOUD_WIDTH_DP, POTSIK_CLOUD_WINDOW_HEIGHT_DP),
            potsikCloudWindowSize(PotsikCloudState()),
        )
        assertEquals(72f, POTSIK_CLOUD_WINDOW_HEIGHT_DP)
    }

    @Test
    fun `the menu window grows by the notice line when there is no recognizer`() {
        val withVoice = potsikCloudWindowSize(PotsikCloudState(), voiceAvailable = true)
        val withoutVoice = potsikCloudWindowSize(PotsikCloudState(), voiceAvailable = false)
        assertEquals(POTSIK_CLOUD_WIDTH_DP, withoutVoice.widthDp)
        assertEquals(withVoice.heightDp + POTSIK_CLOUD_NOTICE_HEIGHT_DP, withoutVoice.heightDp)
        // The line lives on the menu only, so a pill is the same size either way.
        assertEquals(
            potsikCloudWindowSize(PotsikCloudState(phase = PotsikCloudPhase.Listening), voiceAvailable = true),
            potsikCloudWindowSize(PotsikCloudState(phase = PotsikCloudPhase.Listening), voiceAvailable = false),
        )
    }

    @Test
    fun `the pill is shorter than the menu`() {
        val pill = potsikCloudWindowSize(PotsikCloudState(phase = PotsikCloudPhase.Listening))
        assertEquals(POTSIK_CLOUD_PILL_WIDTH_DP, pill.widthDp)
        assertTrue(pill.heightDp < POTSIK_CLOUD_WINDOW_HEIGHT_DP)
    }

    @Test
    fun `the text field is wider than the menu and tall enough for the field`() {
        val field = potsikCloudWindowSize(PotsikCloudState(phase = PotsikCloudPhase.TextInput))
        assertEquals(POTSIK_CLOUD_INPUT_WIDTH_DP, field.widthDp)
        assertTrue(field.widthDp > POTSIK_CLOUD_WIDTH_DP, "a sentence has to be readable while it is typed")
        // A 56 dp OutlinedTextField plus the 8 dp padding on both sides and the tail.
        assertEquals(POTSIK_CLOUD_INPUT_HEIGHT_DP, field.heightDp)
        assertEquals(56f + POTSIK_CLOUD_GAP_DP * 2 + POTSIK_CLOUD_TAIL_HEIGHT_DP, field.heightDp)
    }

    @Test
    fun `the text field's window is lifted above the keyboard`() {
        // A field in the bottom half would be drawn under the IME: an overlay
        // is always below the keyboard, and no inset ever moves it.
        val height = 144
        val screen = 2400
        assertEquals(2400 / 2 - height, potsikCloudInputTop(y = 1900, windowHeightPx = height, screenHeightPx = screen))
        // Already high enough: the placement above his head is left alone.
        assertEquals(600, potsikCloudInputTop(y = 600, windowHeightPx = height, screenHeightPx = screen))
        // A screen shorter than the window keeps the field on it.
        assertEquals(0, potsikCloudInputTop(y = 900, windowHeightPx = 2000, screenHeightPx = screen))
    }

    @Test
    fun `the capsule is built from its own parts`() {
        // Three 48 dp slots, two 8 dp gaps, two 8 dp paddings.
        assertEquals(
            POTSIK_CLOUD_WIDTH_DP,
            POTSIK_CLOUD_BUTTON_DP * 3 + POTSIK_CLOUD_GAP_DP * 2 + POTSIK_CLOUD_GAP_DP * 2,
        )
        // One row of 48 dp inside an 8 dp padding on each side.
        assertEquals(POTSIK_CLOUD_HEIGHT_DP, POTSIK_CLOUD_BUTTON_DP + POTSIK_CLOUD_GAP_DP * 2)
    }
}
