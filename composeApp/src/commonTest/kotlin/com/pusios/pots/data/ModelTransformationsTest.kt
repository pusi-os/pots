package com.pusios.pots.data

import com.pusios.pots.network.dtos.anthropic.AnthropicModelsResponseDto
import com.pusios.pots.network.dtos.gemini.GeminiModelsResponseDto
import com.pusios.pots.network.dtos.openaicompatible.OpenAICompatibleModelResponseDto
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
