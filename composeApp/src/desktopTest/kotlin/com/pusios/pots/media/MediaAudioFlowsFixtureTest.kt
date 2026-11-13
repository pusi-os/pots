package com.pusios.pots.media

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Canonical numbers for the two audio flows, verified against the catalog
 * dump fixture (`docs/design/media-expansion/pots-audio-catalog-dump.json`,
 * drift-checked 2026-11-13). Cross-checked by @kodi/@pifiya/@linus:
 * voice axes on 16/19 TTS, speed on 7/19, alias distribution, tier counts.
 */
class MediaAudioFlowsFixtureTest {

    private val provider = PlusVibeMediaProvider()

    private val catalog: List<MediaModel> by lazy { provider.parseCatalog(loadDump()) }

    private fun loadDump(): String {
        // Test working dir is the module dir; fall back to the repo root.
        val candidates = listOf(
            File("../docs/design/media-expansion/pots-audio-catalog-dump.json"),
            File("docs/design/media-expansion/pots-audio-catalog-dump.json"),
            File("../../docs/design/media-expansion/pots-audio-catalog-dump.json"),
        )
        val found = candidates.firstOrNull { it.isFile }
        assertNotNull(found, "catalog dump not found; tried: " + candidates.joinToString { it.absolutePath })
        return found.readText()
    }

    private val ttsModels get() = catalog.filter { it.task == "tts" && it.kind == MediaKind.Audio }

    @Test
    fun dumpCarriesCanonCounts() {
        assertEquals(19, ttsModels.size)
        assertEquals(5, catalog.count { it.kind == MediaKind.Music && it.isGenerativeAudio() })
        assertEquals(4, catalog.count { it.task == "sfx" && it.isGenerativeAudio() })
    }

    @Test
    fun ttsFlowShowsEighteenOfNineteen() {
        // mimo-v2-5-tts-voiceclone stays hidden in v1 (spec §14).
        val sheet = audioFlowModels(catalog, AudioFlow.Tts)
        assertEquals(18, sheet.size)
        assertTrue(sheet.none { it.id == "mimo-v2-5-tts-voiceclone" })
        assertTrue(sheet.all { it.task == "tts" })
        val groups = audioFlowGroups(catalog, AudioFlow.Tts)
        assertEquals(listOf(AudioSheetGroup.Voice), groups.map { it.first })
        assertEquals(18, groups.single().second.size)
    }

    @Test
    fun musicFlowShowsMusicAndEffects() {
        val withHistory = audioFlowModels(catalog, AudioFlow.Music, hasSunoHistory = true)
        assertEquals(9, withHistory.size)
        val groups = audioFlowGroups(catalog, AudioFlow.Music, hasSunoHistory = true)
        assertEquals(listOf(AudioSheetGroup.Music, AudioSheetGroup.Effects), groups.map { it.first })
        assertEquals(5, groups.first { it.first == AudioSheetGroup.Music }.second.size)
        assertEquals(4, groups.first { it.first == AudioSheetGroup.Effects }.second.size)
        // Without a Suno track in history the replace-section model is hidden.
        val noHistory = audioFlowModels(catalog, AudioFlow.Music, hasSunoHistory = false)
        assertEquals(8, noHistory.size)
        assertTrue(noHistory.none { it.id == "suno-replace-section" })
    }

    @Test
    fun voiceAxisPresentInSixteenOfNineteenTts() {
        val aliases = listOf("voice", "gemini_voice", "minimax_voice", "qwen3_voice", "voice_id")
        val byAlias = mutableMapOf<String, Int>()
        var withVoice = 0
        ttsModels.forEach { model ->
            val present = model.params.map { it.key }.filter { it in aliases }
            if (present.isNotEmpty()) withVoice++
            present.forEach { byAlias[it] = (byAlias[it] ?: 0) + 1 }
        }
        assertEquals(16, withVoice)
        assertEquals(12, byAlias["voice"])
        assertEquals(4, byAlias["gemini_voice"])
        assertEquals(3, byAlias["voice_id"])
        assertEquals(1, byAlias["minimax_voice"])
        assertEquals(1, byAlias["qwen3_voice"])
    }

