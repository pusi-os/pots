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
 * endpoints". PlusVibe, Polza and AITUNNEL have implemented contracts; the rest
 * must stay null — otherwise the picker would offer a service whose submit
 * would fail at the first paid call.
 */
class MediaProviderFactoryTest {

    @Test
    fun plusVibeServicesHaveProviders() {
        assertTrue(mediaProviderFor(Service.PlusVibe) is PlusVibeMediaProvider)
        assertTrue(mediaProviderFor(Service.PlusVibeReserve) is PlusVibeMediaProvider)
    }

    @Test
    fun polzaAndAiTunnelHaveProviders() {
        assertTrue(mediaProviderFor(Service.Polza) is PolzaMediaProvider)
        assertTrue(mediaProviderFor(Service.AiTunnel) is AitunnelMediaProvider)
    }

    @Test
    fun servicesWithoutAnImplementedContractStayNull() {
        val withoutMedia = listOf(
            Service.Gptunnel,
            Service.LlmRouter,
            Service.[OI]Compatible,
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
        assertTrue(error is MediaException.NoMediaService, "expected NoMediaService, got $error")
    }

    @Test
    fun noMediaServiceExplainsWhereToFixIt() {
        // The studio's snackbar comes from this mapping: it has to name the section.
        val info = mediaErrorInfo(MediaException.NoMediaService())
        assertTrue(info.title.contains("медиатеки"), "title was ${info.title}")
        assertTrue(info.detail.contains("Сервисы"), "detail was ${info.detail}")
    }
}
