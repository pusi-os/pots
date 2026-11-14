package com.pusios.pots.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The `tiers[].when` contract (live since 2026-11-14): a condition value is
 * one option or an array of options («any of these»). Unknown condition
 * shapes degrade to a never-matching set instead of breaking the catalog,
 * and one malformed model never empties the whole list.
 */
class MediaTierWhenParseTest {

    private val provider = PlusVibeMediaProvider()

    private val catalogJson = """
    {
      "models": [
        {
          "model": "multi-when", "kind": "image", "fromRub": 1.0,
          "tiers": [
            {"label": "Лайт", "priceRub": 2.5, "when": {"tier": "lite", "quality": ["basic", "high", "ultra"]}},
            {"label": "Про", "priceRub": 4.0, "when": {"tier": "pro", "quality": "basic"}},
            {"label": "Пустой список", "priceRub": 9.9, "when": {"quality": []}},
            {"label": "Объект", "priceRub": 8.8, "when": {"weird": {"nested": true}}}
          ]
        },
        {
          "model": "broken-note", "kind": "image",
          "note": {"ru": "объект вместо строки"}
        },
        {
          "model": "plain", "kind": "image", "fromRub": 3.0
        }
      ]
    }
    """.trimIndent()

    @Test
    fun arrayWhenValueMatchesAnyListedOption() {
        val model = provider.parseCatalog(catalogJson).first { it.id == "multi-when" }
        assertEquals(
            mapOf("tier" to setOf("lite"), "quality" to setOf("basic", "high", "ultra")),
            model.tiers[0].whenConditions,
        )
        assertEquals(
            mapOf("tier" to setOf("pro"), "quality" to setOf("basic")),
            model.tiers[1].whenConditions,
        )
        assertEquals(2.5, model.tierPrice(mapOf("tier" to "lite", "quality" to "high")))
        assertEquals(4.0, model.tierPrice(mapOf("tier" to "pro", "quality" to "basic")))
        // pro + ultra is covered by no tier.
        assertNull(model.tierPrice(mapOf("tier" to "pro", "quality" to "ultra")))
    }

    @Test
    fun unknownWhenShapesNeverMatchAndNeverThrow() {
        val model = provider.parseCatalog(catalogJson).first { it.id == "multi-when" }
        assertEquals(emptySet(), model.tiers[2].whenConditions["quality"])
        assertEquals(emptySet(), model.tiers[3].whenConditions["weird"])
        // Neither degenerate tier may produce a price.
        assertNull(model.tierPrice(mapOf("quality" to "basic", "weird" to "nested")))
    }

    @Test
    fun oneBrokenModelDoesNotEmptyTheCatalog() {
        val ids = provider.parseCatalog(catalogJson).map { it.id }
        assertEquals(listOf("multi-when", "plain"), ids)
    }
}
