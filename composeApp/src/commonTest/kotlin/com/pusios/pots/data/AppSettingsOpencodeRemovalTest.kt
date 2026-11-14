package com.pusios.pots.data

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The built-in free provider was removed from the app. Settings saved by older versions
 * (current service, configured instances, legacy keys and flags) must not break loading:
 * a one-shot migration cleans them up, and anything missed is ignored downstream.
 */
class AppSettingsOpencodeRemovalTest {

    private fun legacySettings(): MapSettings {
        val settings = MapSettings()
        // Earlier migrations already ran on the old install.
        settings.putBoolean("migration_complete_v1", true)
        settings.putBoolean("services_migration_complete_v1", true)
        settings.putBoolean("instance_migration_complete_v1", true)
        settings.putBoolean("base_url_v1_migration_complete", true)
        settings.putBoolean("custom_model_migration_complete_v1", true)
        // Leftovers of the removed provider.
        settings.putString("current_service_id", "opencode-free")
        settings.putString(
            "configured_services",
            "[{\"instanceId\":\"polza\",\"serviceId\":\"polza\"}," +
                "{\"instanceId\":\"opencode-free\",\"serviceId\":\"opencode-free\"}]",
        )
        settings.putString("service_opencode-free_api_key", "public")
        settings.putString("service_opencode-free_model_id", "big-pickle")
        settings.putString("free_mode", "FAST")
        settings.putBoolean("free_service_primary", true)
        return settings
    }

    @Test
    fun `migration removes the provider instance and resets the current service`() {
        val appSettings = AppSettings(legacySettings())

        appSettings.runMigrations(null)

        val instances = appSettings.getConfiguredServiceInstances()
        assertEquals(listOf("polza"), instances.map { it.instanceId })
        // The current service falls back to the first remaining configured instance.
        assertEquals("polza", appSettings.settings.getString("current_service_id", ""))
    }

    @Test
    fun `migration removes legacy keys and free-mode flags`() {
        val settings = legacySettings()
        val appSettings = AppSettings(settings)

        appSettings.runMigrations(null)

        assertNull(settings.getStringOrNull("service_opencode-free_api_key"))
        assertNull(settings.getStringOrNull("service_opencode-free_model_id"))
        assertNull(settings.getStringOrNull("free_mode"))
        assertFalse(settings.getBoolean("free_service_primary", false))
        assertTrue(settings.getBoolean("opencode_removal_migration_complete_v1", false))
    }

    @Test
    fun `migration with only the free service configured leaves an empty list and blank current service`() {
        val settings = MapSettings()
        settings.putString("current_service_id", "opencode-free")
        settings.putString(
            "configured_services",
            "[{\"instanceId\":\"opencode-free\",\"serviceId\":\"opencode-free\"}]",
        )
        val appSettings = AppSettings(settings)

        appSettings.runMigrations(null)

        assertTrue(appSettings.getConfiguredServiceInstances().isEmpty())
        assertEquals("", appSettings.settings.getString("current_service_id", "x"))
        assertNull(appSettings.currentService())
    }

    @Test
    fun `settings without provider leftovers are untouched`() {
        val settings = MapSettings()
        settings.putString("current_service_id", "plusvibe")
        settings.putString("configured_services", "[{\"instanceId\":\"plusvibe\",\"serviceId\":\"plusvibe\"}]")
        settings.putString("service_plusvibe_api_key", "sk-test")
        val appSettings = AppSettings(settings)

        appSettings.runMigrations(null)

        assertEquals(listOf("plusvibe"), appSettings.getConfiguredServiceInstances().map { it.instanceId })
        assertEquals("plusvibe", settings.getString("current_service_id", ""))
        assertEquals("sk-test", settings.getString("service_plusvibe_api_key", ""))
        assertEquals(Service.PlusVibe, appSettings.currentService())
    }

    @Test
    fun `stored instances of unknown services never surface`() {
        // Even without the migration, reads must survive foreign service ids (e.g. an import
        // from a build that knew other providers).
        val settings = MapSettings()
        settings.putString(
            "configured_services",
            "[{\"instanceId\":\"mystery\",\"serviceId\":\"mystery-service\"}," +
                "{\"instanceId\":\"polza\",\"serviceId\":\"polza\"}]",
        )
        val appSettings = AppSettings(settings)

        val known = appSettings.getConfiguredServiceInstances().filter { Service.fromId(it.serviceId) != null }

        assertEquals(listOf("polza"), known.map { it.instanceId })
    }
}
