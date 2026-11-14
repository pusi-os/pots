package com.pusios.pots.media

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Overlay blending and the drawing-layer state machine (spec §5, §6). */
class ImageOverlayDrawTest {

    // -- Overlay -------------------------------------------------------------

    @Test
    fun opaqueOverlayReplacesBaseInsideItsBoundsOnly() {
        val base = solidGrid(6, 6, argb(255, 255, 255, 255))
        val top = solidGrid(2, 2, argb(255, 0, 0, 0))
        val result = overlayGrid(base, top, offsetX = 2, offsetY = 2)
        assertEquals(argb(255, 0, 0, 0), result[2, 2])
        assertEquals(argb(255, 0, 0, 0), result[3, 3])
        // Outside the overlay: untouched.
        assertEquals(argb(255, 255, 255, 255), result[1, 1])
        assertEquals(argb(255, 255, 255, 255), result[4, 4])
        assertEquals(argb(255, 255, 255, 255), result[0, 0])
    }

    @Test
    fun opacityBlendsOverlayWithBase() {
        val base = solidGrid(1, 1, argb(255, 255, 0, 0))
        val top = solidGrid(1, 1, argb(255, 0, 0, 255))
        val result = overlayGrid(base, top, offsetX = 0, offsetY = 0, opacity = 0.25f)
        val pixel = result[0, 0]
        assertEquals(255, pixel ushr 24 and 0xFF)
        // red: 255*0.75 = 191.25 -> 191; blue: 255*0.25 = 63.75 -> 64
        assertEquals(191, pixel shr 16 and 0xFF)
        assertEquals(0, pixel shr 8 and 0xFF)
        assertEquals(64, pixel and 0xFF)
    }

    @Test
    fun zeroOpacityLeavesBaseUnchanged() {
        val base = solidGrid(3, 3, argb(255, 10, 20, 30))
        val top = solidGrid(2, 2, argb(255, 200, 200, 200))
        val result = overlayGrid(base, top, offsetX = 0, offsetY = 0, opacity = 0f)
        assertContentEquals(base.argb, result.argb)
    }

    @Test
    fun overlayScalesBeforeBlending() {
        val base = solidGrid(6, 6, argb(255, 0, 0, 0))
        val top = solidGrid(2, 2, argb(255, 255, 255, 0))
        val result = overlayGrid(base, top, offsetX = 1, offsetY = 1, scale = 2f)
        // 2x2 scaled to 4x4 covers base pixels (1..4, 1..4).
        assertEquals(argb(255, 255, 255, 0), result[1, 1])
        assertEquals(argb(255, 255, 255, 0), result[4, 4])
        assertEquals(argb(255, 0, 0, 0), result[5, 5])
        assertEquals(argb(255, 0, 0, 0), result[0, 0])
    }

    @Test
    fun overlayPartiallyOutsideBlendsOnlyVisiblePart() {
        val base = solidGrid(4, 4, argb(255, 0, 0, 0))
        val top = solidGrid(3, 3, argb(255, 255, 0, 255))
        val result = overlayGrid(base, top, offsetX = -2, offsetY = -2)
        assertEquals(argb(255, 255, 0, 255), result[0, 0])
        assertEquals(argb(255, 0, 0, 0), result[1, 1])
    }

    @Test
    fun opaqueOverlayCoversTransparentBase() {
        val base = solidGrid(2, 2, argb(0, 0, 0, 0))
        val top = solidGrid(1, 1, argb(255, 0, 255, 0))
        val result = overlayGrid(base, top, offsetX = 1, offsetY = 1)
        assertEquals(argb(255, 0, 255, 0), result[1, 1])
        assertEquals(argb(0, 0, 0, 0), result[0, 0])
    }

