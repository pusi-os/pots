package com.pusios.pots.network.dtos.aitunnel

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Locks the shape of AITUNNEL's public chat catalog (GET /public/aitunnel/models/chat, no key):
 * a JSON object keyed by model id. Fixture values come from a live 2026-11-23 catalog probe
 * (catalog-samples.json, card t_cd33ff78).
 */
class AiTunnelCatalogResponseDtoTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `parses the id-keyed object with unknown keys ignored`() {
        val body = """
            {
              "glm-5.3-flash": {
                "provider": "z-ai",
                "short_description": "Эффективный код и длинные агентные задачи с мультимодальным входом",
                "modalities": {"input": ["text", "image", "video"], "output": ["text"]},
                "created": 1787752741,
                "prompt_cost": 7.98,
                "completion_cost": 15.68,
                "context_size": 1048576,
                "max_output": 131072,
                "cache_discount": 0.24812,
                "batch": {"discount": 0.64, "window": "24h"}
              },
              "deepseek-v4-flash": {
                "provider": "deepseek",
                "prompt_cost": 1.5,
                "completion_cost": 6.0,
                "context_size": 131072,
                "modalities": {"input": ["text"], "output": ["text"]}
              }
            }
        """.trimIndent()
        val catalog = json.decodeFromString(
            MapSerializer(String.serializer(), AiTunnelCatalogEntryDto.serializer()),
            body,
        )
        val glm = catalog.getValue("glm-5.3-flash")
        assertEquals(7.98, glm.prompt_cost)
        assertEquals(15.68, glm.completion_cost)
        assertEquals(1_048_576L, glm.context_size)
        assertTrue(glm.modalities?.input.orEmpty().contains("image"))
        val deepseek = catalog.getValue("deepseek-v4-flash")
        assertTrue(deepseek.modalities?.input.orEmpty().none { it == "image" })
    }

    @Test
    fun `parses an entry without modalities`() {
        val body = """{"model-id": {"prompt_cost": 1.5, "completion_cost": 6.0}}"""
        val catalog = json.decodeFromString(
            MapSerializer(String.serializer(), AiTunnelCatalogEntryDto.serializer()),
            body,
        )
        val entry = catalog.getValue("model-id")
        assertEquals(null, entry.modalities)
        assertEquals(null, entry.context_size)
    }
}
