package com.pusios.pots.media

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/** Solid ARGB helper: 0xAARRGGBB. */
private fun argb(a: Int, r: Int, g: Int, b: Int): Int = (a and 0xFF shl 24) or (r and 0xFF shl 16) or (g and 0xFF shl 8) or (b and 0xFF)

private fun solid(width: Int, height: Int, color: Int) = PixelGrid(width, height, IntArray(width * height) { color })

class ImageEditTest {

    // -- PixelGrid ----------------------------------------------------------

    @Test
    fun gridRejectsNonPositiveDimensions() {
        assertFailsWith<IllegalArgumentException> { PixelGrid(0, 4, IntArray(0)) }
        assertFailsWith<IllegalArgumentException> { PixelGrid(4, -1, IntArray(4)) }
    }

    @Test
    fun gridRejectsMismatchedPixelArray() {
        assertFailsWith<IllegalArgumentException> { PixelGrid(2, 2, IntArray(3)) }
    }

    @Test
    fun gridGetSetRoundTrip() {
        val grid = solid(3, 2, argb(255, 0, 0, 0))
        grid.set(2, 1, argb(255, 10, 20, 30))
        assertEquals(argb(255, 10, 20, 30), grid[2, 1])
        assertEquals(argb(255, 0, 0, 0), grid[0, 0])
    }

    @Test
    fun gridCopyIsIndependent() {
        val grid = solid(2, 2, argb(255, 1, 2, 3))
        val copy = grid.copy()
        assertNotSame(grid, copy)
        copy.set(0, 0, argb(255, 99, 99, 99))
        assertEquals(argb(255, 1, 2, 3), grid[0, 0])
    }

    // -- Crop ---------------------------------------------------------------

    @Test
    fun cropCutsTheExactRegion() {
        // 3x3 grid, pixels numbered by index: color encodes x,y.
        val grid = PixelGrid(3, 3, IntArray(9) { i -> argb(255, i % 3, i / 3, 0) })
        val cropped = grid.cropGrid(1, 1, 2, 2)
        assertEquals(2, cropped.width)
        assertEquals(2, cropped.height)
        assertEquals(argb(255, 1, 1, 0), cropped[0, 0])
        assertEquals(argb(255, 2, 1, 0), cropped[1, 0])
        assertEquals(argb(255, 1, 2, 0), cropped[0, 1])
        assertEquals(argb(255, 2, 2, 0), cropped[1, 1])
        // Source is untouched (non-destructive).
        assertEquals(argb(255, 0, 0, 0), grid[0, 0])
    }

    @Test
    fun cropClampsToImageBounds() {
        val grid = PixelGrid(4, 4, IntArray(16) { i -> argb(255, i % 4, i / 4, 0) })
        // Oversized region with a negative origin clamps to the whole image.
        val cropped = grid.cropGrid(-2, -1, 10, 10)
        assertEquals(4, cropped.width)
        assertEquals(4, cropped.height)
        assertEquals(argb(255, 0, 0, 0), cropped[0, 0])
        assertEquals(argb(255, 3, 3, 0), cropped[3, 3])
        // Negative origin with a small region keeps only the overlapping part.
        val corner = grid.cropGrid(-1, -2, 3, 10)
        assertEquals(2, corner.width)
        assertEquals(4, corner.height)
        assertEquals(argb(255, 1, 0, 0), corner[1, 0])
    }

    @Test
    fun cropOutsideTheImageThrowsEmptyCropWithRussianRescue() {
        val grid = solid(4, 4, argb(255, 0, 0, 0))
        val error = assertFailsWith<ImageEditException.EmptyCrop> { grid.cropGrid(8, 8, 2, 2) }
        assertEquals("Пустая область обрезки", error.title)
        assertTrue(error.detail.isNotBlank())
        assertTrue(error.fix.isNotBlank())
    }

    @Test
    fun cropZeroSizeThrowsEmptyCrop() {
        val grid = solid(4, 4, argb(255, 0, 0, 0))
        assertFailsWith<ImageEditException.EmptyCrop> { grid.cropGrid(1, 1, 0, 2) }
    }

    // -- Resize -------------------------------------------------------------

    @Test
    fun resizeKeepsCornerPixelsAndReportsProgress() {
        // 2x2 checkerboard, each quadrant a different color.
        val red = argb(255, 255, 0, 0)
        val green = argb(255, 0, 255, 0)
        val blue = argb(255, 0, 0, 255)
        val white = argb(255, 255, 255, 255)
        val grid = PixelGrid(2, 2, intArrayOf(red, green, blue, white))
        var lastProgress = 0f
        val resized = grid.resizeGrid(4, 4) { fraction ->
            lastProgress = fraction
            true
        }
        assertEquals(4, resized.width)
        assertEquals(4, resized.height)
        assertEquals(red, resized[0, 0])
        assertEquals(green, resized[3, 0])
        assertEquals(blue, resized[0, 3])
        assertEquals(white, resized[3, 3])
        assertEquals(1f, lastProgress)
    }

    @Test
    fun resizeDownscaleProducesRequestedDimensions() {
        val grid = solid(10, 6, argb(255, 7, 7, 7))
        val resized = grid.resizeGrid(5, 3)
        assertEquals(5, resized.width)
        assertEquals(3, resized.height)
        assertEquals(argb(255, 7, 7, 7), resized[2, 1])
    }

    @Test
    fun resizeRejectsNonPositiveSize() {
        val grid = solid(2, 2, argb(255, 0, 0, 0))
        val error = assertFailsWith<ImageEditException.BadSize> { grid.resizeGrid(0, 4) }
        assertEquals("Недопустимый размер", error.title)
    }

