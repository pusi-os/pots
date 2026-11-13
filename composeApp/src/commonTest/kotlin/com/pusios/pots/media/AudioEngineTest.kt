package com.pusios.pots.media

import com.pusios.pots.ui.media.createMediaAudioEngine
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Platform audio engine contract tests. Desktop decodes MP3 (JLayer) and WAV;
 * unsupported platforms report [MediaAudioEngine.isSupported] = false and the
 * assertions degrade to the no-op contract.
 */
class AudioEngineTest {

    /** 1 s, 8 kHz, mono, 16-bit PCM sine stored as RIFF/WAVE. */
    private fun wavFixture(durationMs: Long = 1000): ByteArray {
        val sampleRate = 8000
        val n = (sampleRate * durationMs / 1000).toInt()
        val data = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = (kotlin.math.sin(i * 0.15) * 8000).toInt().toShort()
            data[i * 2] = (v.toInt() and 0xFF).toByte()
            data[i * 2 + 1] = ((v.toInt() shr 8) and 0xFF).toByte()
        }
        val dataSize = data.size
        val out = ByteArray(44 + dataSize)
        fun put(pos: Int, s: String) = s.toByteArray().forEachIndexed { i, b -> out[pos + i] = b }
        fun putLe32(pos: Int, v: Int) {
            out[pos] = (v and 0xFF).toByte()
            out[pos + 1] = ((v shr 8) and 0xFF).toByte()
            out[pos + 2] = ((v shr 16) and 0xFF).toByte()
            out[pos + 3] = ((v shr 24) and 0xFF).toByte()
        }
        fun putLe16(pos: Int, v: Int) {
            out[pos] = (v and 0xFF).toByte()
            out[pos + 1] = ((v shr 8) and 0xFF).toByte()
        }
        put(0, "RIFF")
        putLe32(4, 36 + dataSize)
        put(8, "WAVE")
        put(12, "fmt ")
        putLe32(16, 16)
        putLe16(20, 1)
        putLe16(22, 1)
        putLe32(24, sampleRate)
        putLe32(28, sampleRate * 2)
        putLe16(32, 2)
        putLe16(34, 16)
        put(36, "data")
        putLe32(40, dataSize)
        data.copyInto(out, 44)
        return out
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun mp3Fixture(): ByteArray = Base64.decode(MP3_1S_22050HZ_BASE64)

    @Test
    fun wavDurationAndSeek() {
        val engine = createMediaAudioEngine()
        if (!engine.isSupported) return
        engine.load(wavFixture(1000))
        assertEquals(1000L, engine.durationMs, 120L)
        assertEquals(0L, engine.positionMs, 5L)
        engine.seek(400)
        assertEquals(400L, engine.positionMs, 30L)
        engine.release()
    }

    @Test
    fun mp3DurationDecoded() {
        val engine = createMediaAudioEngine()
        if (!engine.isSupported) return
        engine.load(mp3Fixture())
        assertTrue(engine.durationMs in 700..1600, "mp3 duration was ${engine.durationMs}")
        engine.seek(300)
        assertEquals(300L, engine.positionMs, 60L)
        engine.release()
    }

    @Test
    fun unsupportedEngineIsSafeNoop() {
        val engine = createMediaAudioEngine()
        // The contract: calling the full surface never throws, whatever the platform.
        engine.load(wavFixture(100))
        engine.play()
        engine.pause()
        engine.seek(10)
        engine.release()
    }
}

private fun assertEquals(expected: Long, actual: Long, tolerance: Long) {
    assertTrue(kotlin.math.abs(expected - actual) <= tolerance, "expected $expected±$tolerance but was $actual")
}

/** 1 s 440 Hz sine, MPEG Layer III, 22050 Hz mono, generated with ffmpeg/lame. */
private const val MP3_1S_22050HZ_BASE64 = "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"
