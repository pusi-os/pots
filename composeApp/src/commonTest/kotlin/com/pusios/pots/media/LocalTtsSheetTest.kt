package com.pusios.pots.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Local TTS sheet rules (spec §14): platform engines form a separate bottom
 * group «Локально · 0 ₽» of the TTS flow only. Everything here is pure —
 * the platform actuals only supply the model list, so non-Android targets
 * (no file synthesis, research §4.2) run the same logic over an empty list
 * and get no group at all.
 */
class LocalTtsSheetTest {

    private val piper = localTtsModel("local:piper-irina", "Офлайн, только русский")
    private val system = localTtsModel("local:system", "Голос устройства")
    private val cloudTts = MediaModel(id = "gpt-4o-mini-tts", kind = MediaKind.Audio, task = "tts", fromRub = 1.0, unit = "за 1000 символов")
    private val suno = MediaModel(id = "suno", kind = MediaKind.Music, task = "music", fromRub = 10.0)

    @Test
    fun localModelsAreSyntheticTtsWithSpeedAxisAndZeroPrice() {
        assertEquals(MediaKind.Audio, piper.kind)
        assertEquals("tts", piper.task)
        assertEquals(0.0, piper.fromRub)
        assertEquals("exact", piper.priceStatus)
        assertTrue(piper.isLocalTts)
        // Speed rides the standard preset mechanism (Piper and the system
        // engine both accept a rate; neither takes pitch).
        val speed = piper.params.single { it.key == "speed" }
        val prepared = audioToolbarParams(piper)
        assertTrue(prepared.any { it.key == "speed" && it.options.isNotEmpty() }, "speed chip with presets expected, got $prepared")
        assertEquals("Скорость", speed.label)
    }

    @Test
    fun localGroupIsLastAndOnlyForTtsFlow() {
        val catalog = listOf(cloudTts, suno, piper, system)
        val ttsGroups = audioFlowGroups(catalog, AudioFlow.Tts)
        assertEquals(listOf(AudioSheetGroup.Voice, AudioSheetGroup.Local), ttsGroups.map { it.first })
        assertEquals(listOf("local:piper-irina", "local:system"), ttsGroups.last().second.map { it.id })
        // The music flow never shows local engines.
        val musicGroups = audioFlowGroups(catalog, AudioFlow.Music)
        assertEquals(listOf(AudioSheetGroup.Music), musicGroups.map { it.first })
    }

    @Test
    fun emptyLocalCatalogMeansNoLocalGroup() {
        // Desktop/iOS/web actuals return no local models — the group must not
        // render at all (no disabled fakes, spec §14).
        val groups = audioFlowGroups(listOf(cloudTts), AudioFlow.Tts)
        assertEquals(listOf(AudioSheetGroup.Voice), groups.map { it.first })
    }

    @Test
    fun localBadgeLabelIsFixed() {
        assertEquals("Локально · 0 ₽", audioPriceLabel(piper))
        assertEquals("Локально · 0 ₽", audioPriceLabel(system))
        assertFalse(audioPriceLabel(cloudTts).contains("Локально"))
    }

    @Test
    fun localModelsPassFlowFiltersButNeverDefault() {
        // They are generative TTS so resolveModel/sheet composition can reuse
        // the existing filters…
        val flowModels = audioFlowModels(listOf(cloudTts, piper), AudioFlow.Tts)
        assertEquals(2, flowModels.size)
        // …but the research default stays the cloud model: local is never auto-selected.
        assertEquals("gpt-4o-mini-tts", defaultAudioModel(listOf(cloudTts, piper), AudioFlow.Tts)?.id)
    }

    @Test
    fun localModelSelectionSurvivesCatalogValidation() {
        // loadModels drops selections that vanished from the cloud catalog; a
        // saved local id must survive that pass on platforms that have it.
        assertTrue(isKnownLocalTtsId("local:piper-irina", listOf(piper, system)))
        assertFalse(isKnownLocalTtsId("local:piper-irina", emptyList()))
        assertFalse(isKnownLocalTtsId("gpt-4o-mini-tts", listOf(piper, system)))
    }
}
