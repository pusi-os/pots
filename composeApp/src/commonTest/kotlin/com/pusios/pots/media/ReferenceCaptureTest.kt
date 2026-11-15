package com.pusios.pots.media

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Block 7 (@joni's contract, spec §5): the reference capture is the element
 * content with the pencil layer baked in, at the SOURCE's native resolution —
 * never the ~232.dp screen widget — and nothing from the UI chrome. Pure
 * pixel pipeline, hermetic: no provider, no platform capture.
 */
class ReferenceCaptureTest {

    @Test
    fun bakedCaptureKeepsSourceResolutionAndShowsStroke() {
        val w = 400
        val h = 300
        val source = PixelGrid(w, h, IntArray(w * h) { 0xFFFFFFFF.toInt() })
        val decoded = decodeImageToGrid(encodeGridToPng(source))
        assertEquals(w, decoded.width)
        assertEquals(h, decoded.height)

        val stroke = Stroke(
            points = listOf(Offset(50f, 50f), Offset(150f, 150f)),
            colorArgb = 0xFFE53935.toInt(),
            widthPx = 12f,
        )
        val capture = decodeImageToGrid(encodeGridToPng(decoded.bakeStrokes(listOf(stroke))))

        // The capture is the source resolution, not the canvas element box.
        assertEquals(w, capture.width)
        assertEquals(h, capture.height)
        // The pencil layer is in the frame: a mid-stroke pixel changed, a far one did not.
        assertTrue(decoded[100, 100] != capture[100, 100], "stroke must be baked into the capture")
        assertEquals(decoded[390, 10], capture[390, 10], "pixels away from the stroke stay untouched")
    }
}
