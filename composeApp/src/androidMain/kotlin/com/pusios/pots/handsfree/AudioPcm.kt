package com.pusios.pots.handsfree

import kotlin.math.log10
import kotlin.math.sqrt

/** Silence, in dBFS: the bottom of the 0..1 scale used by the wave. */
private const val LEVEL_FLOOR_DBFS = -60f

/** Loud speech, in dBFS: the top of the 0..1 scale used by the wave. */
private const val LEVEL_CEIL_DBFS = -15f

/**
 * Root-mean-square level of a PCM 16-bit little-endian buffer, mapped onto
 * `0..1` between [LEVEL_FLOOR_DBFS] and [LEVEL_CEIL_DBFS].
 *
 * This is the audio the recognizer is actually consuming, which is why the wave
 * moves with the voice: it is a measurement, not an animation. Shared by every
 * offline engine so the recording indicator means the same thing whichever one
 * is selected.
 */
internal fun audioLevelOf(buffer: ByteArray, length: Int): Float {
    val samples = length / 2
    if (samples == 0) return 0f
    var sum = 0.0
    var i = 0
    while (i + 1 < length) {
        val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort()
        val value = sample / 32768f
        sum += value * value
        i += 2
    }
    val rms = sqrt(sum / samples).toFloat()
    val dbfs = if (rms <= 0f) LEVEL_FLOOR_DBFS else 20f * log10(rms)
    return ((dbfs - LEVEL_FLOOR_DBFS) / (LEVEL_CEIL_DBFS - LEVEL_FLOOR_DBFS)).coerceIn(0f, 1f)
}

/**
 * PCM 16-bit little-endian bytes as the float samples sherpa-onnx expects
 * (`-1..1`).
 *
 * [bytes] is read from [offset] for [length] bytes; an odd trailing byte is
 * dropped, which is what a partially filled read leaves behind.
 */
internal fun pcm16ToFloats(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): FloatArray {
    val samples = length / 2
    val out = FloatArray(samples)
    var i = 0
    while (i < samples) {
        val lo = bytes[offset + i * 2].toInt() and 0xFF
        val hi = bytes[offset + i * 2 + 1].toInt()
        out[i] = ((hi shl 8) or lo).toShort() / 32768f
        i++
    }
    return out
}