    @Test
    fun overlayRejectsBadParameters() {
        val base = solidGrid(2, 2, argb(255, 0, 0, 0))
        val top = solidGrid(1, 1, argb(255, 0, 0, 0))
        assertFailsWith<ImageEditException.BadOverlay> { overlayGrid(base, top, 0, 0, opacity = 1.5f) }
        assertFailsWith<ImageEditException.BadOverlay> { overlayGrid(base, top, 0, 0, opacity = -0.1f) }
        assertFailsWith<ImageEditException.BadOverlay> { overlayGrid(base, top, 0, 0, scale = 0f) }
    }

    @Test
    fun overlayDoesNotTouchTheBase() {
        val base = solidGrid(3, 3, argb(255, 1, 2, 3))
        val top = solidGrid(2, 2, argb(255, 9, 9, 9))
        overlayGrid(base, top, offsetX = 0, offsetY = 0)
        assertContentEquals(IntArray(9) { argb(255, 1, 2, 3) }, base.argb)
    }

    @Test
    fun overlayReportsProgressAndCancels() {
        val base = solidGrid(8, 8, argb(255, 0, 0, 0))
        val top = solidGrid(8, 8, argb(255, 1, 1, 1))
        var last = 0f
        overlayGrid(base, top, 0, 0) { fraction ->
            last = fraction
            true
        }
        assertEquals(1f, last)
        assertFailsWith<ImageEditException.Cancelled> {
            overlayGrid(base, top, 0, 0) { false }
        }
    }

    // -- Drawing layer state ---------------------------------------------------

    @Test
    fun layerStartsEmpty() {
        val layer = DrawingLayer()
        assertTrue(layer.isEmpty)
        assertTrue(layer.strokes.isEmpty())
    }

    @Test
    fun finishedStrokeLandsInLayer() {
        val layer = DrawingLayer()
        layer.startStroke(argb(255, 255, 0, 0), widthPx = 6f, at = Offset(1f, 1f))
        layer.extendStroke(Offset(2f, 2f))
        layer.extendStroke(Offset(5f, 6f))
        layer.finishStroke()
        assertEquals(1, layer.strokes.size)
        val stroke = layer.strokes.single()
        assertEquals(3, stroke.points.size)
        assertEquals(argb(255, 255, 0, 0), stroke.colorArgb)
        assertEquals(6f, stroke.widthPx)
    }

    @Test
    fun duplicateConsecutivePointsAreDropped() {
        val layer = DrawingLayer()
        layer.startStroke(argb(255, 0, 0, 0), widthPx = 2f, at = Offset(3f, 3f))
        layer.extendStroke(Offset(3f, 3f))
        layer.extendStroke(Offset(4f, 3f))
        layer.finishStroke()
        assertEquals(2, layer.strokes.single().points.size)
    }

    @Test
    fun unfinishedStrokeIsNotCountedAndClearsOnReset() {
        val layer = DrawingLayer()
        layer.startStroke(argb(255, 0, 0, 0), widthPx = 2f, at = Offset(0f, 0f))
        layer.extendStroke(Offset(9f, 9f))
        assertTrue(layer.strokes.isEmpty())
        assertFalse(layer.isEmpty) // a stroke in progress counts as content
        layer.discardCurrentStroke()
        assertTrue(layer.isEmpty)
    }

    @Test
    fun undoRemovesLastStroke() {
        val layer = DrawingLayer()
        layer.startStroke(argb(255, 1, 1, 1), widthPx = 2f, at = Offset(0f, 0f))
        layer.finishStroke()
        layer.startStroke(argb(255, 2, 2, 2), widthPx = 2f, at = Offset(1f, 1f))
        layer.finishStroke()
        assertTrue(layer.undoLast())
        assertEquals(1, layer.strokes.size)
        assertEquals(argb(255, 1, 1, 1), layer.strokes.single().colorArgb)
        assertTrue(layer.undoLast())
        assertFalse(layer.undoLast())
        assertTrue(layer.isEmpty)
    }

