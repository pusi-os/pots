package com.pusios.pots.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The WAV codec is pure common code: Piper synthesis (Float PCM → WAV file)
 * and the voice-sample duration check both depend on it, so the byte layout
 * is pinned here instead of trusting platform writers.
 */
class WavAudioTest {

    @Test
    fun pcm16ToWavWritesCanonicalHeader() {
        // 10 samples at 8 kHz mono → 20 data bytes.
        val samples = ShortArray(10) { it.toShort() }
        val wav = pcm16ToWav(samples, sampleRate = 8000)

        assertEquals("RIFF", wav.leStr(0))
        assertEquals(36 + 20, wav.leInt(4)) // chunk size = header rest + data
        assertEquals("WAVE", wav.leStr(8))
        assertEquals("fmt ", wav.leStr(12))
        assertEquals(16, wav.leInt(16)) // PCM fmt chunk size
        assertEquals(1, wav.leShort(20)) // audio format PCM
        assertEquals(1, wav.leShort(22)) // mono
        assertEquals(8000, wav.leInt(24))
        assertEquals(16000, wav.leInt(28)) // byteRate = rate * channels * 2
        assertEquals(2, wav.leShort(32)) // blockAlign
        assertEquals(16, wav.leShort(34)) // bits per sample
        assertEquals("data", wav.leStr(36))
        assertEquals(20, wav.leInt(40))
        // Little-endian samples: data starts at 44, sample i at 44+2i.
        // Sample 1 = 0x0001 → byte 46, sample 9 = 0x0009 → byte 62.
        assertEquals(1, wav[46].toInt())
        assertEquals(9, wav[62].toInt())
    }

    @Test
    fun floatSamplesClampToPcm16Range() {
        val shorts = floatSamplesToPcm16(floatArrayOf(0f, 1f, -1f, 2f, -2f))
        assertEquals(0, shorts[0].toInt())
        assertEquals(Short.MAX_VALUE.toInt(), shorts[1].toInt())
        assertEquals((Short.MIN_VALUE + 1).toInt(), shorts[2].toInt())
        // Out-of-range input clamps instead of wrapping.
        assertEquals(Short.MAX_VALUE.toInt(), shorts[3].toInt())
        assertEquals((Short.MIN_VALUE + 1).toInt(), shorts[4].toInt())
    }

    @Test
    fun wavDurationSecondsParsesOwnOutput() {
        // Exactly 2 seconds of silence at 22050 Hz.
        val wav = pcm16ToWav(ShortArray(22050 * 2), sampleRate = 22050)
        assertEquals(2.0, wavDurationSeconds(wav)!!, 0.001)
    }

    @Test
    fun wavDurationRejectsGarbage() {
        assertNull(wavDurationSeconds(ByteArray(10)))
        assertNull(wavDurationSeconds("not a riff file at all".encodeToByteArray()))
        // RIFF header but no data chunk.
        val head = pcm16ToWav(ShortArray(8), 8000).copyOfRange(0, 36)
        assertNull(wavDurationSeconds(head))
    }

    @Test
    fun mp3DurationEstimatesFromFirstFrameBitrate() {
        // MPEG-1 Layer III, bitrate index 9 → 128 kbps. 128000 bytes ≈ 8 s.
        val frame = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00)
        val mp3 = frame + ByteArray(128_000 - 4)
        val seconds = mp3DurationSecondsEstimate(mp3)
        assertTrue(seconds != null && seconds > 7.5 && seconds < 8.5, "expected ~8 s, got $seconds")
    }

    @Test
    fun mp3DurationSkipsId3v2Header() {
        // ID3v2 tag of 10 bytes, then a 128 kbps frame, then 64000 payload bytes ≈ 4 s.
        val id3 = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0, 0, 0, 0, 10)
        val frame = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00)
        val mp3 = id3 + ByteArray(10) + frame + ByteArray(64_000)
        val seconds = mp3DurationSecondsEstimate(mp3)
        assertTrue(seconds != null && seconds > 3.5 && seconds < 4.5, "expected ~4 s, got $seconds")
    }

    @Test
    fun mp3DurationRejectsNonMp3() {
        assertNull(mp3DurationSecondsEstimate(ByteArray(64)))
        assertNull(mp3DurationSecondsEstimate("RIFF".encodeToByteArray() + ByteArray(64)))
    }

    private fun ByteArray.leStr(off: Int): String = decodeToString(off, off + 4)

    private fun ByteArray.leInt(off: Int): Int = (this[off].toInt() and 0xFF) or
        ((this[off + 1].toInt() and 0xFF) shl 8) or
        ((this[off + 2].toInt() and 0xFF) shl 16) or
        ((this[off + 3].toInt() and 0xFF) shl 24)

    private fun ByteArray.leShort(off: Int): Int = (this[off].toInt() and 0xFF) or ((this[off + 1].toInt() and 0xFF) shl 8)
}
