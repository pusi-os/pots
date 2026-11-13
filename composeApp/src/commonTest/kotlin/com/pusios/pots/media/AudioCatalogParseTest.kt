package com.pusios.pots.media

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    private val tiersJson = """
    {
      "models": [
        {
          "model": "tts", "kind": "audio", "task": "tts",
          "unit": "за озвучку", "fromRub": 7,
          "note": "Агрегатор: до 4000 символов за запрос.",
          "tiers": [
            {"label": "Gemini Flash", "priceRub": 7.0, "when": {"engine": "gemini-flash"}},
            {"label": "MiniMax HD", "priceRub": 13.0, "when": {"engine": "minimax-hd"}}
          ]
        },
        {
          "model": "suno", "kind": "music",
          "unit": "за трек", "fromRub": 6.29,
          "tiers": [{"label": "Не указано", "priceRub": 6.29, "when": {"personaModel": ""}}]
        },
        {
          "model": "chatterbox-tts", "kind": "audio", "task": "tts",
          "unit": "за 1000 символов", "fromRub": 3,
          "note": "Chatterbox: до 300 символов за запрос."
        },
        {
          "model": "suno-speech", "kind": "audio", "task": "tts",
          "unit": "за озвучку", "fromRub": 10.82,
          "note": "Расширенный режим: свой текст до 5000 символов, тон до 1000 символов."
        }
      ]
    }
    """.trimIndent()

    @Test
    fun parsesTiersFromCatalog() {
        val parsed = provider.parseCatalog(tiersJson)
        val aggregator = parsed.first { it.id == "tts" }
        assertEquals(2, aggregator.tiers.size)
        assertEquals(mapOf("engine" to "gemini-flash"), aggregator.tiers[0].whenConditions)
        assertEquals(13.0, aggregator.tiers[1].priceRub)
        assertEquals("MiniMax HD", aggregator.tiers[1].label)
        // An empty `when` matches any selected chips (default tier).
        assertEquals(mapOf("personaModel" to ""), parsed.first { it.id == "suno" }.tiers.single().whenConditions)
        // No tiers in the payload -> empty list.
        assertEquals(emptyList(), parsed.first { it.id == "chatterbox-tts" }.tiers)
    }

    @Test
    fun parsesPromptLimitFromNote() {
        val parsed = provider.parseCatalog(tiersJson)
        assertEquals(4000, parsed.first { it.id == "tts" }.promptLimit)
        assertEquals(300, parsed.first { it.id == "chatterbox-tts" }.promptLimit)
        // Several «до N символов» mentions: the largest is the request limit.
        assertEquals(5000, parsed.first { it.id == "suno-speech" }.promptLimit)
        // No documented limit -> null (no counter).
        assertEquals(null, parsed.first { it.id == "suno" }.promptLimit)
    }

    @Test
    fun generateBodyCarriesVoiceAndSpeedOpts() {
        val request = MediaJobRequest(
            model = "gpt-4o-mini-tts",
            prompt = "Привет",
            opts = linkedMapOf("voice" to "nova", "speed" to "1.25"),
        )
        val body = provider.buildGenerateBody(request)
        assertEquals("gpt-4o-mini-tts", body["model"]?.jsonPrimitive?.content)
        assertEquals("Привет", body["prompt"]?.jsonPrimitive?.content)
        val opts = body["opts"]?.jsonObject
        assertEquals("nova", opts?.get("voice")?.jsonPrimitive?.content)
        assertEquals("1.25", opts?.get("speed")?.jsonPrimitive?.content)
    }

    private fun assertNullSafe(value: String?) = assertEquals(null, value)
}
