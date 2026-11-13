package com.pusios.pots.media

/**
 * Audio sheet selection rules. The sheet shows generative `kind=audio|music`
 * models only — STT/analytics (audio input), audio transforms and text
 * utilities are excluded (research: `docs/design/media-expansion/pots-audio-gen-research.md`).
 */
internal fun MediaModel.isGenerativeAudio(): Boolean =
    (kind == MediaKind.Audio || kind == MediaKind.Music) &&
        !acceptsAudioReference &&
        task != "utility"

/** Sheet sections, top to bottom. */
internal enum class AudioSheetGroup(val title: String) {
    Voice("Голос"),
    Music("Музыка"),
    Effects("Эффекты"),
}

internal fun MediaModel.audioSheetGroup(): AudioSheetGroup = when (task) {
    "tts" -> AudioSheetGroup.Voice
    "sfx" -> AudioSheetGroup.Effects
    else -> AudioSheetGroup.Music
}

/** Non-empty sheet sections; models inside each are sorted cheapest first. */
internal fun audioSheetGroups(catalog: List<MediaModel>): List<Pair<AudioSheetGroup, List<MediaModel>>> {
    val generative = catalog.filter { it.isGenerativeAudio() }
    val priceThenId = compareBy<MediaModel>({ it.fromRub ?: Double.MAX_VALUE }, { it.id })
    return AudioSheetGroup.entries.mapNotNull { group ->
        val models = generative.filter { it.audioSheetGroup() == group }.sortedWith(priceThenId)
        if (models.isEmpty()) null else group to models
    }
}

/** Sheet defaults chosen by research; the first present one wins. */
private val PREFERRED_AUDIO_MODELS = listOf("gpt-4o-mini-tts", "suno", "elevenlabs-sound-effects-v2")

internal fun defaultAudioModel(catalog: List<MediaModel>): MediaModel? {
    val generative = catalog.filter { it.isGenerativeAudio() }
    return PREFERRED_AUDIO_MODELS.firstNotNullOfOrNull { id -> generative.firstOrNull { it.id == id } }
        ?: generative.firstOrNull()
}

/** Toolbar chip axes for audio: catalog selects with options, priority order, max 4. */
internal fun audioToolbarParams(model: MediaModel?): List<MediaParamDef> {
    val params = model?.params.orEmpty()
    val selectable = params.filter { it.options.isNotEmpty() && it.key !in AUDIO_TECHNICAL_KEYS }
    val remaining = selectable.associateByTo(linkedMapOf()) { it.key }
    val ordered = AUDIO_CHIP_PRIORITY.mapNotNull { remaining.remove(it) } + remaining.values
    return ordered.take(MAX_AUDIO_CHIPS)
}

/** Axes shown only in «Ещё», never as toolbar chips. */
private val AUDIO_TECHNICAL_KEYS = setOf("seed", "sample_rate", "bitrate", "channel", "stability", "similarity_boost")

private val AUDIO_CHIP_PRIORITY = listOf("duration", "voice", "response_format", "output_format", "language", "language_code")

private const val MAX_AUDIO_CHIPS = 4

/** `от 0,07 ₽ за 1000 символов` — price badge text for the audio sheet (ru locale). */
internal fun audioPriceLabel(model: MediaModel): String {
    if (model.priceUnavailable) return "цена недоступна"
    val unit = model.unit.orEmpty()
    val price = model.fromRub ?: return unit
    val prefix = if (model.priceStatus == "estimate") "≈ " else ""
    val formatted = price.toString().replace('.', ',').let { if (it.endsWith(",0")) it.dropLast(2) else it }
    return if (unit.isBlank()) prefix + "от " + formatted + " ₽" else prefix + "от " + formatted + " ₽ " + unit
}
