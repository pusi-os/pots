package com.pusios.pots.media

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

actual val isImageEditSupported: Boolean = true

/**
 * Desktop decodes with ImageIO and normalizes into an ARGB buffer, so every
 * supported format (PNG, JPEG, …) yields straight-alpha 0xAARRGGBB pixels.
 */
actual fun decodeImageToGrid(bytes: ByteArray): PixelGrid {
    if (bytes.isEmpty()) throw ImageEditException.DecodeFailed()
    val decoded = try {
        ImageIO.read(ByteArrayInputStream(bytes))
    } catch (e: Exception) {
        null
    } ?: throw ImageEditException.DecodeFailed()
    // getRGB returns unpremultiplied ARGB from any storage type; going through
    // Graphics2D instead would premultiply and destroy low-alpha colors.
    return PixelGrid(decoded.width, decoded.height, decoded.getRGB(0, 0, decoded.width, decoded.height, null, 0, decoded.width))
}

/** PNG keeps every channel lossless, so edited pixels survive a round trip. */
actual fun encodeGridToPng(grid: PixelGrid): ByteArray {
    val image = BufferedImage(grid.width, grid.height, BufferedImage.TYPE_INT_ARGB)
    image.setRGB(0, 0, grid.width, grid.height, grid.argb, 0, grid.width)
    val out = ByteArrayOutputStream()
    val written = try {
        ImageIO.write(image, "png", out)
    } catch (e: Exception) {
        false
    }
    if (!written) throw ImageEditException.ExportFailed()
    return out.toByteArray()
}
