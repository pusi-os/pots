package com.pusios.pots.media

import com.pusios.pots.data.AppSettings

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

fun AppSettings.removeInstanceMediaSettings(instanceId: String) {
    settings.remove("instance_${instanceId}_media_image_model_id")
    settings.remove("instance_${instanceId}_media_video_model_id")
}
