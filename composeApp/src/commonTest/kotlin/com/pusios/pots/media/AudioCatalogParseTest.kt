package com.pusios.pots.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Catalog parsing of audio fields (`task`, audio input detection). */
class AudioCatalogParseTest {

    private val provider = PlusVibeMediaProvider()

    private val catalogJson = """
    {
      "models": [
        {
          "model": "gpt-4o-mini-tts", "kind": "audio", "task": "tts",
          "unit": "за 1000 символов", "fromRub": 0.07, "priceStatus": "exact",
          "inputTypes": ["text"],
          "params": [
            {"key":"voice","label":"Голос","type":"select","default":"alloy","options":["alloy","nova"],"optionLabels":["Alloy","Nova"]},
            {"key":"response_format","label":"Формат","type":"select","default":"mp3","options":["mp3","wav"]}
          ]
        },
        {
          "model": "whisper-large-v3", "kind": "audio", "task": "asr",
          "unit": "за минуту", "fromRub": 0.06,
          "inputTypes": ["audio"],
          "params": [{"key":"audio_url","label":"Аудио (URL)","type":"text"}]
        },
        {
          "model": "suno", "kind": "music",
          "unit": "за трек", "fromRub": 6.29, "priceStatus": "exact",
          "inputTypes": ["text"],
          "axes": [
            {"key":"duration","label":"Длительность","type":"select","default":"30","options":[{"value":"30","label":"30 с"},{"value":"60","label":"60 с"}]}
          ]
        },
        {
          "model": "suno-lyrics", "kind": "audio", "task": "utility",
          "unit": "за файл", "fromRub": 1.89,
          "inputTypes": ["text"]
        },
        {
          "model": "elevenlabs-audio-isolation", "kind": "audio",
          "unit": "за минуту", "fromRub": 21.12,
          "inputTypes": ["audio"],
          "params": [{"key":"audio_url","label":"Аудио (URL)","type":"text"}]
        }
      ]
    }
    """.trimIndent()

    @Test
    fun parsesTaskAndAudioInputFromCatalog() {
        val parsed = provider.parseCatalog(catalogJson)
        assertEquals(5, parsed.size)

        val tts = parsed.first { it.id == "gpt-4o-mini-tts" }
        assertEquals("tts", tts.task)
        assertFalse(tts.acceptsAudioReference)
        assertEquals(MediaKind.Audio, tts.kind)

        val stt = parsed.first { it.id == "whisper-large-v3" }
        assertEquals("asr", stt.task)
        assertTrue(stt.acceptsAudioReference)

        val music = parsed.first { it.id == "suno" }
        assertEquals(MediaKind.Music, music.kind)
        assertFalse(music.acceptsAudioReference)
        assertNullSafe(music.task)

        val isolation = parsed.first { it.id == "elevenlabs-audio-isolation" }
        assertTrue(isolation.acceptsAudioReference)
    }

    @Test
    fun sheetFilterOnParsedCatalogMatchesResearch() {
        val parsed = provider.parseCatalog(catalogJson)
        val sheet = parsed.filter { it.isGenerativeAudio() }.map { it.id }
        assertEquals(listOf("gpt-4o-mini-tts", "suno"), sheet)
    }

    private fun assertNullSafe(value: String?) = assertEquals(null, value)
}
