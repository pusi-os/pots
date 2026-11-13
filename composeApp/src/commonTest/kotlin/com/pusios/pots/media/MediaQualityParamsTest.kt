package com.pusios.pots.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Image quality selector and video sound/resolution toolbar controls
 * (spec §19). The rules are pure catalog-axis logic: visibility by axis
 * presence, the cheapest-level default, explicit `opts.quality`, catalog
 * audio defaults. Prices live in [MediaPriceTablesTest]; the live catalog
 * numbers in [MediaQualityFixtureTest] (desktopTest).
 */
class MediaQualityParamsTest {

    private fun qualityAxis(options: List<MediaCapValue>, default: String? = null) = MediaParamDef(
        key = "quality",
        label = "Качество",
        defaultValue = default,
        options = options,
    )

    private fun model(id: String, kind: MediaKind = MediaKind.Image, params: List<MediaParamDef>) = MediaModel(
        id = id,
        kind = kind,
        params = params,
    )

    // --- default quality: cheapest level, ties and missing prices -> first ---

    @Test
    fun defaultQualityIsCheapestOption() {
        val axis = qualityAxis(
            listOf(
                MediaCapValue("very_low", "Very Low", priceRub = 1.09),
                MediaCapValue("low", "Low", priceRub = 4.07),
                MediaCapValue("medium", "Medium", priceRub = 8.13),
                MediaCapValue("high", "High", priceRub = 13.54),
            ),
            default = "medium",
        )
        // The catalog default (medium) is overridden by the cheapest level.
        assertEquals("very_low", defaultQualityOption(axis)?.value)
    }

    @Test
    fun defaultQualityTieGoesToFirstCatalogOption() {
        // gpt-image-2-6: all three levels cost 1.44 — the first (low) wins,
        // overriding the catalog default `high`.
        val axis = qualityAxis(
            listOf(
                MediaCapValue("low", "Low", priceRub = 1.44),
                MediaCapValue("medium", "Medium", priceRub = 1.44),
                MediaCapValue("high", "High", priceRub = 1.44),
            ),
            default = "high",
        )
        assertEquals("low", defaultQualityOption(axis)?.value)
    }

    @Test
    fun defaultQualityWithoutPricesIsFirstOption() {
        // seedream-4-5: estimate prices, no priceRub on options.
        val axis = qualityAxis(listOf(MediaCapValue("basic", "Basic (2K)"), MediaCapValue("high", "High (4K)")), default = "basic")
        assertEquals("basic", defaultQualityOption(axis)?.value)
    }

    @Test
    fun defaultQualityOfFluxVideoIsDraft() {
        val axis = qualityAxis(
            listOf(
                MediaCapValue("full", "Полное", priceRub = 21.37),
                MediaCapValue("draft", "Черновик (быстро и дёшево)", priceRub = 7.54),
            ),
            default = "full",
        )
        assertEquals("draft", defaultQualityOption(axis)?.value)
    }

    // --- selector visibility: quality axis with >= 2 options only ---

    @Test
    fun qualitySelectorNeedsTwoOrMoreOptions() {
        val degenerate = model("grok-imagine-image-2.0", params = listOf(qualityAxis(listOf(MediaCapValue("low", "Низкий")))))
        assertNull(degenerate.qualitySelectorAxis())
        val full = model(
            "gpt-image-2-6",
            params = listOf(qualityAxis(listOf(MediaCapValue("low"), MediaCapValue("medium"), MediaCapValue("high")))),
        )
        assertEquals("quality", full.qualitySelectorAxis()?.key)
    }

    @Test
    fun qualitySelectorHiddenWithoutQualityAxis() {
        val noAxis = model("gpt-image-2", params = listOf(MediaParamDef("resolution", "Разрешение", options = listOf(MediaCapValue("1K")))))
        assertNull(noAxis.qualitySelectorAxis())
    }

    @Test
    fun resolutionLikeQualityIsNotASelector() {
        // pixverse-v6: resolution values live in the quality axis — a resolution
        // chip, never the quality dropdown.
        val pixverse = qualityAxis(listOf(MediaCapValue("360p"), MediaCapValue("540p"), MediaCapValue("720p"), MediaCapValue("1080p")))
        assertTrue(pixverse.isResolutionLike())
        assertNull(model("pixverse-v6", kind = MediaKind.Video, params = listOf(pixverse)).qualitySelectorAxis())
        // flux-3: full/draft is a real quality level.
        val flux = qualityAxis(listOf(MediaCapValue("full"), MediaCapValue("draft")))
        assertFalse(flux.isResolutionLike())
        assertEquals("quality", model("flux-3", kind = MediaKind.Video, params = listOf(flux)).qualitySelectorAxis()?.key)
    }

