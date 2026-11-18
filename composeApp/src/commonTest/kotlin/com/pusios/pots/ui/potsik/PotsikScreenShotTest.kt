package com.pusios.pots.ui.potsik

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The parts of the screen mode that do not need a phone: the plane copy, the
 * caption, and which consent intent an OS version gets.
 *
 * The plane copy is where the feature actually breaks: the platform pads rows,
 * and a buffer read as one continuous block gives a sheared picture that looks
 * like noise. Every row stride the platform can report is checked here on
 * synthetic bytes, so the shape of the bug is caught without a device.
 */
class PotsikScreenShotTest {

    @Test
    fun `a tightly packed plane is copied as it is`() {
        // 2×2 pixels, rowStride exactly width * 4: the fast path.
        val source = ByteArray(2 * 2 * 4) { it.toByte() }
        val packed = potsikRepackRgba(source, width = 2, height = 2, rowStride = 8, pixelStride = 4)
        assertContentEquals(source, packed)
    }

    @Test
    fun `a padded row stride keeps the rows where they belong`() {
        // 2×2 pixels, rowStride 12: four bytes of padding after every row.
        // Reading this as one block would shift the second row by four bytes.
        val source = byteArrayOf(
            1, 2, 3, 4, 5, 6, 7, 8, 0, 0, 0, 0,
            9, 10, 11, 12, 13, 14, 15, 16, 0, 0, 0, 0,
        )
        val packed = potsikRepackRgba(source, width = 2, height = 2, rowStride = 12, pixelStride = 4)
        assertContentEquals(
            byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16),
            packed,
        )
    }

    @Test
    fun `a wider pixel stride is walked, not assumed`() {
        // Every pixel occupies 8 bytes (the first four are the colour, the rest
        // is padding): a copy that assumes 4 would take the padding as pixels.
        val source = byteArrayOf(
            1, 2, 3, 4, 90, 91, 92, 93,
            5, 6, 7, 8, 94, 95, 96, 97,
        )
        val packed = potsikRepackRgba(source, width = 2, height = 1, rowStride = 16, pixelStride = 8)
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), packed)
    }

    @Test
    fun `the packed buffer is width times four bytes per row`() {
        val source = ByteArray(100 * 3 * 4)
        val packed = potsikRepackRgba(source, width = 100, height = 3, rowStride = 100 * 4, pixelStride = 4)
        assertEquals(100 * 4 * 3, packed.size)
    }

    @Test
    fun `a plane that is too short is refused instead of read past its end`() {
        assertFailsWith<IllegalArgumentException> {
            potsikRepackRgba(ByteArray(10), width = 4, height = 2, rowStride = 16, pixelStride = 4)
        }
    }

    @Test
    fun `a pixel stride below four is refused`() {
        assertFailsWith<IllegalArgumentException> {
            potsikRepackRgba(ByteArray(64), width = 4, height = 2, rowStride = 16, pixelStride = 2)
        }
    }

    @Test
    fun `the caption is the size with a multiplication sign`() {
        assertEquals("1080×2400", potsikScreenshotSizeLabel(1080, 2400))
        assertEquals("2400×1080", potsikScreenshotSizeLabel(2400, 1080))
    }

    @Test
    fun `the whole display is asked for from Android 14 on`() {
        assertEquals(PotsikScreenCaptureRequest.UserChoice, potsikScreenCaptureRequest(26))
        assertEquals(PotsikScreenCaptureRequest.UserChoice, potsikScreenCaptureRequest(33))
        assertEquals(PotsikScreenCaptureRequest.WholeDisplay, potsikScreenCaptureRequest(34))
        assertEquals(PotsikScreenCaptureRequest.WholeDisplay, potsikScreenCaptureRequest(35))
        assertEquals(PotsikScreenCaptureRequest.WholeDisplay, potsikScreenCaptureRequest(37))
    }
}
