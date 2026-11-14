package com.pusios.pots.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Two audio flows («Озвучить текст» / «Создать музыку») and the audio sheet
 * rules. The sheet shows generative `kind=audio|music` models only — no STT
 * (audio input), no audio transforms, no text utilities — split by flow:
 * TTS flow = `task=tts`, music flow = music + sfx groups.
 * Spec: `~/wiki/design/pots-media-ux-spec.md` §13–§15; research:
 * `docs/design/media-expansion/pots-audio-catalog-dump.json`.
 */
class AudioModelsTest {

    private fun model(
        id: String,
        kind: MediaKind = MediaKind.Audio,
        task: String? = null,
        acceptsAudio: Boolean = false,
        fromRub: Double? = 1.0,
        unit: String? = "за 1000 символов",
        tiers: List<MediaTierPrice> = emptyList(),
    ) = MediaModel(
        id = id,
        kind = kind,
        task = task,
        acceptsAudioReference = acceptsAudio,
        fromRub = fromRub,
        unit = unit,
        tiers = tiers,
    )

    private val tts = model("gpt-4o-mini-tts", task = "tts")
    private val stt = model("whisper-large-v3", task = "asr", acceptsAudio = true, unit = "за минуту")
    private val transform = model("elevenlabs-audio-isolation", task = null, acceptsAudio = true, unit = "за минуту")
    private val music = model("suno", kind = MediaKind.Music, task = null, fromRub = 6.29, unit = "за трек")
    private val musicTransform = model("suno-cover-audio", kind = MediaKind.Music, acceptsAudio = true, unit = "за трек")
    private val sfx = model("elevenlabs-sound-effects-v2", task = "sfx", fromRub = 1.36, unit = "за файл")
    private val utility = model("suno-lyrics", task = "utility", fromRub = 1.89, unit = "за файл")

    private val catalog = listOf(tts, stt, transform, music, musicTransform, sfx, utility)

    @Test
    fun generativeFilterKeepsTtsSfxMusicOnly() {
        val sheet = catalog.filter { it.isGenerativeAudio() }
        assertEquals(listOf("gpt-4o-mini-tts", "suno", "elevenlabs-sound-effects-v2"), sheet.map { it.id })
    }

    @Test
    fun sttAndTransformsAreNotGenerative() {
        assertFalse(stt.isGenerativeAudio())
        assertFalse(transform.isGenerativeAudio())
        assertFalse(musicTransform.isGenerativeAudio())
        assertFalse(utility.isGenerativeAudio())
        assertTrue(tts.isGenerativeAudio())
        assertTrue(sfx.isGenerativeAudio())
        assertTrue(music.isGenerativeAudio())
    }

    @Test
    fun imageAndVideoModelsNeverEnterAudioSheet() {
        assertFalse(model("gpt-image-2", kind = MediaKind.Image, task = null).isGenerativeAudio())
        assertFalse(model("veo-3.1", kind = MediaKind.Video, task = null).isGenerativeAudio())
    }

    @Test
    fun flowMembershipFollowsTtsTask() {
        assertEquals(AudioFlow.Tts, tts.audioFlow())
        assertEquals(AudioFlow.Music, music.audioFlow())
        assertEquals(AudioFlow.Music, sfx.audioFlow())
        // STT / transforms / utilities belong to no flow.
        assertNull(stt.audioFlow())
        assertNull(transform.audioFlow())
        assertNull(utility.audioFlow())
        assertNull(model("gpt-image-2", kind = MediaKind.Image, task = null).audioFlow())
    }

    @Test
    fun mimoVoicecloneIsHiddenInV1() {
        val hidden = model("mimo-v2-5-tts-voiceclone", task = "tts", fromRub = 0.01)
        assertNull(hidden.audioFlow())
        assertTrue(audioFlowModels(listOf(tts, hidden), AudioFlow.Tts).none { it.id == "mimo-v2-5-tts-voiceclone" })
    }

    @Test
    fun ttsFlowShowsOnlyVoiceGroup() {
        val cheapTts = model("mimo-v2-5-tts", task = "tts", fromRub = 0.01)
        val groups = audioFlowGroups(catalog + cheapTts, AudioFlow.Tts)
        assertEquals(listOf(AudioSheetGroup.Voice), groups.map { it.first })
        // Inside the group: cheapest first.
        assertEquals(listOf("mimo-v2-5-tts", "gpt-4o-mini-tts"), groups.single().second.map { it.id })
    }

    @Test
    fun musicFlowShowsMusicAndEffectsSortedByPrice() {
        val cheapSfx = model("mirelo-sfx-2", task = "sfx", fromRub = 0.5, unit = "за файл")
        val groups = audioFlowGroups(catalog + cheapSfx, AudioFlow.Music)
        assertEquals(listOf(AudioSheetGroup.Music, AudioSheetGroup.Effects), groups.map { it.first })
        assertEquals(listOf("suno"), groups.first { it.first == AudioSheetGroup.Music }.second.map { it.id })
        assertEquals(listOf("mirelo-sfx-2", "elevenlabs-sound-effects-v2"), groups.first { it.first == AudioSheetGroup.Effects }.second.map { it.id })
    }

