package com.pusios.pots.ui.settings

/**
 * Speaks [phrase] with the bundled Piper engine and returns whether it
 * worked. Platforms without Piper always return false.
 */
expect suspend fun testPiperVoice(voiceId: String, phrase: String): Boolean
