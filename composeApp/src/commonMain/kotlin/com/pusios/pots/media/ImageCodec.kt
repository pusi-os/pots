package com.pusios.pots.media

/**
 * Platform image codecs for the editors. Decoding/encoding is the only
 * platform-dependent step; every edit rule lives in pure common code.
 * Targets without a usable codec keep the UI entries hidden via
 * [isImageEditSupported].
 */
expect val isImageEditSupported: Boolean

/**
 * Decodes PNG/JPEG bytes into straight-alpha ARGB pixels.
 * Throws [ImageEditException.DecodeFailed] on unreadable input.
 */
expect fun decodeImageToGrid(bytes: ByteArray): PixelGrid

/**
 * Encodes pixels as PNG (lossless, alpha kept).
 * Throws [ImageEditException.ExportFailed] when the encoder fails.
 */
expect fun encodeGridToPng(grid: PixelGrid): ByteArray
