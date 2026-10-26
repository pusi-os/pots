package com.pusios.pots.handsfree

/**
 * Speech recognition for the hands-free dialogue loop.
 *
 * The interface is deliberately tiny: the controller only needs to start a session and be told
 * what was said. Platform quirks (the Android recognizer's lifecycle, its permission model,
 * its "no match" case) live in the actuals.
 */
interface SpeechToText {
    /** True when the platform can recognize speech right now. */
    val isAvailable: Boolean

    /**
     * Starts a recognition session.
     *
     * [onPartial] receives the running transcript, [onResult] the final one. Exactly one of
     * [onResult] and [onError] is called per session; a session that is cancelled calls
     * neither.
     *
     * Must be called from the main thread — the Android recognizer requires it.
     */
    fun start(
        onPartial: (String) -> Unit = {},
        onResult: (String) -> Unit,
        onError: (SpeechToTextError) -> Unit,
    )

    /** Stops listening and lets the recognizer finalise the current result. */
    fun stop()

    /** Stops listening and discards the session. */
    fun cancel()

    /**
     * Releases the resources the recognizer holds.
     *
     * The default implementation does nothing — only platforms with an operating-system
     * resource (Android's recognizer) have anything to release.
     */
    fun release() = Unit
}

/** Why a recognition session ended without a transcript. */
enum class SpeechToTextError {
    /** The platform has no speech recognition service, or it is switched off. */
    Unavailable,

    /** The microphone permission is missing. */
    PermissionDenied,

    /** Nothing was recognised in the session. */
    NoMatch,

    /** The recognizer failed for another reason. */
    Failed,
}

/**
 * Creates the platform's speech recognizer, or null when the platform has none.
 *
 * A null result is not an error: the hands-free button is simply hidden on platforms without
 * a recognizer.
 */
expect fun createSpeechToText(): SpeechToText?