    @Test
    fun resolutionLikeDetectionCoversPAndKValuesCaseInsensitive() {
        assertTrue(qualityAxis(listOf(MediaCapValue("720p"), MediaCapValue("1080P"))).isResolutionLike())
        assertTrue(qualityAxis(listOf(MediaCapValue("2k"), MediaCapValue("4K"))).isResolutionLike())
        assertFalse(qualityAxis(listOf(MediaCapValue("720p"), MediaCapValue("draft"))).isResolutionLike())
        assertFalse(qualityAxis(listOf(MediaCapValue("hd"))).isResolutionLike())
    }

    // --- audio toggle: only `audio` / `generate_audio` axes ---

    @Test
    fun audioToggleAxisOnlyForSoundGenerationAxes() {
        val kling = model("kling-v2.6", kind = MediaKind.Video, params = listOf(MediaParamDef("audio", "Со звуком", type = "bool", defaultValue = "false")))
        assertEquals("audio", kling.audioToggleAxis()?.key)
        val flux = model("flux-3", kind = MediaKind.Video, params = listOf(MediaParamDef("generate_audio", "Синхронный звук", type = "bool", defaultValue = "true")))
        assertEquals("generate_audio", flux.audioToggleAxis()?.key)
        // Other sound axes keep their different semantics — no toggle.
        val keep = model("p-video-2-pro-reference", kind = MediaKind.Video, params = listOf(MediaParamDef("keep_audio", "Сохранить звук", type = "bool")))
        assertNull(keep.audioToggleAxis())
        val input = model("kling-ai-avatar", kind = MediaKind.Video, params = listOf(MediaParamDef("audio_url", "Аудио (URL)", type = "text")))
        assertNull(input.audioToggleAxis())
        val none = model("veo-3.1", kind = MediaKind.Video, params = listOf(MediaParamDef("resolution", "Разрешение", options = listOf(MediaCapValue("720p")))))
        assertNull(none.audioToggleAxis())
    }

    // --- Russian labels for quality levels ---

    @Test
    fun qualityOptionLabelsAreRussianWithCatalogSuffixes() {
        assertEquals("Низкое", qualityOptionLabel(MediaCapValue("low", "Low")))
        assertEquals("Среднее", qualityOptionLabel(MediaCapValue("medium", "Medium")))
        assertEquals("Высокое", qualityOptionLabel(MediaCapValue("high", "High")))
        assertEquals("Минимальное", qualityOptionLabel(MediaCapValue("very_low", "Very Low")))
        assertEquals("Базовое", qualityOptionLabel(MediaCapValue("basic", "Basic")))
        assertEquals("Ультра", qualityOptionLabel(MediaCapValue("ultra", "Ultra")))
        // Catalog suffixes in parentheses survive the translation.
        assertEquals("Базовое (2K)", qualityOptionLabel(MediaCapValue("basic", "Basic (2K)")))
        assertEquals("Ультра (4K, Lite)", qualityOptionLabel(MediaCapValue("ultra", "Ultra (4K, Lite)")))
        // Already-Russian catalog labels stay as they are.
        assertEquals("Полное", qualityOptionLabel(MediaCapValue("full", "Полное")))
        assertEquals("Черновик (быстро и дёшево)", qualityOptionLabel(MediaCapValue("draft", "Черновик (быстро и дёшево)")))
        // Unknown values render as the catalog does.
        assertEquals("Custom Tier", qualityOptionLabel(MediaCapValue("custom", "Custom Tier")))
    }

    // --- opts mapping: quality is always explicit, generation and editing alike ---

    @Test
    fun optsAlwaysCarryDefaultQualityForGeneration() {
        val m = model(
            "gpt-image-2-6",
            params = listOf(
                qualityAxis(
                    listOf(
                        MediaCapValue("low", "Low", priceRub = 1.44),
                        MediaCapValue("medium", "Medium", priceRub = 1.44),
                        MediaCapValue("high", "High", priceRub = 1.44),
                    ),
                    default = "high",
                ),
            ),
        )
        assertEquals(mapOf("quality" to "low"), applyMediaOptDefaults(emptyMap(), m))
        // A saved selection wins over the default.
        assertEquals(mapOf("quality" to "high"), applyMediaOptDefaults(emptyMap(), m, savedQuality = "high"))
        // An explicit chip selection is never overridden.
        assertEquals(mapOf("quality" to "medium"), applyMediaOptDefaults(mapOf("quality" to "medium"), m))
    }

