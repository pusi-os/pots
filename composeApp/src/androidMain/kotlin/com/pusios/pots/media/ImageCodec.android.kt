package com.pusios.pots.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory

actual val isImageEditSupported: Boolean = true

/** Android decodes with BitmapFactory; getPixels yields unpremultiplied ARGB. */
actual fun decodeImageToGrid(bytes: ByteArray): PixelGrid {
    if (bytes.isEmpty()) throw ImageEditException.DecodeFailed()
    val decoded = try {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (e: Exception) {
        null
    } ?: throw ImageEditException.DecodeFailed()
    val width = decoded.width
    val height = decoded.height
    val source = if (decoded.config == Bitmap.Config.ARGB_8888) {
        decoded
    } else {
        decoded.copy(Bitmap.Config.ARGB_8888, false) ?: run {
            decoded.recycle()
            throw ImageEditException.DecodeFailed()
        }
    }
    val argb = IntArray(width * height)
    source.getPixels(argb, 0, width, 0, 0, width, height)
    if (source !== decoded) source.recycle()
    decoded.recycle()
    return PixelGrid(width, height, argb)
}

/** PNG keeps every channel lossless, so edited pixels survive a round trip. */
actual fun encodeGridToPng(grid: PixelGrid): ByteArray {
    val bitmap = Bitmap.createBitmap(grid.width, grid.height, Bitmap.Config.ARGB_8888)
    bitmap.setPixels(grid.argb, 0, grid.width, 0, 0, grid.width, grid.height)
    val out = java.io.ByteArrayOutputStream()
    val written = try {
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    } catch (e: Exception) {
        false
    }
    bitmap.recycle()
    if (!written) throw ImageEditException.ExportFailed()
    return out.toByteArray()
}
