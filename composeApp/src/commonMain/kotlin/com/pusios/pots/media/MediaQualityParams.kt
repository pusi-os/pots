package com.pusios.pots.media

/**
 * Generation-quality and video-parameter toolbar rules (spec §19).
 *
 * One quality selector for image generation and editing (default — the
 * cheapest level), a «со звуком / без звука» toggle and resolution controls
 * for video. Everything is derived from the catalog axes; the choices ride
 * the job `opts`. Resolution values in disguise (pixverse/runway keep them
 * in the `quality` axis) are detected and rendered as resolution chips.
 */
private val RESOLUTION_VALUE_REGEX = Regex("""\d{3,4}p|[24]k""", RegexOption.IGNORE_CASE)

/** True when every option value looks like a resolution (`720p`, `1080P`, `2k`, `4K`). */
internal fun MediaParamDef.isResolutionLike(): Boolean = options.isNotEmpty() && options.all { RESOLUTION_VALUE_REGEX.matches(it.value) }

/**
 * The quality selector axis: a `quality` select with at least two options
 * whose values are real levels, not resolutions. Degenerate one-option axes
 * and resolution-in-quality models (pixverse, runway) have no selector.
 */
internal fun MediaModel.qualitySelectorAxis(): MediaParamDef? = params.firstOrNull { it.key == "quality" && it.options.size >= 2 && !it.isResolutionLike() }

/** The sound-generation toggle axis (`audio` | `generate_audio`), when present. */
internal fun MediaModel.audioToggleAxis(): MediaParamDef? = params.firstOrNull { it.key == "audio" || it.key == "generate_audio" }

/**
 * The quality default: the lowest level — the option with the minimal
 * `priceRub`; ties or missing prices fall back to the first catalog option.
 * Overrides the catalog default (`gpt-image-2-6` ships `high`).
 */
internal fun defaultQualityOption(axis: MediaParamDef): MediaCapValue? {
    val options = axis.options
    if (options.isEmpty()) return null
    val minPrice = options.mapNotNull { it.priceRub }.minOrNull() ?: return options.first()
    return options.first { it.priceRub == minPrice }
}

/** Russian level names; the catalog's parenthesized suffix survives («Basic (2K)» → «Базовое (2K)»). */
private val QUALITY_LABELS_RU = mapOf(
    "low" to "Низкое",
    "medium" to "Среднее",
    "high" to "Высокое",
    "very_low" to "Минимальное",
    "basic" to "Базовое",
    "ultra" to "Ультра",
    "full" to "Полное",
    "draft" to "Черновик",
)

private val LABEL_SUFFIX_REGEX = Regex("""\([^)]*\)""")

internal fun qualityOptionLabel(option: MediaCapValue): String {
    val ru = QUALITY_LABELS_RU[option.value.lowercase()] ?: return option.label
    val suffix = LABEL_SUFFIX_REGEX.find(option.label)?.value ?: return ru
    return "$ru $suffix"
}

/** One image/video toolbar chip: the axis plus how it renders (spec §19.3). */
internal data class MediaToolbarChip(
    val kind: Kind,
    val def: MediaParamDef,
) {
    enum class Kind {
        /** Existing behaviour: the chip opens `MediaParamSheet`. */
        Sheet,

        /** The quality dropdown with prices (§19.1). */
        QualityDropdown,

        /** The «со звуком / без звука» toggle (§19.2). */
        AudioToggle,
    }
}

/** Toolbar axis priority: resolution → quality → duration → sound (§19.2, §19.3). */
private val MEDIA_CHIP_ORDER = listOf("resolution", "quality", "duration", "audio", "generate_audio")

/** No more than four chips in the row; extra axes stay out of the toolbar. */
private const val MAX_MEDIA_CHIPS = 4

/**
 * Toolbar chips of the image/video modes — the generalized «axis → chip»
 * mechanism. Audio mode keeps [audioToolbarParams]. Input/service axes
 * (`mode`, `tier`, `seed`, `*_url`, …) never become chips.
 */
internal fun mediaToolbarParams(model: MediaModel?): List<MediaToolbarChip> {
    model ?: return emptyList()
    val byKey = model.params.associateBy { it.key }
    return MEDIA_CHIP_ORDER.mapNotNull { key ->
        val def = byKey[key] ?: return@mapNotNull null
        when (key) {
            "quality" -> when {
                def.options.size < 2 -> null
                def.isResolutionLike() -> MediaToolbarChip(MediaToolbarChip.Kind.Sheet, def)
                else -> MediaToolbarChip(MediaToolbarChip.Kind.QualityDropdown, def)
            }

            "audio", "generate_audio" -> MediaToolbarChip(MediaToolbarChip.Kind.AudioToggle, def)

            else -> if (def.options.isNotEmpty()) MediaToolbarChip(MediaToolbarChip.Kind.Sheet, def) else null
        }
    }.take(MAX_MEDIA_CHIPS)
}

/**
 * Explicit opt defaults before submit (§19.1, §19.2). Quality is always sent
 * to selector models — omitting it would silently apply the catalog default
 * (`high` for `gpt-image-2-6`, the cost trap the owner directive closes).
 * The sound toggle rides its catalog default until the user flips it.
 * Explicit user selections are never overridden.
 */
internal fun applyMediaOptDefaults(opts: Map<String, String>, model: MediaModel?, savedQuality: String? = null): Map<String, String> {
    model ?: return opts
    var result = opts
    val qualityAxis = model.qualitySelectorAxis()
    if (qualityAxis != null && "quality" !in result) {
        val values = qualityAxis.options.map { it.value }
        val value = savedQuality?.takeIf { it in values } ?: defaultQualityOption(qualityAxis)?.value
        if (value != null) result = result + ("quality" to value)
    }
    val audioAxis = model.audioToggleAxis()
    if (audioAxis != null && audioAxis.key !in result) {
        result = result + (audioAxis.key to (audioAxis.defaultValue ?: "false"))
    }
    return result
}