    @Test
    fun staleSavedQualityFallsBackToDefaultSilently() {
        // The catalog changed and the saved value is gone from the axis.
        val m = model(
            "seedream-5",
            params = listOf(qualityAxis(listOf(MediaCapValue("basic", "Basic"), MediaCapValue("high", "High"), MediaCapValue("ultra", "Ultra")))),
        )
        assertEquals(mapOf("quality" to "basic"), applyMediaOptDefaults(emptyMap(), m, savedQuality = "gone"))
        // A valid saved value still applies.
        assertEquals(mapOf("quality" to "ultra"), applyMediaOptDefaults(emptyMap(), m, savedQuality = "ultra"))
    }

    @Test
    fun modelsWithoutSelectorSendNoQuality() {
        val m = model("gpt-image-2", params = listOf(MediaParamDef("resolution", "Разрешение", options = listOf(MediaCapValue("1K"), MediaCapValue("2K")))))
        val opts = mapOf("resolution" to "2K")
        assertEquals(opts, applyMediaOptDefaults(opts, m))
        assertNull(applyMediaOptDefaults(emptyMap(), null).get("quality"))
        // Resolution-like quality (pixverse) is a resolution control — the
        // server default applies, nothing is injected.
        val pixverse = model("pixverse-v6", kind = MediaKind.Video, params = listOf(qualityAxis(listOf(MediaCapValue("360p"), MediaCapValue("720p")))))
        assertEquals(emptyMap(), applyMediaOptDefaults(emptyMap(), pixverse))
    }

    @Test
    fun optsCarryCatalogAudioDefault() {
        val kling = model("kling-v2.6", kind = MediaKind.Video, params = listOf(MediaParamDef("audio", "Со звуком", type = "bool", defaultValue = "false")))
        assertEquals(mapOf("audio" to "false"), applyMediaOptDefaults(emptyMap(), kling))
        val wan = model("wan-3-0-prime", kind = MediaKind.Video, params = listOf(MediaParamDef("audio", "Звук", type = "bool", defaultValue = "true")))
        assertEquals(mapOf("audio" to "true"), applyMediaOptDefaults(emptyMap(), wan))
        // The user toggle wins over the catalog default.
        assertEquals(mapOf("audio" to "false"), applyMediaOptDefaults(mapOf("audio" to "false"), wan))
    }

    // --- toolbar chips: axis -> chip mechanism (spec §19.3) ---

    @Test
    fun videoChipsFollowResolutionQualityDurationAudioOrder() {
        // flux-3: exactly four chips.
        val flux = model(
            "flux-3",
            kind = MediaKind.Video,
            params = listOf(
                MediaParamDef("mode", "Режим", options = listOf(MediaCapValue("text-to-video"))),
                MediaParamDef("duration", "Длительность", options = listOf(MediaCapValue("5"), MediaCapValue("10"))),
                qualityAxis(listOf(MediaCapValue("full", "Полное"), MediaCapValue("draft", "Черновик"))),
                MediaParamDef("resolution", "Разрешение", options = listOf(MediaCapValue("720p"), MediaCapValue("1080p"))),
                MediaParamDef("aspect_ratio", "Соотношение", options = listOf(MediaCapValue("16:9"))),
                MediaParamDef("generate_audio", "Синхронный звук", type = "bool", defaultValue = "true"),
                MediaParamDef("image_url", "Изображение", type = "text"),
            ),
        )
        val chips = mediaToolbarParams(flux)
        assertEquals(
            listOf("resolution" to MediaToolbarChip.Kind.Sheet, "quality" to MediaToolbarChip.Kind.QualityDropdown, "duration" to MediaToolbarChip.Kind.Sheet, "generate_audio" to MediaToolbarChip.Kind.AudioToggle),
            chips.map { it.def.key to it.kind },
        )
    }

    @Test
    fun resolutionLikeQualityBecomesASheetChip() {
        // pixverse-v6: the quality axis renders as the resolution chip.
        val pixverse = model(
            "pixverse-v6",
            kind = MediaKind.Video,
            params = listOf(
                qualityAxis(listOf(MediaCapValue("360p"), MediaCapValue("540p"), MediaCapValue("720p"), MediaCapValue("1080p"))),
                MediaParamDef("duration", "Длительность", options = listOf(MediaCapValue("5"))),
                MediaParamDef("audio", "Генерировать звук", type = "bool", defaultValue = "false"),
                MediaParamDef("seed", "Seed", type = "number"),
            ),
        )
        val chips = mediaToolbarParams(pixverse)
        assertEquals(
            listOf("quality" to MediaToolbarChip.Kind.Sheet, "duration" to MediaToolbarChip.Kind.Sheet, "audio" to MediaToolbarChip.Kind.AudioToggle),
            chips.map { it.def.key to it.kind },
        )
    }

