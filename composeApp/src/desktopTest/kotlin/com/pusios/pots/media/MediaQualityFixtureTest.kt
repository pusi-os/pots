package com.pusios.pots.media

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Canonical image-quality numbers verified against the live fixtures
 * (`docs/design/media-expansion/pots-image-quality-price-tables.json` and
 * `pots-image-video-catalog-dump.json`, both fetched 2026-11-13).
 * Cross-checked by @kodi/@pifiya/@linus: selector visibility, the Low
 * default, exact table prices and the missing-row edge (seedream-5 pro+ultra).
 */
class MediaQualityFixtureTest {

    private val provider = PlusVibeMediaProvider()

    private val catalog: List<MediaModel> by lazy { provider.parseCatalog(loadDump()) }

    private val priceTables: Map<String, MediaPriceTable> by lazy {
        val root = PlusVibeMediaProvider.JsonTolerant.parseToJsonElement(loadPriceTables()).jsonObject
        val tables = root["tables"] as JsonObject
        tables.entries.associate { (key, element) -> key to assertNotNull(parsePriceTable(element.toString()), "table for $key") }
    }

    private fun loadFixture(name: String): String {
        // Test working dir is the module dir; fall back to the repo root.
        val candidates = listOf(
            File("../docs/design/media-expansion/$name"),
            File("docs/design/media-expansion/$name"),
            File("../../docs/design/media-expansion/$name"),
        )
        val found = candidates.firstOrNull { it.isFile }
        assertNotNull(found, "fixture $name not found; tried: " + candidates.joinToString { it.absolutePath })
        return found.readText()
    }

    private fun loadDump(): String = loadFixture("pots-image-video-catalog-dump.json")

    private fun loadPriceTables(): String = loadFixture("pots-image-quality-price-tables.json")

    private fun model(id: String): MediaModel = catalog.first { it.id == id }

    // --- selector visibility on the live dump ---

    @Test
    fun dumpCarriesSelectorCoverage() {
        val withSelector = catalog.filter { it.qualitySelectorAxis() != null }.map { it.id }.toSet()
        assertEquals(
            setOf(
                "gpt-image-2-6",
                "gpt-image-1-5",
                "ideogram-4-5",
                "ideogram-4-5-precise-edit",
                "seedream-5",
                "seedream-5-uncensored",
                "seedream-4-5",
                "flux-3",
            ),
            withSelector,
        )
        // Degenerate one-option axis and resolution-in-quality stay hidden.
        assertNull(model("grok-imagine-image-2.0").qualitySelectorAxis())
        assertNull(model("pixverse-v6").qualitySelectorAxis())
        assertNull(model("pixverse-v6-max").qualitySelectorAxis())
        assertNull(model("runway-gen4").qualitySelectorAxis())
        assertNull(model("gpt-image-2").qualitySelectorAxis())
    }

    @Test
    fun dumpCarriesAudioToggleCoverage() {
        val toggles = catalog.mapNotNull { m -> m.audioToggleAxis()?.let { m.id to it } }.toMap()
        assertEquals(
            setOf("kling-v2.6", "kling-v3-omni", "pixverse-v6", "pixverse-v6-max", "wan-3-0-prime", "vidu-q4", "flux-3"),
            toggles.keys,
        )
        // Catalog defaults: wan/vidu ship with sound on, the rest off.
        assertEquals("false", toggles["kling-v2.6"]?.defaultValue)
        assertEquals("false", toggles["pixverse-v6"]?.defaultValue)
        assertEquals("true", toggles["wan-3-0-prime"]?.defaultValue)
        assertEquals("true", toggles["vidu-q4"]?.defaultValue)
        assertEquals("generate_audio", toggles["flux-3"]?.key)
        assertEquals("true", toggles["flux-3"]?.defaultValue)
        // veo-3.1 has sound built in — no toggle axis.
        assertNull(model("veo-3.1").audioToggleAxis())
    }

    @Test
    fun resolutionLikeQualityRendersAsSheetChip() {
        val pixverse = mediaToolbarParams(model("pixverse-v6"))
        assertEquals(
            listOf("quality" to MediaToolbarChip.Kind.Sheet, "duration" to MediaToolbarChip.Kind.Sheet, "audio" to MediaToolbarChip.Kind.AudioToggle),
            pixverse.map { it.def.key to it.kind },
        )
        val runway = mediaToolbarParams(model("runway-gen4"))
        assertEquals(listOf("quality" to MediaToolbarChip.Kind.Sheet, "duration" to MediaToolbarChip.Kind.Sheet), runway.map { it.def.key to it.kind })
    }

    // --- the Low default (owner directive) on live data ---

    @Test
    fun defaultsAreTheCheapestLevels() {
        // gpt-image-2-6: all levels 1.44 — tie goes to the first (low),
        // overriding the catalog default `high`.
        assertEquals("low", defaultQualityOption(model("gpt-image-2-6").qualitySelectorAxis()!!)?.value)
        assertEquals("medium", defaultQualityOption(model("gpt-image-1-5").qualitySelectorAxis()!!)?.value)
        assertEquals("very_low", defaultQualityOption(model("ideogram-4-5").qualitySelectorAxis()!!)?.value)
        // Flat seedream-5: every level 3.72 — the first (basic).
        assertEquals("basic", defaultQualityOption(model("seedream-5").qualitySelectorAxis()!!)?.value)
        // No catalog prices at all — the first option (basic).
        assertEquals("basic", defaultQualityOption(model("seedream-4-5").qualitySelectorAxis()!!)?.value)
        // flux-3 video quality: draft is the cheap one, catalog default full overridden.
        assertEquals("draft", defaultQualityOption(model("flux-3").qualitySelectorAxis()!!)?.value)
    }

