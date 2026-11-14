package com.pusios.pots.data

import com.pusios.pots.data.AppSettings.Companion.KEY_APP_OPENS
import com.pusios.pots.data.AppSettings.Companion.KEY_BASE_URL_V1_MIGRATION_COMPLETE
import com.pusios.pots.data.AppSettings.Companion.KEY_CURRENT_SERVICE_ID
import com.pusios.pots.data.AppSettings.Companion.KEY_CUSTOM_MODEL_MIGRATION_COMPLETE
import com.pusios.pots.data.AppSettings.Companion.KEY_INSTANCE_MIGRATION_COMPLETE
import com.pusios.pots.data.AppSettings.Companion.KEY_MIGRATION_COMPLETE
import com.pusios.pots.data.AppSettings.Companion.KEY_OPENCODE_REMOVAL_MIGRATION_COMPLETE
import com.pusios.pots.data.AppSettings.Companion.KEY_SERVICES_MIGRATION_COMPLETE
import com.russhwolf.settings.Settings

internal val versionPathRegex = Regex("/v\\d+$")

fun AppSettings.runMigrations(legacySettings: Settings?) {
    migrateFromLegacyIfNeeded(legacySettings)
    migrateConfiguredServicesIfNeeded()
    migrateInstanceSettingsIfNeeded()
    migrateBaseUrlsToV1PathIfNeeded()
    migrateCustomModelSettingsIfNeeded()
    migrateRemovedOpencodeIfNeeded()
}

fun AppSettings.migrateFromLegacyIfNeeded(legacySettings: Settings?) {
    if (legacySettings == null) return
    if (settings.getBoolean(KEY_MIGRATION_COMPLETE, false)) return

    migrateString(legacySettings, KEY_CURRENT_SERVICE_ID)
    migrateInt(legacySettings, KEY_APP_OPENS)

    for (service in Service.all) {
        if (service.settingsKeyPrefix.isNotEmpty()) {
            migrateString(legacySettings, service.apiKeyKey)
            migrateString(legacySettings, service.modelIdKey)
        }
    }
    migrateString(legacySettings, Service.OpenAICompatible.baseUrlKey)

    settings.putBoolean(KEY_MIGRATION_COMPLETE, true)
}

private fun AppSettings.migrateString(legacy: Settings, key: String) {
    val value = legacy.getStringOrNull(key)
    if (value != null && settings.getStringOrNull(key) == null) {
        settings.putString(key, value)
    }
}

private fun AppSettings.migrateInt(legacy: Settings, key: String) {
    if (legacy.hasKey(key) && !settings.hasKey(key)) {
        settings.putInt(key, legacy.getInt(key, 0))
    }
}

fun AppSettings.migrateConfiguredServicesIfNeeded() {
    if (settings.getBoolean(KEY_SERVICES_MIGRATION_COMPLETE, false)) return

    val existing = getConfiguredServiceInstances()
    val existingServiceIds = existing.map { it.serviceId }.toSet()
    val instances = existing.toMutableList()

    val currentServiceId = settings.getString(KEY_CURRENT_SERVICE_ID, "")
    val currentService = Service.fromId(currentServiceId)
    if (currentService != null && currentService.id !in existingServiceIds) {
        instances.add(ServiceInstance(instanceId = currentService.id, serviceId = currentService.id))
    }

    for (service in Service.all) {
        if (service.id in existingServiceIds) continue
        if (instances.any { it.serviceId == service.id }) continue
        val apiKey = getApiKey(service)
        if (apiKey.isNotBlank()) {
            instances.add(ServiceInstance(instanceId = service.id, serviceId = service.id))
        }
    }

    if (instances.size > existing.size) {
        setConfiguredServiceInstances(instances)
    }

    settings.putBoolean(KEY_SERVICES_MIGRATION_COMPLETE, true)
}

/**
 * The built-in free provider (`opencode-free`) was removed from the app. Older installs may
 * still hold its keys, flags, a configured instance, or a `current_service_id` pointing at it.
 * Clean all of it up so nothing stale surfaces in the service list, and reset the current
 * service to the first remaining configured instance (blank when none is left).
 */
