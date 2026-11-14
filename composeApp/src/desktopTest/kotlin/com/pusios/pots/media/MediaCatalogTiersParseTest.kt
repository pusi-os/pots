package com.pusios.pots.media

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The live catalog of 2026-11-14, a verbatim excerpt of the real response
 * (`docs/design/media-expansion/pots-media-catalog-tiers-live-2026-11-14.json`).
 *
 * The provider extended `tiers[].when`: a condition value may be one option
 * or an array of options («any of these»). The parse must survive both
 * shapes — on this payload the release build (v3.12.0) crashed with
 * «Element … is not JSONPrimitive» and the whole model list stayed empty.
 */
class MediaCatalogTiersParseTest {

    private val provider = PlusVibeMediaProvider()

    private val catalog: List<MediaModel> by lazy { provider.parseCatalog(loadFixture()) }

    private fun loadFixture(): String {
        // Test working dir is the module dir; fall back to the repo root.
        val name = "pots-media-catalog-tiers-live-2026-11-14.json"
        val candidates = listOf(
            File("../docs/design/media-expansion/$name"),
            File("docs/design/media-expansion/$name"),
            File("../../docs/design/media-expansion/$name"),
        )
        val found = candidates.firstOrNull { it.isFile }
        assertNotNull(found, "fixture $name not found; tried: " + candidates.joinToString { it.absolutePath })
        return found.readText()
    }

    private fun model(id: String): MediaModel = catalog.first { it.id == id }

    @Test
    fun liveCatalogParsesDespiteArrayWhenValues() {
        assertEquals(
            setOf(
                "seedream-5",
                "flux-3",
                "z-image-uncensored",
                "gemini-omni-video",
                "gemini-omni-flash-1-1",
                "gpt-image-2",
            ),
            catalog.map { it.id }.toSet(),
        )
        assertTrue(catalog.none { it.tiers.isEmpty() }, "every fixture model carries tiers")
    }

    @Test
    fun arrayWhenBecomesTheSetValue() {
        val tiers = model("seedream-5").tiers
        assertEquals("Lite", tiers.first().label)
        assertEquals(3.72, tiers.first().priceRub)
        assertEquals(
            mapOf("tier" to setOf("lite"), "quality" to setOf("basic", "high", "ultra")),
            tiers.first().whenConditions,
        )
        // A tier may mix a primitive condition with an array one.
        assertEquals(
            mapOf("tier" to setOf("pro"), "quality" to setOf("basic")),
            tiers[1].whenConditions,
        )
    }

    @Test
    fun tierPriceMatchesAnyListedValue() {
        val seedream = model("seedream-5")
        assertEquals(3.72, seedream.tierPrice(mapOf("tier" to "lite", "quality" to "ultra")))
        assertEquals(3.72, seedream.tierPrice(mapOf("tier" to "lite", "quality" to "basic")))
        assertEquals(4.74, seedream.tierPrice(mapOf("tier" to "pro", "quality" to "basic")))
        // No tier covers pro + ultra: the badge falls back to «от fromRub».
        assertNull(seedream.tierPrice(mapOf("tier" to "pro", "quality" to "ultra")))

        // flux-3 mixes the array `mode` with primitive conditions.
        val draft = model("flux-3").tierPrice(
            mapOf("mode" to "image-to-video", "quality" to "draft", "resolution" to "720p"),
        )
        assertEquals(7.54, draft)
    }
}
