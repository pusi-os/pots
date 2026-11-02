package com.pusios.pots.ui.tts

import android.content.Context
import com.pusios.pots.speech.piper.PiperTts
import org.koin.java.KoinJavaComponent.inject

/**
 * The bundled offline Piper engine, shared per voice id so the chat, the
 * settings test button and the cube dialogue load the model at most once.
 * Returns null when the voice is not bundled or the context is unavailable.
 */
actual fun createPiperTextToSpeech(voiceId: String): PotsTextToSpeech? = try {
    val context: Context by inject(Context::class.java)
    PiperTts.shared(context, voiceId)
} catch (_: Exception) {
    null
}
