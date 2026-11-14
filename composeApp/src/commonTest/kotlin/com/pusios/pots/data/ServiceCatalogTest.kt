package com.pusios.pots.data

import com.pusios.pots.testutil.FakeDataRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServiceCatalogTest {
    @Test
    fun `RF catalog exposes only Russian-accessible providers`() {
        assertEquals(
            listOf(
                "polza",
                "gptunnel",
                "aitunnel",
                "plusvibe",
                "plusvibe-reserve",
                "llm-router",
                "openai-compatible",
                "litert",
            ),
            Service.all.map { it.id },
        )
    }

    @Test
    fun `the built-in free provider is gone and unknown ids resolve to nothing`() {
        assertFalse(Service.all.any { it.id == "opencode-free" })
        assertNull(Service.fromId("opencode-free"))
        assertNull(Service.fromId("no-such-service"))
        assertEquals(Service.PlusVibe, Service.fromId("plusvibe"))
        assertEquals(Service.LiteRT, Service.fromId("litert"))
    }

    @Test
    fun `available service entries list only configured instances`() {
        val repository = FakeDataRepository().apply { setConfiguredServices(Service.PlusVibe, Service.Gptunnel) }

        val entries = repository.getAvailableServiceEntries()

        assertEquals(listOf("plusvibe", "gptunnel"), entries.map { it.instanceId })
    }

    @Test
    fun `GPTunnel uses raw authorization while the other gateways use bearer`() {
        assertEquals(ApiKeyAuthStyle.RAW, Service.Gptunnel.apiKeyAuthStyle)
        assertEquals(ApiKeyAuthStyle.BEARER, Service.Polza.apiKeyAuthStyle)
        assertEquals(ApiKeyAuthStyle.BEARER, Service.AiTunnel.apiKeyAuthStyle)
        assertEquals(ApiKeyAuthStyle.BEARER, Service.PlusVibe.apiKeyAuthStyle)
        assertEquals(ApiKeyAuthStyle.BEARER, Service.PlusVibeReserve.apiKeyAuthStyle)
        assertEquals(ApiKeyAuthStyle.BEARER, Service.LlmRouter.apiKeyAuthStyle)
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
        for (service in listOf(Service.Polza, Service.Gptunnel, Service.AiTunnel, Service.PlusVibe, Service.PlusVibeReserve, Service.LlmRouter)) {
            assertTrue(service.requiresApiKey, service.id)
            assertTrue(service.chatUrl.startsWith("https://"), service.id)
            assertTrue(service.modelsUrl?.startsWith("https://") == true, service.id)
            assertTrue(service.apiKeyUrl?.startsWith("https://") == true, service.id)
        }
    }
}