    @Test
    fun sunoReplaceSectionNeedsSunoHistory() {
        val replace = model("suno-replace-section", kind = MediaKind.Music, task = null, fromRub = 4.51, unit = "за трек")
        val withHistory = audioFlowModels(listOf(music, replace), AudioFlow.Music, hasSunoHistory = true)
        val withoutHistory = audioFlowModels(listOf(music, replace), AudioFlow.Music, hasSunoHistory = false)
        assertEquals(listOf("suno", "suno-replace-section"), withHistory.map { it.id })
        assertEquals(listOf("suno"), withoutHistory.map { it.id })
    }

    @Test
    fun defaultModelPerFlow() {
        assertEquals("gpt-4o-mini-tts", defaultAudioModel(catalog, AudioFlow.Tts)?.id)
        assertEquals("suno", defaultAudioModel(catalog, AudioFlow.Music)?.id)
        // Without the music default the effects default wins.
        assertEquals("elevenlabs-sound-effects-v2", defaultAudioModel(catalog - music, AudioFlow.Music)?.id)
        // Without any preferred model the first flow model wins.
        assertEquals("mimo-v2-5-tts", defaultAudioModel(listOf(utility, model("mimo-v2-5-tts", task = "tts", fromRub = 0.01)), AudioFlow.Tts)?.id)
        assertNull(defaultAudioModel(listOf(utility, stt), AudioFlow.Tts))
        assertNull(defaultAudioModel(listOf(tts), AudioFlow.Music))
    }

    @Test
    fun toolbarChipsFollowPriorityAndDropTechnicalAxes() {
        val model = MediaModel(
            id = "elevenlabs-v4-tts",
            kind = MediaKind.Audio,
            params = listOf(
                MediaParamDef("seed", "Seed"),
                MediaParamDef("language_code", "Язык", options = listOf(MediaCapValue("auto"), MediaCapValue("ru"))),
                MediaParamDef("voice", "Голос", options = listOf(MediaCapValue("Rachel"), MediaCapValue("Aria"))),
                MediaParamDef("stability", "Стабильность", options = listOf(MediaCapValue("0.5"))),
                MediaParamDef("output_format", "Формат", options = listOf(MediaCapValue("mp3"), MediaCapValue("opus"))),
                MediaParamDef("duration", "Длительность", options = listOf(MediaCapValue("30"))),
            ),
        )
        val chips = audioToolbarParams(model)
        assertEquals(listOf("duration", "voice", "output_format", "language_code"), chips.map { it.key })
    }

    @Test
    fun toolbarChipsCapAtFour() {
        val model = MediaModel(
            id = "suno",
            kind = MediaKind.Music,
            params = listOf(
                MediaParamDef("duration", "Длительность", options = listOf(MediaCapValue("30"))),
                MediaParamDef("voice", "Голос", options = listOf(MediaCapValue("a"))),
                MediaParamDef("output_format", "Формат", options = listOf(MediaCapValue("mp3"))),
                MediaParamDef("language", "Язык", options = listOf(MediaCapValue("ru"))),
                MediaParamDef("version", "Версия", options = listOf(MediaCapValue("4"))),
            ),
        )
        val chips = audioToolbarParams(model)
        assertEquals(4, chips.size)
        assertEquals(listOf("duration", "voice", "output_format", "language"), chips.map { it.key })
    }

    @Test
    fun toolbarChipsSkipAxesWithoutOptions() {
        val model = MediaModel(
            id = "lyria-3-5",
            kind = MediaKind.Music,
            params = listOf(
                MediaParamDef("lyrics", "Текст песни", type = "text"),
                MediaParamDef("bpm", "BPM", type = "number"),
                MediaParamDef("duration", "Длительность", options = emptyList()),
            ),
        )
        assertEquals(emptyList(), audioToolbarParams(model))
    }

    @Test
    fun voiceAliasAxesBecomeChips() {
        // gemini-tts exposes voice as `gemini_voice` and speed as `pace` (select).
        val model = MediaModel(
            id = "gemini-tts",
            kind = MediaKind.Audio,
            params = listOf(
                MediaParamDef("gemini_voice", "Голос Gemini", options = listOf(MediaCapValue("Kore"), MediaCapValue("Puck"))),
                MediaParamDef("pace", "Темп", options = listOf(MediaCapValue("Natural"), MediaCapValue("Fast"))),
                MediaParamDef("style", "Стиль", type = "text"),
            ),
        )
        val chips = audioToolbarParams(model)
        assertEquals(listOf("gemini_voice", "pace"), chips.map { it.key })
    }

