package com.pusios.pots.media

/**
 * iOS/macOS file synthesis (AVSpeechSynthesizer + AVAudioEngine capture) is
 * not wired in the app or in TextToSpeechKt (research §4.2), so the local
 * group does not exist on this target (spec §14).
 */
actual fun localTtsCatalog(): List<MediaModel> = emptyList()

actual fun createLocalTtsSynthesizer(modelId: String): LocalTtsSynthesizer? = null