    @Test
    fun imageChipsKeepResolutionAndAddQuality() {
        val gpt = model(
            "gpt-image-2-6",
            params = listOf(
                MediaParamDef("aspect_ratio", "Соотношение", options = listOf(MediaCapValue("1:1"))),
                MediaParamDef("resolution", "Разрешение", options = listOf(MediaCapValue("1K"), MediaCapValue("2K"))),
                qualityAxis(listOf(MediaCapValue("low"), MediaCapValue("medium"), MediaCapValue("high"))),
                MediaParamDef("complexity", "Сложность", options = listOf(MediaCapValue("low"), MediaCapValue("high"))),
                MediaParamDef("tier", "Вариант", options = listOf(MediaCapValue("flare"))),
                MediaParamDef("mode", "Режим", options = listOf(MediaCapValue("text-to-image"))),
            ),
        )
        val chips = mediaToolbarParams(gpt)
        // complexity/tier/mode stay out of the toolbar (stop-list §19.3).
        assertEquals(
            listOf("resolution" to MediaToolbarChip.Kind.Sheet, "quality" to MediaToolbarChip.Kind.QualityDropdown),
            chips.map { it.def.key to it.kind },
        )
    }

    @Test
    fun degenerateQualityAndAxislessModelsGetNoQualityChips() {
        val grok = model("grok-imagine-image-2.0", params = listOf(qualityAxis(listOf(MediaCapValue("low", "Низкий"))), MediaParamDef("resolution", "Разрешение", options = listOf(MediaCapValue("1K")))))
        assertEquals(listOf("resolution"), mediaToolbarParams(grok).map { it.def.key })
        val avatar = model("MiniMax-H3-Context-IR", kind = MediaKind.Video, params = listOf(MediaParamDef("input", "Вход", options = listOf(MediaCapValue("context")))))
        assertEquals(emptyList(), mediaToolbarParams(avatar))
        assertNull(mediaToolbarParams(null).firstOrNull())
    }

    @Test
    fun chipsCapAtFour() {
        val crowded = model(
            "busy",
            kind = MediaKind.Video,
            params = listOf(
                MediaParamDef("resolution", "Разрешение", options = listOf(MediaCapValue("720p"))),
                qualityAxis(listOf(MediaCapValue("full"), MediaCapValue("draft"))),
                MediaParamDef("duration", "Длительность", options = listOf(MediaCapValue("5"))),
                MediaParamDef("audio", "Звук", type = "bool", defaultValue = "false"),
                MediaParamDef("generate_audio", "Генерировать звук", type = "bool", defaultValue = "false"),
            ),
        )
        val chips = mediaToolbarParams(crowded)
        assertTrue(chips.size <= 4)
        assertEquals(listOf("resolution", "quality", "duration", "audio"), chips.map { it.def.key })
    }

    // --- catalog parsing keeps option prices ---

    @Test
    fun catalogParsingKeepsQualityOptionPrices() {
        val catalogJson = """
            {"models":[{"model":"gpt-image-2-6","kind":"image","fromRub":1.44,"priceStatus":"exact",
              "params":[{"key":"quality","label":"Качество","type":"select","default":"high",
                         "options":["low","medium","high"],"optionLabels":["Low","Medium","High"]}],
              "axes":[{"key":"quality","label":"Качество","type":"select","default":"high",
                       "options":[{"value":"low","label":"Low","priceRub":1.44},
                                  {"value":"medium","label":"Medium","priceRub":1.44},
                                  {"value":"high","label":"High","priceRub":1.44}]}]}]}
        """.trimIndent()
        val parsed = PlusVibeMediaProvider().parseCatalog(catalogJson)
        val quality = parsed.single().params.first { it.key == "quality" }
        // The params representation carries the labels, the axes the prices.
        assertEquals(listOf("Low", "Medium", "High"), quality.options.map { it.label })
        assertEquals(listOf(1.44, 1.44, 1.44), quality.options.map { it.priceRub })
        assertEquals("high", quality.defaultValue)
        // The merged axis keeps the catalog default `high`, which the app
        // overrides with the cheapest level (default Low).
        assertEquals("low", defaultQualityOption(quality)?.value)
    }
}