    @Test
    fun clearDropsEverything() {
        val layer = DrawingLayer()
        layer.startStroke(argb(255, 1, 1, 1), widthPx = 2f, at = Offset(0f, 0f))
        layer.finishStroke()
        layer.startStroke(argb(255, 2, 2, 2), widthPx = 2f, at = Offset(1f, 1f))
        layer.clear()
        assertTrue(layer.isEmpty)
        assertTrue(layer.strokes.isEmpty())
    }

    // -- Stroke baking -----------------------------------------------------------

    @Test
    fun horizontalStrokePaintsAlongTheLineOnly() {
        val canvas = solidGrid(12, 11, argb(255, 255, 255, 255))
        val stroke = Stroke(
            points = listOf(Offset(3f, 5f), Offset(9f, 5f)),
            colorArgb = argb(255, 255, 0, 0),
            widthPx = 3f,
        )
        val baked = canvas.bakeStrokes(listOf(stroke))
        val red = argb(255, 255, 0, 0)
        assertEquals(red, baked[6, 5]) // on the line
        assertEquals(red, baked[3, 5]) // round cap start
        assertEquals(red, baked[9, 5]) // round cap end
        assertEquals(red, baked[6, 4]) // half-width above (1 <= 1.5)
        assertEquals(red, baked[6, 6]) // half-width below
        assertEquals(argb(255, 255, 255, 255), baked[6, 2]) // 3 px away: untouched
        assertEquals(argb(255, 255, 255, 255), baked[0, 5]) // left of the cap
        assertEquals(argb(255, 255, 255, 255), baked[11, 5]) // right of the cap
    }

    @Test
    fun singlePointStrokePaintsADot() {
        val canvas = solidGrid(7, 7, argb(255, 255, 255, 255))
        val stroke = Stroke(listOf(Offset(3.4f, 3.6f)), argb(255, 0, 0, 255), widthPx = 4f)
        val baked = canvas.bakeStrokes(listOf(stroke))
        assertEquals(argb(255, 0, 0, 255), baked[3, 3])
        assertEquals(argb(255, 255, 255, 255), baked[0, 0])
        assertEquals(argb(255, 255, 255, 255), baked[6, 6])
    }

    @Test
    fun semiTransparentStrokeBlendsWithCanvas() {
        val canvas = solidGrid(5, 1, argb(255, 255, 255, 255))
        val stroke = Stroke(listOf(Offset(0.5f, 0.5f), Offset(4.5f, 0.5f)), argb(128, 0, 0, 0), widthPx = 2f)
        val baked = canvas.bakeStrokes(listOf(stroke))
        val pixel = baked[2, 0]
        // "over" composite of 50% black over opaque white -> ~128 grey, opaque.
        assertEquals(255, pixel ushr 24 and 0xFF)
        val grey = pixel shr 16 and 0xFF
        assertTrue(grey in 126..130, "expected ~128 grey, got $grey")
        assertEquals(pixel shr 16 and 0xFF, pixel shr 8 and 0xFF)
        assertEquals(pixel shr 8 and 0xFF, pixel and 0xFF)
    }

    @Test
    fun bakeKeepsTheSourceCanvasIntact() {
        val canvas = solidGrid(6, 6, argb(255, 255, 255, 255))
        val stroke = Stroke(listOf(Offset(1f, 1f), Offset(4f, 4f)), argb(255, 0, 0, 0), widthPx = 3f)
        canvas.bakeStrokes(listOf(stroke))
        assertContentEquals(IntArray(36) { argb(255, 255, 255, 255) }, canvas.argb)
    }

    @Test
    fun emptyStrokeListReturnsIdenticalPixels() {
        val canvas = PixelGrid(2, 2, intArrayOf(argb(255, 1, 2, 3), argb(9, 9, 9, 9), argb(8, 8, 8, 8), argb(7, 7, 7, 7)))
        val baked = canvas.bakeStrokes(emptyList())
        assertContentEquals(canvas.argb, baked.argb)
    }

