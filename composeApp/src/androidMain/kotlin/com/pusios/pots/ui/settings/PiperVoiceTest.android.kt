package com.pusios.pots.ui.settings

import android.content.Context
import com.pusios.pots.speech.piper.PiperTts
import org.koin.java.KoinJavaComponent.inject

/**
 * Speaks the phrase through the shared Piper engine (the same one the chat
 * uses), so the test plays exactly the voice the app will speak with.
 */
actual suspend fun testPiperVoice(voiceId: String, phrase: String): Boolean {
    val context: Context = try {
        val c: Context by inject(Context::class.java)
        c
    } catch (_: Exception) {
        return false
    }
    val tts = PiperTts.shared(context, voiceId) ?: return false
    return runCatching { tts.say(phrase) }.isSuccess
}
