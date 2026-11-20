package com.pusios.pots.ui.media

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Block 4 (owner): canvas elements show the whole frame, proportional to the
 * source — no square crop. Design contract (@joni): width stays 232.dp, the
 * height follows the source aspect ratio, clamped to roughly 16:9…9:16; beyond
 * the clamp the height sticks to the bound and the content renders Fit inside.
 */
class CanvasLayerProportionsTest {

    private fun assertClose(expected: Float, actual: Float) {
        assertEquals(expected, actual, 0.01f)
    }

    @Test
    fun squareSourceKeepsSquareBox() {
        assertClose(CANVAS_ELEMENT_SIZE_DP, canvasElementHeightDp(1f))
    }

    @Test
    fun landscape169GetsShorterBox() {
        // 232 / (16/9) = 130.5
        assertClose(232f * 9f / 16f, canvasElementHeightDp(16f / 9f))
    }

    @Test
    fun portrait916GetsTallerBox() {
        // 232 / (9/16) = 412.44
        assertClose(232f * 16f / 9f, canvasElementHeightDp(9f / 16f))
    }

    @Test
    fun moderateLandscape43() {
        assertClose(232f * 3f / 4f, canvasElementHeightDp(4f / 3f))
    }

    @Test
    fun extremePanoramaClampsToLandscapeBound() {
        // 3:1 is wider than the 16:9 clamp — height sticks to the bound.
        assertClose(232f * 9f / 16f, canvasElementHeightDp(3f))
    }

    @Test
    fun extremeTallClampsToPortraitBound() {
        assertClose(232f * 16f / 9f, canvasElementHeightDp(1f / 3f))
    }

    @Test
    fun unknownRatioDefaultsToSquareWithoutJump() {
        // Remote images: the ratio is known only after the load — until then
        // the box keeps the old square footprint (no visual jump).
        assertClose(CANVAS_ELEMENT_SIZE_DP, canvasElementHeightDp(null))
    }

    @Test
    fun junkRatiosDegradeToSquare() {
        assertClose(CANVAS_ELEMENT_SIZE_DP, canvasElementHeightDp(0f))
        assertClose(CANVAS_ELEMENT_SIZE_DP, canvasElementHeightDp(-1.5f))
        assertClose(CANVAS_ELEMENT_SIZE_DP, canvasElementHeightDp(Float.NaN))
        assertClose(CANVAS_ELEMENT_SIZE_DP, canvasElementHeightDp(Float.POSITIVE_INFINITY))
    }
}
