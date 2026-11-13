package com.pusios.pots.media

/**
 * Audio sheet selection rules. The sheet shows generative `kind=audio|music`
 * models only — STT/analytics (audio input), audio transforms and text
 * utilities are excluded (research: `docs/design/media-expansion/pots-audio-gen-research.md`).
 *
 * Audio mode has two flows (spec §13): «Озвучить текст» (TTS) and «Создать
 * музыку» (music + effects). The sheet composition, the model selection and
 * the prompt draft all follow the active flow.
 */
enum class AudioFlow(val title: String, val storageKey: String) {
    Tts("Озвучить текст", "tts"),
    Music("Создать музыку", "music"),
    ;

    companion object {
        fun fromStorage(raw: String?): AudioFlow = entries.firstOrNull { it.storageKey == raw } ?: Tts
    }
}

internal fun MediaModel.isGenerativeAudio(): Boolean = (kind == MediaKind.Audio || kind == MediaKind.Music) &&
    !acceptsAudioReference &&
    task != "utility"

/**
 * Hidden in v1 (spec §15): the model needs a cloned `voice_id`, but the provider
 * documents no upload path for it and the voice-clone contract only creates voices
 * for elevenlabs engines, so the clone-voice picker would be structurally empty here.
 * It returns to the sheet once the provider documents voice upload for Dune/MiMo —
 * documentation is the unblock condition, not a live generation test.
 */
internal val HIDDEN_AUDIO_MODEL_IDS = setOf("mimo-v2-5-tts-voiceclone")

/** Shown only when the journal already has a Suno track to replace a section of. */
internal const val SUNO_REPLACE_MODEL_ID = "suno-replace-section"

/** The flow a generative model belongs to; null for everything outside the audio sheet. */
internal fun MediaModel.audioFlow(): AudioFlow? = when {
    !isGenerativeAudio() -> null
    id in HIDDEN_AUDIO_MODEL_IDS -> null
    task == "tts" -> AudioFlow.Tts
    else -> AudioFlow.Music
}

/** Generative models of one flow, optionally without the gated `suno-replace-section`. */
internal fun audioFlowModels(catalog: List<MediaModel>, flow: AudioFlow, hasSunoHistory: Boolean = true): List<MediaModel> = catalog.filter { it.audioFlow() == flow && (hasSunoHistory || it.id != SUNO_REPLACE_MODEL_ID) }

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

/** Non-empty sheet sections of one flow; models inside each are sorted cheapest first. */
internal fun audioFlowGroups(catalog: List<MediaModel>, flow: AudioFlow, hasSunoHistory: Boolean = true): List<Pair<AudioSheetGroup, List<MediaModel>>> {
    val models = audioFlowModels(catalog, flow, hasSunoHistory)
    val priceThenId = compareBy<MediaModel>({ it.fromRub ?: Double.MAX_VALUE }, { it.id })
    return AudioSheetGroup.entries.mapNotNull { group ->
        val grouped = models.filter { it.audioSheetGroup() == group }.sortedWith(priceThenId)
        if (grouped.isEmpty()) null else group to grouped
    }
}

/** Sheet defaults chosen by research; the first present one wins. */
private val PREFERRED_MODELS_BY_FLOW = mapOf(
    AudioFlow.Tts to listOf("gpt-4o-mini-tts"),
    AudioFlow.Music to listOf("suno", "elevenlabs-sound-effects-v2"),
)

internal fun defaultAudioModel(catalog: List<MediaModel>, flow: AudioFlow): MediaModel? {
    val flowModels = audioFlowModels(catalog, flow)
    return PREFERRED_MODELS_BY_FLOW[flow].orEmpty().firstNotNullOfOrNull { id -> flowModels.firstOrNull { it.id == id } }
        ?: flowModels.firstOrNull()
}

/** Toolbar chip axes for audio: catalog selects with options, priority order, max 4. */
internal fun audioToolbarParams(model: MediaModel?): List<MediaParamDef> {
    val params = model?.params.orEmpty().map(::prepareAudioParam)
    val selectable = params.filter { it.options.isNotEmpty() && it.key !in AUDIO_TECHNICAL_KEYS }
    val remaining = selectable.associateByTo(linkedMapOf()) { it.key }
    val ordered = AUDIO_CHIP_PRIORITY.mapNotNull { remaining.remove(it) } + remaining.values
    return ordered.take(MAX_AUDIO_CHIPS)
}

/**
 * Axes the chips understand even when the catalog gives no options:
 * numeric voice/speed aliases get fixed presets (spec §15), bool axes become
 * two-option toggles (`instrumental` = «Без слов»).
 */
private fun prepareAudioParam(def: MediaParamDef): MediaParamDef = when {
    def.options.isNotEmpty() -> def
    def.key in SPEED_AXIS_ALIASES -> def.copy(type = "select", options = SPEED_PRESET_OPTIONS)
    def.type == "bool" -> def.copy(type = "select", options = listOf(MediaCapValue("false", "Выкл"), MediaCapValue("true", "Вкл")))
    else -> def
}

private val SPEED_AXIS_ALIASES = setOf("speed", "speaking_rate", "pace")

private val SPEED_PRESET_OPTIONS = listOf("0.5", "0.75", "1", "1.25", "1.5", "2").map { MediaCapValue(it, "$it×") }

/** Axes shown only in «Ещё», never as toolbar chips. */
private val AUDIO_TECHNICAL_KEYS = setOf("seed", "sample_rate", "bitrate", "channel", "stability", "similarity_boost")

private val AUDIO_CHIP_PRIORITY = listOf(
    "duration",
    "voice", "voice_id", "gemini_voice", "minimax_voice", "qwen3_voice",
    "response_format", "output_format",
    "language", "language_code",
    "speed", "speaking_rate", "pace",
)

private const val MAX_AUDIO_CHIPS = 4

/**
 * The tier price for the selected chips; null when the model is flat, no chips
 * are selected, or no tier matches. Empty `when` conditions match anything.
 */
internal fun MediaModel.tierPrice(opts: Map<String, String>): Double? {
    if (tiers.isEmpty() || opts.isEmpty()) return null
    return tiers.firstOrNull { tier -> tier.whenConditions.all { (key, value) -> opts[key].orEmpty() == value } }?.priceRub
}

/** `от 0,07 ₽ за 1000 символов` — price badge text for the audio sheet (ru locale). */
internal fun audioPriceLabel(model: MediaModel, opts: Map<String, String> = emptyMap()): String {
    if (model.priceUnavailable) return "цена недоступна"
    val unit = model.unit.orEmpty()
    val tier = model.tierPrice(opts)?.let { formatRub(it) }
    if (tier != null) return if (unit.isBlank()) "$tier ₽" else "$tier ₽ $unit"
    val price = model.fromRub ?: return unit
    val prefix = if (model.priceStatus == "estimate") "≈ " else ""
    return if (unit.isBlank()) prefix + "от " + formatRub(price) + " ₽" else prefix + "от " + formatRub(price) + " ₽ " + unit
}

private fun formatRub(price: Double): String = price.toString().replace('.', ',').let { if (it.endsWith(",0")) it.dropLast(2) else it }

/**
 * Quote policy (spec §2): metered models («за 1000 символов», «за секунду») and
 * `mirelo-sfx-2` (price finalized by duration) are quoted before submit; fixed
 * prices submit without the extra request.
 */
internal fun requiresPriceQuote(model: MediaModel): Boolean {
    val unit = model.unit.orEmpty()
    return unit.contains("за 1000 символов") || unit.contains("за секунду") || model.id == "mirelo-sfx-2"
}
