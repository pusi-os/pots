package com.pusios.pots.data

import com.pusios.pots.testutil.FakeDataRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServiceCatalogTest {
    @Test
    fun `RF catalog exposes only built-in and Russian-accessible providers`() {
        assertEquals(
            listOf(
                "opencode-free",
                "free",
                "polza",
                "gptunnel",
                "aitunnel",
                "openai-compatible",
                "litert",
            ),
            Service.all.map { it.id },
        )
    }

    @Test
    fun `free presets pin the expected services and models`() {
        assertEquals(OPENCODE_FREE_MODEL_ID, FreeMode.FAST.modelId)
        assertEquals(Service.OpenCodeFree, FreeMode.FAST.service)
        assertEquals(FREE_TIER_MODEL_ID, FreeMode.EXPERT.modelId)
        assertEquals(Service.Free, FreeMode.EXPERT.service)
        assertTrue(FreeMode.entries.all { it.service.isFreeTier })
    }

    @Test
    fun `free preset picker hides the internal model id`() {
        val repository = FakeDataRepository().apply { setFreeServicePrimary(true) }

        val freeEntries = repository.getAvailableServiceEntries().filter { it.instanceId.startsWith("free-") }

        assertEquals(FreeMode.entries.size, freeEntries.size)
        assertTrue(freeEntries.all { !it.showModelId })
    }

    @Test
    fun `GPTunnel uses raw authorization while the other gateways use bearer`() {
        assertEquals(ApiKeyAuthStyle.RAW, Service.Gptunnel.apiKeyAuthStyle)
        assertEquals(ApiKeyAuthStyle.BEARER, Service.Polza.apiKeyAuthStyle)
        assertEquals(ApiKeyAuthStyle.BEARER, Service.AiTunnel.apiKeyAuthStyle)
    }

    @Test
    fun `Pulse accepts on-device models while interactive remote flows stay remote-only`() {
        assertFalse(supportsAgenticFlows(Service.LiteRT.id, "local-model"))
        assertTrue(supportsPulseModel(Service.LiteRT.id, "local-model"))
        assertTrue(supportsPulseModel(Service.Polza.id, "gpt-4o"))
        assertFalse(supportsPulseModel(Service.Polza.id, "sonar"))
    }

    @Test
    fun `added gateways have chat models and key pages`() {
        for (service in listOf(Service.Polza, Service.Gptunnel, Service.AiTunnel)) {
            assertFalse(service.isFreeTier, service.id)
            assertTrue(service.requiresApiKey, service.id)
            assertTrue(service.chatUrl.startsWith("https://"), service.id)
            assertTrue(service.modelsUrl?.startsWith("https://") == true, service.id)
            assertTrue(service.apiKeyUrl?.startsWith("https://") == true, service.id)
        }
    }
}