    @Test
    fun numericSpeedAxisGetsPresetOptions() {
        // gpt-4o-mini-tts `speed` is a free number — chips use fixed presets.
        val model = MediaModel(
            id = "gpt-4o-mini-tts",
            kind = MediaKind.Audio,
            params = listOf(
                MediaParamDef("voice", "Голос", options = listOf(MediaCapValue("alloy"))),
                MediaParamDef("speed", "Скорость (0.25–4)", type = "number"),
            ),
        )
        val chips = audioToolbarParams(model)
        val speed = chips.first { it.key == "speed" }
        assertEquals(listOf("0.5", "0.75", "1", "1.25", "1.5", "2"), speed.options.map { it.value })
        assertEquals(listOf("0.5×", "0.75×", "1×", "1.25×", "1.5×", "2×"), speed.options.map { it.label })
    }

    @Test
    fun boolAxisBecomesToggleChip() {
        // suno `instrumental` (без слов) is a bool — rendered as a two-option chip.
        val model = MediaModel(
            id = "suno",
            kind = MediaKind.Music,
            params = listOf(
                MediaParamDef("instrumental", "Без вокала (инструментал)", type = "bool", defaultValue = "false"),
            ),
        )
        val chips = audioToolbarParams(model)
        val toggle = chips.single { it.key == "instrumental" }
        assertEquals(listOf("false", "true"), toggle.options.map { it.value })
    }

    @Test
    fun priceLabelShowsUnitAndCommaDecimal() {
        assertEquals("от 0,07 ₽ за 1000 символов", audioPriceLabel(tts.copy(fromRub = 0.07)))
        assertEquals("от 6,29 ₽ за трек", audioPriceLabel(music.copy(fromRub = 6.29)))
        // Estimate prices get the ≈ prefix.
        assertEquals("≈ от 1,36 ₽ за файл", audioPriceLabel(sfx.copy(fromRub = 1.36, priceStatus = "estimate")))
        // No price: unit only.
        assertEquals("за файл", audioPriceLabel(sfx.copy(fromRub = null, priceStatus = null)))
    }

    @Test
    fun tierPriceMatchesSelectedChips() {
        val suno = music.copy(
            tiers = listOf(
                MediaTierPrice("Не указано", 6.29, mapOf("personaModel" to setOf(""))),
                MediaTierPrice("Style Persona", 10.82, mapOf("personaModel" to setOf("style_persona"))),
                MediaTierPrice("Voice Persona", 10.82, mapOf("personaModel" to setOf("voice_persona"))),
            ),
        )
        // No chips selected: the default (empty) tier matches exactly.
        assertEquals(6.29, suno.tierPrice(mapOf("version" to "v6")))
        assertEquals(10.82, suno.tierPrice(mapOf("personaModel" to "style_persona")))
        // No tier matches -> null, the badge falls back to «от fromRub».
        assertNull(suno.tierPrice(mapOf("personaModel" to "unknown")))
        // Flat models and empty opts never recompute.
        assertNull(music.tierPrice(mapOf("version" to "v6")))
        assertNull(suno.tierPrice(emptyMap()))
    }

    @Test
    fun tierBadgeRecomputesForSelectedChips() {
        val aggregator = model(
            "tts",
            task = "tts",
            fromRub = 7.0,
            unit = "за озвучку",
            tiers = listOf(
                MediaTierPrice("Gemini 3.1 Flash TTS", 7.0, mapOf("engine" to setOf("gemini-flash"))),
                MediaTierPrice("MiniMax Speech 2.8 HD", 13.0, mapOf("engine" to setOf("minimax-hd"))),
            ),
        )
        // Selected engine: exact tier price, no «от».
        assertEquals("13 ₽ за озвучку", audioPriceLabel(aggregator, mapOf("engine" to "minimax-hd")))
        assertEquals("7 ₽ за озвучку", audioPriceLabel(aggregator, mapOf("engine" to "gemini-flash")))
        // Without chips the sheet badge keeps the «от» form.
        assertEquals("от 7 ₽ за озвучку", audioPriceLabel(aggregator))
        // Unknown chip value falls back to «от fromRub».
        assertEquals("от 7 ₽ за озвучку", audioPriceLabel(aggregator, mapOf("engine" to "qwen3")))
    }

    @Test
    fun quotePolicyMeteredAndMireloOnly() {
        assertTrue(requiresPriceQuote(model("gpt-4o-mini-tts", task = "tts", unit = "за 1000 символов")))
        assertTrue(requiresPriceQuote(model("mirelo-sfx-2-video", task = "sfx", unit = "за секунду")))
        assertTrue(requiresPriceQuote(model("mirelo-sfx-2", task = "sfx", unit = "за файл")))
        // Fixed prices submit without an extra quote request.
        assertFalse(requiresPriceQuote(model("suno", kind = MediaKind.Music, unit = "за трек")))
        assertFalse(requiresPriceQuote(model("elevenlabs-sound-effects-v2", task = "sfx", unit = "за файл")))
        assertFalse(requiresPriceQuote(model("tts", task = "tts", unit = "за озвучку")))
    }
}
