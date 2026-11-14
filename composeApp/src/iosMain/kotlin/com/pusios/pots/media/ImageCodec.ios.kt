package com.pusios.pots.media

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

actual val isImageEditSupported: Boolean = true

/** Skia decodes any known format; pixels are read as straight-alpha RGBA. */
actual fun decodeImageToGrid(bytes: ByteArray): PixelGrid {
    if (bytes.isEmpty()) throw ImageEditException.DecodeFailed()
    val image = try {
        Image.makeFromEncoded(bytes)
    } catch (e: Exception) {
        throw ImageEditException.DecodeFailed()
    }
    val width = image.width
    val height = image.height
    try {
        val bitmap = Bitmap.makeFromImage(image)
        val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
        val rgba = bitmap.readPixels(info, width * 4, 0, 0) ?: throw ImageEditException.DecodeFailed()
        if (rgba.size < width * height * 4) throw ImageEditException.DecodeFailed()
        val argb = IntArray(width * height) { i ->
            val r = rgba[i * 4].toInt() and 0xFF
            val g = rgba[i * 4 + 1].toInt() and 0xFF
            val b = rgba[i * 4 + 2].toInt() and 0xFF
            val a = rgba[i * 4 + 3].toInt() and 0xFF
            (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return PixelGrid(width, height, argb)
    } catch (e: ImageEditException) {
        throw e
    } catch (e: Exception) {
        throw ImageEditException.DecodeFailed()
    }
}

/** PNG keeps every channel lossless, so edited pixels survive a round trip. */
actual fun encodeGridToPng(grid: PixelGrid): ByteArray {
    val rgba = ByteArray(grid.width * grid.height * 4)
    for (i in grid.argb.indices) {
        val pixel = grid.argb[i]
        rgba[i * 4] = (pixel shr 16 and 0xFF).toByte()
        rgba[i * 4 + 1] = (pixel shr 8 and 0xFF).toByte()
        rgba[i * 4 + 2] = (pixel and 0xFF).toByte()
        rgba[i * 4 + 3] = (pixel ushr 24 and 0xFF).toByte()
    }
    val info = ImageInfo(grid.width, grid.height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
    val image = try {
        Image.makeRaster(info, rgba, grid.width * 4)
    } catch (e: Exception) {
        throw ImageEditException.ExportFailed()
    }
    val data = try {
        image.encodeToData(EncodedImageFormat.PNG)
    } catch (e: Exception) {
        null
    } ?: throw ImageEditException.ExportFailed()
    return data.bytes
}
