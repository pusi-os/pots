package com.pusios.pots.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Audio sheet filtering: the generation sheet must contain only generative
 * audio/music models — no STT (audio input), no audio transforms, no text
 * utilities without audio output. See
 * `docs/design/media-expansion/pots-audio-gen-research.md`.
 */
class AudioModelsTest {

    private fun model(
        id: String,
        kind: MediaKind = MediaKind.Audio,
        task: String? = null,
        acceptsAudio: Boolean = false,
        fromRub: Double? = 1.0,
        unit: String? = "за 1000 символов",
    ) = MediaModel(
        id = id,
        kind = kind,
        task = task,
        acceptsAudioReference = acceptsAudio,
        fromRub = fromRub,
        unit = unit,
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
    fun sheetGroupsAreVoiceMusicEffectsSortedByPrice() {
        val cheapTts = model("mimo-v2-5-tts", task = "tts", fromRub = 0.01)
        val groups = audioSheetGroups(catalog + cheapTts)
        assertEquals(listOf(AudioSheetGroup.Voice, AudioSheetGroup.Music, AudioSheetGroup.Effects), groups.map { it.first })
        val voice = groups.first { it.first == AudioSheetGroup.Voice }.second
        // Inside a group: cheapest first.
        assertEquals(listOf("mimo-v2-5-tts", "gpt-4o-mini-tts"), voice.map { it.id })
    }

    @Test
    fun defaultModelPrefersResearchDefaults() {
        assertEquals("gpt-4o-mini-tts", defaultAudioModel(catalog)?.id)
        // Without the TTS default the music default wins.
        assertEquals("suno", defaultAudioModel(catalog - tts)?.id)
        // Without any default the first generative model wins.
        assertEquals("mimo-v2-5-tts", defaultAudioModel(listOf(utility, model("mimo-v2-5-tts", task = "tts", fromRub = 0.01)))?.id)
        assertEquals(null, defaultAudioModel(listOf(utility, stt)))
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
    fun priceLabelShowsUnitAndCommaDecimal() {
        assertEquals("от 0,07 ₽ за 1000 символов", audioPriceLabel(tts.copy(fromRub = 0.07)))
        assertEquals("от 6,29 ₽ за трек", audioPriceLabel(music.copy(fromRub = 6.29)))
        // Estimate prices get the ≈ prefix.
        assertEquals("≈ от 1,36 ₽ за файл", audioPriceLabel(sfx.copy(fromRub = 1.36, priceStatus = "estimate")))
        // No price: unit only.
        assertEquals("за файл", audioPriceLabel(sfx.copy(fromRub = null, priceStatus = null)))
    }
}
