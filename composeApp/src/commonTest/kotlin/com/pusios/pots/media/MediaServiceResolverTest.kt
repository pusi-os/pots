package com.pusios.pots.media

import com.pusios.pots.data.AppSettings
import com.pusios.pots.data.Service
import com.pusios.pots.data.ServiceInstance
import com.pusios.pots.data.setConfiguredServiceInstances
import com.pusios.pots.data.setInstanceApiKey
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Which instance the media studio and the agent talk to. The saved choice wins
 * while it is still valid; otherwise the fallback keeps existing installs on
 * PlusVibe, and a service without media endpoints is never chosen.
 */
class MediaServiceResolverTest {

    /** One instance per service, `instanceId == service.id` keeps the test readable. */
    private fun settings(vararg services: Service): AppSettings {
        val appSettings = AppSettings(MapSettings())
        appSettings.setConfiguredServiceInstances(services.map { ServiceInstance(it.id, it.id) })
        services.forEach { appSettings.setInstanceApiKey(it.id, "key-${it.id}") }
        return appSettings
    }

    @Test
    fun noConfiguredServiceMeansNoChoice() {
        assertNull(activeMediaService(AppSettings(MapSettings())))
        assertNull(activeMediaService(settings(Service.Polza, Service.LlmRouter)))
    }

    @Test
    fun onlyServicesWithMediaEndpointsAreOffered() {
        val choices = mediaServiceChoices(settings(Service.Polza, Service.PlusVibe, Service.AiTunnel))
        assertEquals(listOf(Service.PlusVibe), choices.map { it.service })
    }

    @Test
    fun savedChoiceWins() {
        val appSettings = settings(Service.PlusVibe, Service.PlusVibeReserve)
        appSettings.setMediaServiceInstanceId(Service.PlusVibeReserve.id)
        assertEquals(Service.PlusVibeReserve, activeMediaService(appSettings)?.service)
    }

    @Test
    fun choiceCarriesTheInstanceKey() {
        val appSettings = settings(Service.PlusVibe)
        val choice = activeMediaService(appSettings)
        assertEquals("plusvibe", choice?.instanceId)
        assertEquals("key-plusvibe", choice?.apiKey)
    }

    @Test
    fun savedChoiceOnAServiceWithoutMediaFallsBackToPlusVibe() {
        val appSettings = settings(Service.Polza, Service.PlusVibe)
        appSettings.setMediaServiceInstanceId(Service.Polza.id)
        assertEquals(Service.PlusVibe, activeMediaService(appSettings)?.service)
    }

    @Test
    fun fallbackPrefersPlusVibeOverListOrder() {
        val appSettings = settings(Service.PlusVibeReserve, Service.PlusVibe)
        assertEquals(Service.PlusVibe, activeMediaService(appSettings)?.service)
    }

    @Test
    fun savedChoiceOfARemovedInstanceFallsBack() {
        val appSettings = settings(Service.PlusVibeReserve)
        appSettings.setMediaServiceInstanceId("gone")
        assertEquals(Service.PlusVibeReserve, activeMediaService(appSettings)?.service)
    }

    @Test
    fun selectionRoundTripsThroughSettings() {
        val raw = MapSettings()
        AppSettings(raw).setMediaServiceInstanceId("plusvibe-reserve")
        assertEquals("plusvibe-reserve", AppSettings(raw).getMediaServiceInstanceId())
    }
}