fun AppSettings.migrateRemovedOpencodeIfNeeded() {
    if (settings.getBoolean(KEY_OPENCODE_REMOVAL_MIGRATION_COMPLETE, false)) return

    val removedServiceId = "opencode-free"
    val removedPrefix = "service_$removedServiceId"

    val instances = getConfiguredServiceInstances()
    if (instances.any { it.serviceId == removedServiceId }) {
        setConfiguredServiceInstances(instances.filterNot { it.serviceId == removedServiceId })
    }

    if (settings.getString(KEY_CURRENT_SERVICE_ID, "") == removedServiceId) {
        val fallback = getConfiguredServiceInstances().firstOrNull()?.serviceId ?: ""
        settings.putString(KEY_CURRENT_SERVICE_ID, fallback)
    }

    settings.remove("${removedPrefix}_api_key")
    settings.remove("${removedPrefix}_model_id")
    settings.remove("instance_${removedServiceId}_api_key")
    settings.remove("instance_${removedServiceId}_model_id")
    settings.remove("free_mode")
    settings.remove("free_service_primary")

    settings.putBoolean(KEY_OPENCODE_REMOVAL_MIGRATION_COMPLETE, true)
}

/**
 * Migrate per-service settings to per-instance settings.
 * For existing users, the first instance of each service type uses the service's
 * legacy key prefix. This copies those values to the new instance_ keys.
 */
fun AppSettings.migrateInstanceSettingsIfNeeded() {
    if (settings.getBoolean(KEY_INSTANCE_MIGRATION_COMPLETE, false)) return

    val instances = getConfiguredServiceInstances()
    for (instance in instances) {
        val service = Service.fromId(instance.serviceId) ?: continue
        val legacyApiKey = getApiKey(service)
        if (legacyApiKey.isNotBlank() && getInstanceApiKey(instance.instanceId).isBlank()) {
            setInstanceApiKey(instance.instanceId, legacyApiKey)
        }
        val legacyModel = getSelectedModelId(service)
        if (legacyModel.isNotBlank() && getInstanceModelId(instance.instanceId).isBlank()) {
            setInstanceModelId(instance.instanceId, legacyModel)
        }
        if (service == Service.OpenAICompatible) {
            val legacyBaseUrl = getBaseUrl(service)
            if (legacyBaseUrl.isNotBlank() && getInstanceBaseUrl(instance.instanceId).isBlank()) {
                setInstanceBaseUrl(instance.instanceId, legacyBaseUrl)
            }
        }
    }

    settings.putBoolean(KEY_INSTANCE_MIGRATION_COMPLETE, true)
}

/**
 * Migrate existing OpenAI-compatible base URLs to include `/v1` path segment.
 * Previously `/v1` was hardcoded in the endpoint paths; now the base URL should
 * include it (following the OpenAI SDK convention).
 */
fun AppSettings.migrateBaseUrlsToV1PathIfNeeded() {
    if (settings.getBoolean(KEY_BASE_URL_V1_MIGRATION_COMPLETE, false)) return

    val instances = getConfiguredServiceInstances()
    for (instance in instances) {
        val service = Service.fromId(instance.serviceId) ?: continue
        if (service != Service.OpenAICompatible) continue
        val baseUrl = getInstanceBaseUrl(instance.instanceId)
        if (baseUrl.isNotBlank()) {
            setInstanceBaseUrl(instance.instanceId, ensureBaseUrlHasVersionPath(baseUrl))
        }
    }

    val legacyBaseUrl = settings.getString(Service.OpenAICompatible.baseUrlKey, "")
    if (legacyBaseUrl.isNotBlank()) {
        settings.putString(Service.OpenAICompatible.baseUrlKey, ensureBaseUrlHasVersionPath(legacyBaseUrl))
    }

    settings.putBoolean(KEY_BASE_URL_V1_MIGRATION_COMPLETE, true)
}

internal fun ensureBaseUrlHasVersionPath(url: String): String {
    val trimmed = url.trimEnd('/')
    if (trimmed.contains(versionPathRegex)) return trimmed
    return "$trimmed/v1"
}

/**
 * Seeds OpenAI-Compatible custom-model fields introduced for free-text model entry.
 *
 * Existing installs only had `instance_*_model_id` (list selection or free-typed id).
 * This copies that value into `custom_model_id` as a prefill backup and leaves
 * `use_custom_model` off so chat behavior is unchanged until the user enables the
 * "Custom model" checkbox. List selection stays in `model_id`.
 */
fun AppSettings.migrateCustomModelSettingsIfNeeded() {
    if (settings.getBoolean(KEY_CUSTOM_MODEL_MIGRATION_COMPLETE, false)) return

    val instances = getConfiguredServiceInstances()
    for (instance in instances) {
        if (Service.fromId(instance.serviceId) != Service.OpenAICompatible) continue
        val listModelId = getInstanceModelId(instance.instanceId)
        if (listModelId.isNotBlank() && getInstanceCustomModelId(instance.instanceId).isBlank()) {
            setInstanceCustomModelId(instance.instanceId, listModelId)
        }
        // use_custom_model defaults to false when the key is absent — no write needed.
    }

    settings.putBoolean(KEY_CUSTOM_MODEL_MIGRATION_COMPLETE, true)
}
