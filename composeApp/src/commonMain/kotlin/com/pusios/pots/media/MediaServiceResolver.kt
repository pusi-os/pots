package com.pusios.pots.media

import com.pusios.pots.data.AppSettings
import com.pusios.pots.data.Service
import com.pusios.pots.data.ServiceInstance
import com.pusios.pots.data.getConfiguredServiceInstances
import com.pusios.pots.data.getInstanceApiKey

/** Which service instance the media studio and the agent's media tools talk to. */
data class MediaServiceChoice(
    val service: Service,
    val instanceId: String,
    val apiKey: String,
)

/** The one place the chosen media instance is stored (`MediaSettings.kt` keeps the models). */
fun AppSettings.getMediaServiceInstanceId(): String = settings.getString(KEY_MEDIA_SERVICE_INSTANCE, "")

fun AppSettings.setMediaServiceInstanceId(instanceId: String) {
    settings.putString(KEY_MEDIA_SERVICE_INSTANCE, instanceId)
}

private const val KEY_MEDIA_SERVICE_INSTANCE = "media_service_instance_id"

/**
 * Every configured instance whose service has media endpoints, in the order the
 * user arranged the services. The factory decides — never a list of service ids
 * here, so a new provider shows up in the picker the moment its branch exists.
 */
fun mediaServiceChoices(
    appSettings: AppSettings,
    instances: List<ServiceInstance> = appSettings.getConfiguredServiceInstances(),
): List<MediaServiceChoice> = instances.mapNotNull { instance ->
    val service = Service.fromId(instance.serviceId) ?: return@mapNotNull null
    if (mediaProviderFor(service) == null) return@mapNotNull null
    MediaServiceChoice(service, instance.instanceId, appSettings.getInstanceApiKey(instance.instanceId))
}

/**
 * The active media service: the saved choice while it is still configured and
 * still has media endpoints, otherwise the first suitable one — PlusVibe when it
 * is configured (existing installs keep generating where they did), else
 * whatever comes first in the list. Null when no configured service has media.
 */
fun activeMediaService(
    appSettings: AppSettings,
    instances: List<ServiceInstance> = appSettings.getConfiguredServiceInstances(),
): MediaServiceChoice? {
    val choices = mediaServiceChoices(appSettings, instances)
    if (choices.isEmpty()) return null
    val saved = appSettings.getMediaServiceInstanceId()
    return choices.firstOrNull { it.instanceId == saved }
        ?: choices.firstOrNull { it.service == Service.PlusVibe }
        ?: choices.first()
}
