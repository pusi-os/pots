package com.pusios.pots.network.dtos.openaicompatible

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Locks the optional fields the model card's tools and vision tiles depend on. Every one of them
 * is nullable: providers that do not send them must keep parsing.
 */
class OpenAICompatibleModelResponseDtoTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `parses polza architecture and top_provider supported_parameters`() {
        val body = """
            {"object":"list","data":[
              {"id":"google/gemini-4-neon","object":"model","type":"chat",
               "architecture":{"modality":"text+image->text","input_modalities":["text","image"],"output_modalities":["text"]},
               "top_provider":{"pricing":{"prompt_per_million":"175.80240000","currency":"RUB"},
                               "supported_parameters":["temperature","tools","tool_choice"]}}
            ]}
        """.trimIndent()
        val model = json.decodeFromString(OpenAICompatibleModelResponseDto.serializer(), body).data.single()
        assertEquals(listOf("text", "image"), model.architecture?.input_modalities)
        assertTrue(model.top_provider?.supported_parameters.orEmpty().contains("tools"))
        assertEquals("175.80240000", model.top_provider?.pricing?.prompt_per_million)
    }

    @Test
    fun `parses minimal openai-compatible payload without the optional fields`() {
        val body = """{"object":"list","data":[{"id":"llama3.2","object":"model","created":1700000000,"owned_by":"library"}]}"""
        val model = json.decodeFromString(OpenAICompatibleModelResponseDto.serializer(), body).data.single()
        assertNull(model.architecture)
        assertNull(model.supported_parameters)
        assertNull(model.top_provider)
    }
}
