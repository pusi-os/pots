package com.pusios.pots.media

/**
 * Desktop has no file synthesis: TextToSpeechKt wraps FreeTTS for playback
 * only (research §4.2), so the «Локально · 0 ₽» group does not exist here —
 * no disabled fakes (spec §14).
 */
actual fun localTtsCatalog(): List<MediaModel> = emptyList()

actual fun createLocalTtsSynthesizer(modelId: String): LocalTtsSynthesizer? = null
