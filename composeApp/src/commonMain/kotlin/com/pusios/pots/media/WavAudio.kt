package com.pusios.pots.media

// Minimal WAV support in pure Kotlin: the local TTS engines write their
// results as WAV bytes, and the voice-clone sample check needs a duration
// before upload. No platform codecs involved, so both are unit-testable.

/** Converts float samples in [-1, 1] to 16-bit PCM, clamping overflow. */
fun floatSamplesToPcm16(samples: FloatArray): ShortArray = ShortArray(samples.size) { i ->
    (samples[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
}

/** Builds a canonical 16-bit PCM WAV (RIFF) from mono/stereo samples. */
fun pcm16ToWav(samples: ShortArray, sampleRate: Int, channels: Int = 1): ByteArray {
    val dataLen = samples.size * 2
    val out = ByteArray(44 + dataLen)
    fun str(off: Int, s: String) {
        for (i in 0 until 4) out[off + i] = s[i].code.toByte()
    }
    fun intLe(off: Int, v: Int) {
        out[off] = (v and 0xFF).toByte()
        out[off + 1] = ((v shr 8) and 0xFF).toByte()
        out[off + 2] = ((v shr 16) and 0xFF).toByte()
        out[off + 3] = ((v shr 24) and 0xFF).toByte()
    }
    fun shortLe(off: Int, v: Int) {
        out[off] = (v and 0xFF).toByte()
        out[off + 1] = ((v shr 8) and 0xFF).toByte()
    }
    str(0, "RIFF")
    intLe(4, 36 + dataLen)
    str(8, "WAVE")
    str(12, "fmt ")
    intLe(16, 16)
    shortLe(20, 1) // PCM
    shortLe(22, channels)
    intLe(24, sampleRate)
    intLe(28, sampleRate * channels * 2) // byte rate
    shortLe(32, channels * 2) // block align
    shortLe(34, 16) // bits per sample
    str(36, "data")
    intLe(40, dataLen)
    var off = 44
    for (sample in samples) {
        val v = sample.toInt()
        out[off] = (v and 0xFF).toByte()
        out[off + 1] = ((v shr 8) and 0xFF).toByte()
        off += 2
    }
    return out
}

/**
 * Duration of a 16-bit PCM WAV in seconds; null when the bytes are not a
 * parseable WAV. Walks chunks instead of assuming the canonical 44-byte
 * layout, because system TTS writers sometimes insert extra chunks.
 */
fun wavDurationSeconds(bytes: ByteArray): Double? {
    if (bytes.size < 44) return null
    if (bytes.riffStr(0) != "RIFF" || bytes.riffStr(8) != "WAVE") return null
    var pos = 12
    var channels = 0
    var sampleRate = 0
    var bits = 0
    var dataLen = -1
    while (pos + 8 <= bytes.size) {
        val id = bytes.riffStr(pos)
        val size = bytes.riffInt(pos + 4)
        val body = pos + 8
        when (id) {
            "fmt " -> {
                if (body + 16 > bytes.size) return null
                channels = bytes.riffShort(body + 2)
                sampleRate = bytes.riffInt(body + 4)
                bits = bytes.riffShort(body + 14)
            }

            "data" -> dataLen = size
        }
        if (dataLen >= 0) break
        pos = body + size + (size and 1)
        if (pos <= body) return null
    }
    if (channels <= 0 || sampleRate <= 0 || bits <= 0 || dataLen < 0) return null
    val bytesPerSecond = sampleRate.toDouble() * channels * (bits / 8.0)
    if (bytesPerSecond <= 0) return null
    return dataLen / bytesPerSecond
}

/**
 * CBR estimate of an MP3 duration from the first frame header: the provider
 * does the authoritative validation, this only screens obviously unfit
 * samples before upload. Understands MPEG-1/2/2.5 Layer III and skips a
 * leading ID3v2 tag. Null when no frame sync is found.
 */
fun mp3DurationSecondsEstimate(bytes: ByteArray): Double? {
    var off = 0
    if (bytes.size > 10 && bytes.riffStr(0).take(3) == "ID3") {
        // Syncsafe 28-bit size at bytes 6..9, plus the 10-byte header.
        val tagSize = ((bytes[6].toInt() and 0x7F) shl 21) or
            ((bytes[7].toInt() and 0x7F) shl 14) or
            ((bytes[8].toInt() and 0x7F) shl 7) or
            (bytes[9].toInt() and 0x7F)
        off = 10 + tagSize
    }
    if (off + 4 > bytes.size) return null
    val b1 = bytes[off + 1].toInt() and 0xFF
    if (bytes[off].toInt() and 0xFF != 0xFF || b1 and 0xE0 != 0xE0) return null
    val version = (b1 shr 3) and 0x3 // 0=2.5, 2=2, 3=1
    val layer = (b1 shr 1) and 0x3 // 1 = Layer III
    if (layer != 1 || version == 1) return null
    val b2 = bytes[off + 2].toInt() and 0xFF
    val bitrateIndex = (b2 shr 4) and 0xF
    if (bitrateIndex == 0 || bitrateIndex == 15) return null
    val bitrateKbps = if (version == 3) {
        MP3_BITRATES_V1_L3[bitrateIndex]
    } else {
        MP3_BITRATES_V2_L3[bitrateIndex]
    }
    val audioBytes = bytes.size - off
    return audioBytes * 8.0 / (bitrateKbps * 1000.0)
}

private val MP3_BITRATES_V1_L3 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
private val MP3_BITRATES_V2_L3 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0)

/**
 * Decodes a 16-bit PCM WAV into interleaved samples. Walks chunks like
 * [wavDurationSeconds]; returns null for non-WAV bytes, unsupported
 * encodings or bit depths the editor does not handle.
 */
fun wavToPcm(bytes: ByteArray): AudioPcm? {
    if (bytes.size < 44) return null
    if (bytes.riffStr(0) != "RIFF" || bytes.riffStr(8) != "WAVE") return null
    var pos = 12
    var format = -1
    var channels = 0
    var sampleRate = 0
    var bits = 0
    var dataStart = -1
    var dataLen = -1
    while (pos + 8 <= bytes.size) {
        val id = bytes.riffStr(pos)
        val size = bytes.riffInt(pos + 4)
        if (size < 0) return null
        val body = pos + 8
        when (id) {
            "fmt " -> {
                if (body + 16 > bytes.size) return null
                format = bytes.riffShort(body)
                channels = bytes.riffShort(body + 2)
                sampleRate = bytes.riffInt(body + 4)
                bits = bytes.riffShort(body + 14)
            }

            "data" -> {
                dataStart = body
                dataLen = size
            }
        }
        if (dataStart >= 0) break
        pos = body + size + (size and 1)
        if (pos <= body) return null
    }
    if (format != 1 || bits != 16 || channels <= 0 || sampleRate <= 0 || dataStart < 0) return null
    val available = bytes.size - dataStart
    val usable = minOf(dataLen, available) and 1.inv()
    if (usable < 2) return null
    val samples = ShortArray(usable / 2)
    var off = dataStart
    for (i in samples.indices) {
        samples[i] = ((bytes[off].toInt() and 0xFF) or (bytes[off + 1].toInt() shl 8)).toShort()
        off += 2
    }
    // A truncated file can end mid-frame: drop the dangling partial frame.
    val frames = samples.size / channels
    val whole = if (frames * channels == samples.size) samples else samples.copyOf(frames * channels)
    return AudioPcm(whole, sampleRate, channels)
}

private fun ByteArray.riffStr(off: Int): String = if (off + 4 <= size) decodeToString(off, off + 4) else ""

private fun ByteArray.riffInt(off: Int): Int = (this[off].toInt() and 0xFF) or
    ((this[off + 1].toInt() and 0xFF) shl 8) or
    ((this[off + 2].toInt() and 0xFF) shl 16) or
    ((this[off + 3].toInt() and 0xFF) shl 24)

private fun ByteArray.riffShort(off: Int): Int = (this[off].toInt() and 0xFF) or ((this[off + 1].toInt() and 0xFF) shl 8)
