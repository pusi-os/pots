package com.pusios.pots.handsfree

import android.content.Context
import org.koin.java.KoinJavaComponent.inject

/**
 * Creates the Android speech recognizer.
 *
 * Returns null when the context cannot be resolved — the hands-free button is then hidden
 * instead of failing at the tap.
 */
actual fun createSpeechToText(): SpeechToText? = try {
    val context: Context by inject(Context::class.java)
    AndroidSpeechToText(context)
} catch (_: Exception) {
    null
}
