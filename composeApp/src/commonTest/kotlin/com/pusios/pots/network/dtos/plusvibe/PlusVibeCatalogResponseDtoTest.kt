package com.pusios.pots.network.dtos.plusvibe

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Locks the shape of PlusVibe's public catalog (GET /api/catalog, no key). The /v1/models
 * endpoint only returns id+object, so the model cards take prices, context and tools/vision
 * from here. Fixture values come from a live 2026-11-23 catalog probe (catalog-samples.json,
 * card t_cd33ff78).
 */
class PlusVibeCatalogResponseDtoTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `parses the catalog array with unknown keys ignored`() {
        val body = """
            [
              {"model":"glm-5.3-flash","apiModel":"z-ai/glm-5.3-flash","displayLabel":"GLM-5.3 Flash",
               "maker":"Zhipu AI","popularity":3.2,"byRequest":false,"dateOrder":2069120693,
               "variants":[
                 {"variant":2,"label":"Cxz","inputRub":9.51,"outputRub":31.7,"cache":true,"isDefault":false,
                  "addressAs":"z-ai/glm-5.3-flash:cxz","maxContext":1048000,"maxOutput":128000,"vision":true},
                 {"variant":1,"label":"Cxbc · по умолчанию","inputRub":2,"outputRub":6.67,
                  "cachedInputRub":0.2,"cachedWriteInputRub":2,"isDefault":true,
                  "addressAs":"z-ai/glm-5.3-flash:cxbc","maxContext":1048000,"maxOutput":128000,"vision":true}],
               "caps":{"contextWindow":1048000,"maxOutput":128000,"toolCalling":true,"jsonMode":false,
                       "vision":true,"knowledgeCutoff":"2025-08"},
               "officialInputUsd":0.15,"officialOutputUsd":0.5}
            ]
        """.trimIndent()
        val entries = json.decodeFromString(ListSerializer(PlusVibeCatalogEntryDto.serializer()), body)
        val entry = entries.single()
        assertEquals("z-ai/glm-5.3-flash", entry.apiModel)
        assertEquals(2, entry.variants.size)
        assertTrue(entry.caps?.toolCalling == true)
        assertTrue(entry.caps?.vision == true)
        assertEquals(1_048_000L, entry.caps?.contextWindow)
        assertTrue(entry.variants.first { it.isDefault == true }.vision == true)
    }

    @Test
    fun `parses an entry without caps`() {
        val body = """[{"apiModel":"vendor/model","variants":[{"inputRub":1.0,"outputRub":2.0}]}]"""
        val entries = json.decodeFromString(ListSerializer(PlusVibeCatalogEntryDto.serializer()), body)
        val entry = entries.single()
        assertEquals("vendor/model", entry.apiModel)
        assertEquals(null, entry.caps)
    }
}
