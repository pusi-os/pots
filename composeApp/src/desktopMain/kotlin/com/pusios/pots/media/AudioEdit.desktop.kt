package com.pusios.pots.media

private const val SUPPORTED_FORMATS = "MP3 и WAV"

/**
 * Desktop decodes WAV (pure common parser) and MP3 (JLayer, already in the
 * dependency graph for playback) into PCM — the editor's real implementation.
 */
actual fun decodeAudioToPcm(bytes: ByteArray): AudioPcm {
    if (bytes.size >= 12 && bytes.startsWithAscii("RIFF", 0) && bytes.startsWithAscii("WAVE", 8)) {
        return wavToPcm(bytes) ?: throw AudioEditException.DecodeFailed()
    }
    if (looksLikeMp3(bytes)) {
        try {
            return decodeMp3ToPcm(bytes)
        } catch (e: AudioEditException) {
            throw e
        } catch (e: Exception) {
            throw AudioEditException.DecodeFailed()
        }
    }
    throw AudioEditException.UnsupportedFormat(SUPPORTED_FORMATS)
}

/** Whole-file JLayer decode into interleaved 16-bit PCM. */
private fun decodeMp3ToPcm(bytes: ByteArray): AudioPcm {
    val bitstream = javazoom.jl.decoder.Bitstream(java.io.ByteArrayInputStream(bytes))
    val decoder = javazoom.jl.decoder.Decoder()
    var sampleRate = 0
    var channels = 0
    var buffer = ShortArray(0)
    var filled = 0
    try {
        while (true) {
            val header = bitstream.readFrame() ?: break
            // Tolerate a bad mid-stream frame like the player does; if nothing
            // decodes at all the result is reported as DecodeFailed below.
            val output = try {
                decoder.decodeFrame(header, bitstream) as? javazoom.jl.decoder.SampleBuffer
            } catch (e: Exception) {
                null
            } finally {
                bitstream.closeFrame()
            }
            if (output != null && output.bufferLength > 0) {
                if (sampleRate == 0) {
                    sampleRate = output.sampleFrequency
                    channels = output.channelCount
                }
                val length = output.bufferLength
                if (filled + length > buffer.size) {
                    var newSize = if (buffer.isEmpty()) 65_536 else buffer.size * 2
                    while (newSize < filled + length) newSize *= 2
                    buffer = buffer.copyOf(newSize)
                }
                output.buffer.copyInto(buffer, filled, 0, length)
                filled += length
            }
        }
    } finally {
        runCatching { bitstream.close() }
    }
    if (sampleRate <= 0 || channels <= 0 || filled == 0) throw AudioEditException.DecodeFailed()
    return AudioPcm(buffer.copyOf(filled), sampleRate, channels)
}

/** ID3v2 tag or MPEG frame sync (same rule as the playback engine). */
private fun looksLikeMp3(bytes: ByteArray): Boolean {
    if (bytes.size < 2) return false
    if (bytes.size >= 3 && bytes.startsWithAscii("ID3", 0)) return true
    val b0 = bytes[0].toInt() and 0xFF
    val b1 = bytes[1].toInt() and 0xFF
    return b0 == 0xFF && (b1 and 0xE0) == 0xE0
}

private fun ByteArray.startsWithAscii(text: String, offset: Int): Boolean {
    if (offset + text.length > size) return false
    for (i in text.indices) {
        if (this[offset + i] != text[i].code.toByte()) return false
    }
    return true
}
