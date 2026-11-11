package com.pusios.pots.media

import com.pusios.pots.data.AppSettings
import com.pusios.pots.data.ServiceInstance
import com.pusios.pots.data.getConfiguredServiceInstances
import com.pusios.pots.data.getInstanceApiKey

/**
 * Per-instance media model selection. Stored in the same `Settings` backend
 * as chat models (no SQLDelight migration), keyed per service instance so
 * each configured account keeps its own image/video model.
 */
fun AppSettings.getInstanceMediaImageModelId(instanceId: String): String = settings.getString("instance_${instanceId}_media_image_model_id", "")

fun AppSettings.setInstanceMediaImageModelId(instanceId: String, modelId: String) {
    settings.putString("instance_${instanceId}_media_image_model_id", modelId)
}

fun AppSettings.getInstanceMediaVideoModelId(instanceId: String): String = settings.getString("instance_${instanceId}_media_video_model_id", "")

fun AppSettings.setInstanceMediaVideoModelId(instanceId: String, modelId: String) {
    settings.putString("instance_${instanceId}_media_video_model_id", modelId)
}

/**
 * The service instance media generation bills. Chat keys double as media keys,
 * so prefer the first instance that actually has one; the first instance is
 * still returned (with an empty key) so callers can explain the missing key.
 */
fun AppSettings.resolveMediaInstance(): ServiceInstance? {
    val instances = getConfiguredServiceInstances()
    return instances.firstOrNull { getInstanceApiKey(it.instanceId).isNotBlank() }
        ?: instances.firstOrNull()
}

fun AppSettings.removeInstanceMediaSettings(instanceId: String) {
    settings.remove("instance_${instanceId}_media_image_model_id")
    settings.remove("instance_${instanceId}_media_video_model_id")
}
