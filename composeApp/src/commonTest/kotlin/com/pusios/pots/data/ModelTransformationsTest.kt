package com.pusios.pots.data

import com.pusios.pots.network.dtos.anthropic.AnthropicModelsResponseDto
import com.pusios.pots.network.dtos.aitunnel.AiTunnelCatalogEntryDto
import com.pusios.pots.network.dtos.gemini.GeminiModelsResponseDto
import com.pusios.pots.network.dtos.llmrouter.LlmRouterPricingResponseDto
import com.pusios.pots.network.dtos.openaicompatible.OpenAICompatibleModelResponseDto
import com.pusios.pots.network.dtos.plusvibe.PlusVibeCatalogEntryDto
import com.pusios.pots.ui.settings.SettingsModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelTransformationsTest {

    // --- Anthropic ---

    // --- OpenAI-Compatible ---

    @Test
    fun `mapOpenAICompatibleModels attaches LLM Router per-million prices by model id`() {
        val models = listOf(
            OpenAICompatibleModelResponseDto.Model(id = "gpt-6-luna"),
            OpenAICompatibleModelResponseDto.Model(id = "no-price-model"),
        )
        val pricing = listOf(
            LlmRouterPricingResponseDto.Entry(
                id = "gpt-6-luna",
                modality = "text",
                prices = listOf(
                    LlmRouterPricingResponseDto.Price(op = "input", unit = "1m_tokens", usd = 0.04),
                    LlmRouterPricingResponseDto.Price(op = "output", unit = "1m_tokens", usd = 0.2),
                    LlmRouterPricingResponseDto.Price(op = "cache_read", unit = "1m_tokens", usd = 0.01),
                ),
            ),
        )
        val result = mapOpenAICompatibleModels(models, Service.LlmRouter, selectedModelId = "", pricing = pricing)
        val priced = result.first { it.id == "gpt-6-luna" }
        assertEquals(0.04, priced.pricePerMillion)
        assertEquals(0.2, priced.outputPricePerMillion)
        assertEquals(0.01, priced.cachePricePerMillion)
        val unpriced = result.first { it.id == "no-price-model" }
        assertNull(unpriced.pricePerMillion)
        assertNull(unpriced.outputPricePerMillion)
        assertNull(unpriced.cachePricePerMillion)
    }

    @Test
    fun `mapOpenAICompatibleModels non-strict active filtering keeps true and null`() {
        val models = listOf(
            OpenAICompatibleModelResponseDto.Model(id = "m1", isActive = true),
            OpenAICompatibleModelResponseDto.Model(id = "m2", isActive = null),
            OpenAICompatibleModelResponseDto.Model(id = "m3", isActive = false),
        )
        val result = mapOpenAICompatibleModels(models, Service.Gptunnel, selectedModelId = "")
        assertEquals(2, result.size)
        assertTrue(result.any { it.id == "m1" })
        assertTrue(result.any { it.id == "m2" })
    }

    @Test
    fun `mapOpenAICompatibleModels deduplication`() {
        val models = listOf(
            OpenAICompatibleModelResponseDto.Model(id = "model-a"),
            OpenAICompatibleModelResponseDto.Model(id = "model-a"),
            OpenAICompatibleModelResponseDto.Model(id = "model-b"),
        )
        val result = mapOpenAICompatibleModels(models, Service.Gptunnel, selectedModelId = "")
        assertEquals(2, result.size)
    }

    @Test
    fun `mapOpenAICompatibleModels sortModelsById sorts alphabetically`() {
        val models = listOf(
            OpenAICompatibleModelResponseDto.Model(id = "zebra-model"),
            OpenAICompatibleModelResponseDto.Model(id = "alpha-model"),
            OpenAICompatibleModelResponseDto.Model(id = "mid-model"),
        )
        val result = mapOpenAICompatibleModels(models, Service.Gptunnel, selectedModelId = "")
        assertEquals("alpha-model", result[0].id)
        assertEquals("mid-model", result[1].id)
        assertEquals("zebra-model", result[2].id)
    }

    @Test
    fun `mapOpenAICompatibleModels sorts by context_window descending when not sortModelsById`() {
        val models = listOf(
            OpenAICompatibleModelResponseDto.Model(id = "small", context_window = 4096),
            OpenAICompatibleModelResponseDto.Model(id = "large", context_window = 131072),
            OpenAICompatibleModelResponseDto.Model(id = "medium", context_window = 32768),
        )
        val result = mapOpenAICompatibleModels(models, Service.Gptunnel, selectedModelId = "")
        assertEquals("large", result[0].id)
        assertEquals("medium", result[1].id)
        assertEquals("small", result[2].id)
    }

    @Test
    fun `mapOpenAICompatibleModels propagates context_window to SettingsModel`() {
        val models = listOf(
            OpenAICompatibleModelResponseDto.Model(id = "m1", context_window = 131_072),
        )
        val result = mapOpenAICompatibleModels(models, Service.Gptunnel, selectedModelId = "")
        assertEquals(131_072L, result[0].contextWindow)
    }

    @Test
    fun `mapOpenAICompatibleModels propagates tools and vision from the wire payload`() {
        val models = listOf(
            OpenAICompatibleModelResponseDto.Model(
                id = "vision-with-tools",
                supported_parameters = listOf("temperature", "tools"),
                architecture = OpenAICompatibleModelResponseDto.Model.Architecture(
                    input_modalities = listOf("text", "image"),
                ),
            ),
            OpenAICompatibleModelResponseDto.Model(
                id = "polza-style-top-provider",
                top_provider = OpenAICompatibleModelResponseDto.Model.TopProvider(
                    supported_parameters = listOf("tool_choice"),
                ),
            ),
            OpenAICompatibleModelResponseDto.Model(id = "plain-text"),
        )
        val result = mapOpenAICompatibleModels(models, Service.Polza, selectedModelId = "")

        val vision = result.first { it.id == "vision-with-tools" }
        assertTrue(vision.supportsTools)
        assertTrue(vision.supportsVision)

        val topProvider = result.first { it.id == "polza-style-top-provider" }
        assertTrue(topProvider.supportsTools)
        assertFalse(topProvider.supportsVision)

        val plain = result.first { it.id == "plain-text" }
        assertFalse(plain.supportsTools)
        assertFalse(plain.supportsVision)
    }

    @Test
    fun `mapOpenAICompatibleModels catalog wins over OpenRouter context_length and name`() {
        val models = listOf(
            OpenAICompatibleModelResponseDto.Model(
                id = "anthropic/claude-3.5-sonnet",
                name = "Anthropic: Claude 3.5 Sonnet",
                context_length = 200_000,
            ),
        )
        val result = mapOpenAICompatibleModels(models, Service.Polza, selectedModelId = "")
        // Catalog has "Claude 3.5 Sonnet" and 200_000 — takes priority over API
        assertEquals("Claude 3.5 Sonnet", result[0].displayName)
        assertEquals(200_000L, result[0].contextWindow)
    }

    @Test
    fun `mapOpenAICompatibleModels keeps Requesty vendor-prefixed ids and context_window`() {
        val models = listOf(
            OpenAICompatibleModelResponseDto.Model(id = "openai/gpt-4o-mini", context_window = 128_000),
            OpenAICompatibleModelResponseDto.Model(id = "openai/text-embedding-3-small", context_window = 8_191),
        )
        val result = mapOpenAICompatibleModels(models, Service.Gptunnel, selectedModelId = "")
        assertEquals(listOf("openai/gpt-4o-mini"), result.map { it.id })
        assertEquals(128_000L, result[0].contextWindow)
        assertFalse(result[0].isFreeTier)
    }

    @Test
    fun `mapOpenAICompatibleModels propagates created epoch as ISO releaseDate`() {
        val models = listOf(
            // 1700000000 = 2023-11-14T22:13:20Z
            OpenAICompatibleModelResponseDto.Model(id = "m1", created = 1_700_000_000),
        )
        val result = mapOpenAICompatibleModels(models, Service.Gptunnel, selectedModelId = "")
        assertEquals("2023-11-14", result[0].releaseDate)
    }

    @Test
    fun `mapOpenAICompatibleModels catalog fills missing context_window for known models`() {
        val models = listOf(
            OpenAICompatibleModelResponseDto.Model(id = "gpt-4o", context_window = null),
        )
        val result = mapOpenAICompatibleModels(models, Service.Gptunnel, selectedModelId = "")
        assertEquals(128_000L, result[0].contextWindow)
    }

    @Test
    fun `mapOpenAICompatibleModels catalog context_window wins over API`() {
        val models = listOf(
            OpenAICompatibleModelResponseDto.Model(id = "gpt-4o", context_window = 999_999),
        )
        val result = mapOpenAICompatibleModels(models, Service.Gptunnel, selectedModelId = "")
        // Catalog has 128_000 for gpt-4o — takes priority
        assertEquals(128_000L, result[0].contextWindow)
    }

    @Test
    fun `mapOpenAICompatibleModels marks selected model`() {
        val models = listOf(
            OpenAICompatibleModelResponseDto.Model(id = "m1"),
            OpenAICompatibleModelResponseDto.Model(id = "m2"),
        )
        val result = mapOpenAICompatibleModels(models, Service.Gptunnel, selectedModelId = "m2")
        assertFalse(result.first { it.id == "m1" }.isSelected)
        assertTrue(result.first { it.id == "m2" }.isSelected)
    }

    @Test
    fun `mapOpenAICompatibleModels empty list`() {
        val result = mapOpenAICompatibleModels(emptyList(), Service.Gptunnel, selectedModelId = "")
        assertTrue(result.isEmpty())
    }

    // --- output price + public catalog enrichment ---

    @Test
    fun `outputPricePerMillion reads polza completion_per_million`() {
        val model = OpenAICompatibleModelResponseDto.Model(
            id = "z-ai/glm-5.3-flash",
            top_provider = OpenAICompatibleModelResponseDto.Model.TopProvider(
                pricing = OpenAICompatibleModelResponseDto.Model.Pricing(
                    prompt_per_million = "4.68",
                    completion_per_million = "9.19",
                ),
            ),
        )
        assertEquals(9.19, model.outputPricePerMillion())
        assertEquals(4.68, model.pricePerMillion())
    }

    @Test
    fun `outputPricePerMillion scales openrouter per-token completion price`() {
        val model = OpenAICompatibleModelResponseDto.Model(
            id = "vendor/model",
            pricing = OpenAICompatibleModelResponseDto.Model.Pricing(
                prompt = "0.000002",
                completion = "0.000008",
            ),
        )
        assertEquals(8.0, model.outputPricePerMillion())
        assertEquals(2.0, model.pricePerMillion())
    }

    @Test
    fun `outputPricePerMillion is null without completion fields`() {
        val model = OpenAICompatibleModelResponseDto.Model(
            id = "vendor/model",
            pricing = OpenAICompatibleModelResponseDto.Model.Pricing(prompt = "0.000002"),
        )
        assertNull(model.outputPricePerMillion())
    }

    @Test
    fun `mapOpenAICompatibleModels merges plusvibe catalog prices caps and context`() {
        val models = listOf(OpenAICompatibleModelResponseDto.Model(id = "catalog-merge-model"))
        val catalog = mapOf(
            "catalog-merge-model" to PlusVibeCatalogEntryDto(
                apiModel = "catalog-merge-model",
                variants = listOf(
                    PlusVibeCatalogEntryDto.Variant(inputRub = 9.51, outputRub = 31.7, isDefault = false),
                    PlusVibeCatalogEntryDto.Variant(
                        inputRub = 2.0,
                        outputRub = 6.67,
                        cachedInputRub = 0.2,
                        maxContext = 1_048_000,
                        vision = true,
                        isDefault = true,
                    ),
                ),
                caps = PlusVibeCatalogEntryDto.Caps(toolCalling = true, vision = true, contextWindow = 1_048_000),
            ).toEnrichment(),
        )
        val result = mapOpenAICompatibleModels(models, Service.PlusVibe, selectedModelId = "", catalog = catalog)
        val model = result.single()
        assertEquals(2.0, model.pricePerMillion)
        assertEquals(6.67, model.outputPricePerMillion)
        assertEquals(0.2, model.cachePricePerMillion)
        assertEquals(1_048_000L, model.contextWindow)
        assertTrue(model.supportsTools)
        assertTrue(model.supportsVision)
    }

    @Test
    fun `mapOpenAICompatibleModels merges aitunnel catalog prices and vision`() {
        val models = listOf(OpenAICompatibleModelResponseDto.Model(id = "catalog-merge-model"))
        val catalog = mapOf(
            "catalog-merge-model" to AiTunnelCatalogEntryDto(
                prompt_cost = 7.98,
                completion_cost = 15.68,
                context_size = 1_048_576,
                modalities = AiTunnelCatalogEntryDto.Modalities(input = listOf("text", "image", "video")),
            ).toEnrichment(),
        )
        val result = mapOpenAICompatibleModels(models, Service.AiTunnel, selectedModelId = "", catalog = catalog)
        val model = result.single()
        assertEquals(7.98, model.pricePerMillion)
        assertEquals(15.68, model.outputPricePerMillion)
        assertEquals(1_048_576L, model.contextWindow)
        assertTrue(model.supportsVision)
        // AITUNNEL's catalog has no function-calling flag — the tile stays contour.
        assertFalse(model.supportsTools)
    }

    @Test
    fun `mapOpenAICompatibleModels wire pricing wins over catalog enrichment`() {
        val models = listOf(
            OpenAICompatibleModelResponseDto.Model(
                id = "catalog-merge-model",
                top_provider = OpenAICompatibleModelResponseDto.Model.TopProvider(
                    pricing = OpenAICompatibleModelResponseDto.Model.Pricing(
                        prompt_per_million = "4.68",
                        completion_per_million = "9.19",
                    ),
                    supported_parameters = listOf("tools"),
                ),
            ),
        )
        val catalog = mapOf(
            "catalog-merge-model" to ApiCatalogEnrichment(
                inputPricePerMillion = 2.0,
                outputPricePerMillion = 6.67,
                supportsVision = true,
            ),
        )
        val result = mapOpenAICompatibleModels(models, Service.Polza, selectedModelId = "", catalog = catalog)
        val model = result.single()
        assertEquals(4.68, model.pricePerMillion)
        assertEquals(9.19, model.outputPricePerMillion)
        assertTrue(model.supportsTools)
        assertTrue(model.supportsVision)
    }

    @Test
    fun `mapOpenAICompatibleModels without catalog entry keeps contour tiles`() {
        val models = listOf(
            OpenAICompatibleModelResponseDto.Model(id = "unlisted-model"),
            OpenAICompatibleModelResponseDto.Model(id = "catalog-merge-model"),
        )
        val catalog = mapOf(
            "catalog-merge-model" to ApiCatalogEnrichment(inputPricePerMillion = 2.0),
        )
        val result = mapOpenAICompatibleModels(models, Service.PlusVibe, selectedModelId = "", catalog = catalog)
        val unlisted = result.first { it.id == "unlisted-model" }
        assertNull(unlisted.pricePerMillion)
        assertNull(unlisted.outputPricePerMillion)
        assertFalse(unlisted.supportsTools)
        val enriched = result.first { it.id == "catalog-merge-model" }
        assertEquals(2.0, enriched.pricePerMillion)
    }

    @Test
    fun `plusvibe toEnrichment picks the default variant`() {
        val entry = PlusVibeCatalogEntryDto(
            apiModel = "z-ai/glm-5.3-flash",
            variants = listOf(
                PlusVibeCatalogEntryDto.Variant(inputRub = 9.51, outputRub = 31.7, isDefault = false),
                PlusVibeCatalogEntryDto.Variant(
                    inputRub = 2.0,
                    outputRub = 6.67,
                    cachedInputRub = 0.2,
                    maxContext = 1_048_000,
                    vision = true,
                    isDefault = true,
                ),
            ),
            caps = PlusVibeCatalogEntryDto.Caps(toolCalling = true, vision = true, contextWindow = 1_048_000),
        )
        val enrichment = entry.toEnrichment()
        assertEquals(2.0, enrichment.inputPricePerMillion)
        assertEquals(6.67, enrichment.outputPricePerMillion)
        assertEquals(0.2, enrichment.cachePricePerMillion)
        assertEquals(1_048_000L, enrichment.contextWindow)
        assertTrue(enrichment.supportsTools == true)
        assertTrue(enrichment.supportsVision == true)
    }

    @Test
    fun `plusvibe toEnrichment falls back to caps-free variant fields`() {
        val entry = PlusVibeCatalogEntryDto(
            apiModel = "vendor/model",
            variants = listOf(
                PlusVibeCatalogEntryDto.Variant(inputRub = 1.0, outputRub = 2.0, vision = false, maxContext = 32_768),
            ),
        )
        val enrichment = entry.toEnrichment()
        assertEquals(1.0, enrichment.inputPricePerMillion)
        assertEquals(2.0, enrichment.outputPricePerMillion)
        assertEquals(32_768L, enrichment.contextWindow)
        assertNull(enrichment.supportsTools)
        assertTrue(enrichment.supportsVision == false)
    }

    @Test
    fun `aitunnel toEnrichment maps image modalities to vision and leaves tools unknown`() {
        val entry = AiTunnelCatalogEntryDto(
            prompt_cost = 7.98,
            completion_cost = 15.68,
            context_size = 1_048_576,
            modalities = AiTunnelCatalogEntryDto.Modalities(input = listOf("text", "image", "video")),
        )
        val enrichment = entry.toEnrichment()
        assertEquals(7.98, enrichment.inputPricePerMillion)
        assertEquals(15.68, enrichment.outputPricePerMillion)
        assertEquals(1_048_576L, enrichment.contextWindow)
        assertTrue(enrichment.supportsVision == true)
        assertNull(enrichment.supportsTools)
    }

    @Test
    fun `aitunnel toEnrichment keeps vision off for text-only models`() {
        val entry = AiTunnelCatalogEntryDto(
            prompt_cost = 1.0,
            completion_cost = 2.0,
            modalities = AiTunnelCatalogEntryDto.Modalities(input = listOf("text")),
        )
        val enrichment = entry.toEnrichment()
        assertTrue(enrichment.supportsVision == false)
    }

    // --- ensureSelectedModelPresent ---

    @Test
    fun `ensureSelectedModelPresent prepends custom id missing from list`() {
        val models = listOf(
            SettingsModel(id = "listed-a", subtitle = "", isSelected = false),
            SettingsModel(id = "listed-b", subtitle = "", isSelected = true),
        )
        val result = ensureSelectedModelPresent(models, selectedModelId = "glm-4.7-flash")
        assertEquals(3, result.size)
        assertEquals("glm-4.7-flash", result[0].id)
        assertTrue(result[0].isSelected)
        assertTrue(result[0].isManualEntry)
        assertTrue(result.drop(1).none { it.isSelected })
        assertTrue(result.drop(1).none { it.isManualEntry })
    }

    @Test
    fun `ensureSelectedModelPresent replaces prior manual draft instead of accumulating`() {
        val models = listOf(
            SettingsModel(id = "a", subtitle = "", isSelected = false, isManualEntry = true),
            SettingsModel(id = "as", subtitle = "", isSelected = true, isManualEntry = true),
            SettingsModel(id = "listed", subtitle = "", isSelected = false),
        )
        val result = ensureSelectedModelPresent(models, selectedModelId = "asd")
        assertEquals(2, result.size)
        assertEquals("asd", result[0].id)
        assertTrue(result[0].isSelected)
        assertTrue(result[0].isManualEntry)
        assertEquals("listed", result[1].id)
        assertFalse(result[1].isManualEntry)
        assertFalse(result[1].isSelected)
    }

    @Test
    fun `ensureSelectedModelPresent marks existing id selected without duplicating`() {
        val models = listOf(
            SettingsModel(id = "m1", subtitle = "", isSelected = true),
            SettingsModel(id = "m2", subtitle = "", isSelected = false),
        )
        val result = ensureSelectedModelPresent(models, selectedModelId = "m2")
        assertEquals(2, result.size)
        assertFalse(result.first { it.id == "m1" }.isSelected)
        assertTrue(result.first { it.id == "m2" }.isSelected)
        assertTrue(result.none { it.isManualEntry })
    }

    @Test
    fun `ensureSelectedModelPresent drops manual entry when provider list contains the id`() {
        val models = listOf(
            SettingsModel(id = "m1", subtitle = "", isSelected = true, isManualEntry = true),
            SettingsModel(id = "m1", subtitle = "", isSelected = false),
        )
        // After filter, provider has m1 — should not keep a second manual row.
        val result = ensureSelectedModelPresent(
            models.filter { !it.isManualEntry } +
                listOf(SettingsModel(id = "m1", subtitle = "", isManualEntry = true)),
            selectedModelId = "m1",
        )
        assertEquals(1, result.count { it.id == "m1" })
        assertTrue(result.first { it.id == "m1" }.isSelected)
        assertFalse(result.first { it.id == "m1" }.isManualEntry)
    }

    @Test
    fun `ensureSelectedModelPresent with empty id clears selection and manual drafts`() {
        val models = listOf(
            SettingsModel(id = "draft", subtitle = "", isSelected = true, isManualEntry = true),
            SettingsModel(id = "m1", subtitle = "", isSelected = false),
            SettingsModel(id = "m2", subtitle = "", isSelected = false),
        )
        val result = ensureSelectedModelPresent(models, selectedModelId = "")
        assertEquals(2, result.size)
        assertTrue(result.none { it.isSelected })
        assertTrue(result.none { it.isManualEntry })
    }

    @Test
    fun `ensureSelectedModelPresent injects into empty list`() {
        val result = ensureSelectedModelPresent(emptyList(), selectedModelId = "custom-only")
        assertEquals(1, result.size)
        assertEquals("custom-only", result[0].id)
        assertTrue(result[0].isSelected)
        assertTrue(result[0].isManualEntry)
    }
}