    @Test
    fun optsDefaultsApplyToGenerationAndEditing() {
        val gpt = model("gpt-image-2-6")
        assertEquals(mapOf("quality" to "low"), applyMediaOptDefaults(emptyMap(), gpt))
        assertEquals(mapOf("quality" to "high", "resolution" to "2K"), applyMediaOptDefaults(mapOf("resolution" to "2K"), gpt, savedQuality = "high"))
        // Explicit selection is never overridden.
        assertEquals(mapOf("quality" to "medium"), applyMediaOptDefaults(mapOf("quality" to "medium"), gpt))
        // Video: quality + the catalog audio default ride along.
        val flux = model("flux-3")
        assertEquals(mapOf("quality" to "draft", "generate_audio" to "true"), applyMediaOptDefaults(emptyMap(), flux))
        val kling = model("kling-v2.6")
        assertEquals(mapOf("audio" to "false"), applyMediaOptDefaults(emptyMap(), kling))
    }

    // --- price tables: parsing, basis, exact prices ---

    @Test
    fun fixtureCarriesEightParseableTables() {
        assertEquals(8, priceTables.size)
        val quoteModels = priceTables.filterValues { it.isQuoteBasis }.keys
        // seedream-4-5 is supplier_credits — everything else quotes before submit.
        assertEquals(
            setOf("gpt-image-2-6", "gpt-image-1-5", "ideogram-4-5", "ideogram-4-5-precise-edit", "seedream-5", "seedream-5-uncensored", "grok-imagine-image-2.0"),
            quoteModels,
        )
        assertFalse(priceTables.getValue("seedream-4-5").isQuoteBasis)
        assertEquals(listOf("resolution", "quality", "mode"), priceTables.getValue("gpt-image-2-6").priceAffecting)
    }

    @Test
    fun gptImage26ExactPricesFollowResolution() {
        val table = priceTables.getValue("gpt-image-2-6")
        val axis = model("gpt-image-2-6").qualitySelectorAxis()!!
        val at2k = qualityPriceInfo(table, axis, mapOf("resolution" to "2K"), mode = "text-to-image")
        assertEquals(mapOf("low" to 6.77, "medium" to 12.19, "high" to 17.33), at2k.optionPrices)
        assertEquals(QUALITY_SPREAD_NOTE, at2k.footerNote)
        // At 1K all levels are equal — prices shown, no note.
        val at1k = qualityPriceInfo(table, axis, mapOf("resolution" to "1K"), mode = "text-to-image")
        assertEquals(mapOf("low" to 1.44, "medium" to 1.44, "high" to 1.44), at1k.optionPrices)
        assertNull(at1k.footerNote)
        // Editing mode matches generation (same rows, other mode).
        val editing = qualityPriceInfo(table, axis, mapOf("resolution" to "2K"), mode = "image-to-image")
        assertEquals(at2k.optionPrices, editing.optionPrices)
    }

    @Test
    fun seedream5FlatLiteAndMissingProUltraRow() {
        val table = priceTables.getValue("seedream-5")
        val axis = model("seedream-5").qualitySelectorAxis()!!
        val lite = qualityPriceInfo(table, axis, mapOf("tier" to "lite"), mode = "text-to-image")
        assertEquals(mapOf("basic" to 3.72, "high" to 3.72, "ultra" to 3.72), lite.optionPrices)
        assertNull(lite.footerNote)
        // tier=pro + quality=ultra: valid by axes, no table row — no price,
        // no note, no crash (@linus test case).
        val pro = qualityPriceInfo(table, axis, mapOf("tier" to "pro"), mode = "text-to-image")
        assertEquals(mapOf("basic" to 4.74, "high" to 9.48), pro.optionPrices)
        assertNull(pro.footerNote)
    }

    @Test
    fun qualityWithoutPriceEffectShowsNothing() {
        // seedream-4-5: priceAffecting is empty — quality does not move the price.
        val table = priceTables.getValue("seedream-4-5")
        val axis = model("seedream-4-5").qualitySelectorAxis()!!
        val info = qualityPriceInfo(table, axis, emptyMap(), mode = "text-to-image")
        assertTrue(info.optionPrices.isEmpty())
        assertNull(info.footerNote)
    }

    @Test
    fun ideogramEditingHasItsOwnHighPrice() {
        val table = priceTables.getValue("ideogram-4-5-precise-edit")
        val axis = model("ideogram-4-5-precise-edit").qualitySelectorAxis()!!
        val info = qualityPriceInfo(table, axis, emptyMap(), mode = "image-to-image")
        assertEquals(mapOf("very_low" to 1.09, "low" to 4.07, "medium" to 8.13, "high" to 29.79), info.optionPrices)
        assertEquals(QUALITY_SPREAD_NOTE, info.footerNote)
    }

    @Test
    fun everySelectorModelResolvesPricesOrNote() {
        // Smoke: whichever selector model is open, the dropdown ends with
        // either exact lines or the conservative note — never blank. The one
        // honest exception: a live table proving quality does not affect the
        // price (seedream-4-5) shows nothing at all.
        catalog.filter { it.qualitySelectorAxis() != null }.forEach { m ->
            val table = priceTables[m.id]
            if (table != null && "quality" !in table.priceAffecting) return@forEach
            val axis = m.qualitySelectorAxis()!!
            val info = qualityPriceInfo(table, axis, emptyMap(), mode = "text-to-image")
            assertTrue(info.optionPrices.isNotEmpty() || info.footerNote != null, "blank price block for ${m.id}")
        }
    }
}
