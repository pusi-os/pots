package com.pusios.pots.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cloned voices plug into the cloud TTS flow through the model's `voice_id`
 * axis (spec §15): `voice-clone-tts` (required), `elevenlabs-v4-tts` and
 * the `tts` aggregator (optional). The axis is a free-text voice id with no
 * catalog options, so the regular chip filter would hide it — these tests
 * pin the exception that renders it as the «Мой голос» picker instead.
 */
class VoiceCloneSelectionTest {

    private fun model(id: String, vararg params: MediaParamDef) = MediaModel(id = id, kind = MediaKind.Audio, task = "tts", params = params.toList())

    private val voiceIdAxis = MediaParamDef(key = "voice_id", label = "Ваш голос", type = "text", required = true)
    private val voiceAxis = MediaParamDef(
        key = "voice",
        label = "Голос",
        options = listOf(MediaCapValue("rachel", "Rachel"), MediaCapValue("aria", "Aria")),
    )
    private val speedAxis = MediaParamDef(key = "speed", label = "Скорость речи", type = "text")

    @Test
    fun cloneAcceptanceMatchesResearch() {
        // Research §3.3: exactly these three take a cloned voice_id.
        assertTrue(model("voice-clone-tts", voiceIdAxis).acceptsCloneVoices())
        assertTrue(model("elevenlabs-v4-tts", voiceAxis, voiceIdAxis).acceptsCloneVoices())
        assertTrue(model("tts", voiceAxis, voiceIdAxis).acceptsCloneVoices())
        assertFalse(model("gpt-4o-mini-tts", voiceAxis).acceptsCloneVoices())
        assertFalse(model("grok-tts", voiceAxis).acceptsCloneVoices())
        assertFalse(model("local:piper-irina").acceptsCloneVoices())
    }

    @Test
    fun cloneVoiceAxisIsVoiceIdOnlyForCloneModels() {
        assertEquals("voice_id", model("voice-clone-tts", voiceIdAxis).cloneVoiceAxis()?.key)
        // elevenlabs-v4-tts keeps its built-in `voice` axis separate; clones
        // always ride `voice_id` (the catalog's own label says «вместо списка»).
        assertEquals("voice_id", model("elevenlabs-v4-tts", voiceAxis, voiceIdAxis).cloneVoiceAxis()?.key)
        assertNull(model("gpt-4o-mini-tts", voiceAxis).cloneVoiceAxis())
        assertNull(model("voice-clone-tts").cloneVoiceAxis())
    }

    @Test
    fun voiceIdChipRendersForCloneModelsWithoutOptions() {
        val chips = audioToolbarParams(model("voice-clone-tts", voiceIdAxis, speedAxis))
        assertEquals("voice_id", chips.first().key)
        assertTrue(chips.any { it.key == "speed" && it.options.isNotEmpty() })
    }

    @Test
    fun voiceIdChipStaysWithinFourChipCap() {
        val many = model(
            "tts",
            voiceAxis,
            voiceIdAxis,
            MediaParamDef("gemini_voice", "Голос Gemini", options = listOf(MediaCapValue("g1", "G1"))),
            MediaParamDef("minimax_voice", "Голос MiniMax", options = listOf(MediaCapValue("m1", "M1"))),
            MediaParamDef("qwen3_voice", "Голос Qwen", options = listOf(MediaCapValue("q1", "Q1"))),
        )
        val chips = audioToolbarParams(many)
        assertEquals(4, chips.size)
        assertTrue("voice_id" in chips.map { it.key })
        assertTrue("voice" in chips.map { it.key })
    }

    @Test
    fun nonCloneModelsKeepTodayBehavior() {
        // A voice axis with options renders the plain sheet; without options
        // and without clone support there is no chip at all.
        val chips = audioToolbarParams(model("gpt-4o-mini-tts", voiceAxis))
        assertEquals(listOf("voice"), chips.map { it.key })
        assertTrue(audioToolbarParams(model("chatterbox-tts", MediaParamDef("voice", "Голос", type = "text"))).isEmpty())
    }
}
