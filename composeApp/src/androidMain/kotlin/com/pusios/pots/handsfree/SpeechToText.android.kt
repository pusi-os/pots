package com.pusios.pots.handsfree

import android.content.Context
import com.pusios.pots.data.SttEngine
import org.koin.java.KoinJavaComponent.inject

/**
 * Creates the Android speech recognizer for the engine that can actually run.
 *
 * The requested engine goes through [routeSttEngine] first, so a neural engine
 * whose model is missing — or whose memory the device does not have — falls
 * back to Vosk here, once, for every consumer: the hands-free chat, the overlay
 * pill and dictation. The platform recognizer may need the network, so it is
 * never the fallback. Returns null when the context cannot be resolved — the
 * hands-free button is then hidden instead of failing at the tap.
 */
actual fun createSpeechToText(engine: SttEngine, voskModelId: String, neuralModelId: String): SpeechToText? = try {
    val context: Context by inject(Context::class.java)
    when (routeSttEngine(engine, sttRuntimeFacts(neuralModelId)).engine) {
        SttEngine.System -> AndroidSpeechToText(context)
        SttEngine.Vosk -> VoskSpeechToText(context, voskModelId)
        SttEngine.Neural -> SherpaSpeechToText(context, neuralModelId)
    }
} catch (_: Exception) {
    null
}
