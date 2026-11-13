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

fun AppSettings.getInstanceMediaAudioModelId(instanceId: String): String = settings.getString("instance_${instanceId}_media_audio_model_id", "")

fun AppSettings.setInstanceMediaAudioModelId(instanceId: String, modelId: String) {
    settings.putString("instance_${instanceId}_media_audio_model_id", modelId)
}

/** Per-flow audio model selection: TTS and music flows remember separate models (spec §14). */
fun AppSettings.getInstanceMediaAudioTtsModelId(instanceId: String): String = settings.getString("instance_${instanceId}_media_audio_tts_model_id", "")

fun AppSettings.setInstanceMediaAudioTtsModelId(instanceId: String, modelId: String) {
    settings.putString("instance_${instanceId}_media_audio_tts_model_id", modelId)
}

fun AppSettings.getInstanceMediaAudioMusicModelId(instanceId: String): String = settings.getString("instance_${instanceId}_media_audio_music_model_id", "")

fun AppSettings.setInstanceMediaAudioMusicModelId(instanceId: String, modelId: String) {
    settings.putString("instance_${instanceId}_media_audio_music_model_id", modelId)
}

/** The remembered audio flow (spec §13): first tap enables audio with it, re-tap offers the choice. */
fun AppSettings.getMediaAudioFlow(): AudioFlow = AudioFlow.fromStorage(settings.getStringOrNull("media_audio_flow"))

fun AppSettings.setMediaAudioFlow(flow: AudioFlow) {
    settings.putString("media_audio_flow", flow.storageKey)
}

/**
 * Prompt drafts keyed per mode and audio flow (`mediaDraftKey`). Switching
 * modes/flows and generation errors never erase them (spec §8, §17).
 */
fun AppSettings.getMediaDraft(key: String): String = settings.getString("media_draft_$key", "")

fun AppSettings.setMediaDraft(key: String, text: String) {
    settings.putString("media_draft_$key", text)
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
    settings.remove("instance_${instanceId}_media_audio_model_id")
    settings.remove("instance_${instanceId}_media_audio_tts_model_id")
    settings.remove("instance_${instanceId}_media_audio_music_model_id")
}
