package com.pusios.pots.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Quality price display rules (spec §19.1). Exact prices come only from
 * price-table rows for the current (resolution, quality, mode) combination;
 * without them the menu shows the conservative «≈ ×2 / ≈ ×3» note — never a
 * guessed number. Live fixtures: [MediaQualityFixtureTest] (desktopTest).
 */
class MediaPriceTablesTest {

    private fun qualityAxis(vararg options: Pair<String, Double?>) = MediaParamDef(
        key = "quality",
        label = "Качество",
        options = options.map { MediaCapValue(it.first, it.first, it.second) },
    )

    private fun table(
        priceAffecting: List<String>,
        defaults: Map<String, String> = emptyMap(),
        rows: List<MediaPriceTableRow>,
    ) = MediaPriceTable(model = "test", currency = "RUB", priceAffecting = priceAffecting, defaults = defaults, rows = rows)

    private fun row(price: Double?, vararg opts: Pair<String, String>, status: String = "exact", chargeBasis: String = "quote") = MediaPriceTableRow(opts = opts.toMap(), price = price, priceStatus = status, chargeBasis = chargeBasis)

    // --- parsing ---

    @Test
    fun parsesLivePriceTableShape() {
        val json = """
            {"ok":true,"model":"gpt-image-2-6","currency":"RUB",
             "priceAffecting":["resolution","quality","mode"],
             "defaults":{"resolution":"1K","quality":"high","mode":"text-to-image"},
             "rows":[
               {"opts":{"resolution":"1K","quality":"low","mode":"text-to-image"},"price":1.44,"priceStatus":"exact","chargeBasis":"quote"},
               {"opts":{"resolution":"2K","quality":"low","mode":"text-to-image"},"price":6.77,"priceStatus":"exact","chargeBasis":"quote"},
               {"opts":{"resolution":"2K","quality":"high","mode":"image-to-image"},"price":17.33,"priceStatus":"exact","chargeBasis":"quote"}]}
        """.trimIndent()
        val parsed = assertNotNull(parsePriceTable(json))
        assertEquals("gpt-image-2-6", parsed.model)
        assertEquals(listOf("resolution", "quality", "mode"), parsed.priceAffecting)
        assertEquals("1K", parsed.defaults["resolution"])
        assertEquals(3, parsed.rows.size)
        assertEquals(6.77, parsed.rows[1].price)
        assertTrue(parsed.isQuoteBasis)
    }

    @Test
    fun parseToleratesUnknownFieldsAndRejectsNotOk() {
        val unknownFields = """{"ok":true,"model":"m","somethingNew":[1,2],"rows":[]}"""
        assertNotNull(parsePriceTable(unknownFields))
        assertNull(parsePriceTable("""{"ok":false,"model":"m","rows":[]}"""))
        assertNull(parsePriceTable("not json at all"))
    }

    // --- exact prices from the table ---

    private val gptImageTable = table(
        priceAffecting = listOf("resolution", "quality", "mode"),
        defaults = mapOf("resolution" to "1K", "quality" to "high", "mode" to "text-to-image"),
        rows = buildList {
            listOf("1K", "2K", "4K").forEach { res ->
                listOf("low", "medium", "high").forEach { q ->
                    val price = if (res == "1K") {
                        1.44
                    } else {
                        when (q) {
                            "low" -> 6.77
                            "medium" -> 12.19
                            else -> 17.33
                        }
                    }
                    listOf("text-to-image", "image-to-image").forEach { mode ->
                        add(row(price, "resolution" to res, "quality" to q, "mode" to mode))
                    }
                }
            }
        },
    )

    private val gptAxis = qualityAxis("low" to 1.44, "medium" to 1.44, "high" to 1.44)

    @Test
    fun exactPricesFollowSelectedResolution() {
        val info = qualityPriceInfo(gptImageTable, gptAxis, mapOf("resolution" to "2K"), mode = "text-to-image")
        assertEquals(mapOf("low" to 6.77, "medium" to 12.19, "high" to 17.33), info.optionPrices)
        // A confirmed spread carries the conservative note.
        assertEquals(QUALITY_SPREAD_NOTE, info.footerNote)
    }

    @Test
    fun equalPricesNeedNoNote() {
        // At 1K all levels cost the same — the prices speak for themselves.
        val info = qualityPriceInfo(gptImageTable, gptAxis, mapOf("resolution" to "1K"), mode = "text-to-image")
        assertEquals(mapOf("low" to 1.44, "medium" to 1.44, "high" to 1.44), info.optionPrices)
        assertNull(info.footerNote)
    }

    @Test
    fun editingModePricesMatchGeneration() {
        val generation = qualityPriceInfo(gptImageTable, gptAxis, mapOf("resolution" to "2K"), mode = "text-to-image")
        val editing = qualityPriceInfo(gptImageTable, gptAxis, mapOf("resolution" to "2K"), mode = "image-to-image")
        assertEquals(generation.optionPrices, editing.optionPrices)
    }

