package com.pusios.pots.media

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Desktop codec round-trip: PNG is lossless, so pixels must survive exactly. */
class ImageCodecTest {

    private fun argb(a: Int, r: Int, g: Int, b: Int): Int = (a and 0xFF shl 24) or (r and 0xFF shl 16) or (g and 0xFF shl 8) or (b and 0xFF)

    @Test
    fun desktopSupportsImageEditing() {
        assertTrue(isImageEditSupported)
    }

    @Test
    fun encodeDecodeRoundTripKeepsEveryPixelIncludingAlpha() {
        val original = PixelGrid(
            4,
            3,
            intArrayOf(
                argb(255, 255, 0, 0), argb(255, 0, 255, 0), argb(255, 0, 0, 255), argb(255, 255, 255, 255),
                argb(128, 10, 20, 30), argb(1, 2, 3, 4), argb(254, 9, 8, 7), argb(200, 100, 150, 200),
                argb(0, 255, 255, 255), argb(255, 0, 0, 0), argb(77, 88, 99, 111), argb(255, 128, 128, 128),
            ),
        )
        val png = encodeGridToPng(original)
        assertTrue(png.size > 8)
        // PNG magic: 0x89 'P' 'N' 'G'
        assertEquals(0x89.toByte(), png[0])
        assertEquals('P'.code.toByte(), png[1])
        val decoded = decodeImageToGrid(png)
        assertEquals(original.width, decoded.width)
        assertEquals(original.height, decoded.height)
        assertContentEquals(original.argb, decoded.argb)
    }

    @Test
    fun decodeGarbageThrowsDecodeFailed() {
        val error = assertFailsWith<ImageEditException.DecodeFailed> {
            decodeImageToGrid(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10))
        }
        assertEquals("Не удалось прочитать изображение", error.title)
    }

    @Test
    fun decodeEmptyThrowsDecodeFailed() {
        assertFailsWith<ImageEditException.DecodeFailed> { decodeImageToGrid(ByteArray(0)) }
    }
}
