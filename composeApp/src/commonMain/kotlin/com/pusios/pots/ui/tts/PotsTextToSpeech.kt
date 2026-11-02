package com.pusios.pots.ui.tts

import com.pusios.pots.data.TtsEngine
import nl.marc_apps.tts.TextToSpeechInstance

/**
 * The app-wide speaking voice, independent of the engine that produces it.
 *
 * Two engines exist today: the platform TTS wrapped by [NativeTextToSpeech]
 * and the bundled offline Piper voice (Android only, see
 * [createPiperTextToSpeech]). Everything that reads answers aloud — the chat,
 * the hands-free loop, the cube dialogue — talks to this interface, so
 * switching the engine in the settings is a one-line decision in `App`.
 */
interface PotsTextToSpeech {
    /** True when this engine can produce audio right now. */
    val isAvailable: Boolean

    /**
     * Speaks [text].
     *
     * [interrupt] cuts whatever is currently playing; [flush] drops speech that
     * was queued but not started yet. The call returns once the utterance has
     * finished playing (or was interrupted), so sequential calls queue up.
     */
    suspend fun say(text: String, flush: Boolean = true, interrupt: Boolean = true)

    /** Cuts the current speech immediately. */
    fun stop()

    /**
     * Releases the heavy resources of the engine (model, audio track). The
     * platform-native engine is owned by the app entry point and survives the
     * call; engines that load their own model free it and re-initialize lazily
     * on the next [say].
     */
    fun close()
}

/**
 * The platform/system engine (nl.marc-apps:tts) behind [PotsTextToSpeech].
 *
 * The underlying instance is created and owned by the platform entry point
 * (it outlives recompositions and engine switches), so [close] deliberately
 * does not shut it down.
 */
class NativeTextToSpeech(private val tts: TextToSpeechInstance) : PotsTextToSpeech {
    override val isAvailable: Boolean = true

    override suspend fun say(text: String, flush: Boolean, interrupt: Boolean) = tts.say(text, flush, interrupt)

    override fun stop() = tts.stop()

    override fun close() = Unit
}

/** The voice used when the user has not picked one explicitly. */
const val DEFAULT_PIPER_VOICE_ID = "ru_RU-irina-medium"

/**
 * What the Settings "test the voice" button says. The phrase is Russian on
 * purpose: the bundled Piper voice only speaks Russian.
 */
const val PIPER_TEST_PHRASE = "Привет! Я Потсик. Так звучит голос Ирины."

/**
 * Picks the engine for the current setting, falling back to the platform voice
 * whenever the requested engine is unavailable on this platform.
 */
fun resolveTextToSpeech(
    engine: TtsEngine,
    piper: () -> PotsTextToSpeech?,
    native: () -> PotsTextToSpeech?,
): PotsTextToSpeech? = when (engine) {
    TtsEngine.Piper -> piper() ?: native()
    TtsEngine.Native, TtsEngine.Cloud -> native()
}

/**
 * Creates the bundled offline Piper engine for [voiceId], or null on platforms
 * without Piper support or when the voice is not installed.
 */
expect fun createPiperTextToSpeech(voiceId: String): PotsTextToSpeech?
