package com.pusios.pots.ui.settings

/**
 * No Piper support on this platform — the test button stays disabled upstream.
 */
actual suspend fun testPiperVoice(voiceId: String, phrase: String): Boolean = false
