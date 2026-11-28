package com.pusios.pots.handsfree

import com.pusios.pots.data.SttEngine

/**
 * What a recognition session reports back while it runs.
 *
 * The controller needs more than the text: the recording indicator shows the
 * microphone level and the phase, and the inactivity watchdog has to know that
 * the user is still talking. A listener keeps [SpeechToText.start] readable —
 * the alternative was six callbacks in the signature.
 *
 * Exactly one of [onResult] and [onError] ends a session; a session that is
 * cancelled calls neither.
 */
interface SpeechToTextListener {
    /** The running transcript of the session, replaced as recognition improves it. */
    fun onPartial(text: String) = Unit

    /** The final transcript; the session is over. */
    fun onResult(text: String)

    /** Why the session ended without a transcript. */
    fun onError(error: SpeechToTextError)

    /**
     * The current microphone level, normalised to `0..1`. This is the real level
     * of the audio being recognised, not an animation: the wave in the recording
     * indicator is drawn from it.
     */
    fun onLevel(level: Float) = Unit

    /** The engine heard speech begin. Re-arms the controller's watchdog. */
    fun onSpeechStart() = Unit

    /**
     * The engine decided the utterance is over and is finalising it; the result
     * follows in a moment. Lets the indicator freeze its timer instead of
     * looking like it is still listening.
     */
    fun onFinalising() = Unit
}

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
     * [SpeechToTextListener.onPartial] receives the running transcript,
     * [SpeechToTextListener.onResult] the final one. Must be called from the main thread —
     * the Android recognizer requires it.
     */
    fun start(listener: SpeechToTextListener)

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

/**
 * Microphone level (0..1) above which a frame counts as speech.
 *
 * Shared by the engines and the controller on purpose: an engine decides when
 * to finalise an utterance and the controller decides when a session is idle,
 * and both have to agree on what "the user is talking" means.
 */
internal const val SPEECH_LEVEL_THRESHOLD = 0.25f

/** Why a recognition session ended without a transcript. */
enum class SpeechToTextError {
    /** The platform has no speech recognition service, or it is switched off. */
    Unavailable,

    /**
     * The service exists but could not be reached: no network, a timeout, or
     * the server answered with an error. Its own case, not [Unavailable] —
     * «нет распознавания на устройстве» and «сервис недоступен» are two
     * different sentences, and only the second one makes «Повторить» obvious.
     */
    ServiceUnavailable,

    /** The microphone permission is missing. */
    PermissionDenied,

    /** Nothing was recognised in the session. */
    NoMatch,

    /**
     * The recognizer stopped answering: neither a result nor an error arrived
     * within the controller's inactivity watchdog. Its own case, not [Failed],
     * because the user is told «распознавание не отвечает» — a different thing
     * to fix than a session that failed for some other reason.
     */
    Timeout,

    /** The recognizer failed for another reason. */
    Failed,
}

/**
 * Creates the platform's speech recognizer for the selected engine, or null
 * when the platform has none.
 *
 * A null result is not an error: the hands-free button is simply hidden on platforms without
 * a recognizer. [voskModelId] selects the offline Vosk model (Android only).
 */
expect fun createSpeechToText(engine: SttEngine, voskModelId: String): SpeechToText?