    // -- Sketch trimming (empty-canvas draw result) ----------------------------

    @Test
    fun trimCutsToTheOpaqueContentBoundingBox() {
        val grid = solidGrid(6, 6, argb(0, 0, 0, 0))
        val red = argb(255, 255, 0, 0)
        grid.set(2, 3, red)
        grid.set(3, 3, red)
        grid.set(2, 4, red)
        grid.set(3, 4, red)
        val trimmed = grid.trimToContent()!!
        assertEquals(2, trimmed.width)
        assertEquals(2, trimmed.height)
        assertContentEquals(IntArray(4) { red }, trimmed.argb)
    }

    @Test
    fun trimCountsLowAlphaPixelsAsContent() {
        val grid = solidGrid(5, 5, argb(0, 0, 0, 0))
        grid.set(1, 1, argb(1, 10, 10, 10)) // barely visible anti-aliased edge
        grid.set(3, 2, argb(255, 10, 10, 10))
        val trimmed = grid.trimToContent()!!
        assertEquals(3, trimmed.width) // x 1..3
        assertEquals(2, trimmed.height) // y 1..2
        assertEquals(argb(1, 10, 10, 10), trimmed[0, 0])
        assertEquals(argb(255, 10, 10, 10), trimmed[2, 1])
    }

    @Test
    fun trimReturnsNullWhenNothingIsDrawn() {
        val grid = solidGrid(4, 3, argb(0, 0, 0, 0))
        assertEquals(null, grid.trimToContent())
    }

    @Test
    fun trimKeepsContentThatTouchesTheEdges() {
        val grid = solidGrid(4, 4, argb(0, 0, 0, 0))
        grid.set(0, 0, argb(255, 1, 1, 1))
        grid.set(3, 1, argb(255, 2, 2, 2))
        val trimmed = grid.trimToContent()!!
        assertEquals(4, trimmed.width) // x 0..3
        assertEquals(2, trimmed.height) // y 0..1
        assertEquals(argb(255, 1, 1, 1), trimmed[0, 0])
        assertEquals(argb(255, 2, 2, 2), trimmed[3, 1])
    }

    @Test
    fun trimDoesNotModifyTheSourceGrid() {
        val grid = solidGrid(3, 3, argb(0, 0, 0, 0))
        grid.set(1, 1, argb(255, 9, 9, 9))
        val before = grid.argb.copyOf()
        grid.trimToContent()
        assertContentEquals(before, grid.argb)
    }

    @Test
    fun bakedSketchTrimsToTheStrokeBounds() {
        val sketch = solidGrid(12, 12, argb(0, 0, 0, 0))
        val stroke = Stroke(
            points = listOf(Offset(3f, 4f), Offset(6f, 4f)),
            colorArgb = argb(255, 255, 0, 0),
            widthPx = 2f,
        )
        val baked = sketch.bakeStrokes(listOf(stroke))
        // Outside the painted caps the sheet stays fully transparent.
        assertEquals(argb(0, 0, 0, 0), baked[1, 4])
        assertEquals(argb(0, 0, 0, 0), baked[7, 4])
        val trimmed = baked.trimToContent()!!
        // Round caps of radius 1 around the (3,4)-(6,4) segment reach pixels
        // x 2..6 and y 3..4 (distance is measured from pixel centers).
        assertEquals(5, trimmed.width)
        assertEquals(2, trimmed.height)
        assertEquals(argb(255, 255, 0, 0), trimmed[0, 0]) // left cap corner
        assertEquals(argb(255, 255, 0, 0), trimmed[4, 1]) // right cap corner
    }
}

private fun argb(a: Int, r: Int, g: Int, b: Int): Int = (a and 0xFF shl 24) or (r and 0xFF shl 16) or (g and 0xFF shl 8) or (b and 0xFF)

private fun solidGrid(width: Int, height: Int, color: Int) = PixelGrid(width, height, IntArray(width * height) { color })
