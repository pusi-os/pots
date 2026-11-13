package com.pusios.pots.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Voice-clone sample gate (spec §8/§16, contract plusvibe.md): one sample
 * ≤ 10 МБ, wav/mp3, 3–60 s (Premium 10–60 s). Everything is validated
 * BEFORE upload so an unfit file never spends a request on the provider.
 */
class VoiceSampleValidationTest {

    private fun wav(seconds: Int, rate: Int = 16000): ByteArray = pcm16ToWav(ShortArray(seconds * rate), rate)

    @Test
    fun goodWavPasses() {
        assertNull(validateVoiceSample("voice.wav", wav(15), VoiceLevel.ElevenLabs))
        assertNull(validateVoiceSample("voice.WAV", wav(3), VoiceLevel.Standard))
    }

    @Test
    fun goodMp3PassesOnEstimate() {
        // 128 kbps MPEG-1 L3, ~20 s of audio.
        val frame = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00)
        val mp3 = frame + ByteArray(128_000 * 20 / 8 - 4)
        assertNull(validateVoiceSample("voice.mp3", mp3, VoiceLevel.Standard))
    }

    @Test
    fun wrongFormatRefused() {
        val error = validateVoiceSample("voice.ogg", wav(15), VoiceLevel.Standard)
        assertNotNull(error)
        assertTrue(error.contains("Формат не поддерживается"), error)
        assertTrue(error.contains("wav") && error.contains("mp3"), error)
    }

    @Test
    fun oversizeRefused() {
        val big = wav(61) // 61 s at 16 kHz mono ≈ 1.95 МБ — pad past 10 МБ.
        val padded = big + ByteArray(10 * 1024 * 1024)
        val error = validateVoiceSample("voice.wav", padded, VoiceLevel.Standard)
        assertNotNull(error)
        assertTrue(error.contains("10 МБ"), error)
    }

    @Test
    fun tooShortRefusedPerLevel() {
        val standard = validateVoiceSample("s.wav", wav(2), VoiceLevel.Standard)
        assertNotNull(standard)
        assertTrue(standard.contains("3–60"), standard)
        // Premium demands 10–60 s.
        val premium = validateVoiceSample("p.wav", wav(5), VoiceLevel.Premium)
        assertNotNull(premium)
        assertTrue(premium.contains("10–60"), premium)
        val eleven = validateVoiceSample("e.wav", wav(5), VoiceLevel.ElevenLabs)
        assertNotNull(eleven)
        assertTrue(eleven.contains("10–60"), eleven)
    }

    @Test
    fun tooLongRefused() {
        val error = validateVoiceSample("long.wav", wav(61), VoiceLevel.Standard)
        assertNotNull(error)
        assertTrue(error.contains("3–60"), error)
    }

    @Test
    fun unreadableDurationRefused() {
        val error = validateVoiceSample("broken.wav", ByteArray(64), VoiceLevel.Standard)
        assertNotNull(error)
        assertTrue(error.contains("длительность"), error)
    }

    @Test
    fun emptyNameDoesNotCrash() {
        assertNotNull(validateVoiceSample("", wav(15), VoiceLevel.Standard))
        assertNotNull(validateVoiceSample("voice", wav(15), VoiceLevel.Standard))
    }

    @Test
    fun onlyElevenLabsLevelIsCreatableInV1() {
        // The provider documents only `elevenlabs` as a creation engine
        // (research §3.5); Standard/Premium return once the engine strings
        // are verified live.
        assertEquals("elevenlabs", VoiceLevel.ElevenLabs.engine)
        assertNull(VoiceLevel.Standard.engine)
        assertNull(VoiceLevel.Premium.engine)
        assertTrue(VoiceLevel.ElevenLabs.isCreatable)
        assertTrue(!VoiceLevel.Standard.isCreatable && !VoiceLevel.Premium.isCreatable)
    }
}
