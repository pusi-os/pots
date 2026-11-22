package com.pusios.pots.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.pusios.pots.data.AppSettings
import com.pusios.pots.data.Service
import com.pusios.pots.data.ServiceInstance
import com.pusios.pots.data.setConfiguredServiceInstances
import com.pusios.pots.data.setInstanceApiKey
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.media_service_models
import pots.composeapp.generated.resources.media_service_title
import kotlin.test.Test

/**
 * «Сервис для медиатеки» renders only when there is something to choose, and it
 * renders without a Koin container — the screen content under Paparazzi never
 * gets one, which is why the section is injected as a slot by `SettingsScreen`.
 */
@OptIn(ExperimentalTestApi::class)
class MediaServiceSectionTest {

    private fun entry(service: Service, instanceId: String = service.id) = ConfiguredServiceEntry(
        instanceId = instanceId,
        service = service,
    )

    private fun settings(vararg services: Service): AppSettings {
        val appSettings = AppSettings(MapSettings())
        appSettings.setConfiguredServiceInstances(services.map { ServiceInstance(it.id, it.id) })
        services.forEach { appSettings.setInstanceApiKey(it.id, "key-${it.id}") }
        return appSettings
    }

    @Test
    fun `a single candidate hides the section`() = runComposeUiTest {
        val title = runBlocking { getString(Res.string.media_service_title) }

        setContent {
            MaterialTheme {
                MediaServiceSection(
                    entries = listOf(entry(Service.PlusVibe)),
                    appSettings = settings(Service.PlusVibe),
                    modelCount = { 7 },
                )
            }
        }

        onNodeWithText(title).assertDoesNotExist()
    }

    @Test
    fun `two candidates show the picker with the model count`() = runComposeUiTest {
        val title = runBlocking { getString(Res.string.media_service_title) }
        val count = runBlocking { getString(Res.string.media_service_models, 7) }

        setContent {
            MaterialTheme {
                MediaServiceSection(
                    entries = listOf(entry(Service.PlusVibe), entry(Service.PlusVibeReserve)),
                    appSettings = settings(Service.PlusVibe, Service.PlusVibeReserve),
                    modelCount = { 7 },
                )
            }
        }

        waitForIdle()
        onNodeWithText(title).assertExists()
        onNodeWithText(Service.PlusVibe.displayName).assertExists()
        onNodeWithText(count).assertExists()
    }
}
