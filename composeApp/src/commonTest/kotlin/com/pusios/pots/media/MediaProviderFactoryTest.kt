package com.pusios.pots.media

import com.pusios.pots.data.AppSettings
import com.pusios.pots.data.Service
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The factory is the single gate for "can the app drive this service's media
 * endpoints". Only PlusVibe has an implemented contract, so every other service
 * must stay null — otherwise the picker would offer a service whose submit would
 * fail at the first paid call.
 */
class MediaProviderFactoryTest {

    @Test
    fun plusVibeServicesHaveProviders() {
        assertTrue(mediaProviderFor(Service.PlusVibe) is PlusVibeMediaProvider)
        assertTrue(mediaProviderFor(Service.PlusVibeReserve) is PlusVibeMediaProvider)
    }

    @Test
    fun servicesWithoutAnImplementedContractStayNull() {
        val withoutMedia = listOf(
            Service.Polza,
            Service.Gptunnel,
            Service.AiTunnel,
            Service.LlmRouter,
            Service.OpenAICompatible,
            Service.LiteRT,
        )
        withoutMedia.forEach { service ->
            assertNull(mediaProviderFor(service), "${service.id} must not be offered until its provider exists")
        }
    }

    @Test
    fun providerInstanceIsReusedPerService() {
        // The provider keeps the parsed catalog for reference mapping; a fresh
        // instance per call would silently drop it.
        assertSame(mediaProviderFor(Service.PlusVibe), mediaProviderFor(Service.PlusVibe))
    }

    @Test
    fun activeProviderWithoutAMediaServiceFailsWithAReason() = runTest {
        val provider = ActiveMediaProvider(AppSettings(MapSettings()))
        val error = runCatching { provider.listModels("") }.exceptionOrNull()
        assertTrue(error is MediaException.Unavailable, "expected Unavailable, got $error")
    }
}
