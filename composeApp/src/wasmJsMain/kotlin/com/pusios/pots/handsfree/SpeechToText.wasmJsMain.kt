package com.pusios.pots.handsfree

/**
 * No speech recognizer on this platform.
 *
 * The hands-free button is hidden when the factory returns null, so the rest of the app is
 * unaffected by the missing capability.
 */
actual fun createSpeechToText(): SpeechToText? = null