    @Test
    fun speedAxisPresentInSevenOfNineteenTts() {
        val aliases = listOf("speed", "speaking_rate", "pace")
        val byAlias = mutableMapOf<String, Int>()
        var withSpeed = 0
        ttsModels.forEach { model ->
            val present = model.params.map { it.key }.filter { it in aliases }
            if (present.isNotEmpty()) withSpeed++
            present.forEach { byAlias[it] = (byAlias[it] ?: 0) + 1 }
        }
        assertEquals(7, withSpeed)
        assertEquals(4, byAlias["speed"])
        assertEquals(2, byAlias["speaking_rate"])
        assertEquals(1, byAlias["pace"])
    }

    @Test
    fun tiersParsedForVariablePriceModels() {
        assertEquals(6, catalog.first { it.id == "tts" }.tiers.size)
        assertEquals(2, catalog.first { it.id == "gemini-3.8-flash-tts" }.tiers.size)
        assertEquals(3, catalog.first { it.id == "suno" }.tiers.size)
        assertEquals(30, catalog.first { it.id == "gemini-3.8-flash-tts-lite" }.tiers.size)
        // Flat models keep today's badge: tier matching runs through the same
        // code path but only changes the label when chips actually select a tier.
        assertNull(catalog.first { it.id == "gpt-4o-mini-tts" }.tiers.takeIf { it.isNotEmpty() })
    }

    @Test
    fun promptLimitsMatchResearch() {
        fun limit(id: String) = catalog.first { it.id == id }.promptLimit
        assertEquals(300, limit("chatterbox-tts"))
        assertEquals(4000, limit("qwen3-tts"))
        assertEquals(4000, limit("voice-clone-tts"))
        assertEquals(4096, limit("gpt-4o-mini-tts"))
        assertEquals(5000, limit("elevenlabs-v4-tts"))
        assertEquals(5000, limit("suno-speech"))
        assertEquals(15000, limit("grok-tts"))
        assertEquals(450, limit("elevenlabs-sound-effects-v2"))
        assertEquals(2000, limit("mirelo-sfx-2"))
        // Models without a documented limit have no counter.
        assertNull(limit("suno"))
        assertNull(limit("gemini-tts"))
    }

    @Test
    fun quotePolicyCoversMeteredAndMirelo() {
        val quoted = catalog.filter { requiresPriceQuote(it) }.map { it.id }.toSet()
        // Every «за 1000 символов» / «за секунду» model plus mirelo-sfx-2.
        assertTrue("gpt-4o-mini-tts" in quoted)
        assertTrue("voice-clone-tts" in quoted)
        assertTrue("mirelo-sfx-2-video" in quoted)
        assertTrue("acestep-v15-xl-sft" in quoted)
        assertTrue("mirelo-sfx-2" in quoted)
        // Fixed prices submit without a quote.
        assertTrue("suno" !in quoted)
        assertTrue("lyria-3-5" !in quoted)
        assertTrue("elevenlabs-sound-effects-v2" !in quoted)
        assertTrue("suno-sfx" !in quoted)
        assertTrue("tts" !in quoted)
        assertTrue("suno-speech" !in quoted)
        // 17 «за 1000 символов» TTS + 2 «за секунду» + явный mirelo-sfx-2.
        assertEquals(20, quoted.count { id -> catalog.first { it.id == id }.isGenerativeAudio() })
    }

    @Test
    fun flowDefaultsMatchResearch() {
        assertEquals("gpt-4o-mini-tts", defaultAudioModel(catalog, AudioFlow.Tts)?.id)
        assertEquals("suno", defaultAudioModel(catalog, AudioFlow.Music)?.id)
    }
}