    @Test
    fun missingResolutionFallsBackToTableDefault() {
        // No resolution chip selection yet — the table default (1K) applies.
        val info = qualityPriceInfo(gptImageTable, gptAxis, emptyMap(), mode = "text-to-image")
        assertEquals(mapOf("low" to 1.44, "medium" to 1.44, "high" to 1.44), info.optionPrices)
    }

    // --- missing rows: no note, no crash, no guessing (@linus test case) ---

    private val seedream5Table = table(
        priceAffecting = listOf("tier", "quality"),
        defaults = mapOf("tier" to "lite", "quality" to "basic"),
        rows = listOf(
            row(3.72, "tier" to "lite", "quality" to "basic"),
            row(3.72, "tier" to "lite", "quality" to "high"),
            row(3.72, "tier" to "lite", "quality" to "ultra"),
            row(4.74, "tier" to "pro", "quality" to "basic"),
            row(9.48, "tier" to "pro", "quality" to "high"),
            // tier=pro + quality=ultra is valid per the catalog axes but has no row.
        ),
    )

    private val seedream5Axis = qualityAxis("basic" to 3.72, "high" to 3.72, "ultra" to 3.72)

    @Test
    fun flatTierShowsEqualPricesWithoutNote() {
        val info = qualityPriceInfo(seedream5Table, seedream5Axis, mapOf("tier" to "lite"), mode = "text-to-image")
        assertEquals(mapOf("basic" to 3.72, "high" to 3.72, "ultra" to 3.72), info.optionPrices)
        assertNull(info.footerNote)
    }

    @Test
    fun combinationWithoutTableRowGetsNoNoteAndNoGuess() {
        // tier=pro: basic and high have rows, ultra does not — ultra shows no
        // price, the spread note is withheld, nothing crashes or guesses.
        val info = qualityPriceInfo(seedream5Table, seedream5Axis, mapOf("tier" to "pro"), mode = "text-to-image")
        assertEquals(mapOf("basic" to 4.74, "high" to 9.48), info.optionPrices)
        assertNull(info.footerNote)
    }

    @Test
    fun unresolvableCombinationShowsNothing() {
        val info = qualityPriceInfo(seedream5Table, seedream5Axis, mapOf("tier" to "unknown"), mode = "text-to-image")
        assertTrue(info.optionPrices.isEmpty())
        assertNull(info.footerNote)
    }

    @Test
    fun tableWithoutQualityInPriceAffectingShowsNothing() {
        // seedream-4-5: quality does not affect the price — no numbers, no note.
        val flat = table(priceAffecting = emptyList(), rows = listOf(row(4.09, status = "estimate")))
        val axis = qualityAxis("basic" to null, "high" to null)
        val info = qualityPriceInfo(flat, axis, emptyMap(), mode = "text-to-image")
        assertTrue(info.optionPrices.isEmpty())
        assertNull(info.footerNote)
    }

    @Test
    fun estimateRowsAreNotExactPrices() {
        val estimateTable = table(
            priceAffecting = listOf("quality"),
            rows = listOf(row(4.07, "quality" to "basic", status = "estimate"), row(8.13, "quality" to "high", status = "estimate")),
        )
        val axis = qualityAxis("basic" to null, "high" to null)
        val info = qualityPriceInfo(estimateTable, axis, emptyMap())
        assertTrue(info.optionPrices.isEmpty())
        // The table exists, so nothing is guessed — no note either.
        assertNull(info.footerNote)
    }

    // --- no table: flat catalog prices or the note ---

    @Test
    fun withoutTableFlatCatalogPricesGetFlatNote() {
        // The price table is unavailable; every option carries the same exact
        // catalog price — show it with the «не влияет на цену» note.
        val axis = qualityAxis("basic" to 3.72, "high" to 3.72, "ultra" to 3.72)
        val info = qualityPriceInfo(null, axis, emptyMap())
        assertEquals(mapOf("basic" to 3.72, "high" to 3.72, "ultra" to 3.72), info.optionPrices)
        assertEquals(QUALITY_FLAT_NOTE, info.footerNote)
    }

    @Test
    fun withoutTableSpreadGetsConservativeNote() {
        // flux-3 (video): catalog prices differ, no table — the note only.
        val axis = qualityAxis("full" to 21.37, "draft" to 7.54)
        val info = qualityPriceInfo(null, axis, emptyMap())
        assertTrue(info.optionPrices.isEmpty())
        assertEquals(QUALITY_SPREAD_NOTE, info.footerNote)
    }

    @Test
    fun withoutTableAndWithoutPricesOnlyTheNote() {
        val axis = qualityAxis("basic" to null, "high" to null)
        val info = qualityPriceInfo(null, axis, emptyMap())
        assertTrue(info.optionPrices.isEmpty())
        assertEquals(QUALITY_SPREAD_NOTE, info.footerNote)
    }

    @Test
    fun quoteBasisFollowsRows() {
        assertTrue(gptImageTable.isQuoteBasis)
        assertFalse(table(priceAffecting = emptyList(), rows = listOf(row(1.0, chargeBasis = "supplier_credits"))).isQuoteBasis)
    }
}
