package com.pusios.pots.data

import com.pusios.pots.ui.settings.SettingsModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecommendedModelsTest {

    private fun model(id: String) = SettingsModel(id = id, subtitle = "")

    // --- Table B integrity ---

    @Test
    fun `every table entry covers all three classes with unique ids`() {
        val services = listOf(
            Service.PlusVibe,
            Service.PlusVibeReserve,
            Service.Polza,
            Service.AiTunnel,
            Service.Gptunnel,
            Service.LlmRouter,
        )
        for (service in services) {
            val picks = RecommendedModels.picksFor(service)
            assertTrue(picks.size in 3..5, "$service must offer 3-5 picks, got ${picks.size}")
            for (modelClass in RecommendedModels.ModelClass.entries) {
                assertTrue(
                    picks.any { it.modelClass == modelClass },
                    "$service is missing the $modelClass pick",
                )
            }
            assertEquals(picks.size, picks.map { it.id }.toSet().size, "$service has duplicate ids")
            assertTrue(picks.all { it.description.isNotBlank() }, "$service has an empty description")
        }
    }

    @Test
    fun `services without a table entry get no picks`() {
        assertTrue(RecommendedModels.picksFor(Service.OpenAICompatible).isEmpty())
        assertTrue(RecommendedModels.picksFor(Service.LiteRT).isEmpty())
        assertNull(RecommendedModels.everydayModelId(Service.OpenAICompatible))
    }

    @Test
    fun `everyday ids match the live catalogs`() {
        assertEquals("z-ai/glm-5.3-flash", RecommendedModels.everydayModelId(Service.PlusVibe))
        assertEquals("z-ai/glm-5.3-flash", RecommendedModels.everydayModelId(Service.Polza))
        assertEquals("glm-5.3-flash", RecommendedModels.everydayModelId(Service.AiTunnel))
        assertEquals("gpt-6-luna", RecommendedModels.everydayModelId(Service.Gptunnel))
        assertEquals("glm-5.3-flash", RecommendedModels.everydayModelId(Service.LlmRouter))
    }

    // --- selectRecommended ---

    @Test
    fun `recommended list is everyday-first and only contains catalog models`() {
        // PlusVibe catalog as the service returned it (not in table order).
        val catalog = listOf(
            model("gpt-6.2-astra"),
            model("deepseek-v4.1-flash-1017"),
            model("google/gemini-3.8-flash"),
            model("z-ai/glm-5.3-flash"),
            model("some-foreign-model"),
        )
        val result = RecommendedModels.selectRecommended(catalog, Service.PlusVibe, selectedId = null)
        assertEquals(
            listOf("z-ai/glm-5.3-flash", "deepseek-v4.1-flash-1017", "google/gemini-3.8-flash", "gpt-6.2-astra"),
            result.map { it.id },
        )
    }

    @Test
    fun `missing class pick is skipped without leaving a hole`() {
        // GPTunnel catalog without the middle-class gemini-3.6-flash.
        val catalog = listOf(model("gpt-6-luna"), model("gpt-6.2-astra"))
        val result = RecommendedModels.selectRecommended(catalog, Service.Gptunnel, selectedId = null)
        assertEquals(listOf("gpt-6-luna", "gpt-6.2-astra"), result.map { it.id })
    }

    @Test
    fun `current choice stays reachable even when it is outside the table`() {
        val catalog = listOf(
            model("z-ai/glm-5.3-flash"),
            model("google/gemini-3.8-flash"),
            model("gpt-6.2-astra"),
            model("my-fine-tuned-model"),
        )
        val result = RecommendedModels.selectRecommended(catalog, Service.PlusVibe, selectedId = "my-fine-tuned-model")
        assertEquals("my-fine-tuned-model", result.first().id)
        assertEquals(5, result.size)
    }

    @Test
    fun `list is capped at five entries`() {
        val catalog = listOf(
            model("z-ai/glm-5.3-flash"),
            model("deepseek-v4.1-flash-1017"),
            model("google/gemini-3.8-flash"),
            model("gpt-6.2-astra"),
            model("user-picked-oddity"),
            model("another-one"),
        )
        val result = RecommendedModels.selectRecommended(catalog, Service.PlusVibe, selectedId = "user-picked-oddity")
        assertTrue(result.size <= 5, "recommended list must cap at 5, got ${result.size}")
        assertEquals("user-picked-oddity", result.first().id)
    }

    @Test
    fun `service without a table entry keeps the full catalog`() {
        val catalog = listOf(model("a"), model("b"))
        assertEquals(catalog.map { it.id }, RecommendedModels.selectRecommended(catalog, Service.OpenAICompatible, null).map { it.id })
    }
}
