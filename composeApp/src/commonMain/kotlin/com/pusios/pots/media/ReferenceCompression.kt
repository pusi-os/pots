package com.pusios.pots.media

import com.pusios.pots.compressImageToJpeg

/** Inline reference limit: base64 payloads above this are rejected by the provider. */
const val MAX_INLINE_REFERENCE_BYTES: Long = 8L * 1024 * 1024

/** Step-down ladder: longest side in px and JPEG quality, tried in order until the result fits. */
private val COMPRESSION_LADDER: List<Pair<Int, Int>> = listOf(
    2048 to 85,
    2048 to 70,
    1536 to 75,
    1280 to 70,
    1024 to 70,
    768 to 60,
)

/**
 * Re-encodes an image as JPEG so it fits under [MAX_INLINE_REFERENCE_BYTES].
 * Returns null when the platform cannot decode the image or no ladder step fits.
 */
suspend fun compressReferenceToLimit(bytes: ByteArray, limit: Long = MAX_INLINE_REFERENCE_BYTES): ByteArray? {
    for ((maxDim, quality) in COMPRESSION_LADDER) {
        val encoded = compressImageToJpeg(bytes, maxDim, quality) ?: return null
        if (encoded.size <= limit) return encoded
    }
    return null
}
