package com.pusios.pots.media

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Desktop-specific decode path: WAV goes through the pure common parser,
 * everything non-audio must refuse with the §8 error instead of crashing.
 */
class AudioEditDecodeTest {

    private fun wavFixture(frames: Int = 800, sampleRate: Int = 8000): ByteArray {
        val samples = ShortArray(frames) { ((it % 50) * 300).toShort() }
        return pcm16ToWav(samples, sampleRate, channels = 1)
    }

    @Test
    fun desktopDecodesWavThroughCommonParser() {
        val pcm = decodeAudioToPcm(wavFixture())
        assertEquals(800, pcm.frameCount)
        assertEquals(8000, pcm.sampleRate)
        assertEquals(1, pcm.channels)
        assertEquals((12 % 50) * 300, pcm.samples[12].toInt())
    }

    @Test
    fun desktopRoundTripsWavSamplesExactly() {
        val samples = ShortArray(256) { (it * 31 - 2000).toShort() }
        val decoded = decodeAudioToPcm(pcm16ToWav(samples, 16000, channels = 1))
        assertContentEquals(samples, decoded.samples)
    }

    @Test
    fun garbageBytesRefuseWithRussianFormatError() {
        val error = assertFailsWith<AudioEditException.UnsupportedFormat> {
            decodeAudioToPcm(ByteArray(128) { 0x42 })
        }
        assertEquals("Формат не поддерживается", error.title)
        assertTrue("MP3 и WAV" in error.detail)
        assertTrue(error.fix.isNotBlank())
    }

    @Test
    fun wavWithUnsupportedEncodingReportsDecodeFailure() {
        val wav = wavFixture()
        wav[34] = 8 // bits per sample → not 16-bit PCM
        val error = assertFailsWith<AudioEditException.DecodeFailed> { decodeAudioToPcm(wav) }
        assertTrue(error.detail.isNotBlank())
    }
}
