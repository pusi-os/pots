package com.pusios.pots.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Provider voices errors map to the §8 error cards: essence + reason +
 * rescue, in Russian, without codes. Codes and error strings are pinned to
 * the contract (plusvibe.md «Клонирование голоса») and the live provider
 * docs verified 2026-11-13.
 */
class VoiceErrorMappingTest {

    private fun mapped(status: Int, errorCode: String? = null, detail: String? = null): VoiceException = mapVoiceHttpError(status, errorCode, detail)

    @Test
    fun sampleErrorsMapToSampleCard() {
        for (code in listOf("voice_sample_too_large", "invalid", "samples_too_large")) {
            val e = mapped(422, code)
            assertTrue(e is VoiceException.SampleRejected, "$code -> $e")
        }
        val text = mapped(422, "voice_sample_too_large").userMessageRu()
        assertTrue(text.startsWith("Сэмпл не подходит"), text)
        assertTrue(text.contains("3–60 секунд"), text)
        assertTrue(text.contains("wav") && text.contains("mp3"), text)
        assertTrue(text.contains("10 МБ"), text)
    }

    @Test
    fun limitErrorsMapToLimitCards() {
        assertTrue(mapped(422, "samples_limit") is VoiceException.SamplesLimit)
        assertTrue(mapped(409, "slots_full") is VoiceException.SamplesLimit)
        val limit = mapped(409, "slots_full").userMessageRu()
        assertTrue(limit.contains("25 записей"), limit)
        assertTrue(limit.contains("300 секунд"), limit)
    }

    @Test
    fun dailyRateLimitCard() {
        val e = mapped(429, "voice_samples_rate_limited")
        assertTrue(e is VoiceException.DailyLimit)
        val text = e.userMessageRu()
        assertTrue(text.contains("20 добавлений"), text)
        assertTrue(text.contains("завтра"), text)
        // A bare 429 without a code is still the daily-limit lane.
        assertTrue(mapped(429) is VoiceException.DailyLimit)
    }

    @Test
    fun missingVoiceCard() {
        val e = mapped(404, "voice_not_found")
        assertTrue(e is VoiceException.VoiceNotFound)
        val text = e.userMessageRu()
        assertTrue(text.startsWith("Голос не найден"), text)
        assertTrue(text.contains("обнови список"), text)
    }

    @Test
    fun engineRefusalCard() {
        assertTrue(mapped(409, "voice_samples_unsupported") is VoiceException.EngineRefused)
        assertTrue(mapped(502, "voice_samples_failed") is VoiceException.EngineRefused)
        val text = mapped(502, "voice_samples_failed").userMessageRu()
        assertTrue(text.startsWith("Не удалось загрузить голос"), text)
        assertTrue(text.contains("попробуй ещё раз"), text)
    }

    @Test
    fun authAndBalanceReuseStudioWordings() {
        assertTrue(mapped(401) is VoiceException.KeyProblem)
        assertTrue(mapped(403) is VoiceException.KeyProblem)
        assertTrue(mapped(402) is VoiceException.Balance)
        assertEquals("API-ключ сервиса не найден или не подходит", mapped(401).userMessageRu())
        assertEquals("Недостаточно баланса для этой операции", mapped(402).userMessageRu())
    }

    @Test
    fun unknownKeepsDetail() {
        val e = mapped(500, null, "boom")
        assertTrue(e is VoiceException.Unknown)
        assertTrue(e.userMessageRu().contains("boom"))
    }

    @Test
    fun errorPayloadParsesBothShapes() {
        // Provider error bodies arrive either nested under `error` or flat.
        val nested = parseVoiceErrorPayload("""{"ok":false,"error":{"code":"voice_sample_too_large","message":"sample exceeds 10 MB"}}""")
        assertEquals("voice_sample_too_large", nested.first)
        assertEquals("sample exceeds 10 MB", nested.second)
        val flat = parseVoiceErrorPayload("""{"code":"slots_full","message":"voice is full"}""")
        assertEquals("slots_full", flat.first)
        val empty = parseVoiceErrorPayload("not json")
        assertEquals(null, empty.first)
    }
}
