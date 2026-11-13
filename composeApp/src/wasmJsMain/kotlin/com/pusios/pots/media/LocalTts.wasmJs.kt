package com.pusios.pots.media

/**
 * Browser `speechSynthesis` never exposes audio data (research §4.2), so the
 * local group does not exist on the web target (spec §14).
 */
actual fun localTtsCatalog(): List<MediaModel> = emptyList()

actual fun createLocalTtsSynthesizer(modelId: String): LocalTtsSynthesizer? = null
