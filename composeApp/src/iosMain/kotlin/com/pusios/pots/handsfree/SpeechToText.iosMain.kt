package com.pusios.pots.handsfree

import com.pusios.pots.data.SttEngine

/**
 * No speech recognizer on this platform.
 *
 * The hands-free button is hidden when the factory returns null, so the rest of the app is
 * unaffected by the missing capability.
 */
actual fun createSpeechToText(engine: SttEngine, voskModelId: String, neuralModelId: String): SpeechToText? = null

/** No neural engine either: nothing can be installed, so nothing is. */
actual fun sttRuntimeFacts(neuralModelId: String): SttRuntimeFacts = SttRuntimeFacts(
    neuralModelInstalled = false,
    availableMemoryMb = null,
)
