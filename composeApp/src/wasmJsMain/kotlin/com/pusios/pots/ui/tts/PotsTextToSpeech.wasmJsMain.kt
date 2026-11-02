package com.pusios.pots.ui.tts

/**
 * No Piper support on this platform — the app falls back to the native engine.
 */
actual fun createPiperTextToSpeech(voiceId: String): PotsTextToSpeech? = null