    @Test
    fun resizeCancellationLeavesSourceIntact() {
        val grid = solid(8, 8, argb(255, 1, 2, 3))
        assertFailsWith<ImageEditException.Cancelled> { grid.resizeGrid(64, 64) { false } }
        assertEquals(argb(255, 1, 2, 3), grid[0, 0])
    }

    // -- Rotate / flip --------------------------------------------------------

    @Test
    fun rotate90ClockwiseMovesPixels() {
        // 3x2 grid, color encodes (x, y).
        val grid = PixelGrid(3, 2, IntArray(6) { i -> argb(255, i % 3, i / 3, 0) })
        val rotated = grid.rotateQuadrant(1)
        assertEquals(2, rotated.width)
        assertEquals(3, rotated.height)
        // (x, y) -> (h - 1 - y, x): (0,0)->(1,0), (2,1)->(0,2)
        assertEquals(argb(255, 0, 0, 0), rotated[1, 0])
        assertEquals(argb(255, 2, 1, 0), rotated[0, 2])
        assertEquals(argb(255, 1, 1, 0), rotated[0, 1])
    }

    @Test
    fun rotateFullTurnIsIdentity() {
        val grid = PixelGrid(2, 3, IntArray(6) { it })
        val rotated = grid.rotateQuadrant(4)
        assertEquals(grid.width, rotated.width)
        assertContentEquals(grid.argb, rotated.argb)
    }

    @Test
    fun rotateNegativeTurnsCounterClockwise() {
        val grid = PixelGrid(3, 2, IntArray(6) { i -> argb(255, i % 3, i / 3, 0) })
        // -1 == three clockwise turns.
        val rotated = grid.rotateQuadrant(-1)
        assertEquals(2, rotated.width)
        assertEquals(3, rotated.height)
        // Counter-clockwise: (x, y) -> (y, w - 1 - x): (2,0)->(0,0), (0,1)->(1,2)
        assertEquals(argb(255, 2, 0, 0), rotated[0, 0])
        assertEquals(argb(255, 0, 1, 0), rotated[1, 2])
    }

    @Test
    fun flipHorizontalMirrorsRows() {
        val grid = PixelGrid(3, 1, intArrayOf(argb(255, 1, 0, 0), argb(255, 2, 0, 0), argb(255, 3, 0, 0)))
        val flipped = grid.flipHorizontal()
        assertContentEquals(
            intArrayOf(argb(255, 3, 0, 0), argb(255, 2, 0, 0), argb(255, 1, 0, 0)),
            flipped.argb,
        )
    }

    @Test
    fun flipVerticalMirrorsColumns() {
        val grid = PixelGrid(1, 3, intArrayOf(argb(255, 1, 0, 0), argb(255, 2, 0, 0), argb(255, 3, 0, 0)))
        val flipped = grid.flipVertical()
        assertContentEquals(
            intArrayOf(argb(255, 3, 0, 0), argb(255, 2, 0, 0), argb(255, 1, 0, 0)),
            flipped.argb,
        )
    }

    // -- Brightness / contrast -------------------------------------------------

    @Test
    fun adjustBrightnessShiftsChannelsKeepingAlpha() {
        val grid = PixelGrid(1, 1, intArrayOf(argb(200, 100, 128, 10)))
        val adjusted = grid.adjustBrightnessContrast(brightness = 0.2f, contrast = 1f)
        val pixel = adjusted[0, 0]
        assertEquals(200, pixel ushr 24 and 0xFF)
        assertEquals(151, pixel shr 16 and 0xFF) // 100 + 0.2*255 = 151
        assertEquals(179, pixel shr 8 and 0xFF) // 128 + 51
        assertEquals(61, pixel and 0xFF) // 10 + 51
    }

    @Test
    fun adjustContrastScalesAroundMidpoint() {
        val grid = PixelGrid(1, 1, intArrayOf(argb(255, 128, 78, 178)))
        val adjusted = grid.adjustBrightnessContrast(brightness = 0f, contrast = 2f)
        val pixel = adjusted[0, 0]
        assertEquals(128, pixel shr 16 and 0xFF) // midpoint stays
        assertEquals(28, pixel shr 8 and 0xFF) // (78-128)*2+128 = 28
        assertEquals(228, pixel and 0xFF) // (178-128)*2+128 = 228
    }

    @Test
    fun adjustClampsChannels() {
        val grid = PixelGrid(1, 1, intArrayOf(argb(255, 250, 5, 128)))
        val up = grid.adjustBrightnessContrast(brightness = 1f, contrast = 1f)
        assertEquals(255, up[0, 0] shr 16 and 0xFF)
        assertEquals(255, up[0, 0] shr 8 and 0xFF)
        val down = grid.adjustBrightnessContrast(brightness = -1f, contrast = 1f)
        assertEquals(0, down[0, 0] shr 16 and 0xFF)
        assertEquals(0, down[0, 0] shr 8 and 0xFF)
    }

    @Test
    fun adjustRejectsOutOfRangeParameters() {
        val grid = solid(1, 1, argb(255, 0, 0, 0))
        assertFailsWith<ImageEditException.BadAdjust> { grid.adjustBrightnessContrast(brightness = 1.5f, contrast = 1f) }
        assertFailsWith<ImageEditException.BadAdjust> { grid.adjustBrightnessContrast(brightness = 0f, contrast = -0.1f) }
        assertFailsWith<ImageEditException.BadAdjust> { grid.adjustBrightnessContrast(brightness = 0f, contrast = 4.1f) }
    }

    @Test
    fun identityAdjustReturnsSamePixels() {
        val grid = PixelGrid(2, 1, intArrayOf(argb(255, 1, 2, 3), argb(128, 9, 8, 7)))
        val adjusted = grid.adjustBrightnessContrast(brightness = 0f, contrast = 1f)
        assertContentEquals(grid.argb, adjusted.argb)
    }
}
