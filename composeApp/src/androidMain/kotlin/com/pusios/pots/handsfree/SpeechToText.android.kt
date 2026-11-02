package com.pusios.pots.handsfree

import android.content.Context
import com.pusios.pots.data.SttEngine
import org.koin.java.KoinJavaComponent.inject

/**
 * Creates the Android speech recognizer for the selected engine.
 *
 * Vosk runs fully offline with the bundled model; the system recognizer may
 * need the network depending on the device. Returns null when the context
 * cannot be resolved — the hands-free button is then hidden instead of
 * failing at the tap.
 */
actual fun createSpeechToText(engine: SttEngine, voskModelId: String): SpeechToText? = try {
    val context: Context by inject(Context::class.java)
    when (engine) {
        SttEngine.System -> AndroidSpeechToText(context)
        SttEngine.Vosk -> VoskSpeechToText(context, voskModelId)
    }
} catch (_: Exception) {
    null
}
