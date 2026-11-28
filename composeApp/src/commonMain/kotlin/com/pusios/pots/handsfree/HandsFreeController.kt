package com.pusios.pots.handsfree

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import com.pusios.pots.ui.dynamicui.toSpeakableText
import com.pusios.pots.ui.tts.PotsTextToSpeech
import com.pusios.pots.ui.tts.sayChunked
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import nl.marc_apps.tts.errors.TextToSpeechSynthesisInterruptedError
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** What the hands-free loop is currently doing. */
enum class HandsFreeStatus {
    /** The mode is switched off. */
    Idle,

    /** Waiting for the user to say something. */
    Listening,

    /** The transcript was sent; the agent is working on it. */
    Thinking,

    /** The agent's reply is being spoken. */
    Speaking,

    /** The loop is paused after an error; it can be resumed with the button. */
    Error,

    /**
     * Nothing was recognised. Shown for a moment, then the loop listens again
     * on its own: a missed phrase is part of a dialogue, not a failure. A status
     * of its own rather than [Error], because the chat pauses the loop on Error.
     */
    NoMatch,
}

/**
 * The phase the recording indicator shows. Finer-grained than [HandsFreeStatus]:
 * the loop is [HandsFreeStatus.Listening] for the whole session, while the strip
 * has to say whether speech is being heard and whether the engine is finalising.
 */
enum class RecordingPhase {
    /** The microphone is open, nothing above the speech threshold yet. */
    Listening,

    /** Audio above the speech threshold is arriving, or a partial transcript is in. */
    SpeechDetected,

    /** The engine decided the utterance ended and is producing the final text. */
    Finalising,

    /** The transcript is being handed to the chat. */
    Sending,

    /** The session failed; [HandsFreeRecording.error] says why. */
    Error,
}

/**
 * One snapshot of the recording indicator.
 *
 * [startedAt] is a monotonic mark rather than a millisecond value: the LED timer
 * counts from it, so it must be immune to wall-clock jumps.
 */
@Immutable
data class HandsFreeRecording(
    val phase: RecordingPhase,
    val level: Float,
    val startedAt: TimeMark,
    val error: SpeechToTextError? = null,
)

/**
 * Drives the hands-free dialogue: listen → send → wait → speak → listen.
 *
 * The controller owns the *voice* side of the conversation only. Sending the transcript and
 * observing the reply stay with the chat layer, which knows the conversation state.
 *
 * Three design points worth spelling out:
 *
 * * The recognizer and the synthesizer never run at the same time. Running them together
 *   makes the recognizer transcribe the agent's own voice, which would make the agent
 *   interrupt itself. Barge-in is therefore handled by [interrupt], which stops the speech
 *   first and only then opens the microphone.
 * * Every recognition session carries an id. Callbacks of a session that has been superseded
 *   (cancelled because the user interrupted, or because a new session started) are ignored,
 *   so a late "no match" from the old session cannot derail the loop.
 * * The watchdog measures *inactivity*, not phrase length. A healthy session reports partial
 *   results and microphone levels while the user talks, and every such report re-arms it; a
 *   dead service reports nothing and is failed after [LISTEN_TIMEOUT_MS]. A phrase of any
 *   length is recognised in full.
 */
@Stable
class HandsFreeController(
    private val scope: CoroutineScope,
    private val textToSpeech: PotsTextToSpeech?,
    private val speechToText: SpeechToText?,
    private val onSubmit: (String) -> Unit,
    /**
     * Asks for the microphone permission, returning whether it was granted.
     *
     * The default implementation assumes the permission is already there, which is the case
     * on platforms without runtime permissions.
     */
    private val requestMicrophonePermission: suspend () -> Boolean = { true },
) {
    private val _status = MutableStateFlow(HandsFreeStatus.Idle)
    val status: StateFlow<HandsFreeStatus> = _status.asStateFlow()

    private val _transcript = MutableStateFlow("")
    val transcript: StateFlow<String> = _transcript.asStateFlow()

    private val _error = MutableStateFlow<SpeechToTextError?>(null)
    val error: StateFlow<SpeechToTextError?> = _error.asStateFlow()

    private val _recording = MutableStateFlow<HandsFreeRecording?>(null)

    /**
     * What the recording indicator shows, or null when there is no session to
     * show. In [RecordingPhase.Error] it stays non-null on purpose: the reason
     * and the retry have to remain on screen until the user acts.
     */
    val recording: StateFlow<HandsFreeRecording?> = _recording.asStateFlow()

    private var speakJob: Job? = null
    private var listenWatchdog: Job? = null
    private var spokenMessageId: String? = null
    private var stopAfterReply: Boolean = false
    private var sessionId = 0

    /**
     * Counter of "the session is alive" reports (partials, speech onsets, loud
     * audio frames). The watchdog compares it across ticks instead of reading a
     * clock, so it works under virtual time in tests.
     */
    private var activityCount = 0L

    /**
     * Text that was in the composer when the current session started.
     *
     * The composer is the source of truth: the indicator writes `draft + transcript`
     * into it, and [submit] sends exactly that, so a message typed before dictating
     * is not replaced by what was said. The composer sets this when a session starts
     * and reads it back for «Отменить и начать заново».
     */
    var dictationDraft: String = ""

    companion object {
        /**
         * How long a recognition session may stay inactive before it counts as
         * hung. A healthy system service reports a result or an error within
         * seconds; a dead one never calls back at all and the loop would sit
         * in Listening forever with a closed microphone (block 8, confirmed
         * by device logs).
         */
        internal const val LISTEN_TIMEOUT_MS = 15_000L

        /** How often the inactivity watchdog looks at [activityCount]. */
        internal const val WATCHDOG_TICK_MS = 1_000L

        /**
         * How long «Не расслышал, повторите» stays up before the microphone
         * reopens by itself (the spec's two seconds).
         */
        internal const val NO_MATCH_NOTICE_MS = 2_000L
    }

    /**
     * Whether replies are read out loud. The loop follows the chat's speech
     * toggle: with it off the microphone still opens, the agent still answers,
     * and the loop simply goes back to listening without speaking.
     */
    private var speechOutputEnabled: Boolean = true

    /**
     * True when the platform can run the loop at all.
     *
     * A synthesizer is not required: with one the reply is also read out
     * loud, without one the loop still listens and answers and the reply
     * stays on screen (the overlay pill relies on exactly this).
     */
    val isSupported: Boolean get() = speechToText != null

    /** True while the loop is running. */
    val isActive: Boolean get() = _status.value != HandsFreeStatus.Idle

    /** Switches the mode on, or off when it is already running. */
    fun toggle() {
        if (isActive) stop() else start()
    }

    /**
     * Starts the loop by opening the microphone.
     *
     * The microphone permission is requested first — on Android the recognizer is useless
     * without it, and asking at the moment the user opts in is the least surprising moment.
     */
    fun start() = resume()

    /** Stops the loop, silences the agent and releases the microphone. */
    fun stop() {
        speakJob?.cancel()
        speakJob = null
        stopSession()
        // The transcript is deliberately kept: the recognised text is already in
        // the composer, and the next session resets the flow anyway.
        _error.value = null
        _recording.value = null
        _status.value = HandsFreeStatus.Idle
    }

    /**
     * Ends the session and keeps what was recognised, without sending it.
     *
     * «Остановить и вставить текст в поле»: the transcript is already in the
     * composer, so the loop only has to let go of the microphone. Nothing is
     * dropped and [onSubmit] is not called.
     */
    fun finishListening() {
        if (!isActive) return
        stopSession()
        _error.value = null
        _recording.value = null
        _status.value = HandsFreeStatus.Idle
    }

    /**
     * Drops this session's transcript and opens a new session at once.
     *
     * «Отменить и начать заново»: the caller restores the composer to
     * [dictationDraft] first, so only what was said in this session is lost.
     */
    fun restartListening() {
        if (!isSupported) return
        stopSession()
        _error.value = null
        _status.value = HandsFreeStatus.Listening
        listen()
    }

    /**
     * Ends the session and sends [text] immediately.
     *
     * [text] is the whole composer text (draft included), so it is submitted
     * as is — unlike a recognised result, which is appended to [dictationDraft].
     */
    fun sendNow(text: String) {
        if (!isActive) return
        stopSession()
        submit(text, appendDraft = false)
    }

    /**
     * Asks the loop to stop after the reply currently being spoken finishes.
     *
     * Used when the user says they are done: the last reply is still read out,
     * and then the loop goes idle instead of listening again.
     */
    fun requestStopAfterCurrentReply() {
        stopAfterReply = true
    }

    /**
     * Interrupts the agent mid-sentence and goes back to listening.
     *
     * This is the barge-in: the reply is cut off at the word and the microphone opens, so the
     * user can answer the agent instead of waiting for it to finish.
     */
    fun interrupt() {
        if (!isActive) return
        textToSpeech?.stop()
        speakJob?.cancel()
        speakJob = null
        _status.value = HandsFreeStatus.Listening
        listen()
    }

    /**
     * Cuts the answer short and opens the microphone.
     *
     * A fresh assistant invocation during speech is a new question, not a
     * request to finish the sentence. Unlike [interrupt] this starts the loop
     * from any state, and unlike [start] it does not go through the permission
     * round-trip — the loop was already running, so the microphone was already
     * granted. Speech stops before the recognizer starts, so the "never both at
     * once" invariant holds; [listen] bumps the session id, so the cancelled
     * answer's callbacks are ignored.
     *
     * [stop] is deliberately not called first: it drops to `Idle`, after which
     * [start] asks for the permission again — an extra round-trip on every
     * invocation.
     */
    fun interruptAndListen() {
        if (!isSupported) return
        speakJob?.cancel()
        speakJob = null
        textToSpeech?.stop()
        stopSession()
        _error.value = null
        _status.value = HandsFreeStatus.Listening
        listen()
    }

    /** Resumes the loop after an error. */
    fun resume() {
        if (!isSupported) return
        scope.launch {
            if (!requestMicrophonePermission()) {
                _error.value = SpeechToTextError.PermissionDenied
                _status.value = HandsFreeStatus.Error
                return@launch
            }
            _error.value = null
            textToSpeech?.stop()
            _status.value = HandsFreeStatus.Listening
            listen()
        }
    }

    /**
     * Speaks a new assistant reply and returns to listening.
     *
     * Called for every assistant message that arrives while the loop is running. Messages
     * that were already spoken are skipped, so a re-render (or a tool update that leaves the
     * last assistant message in place) does not make the agent repeat itself.
     */
    fun onAssistantReply(messageId: String, content: String) {
        if (!isActive || messageId == spokenMessageId) return
        spokenMessageId = messageId
        if (!speechOutputEnabled) {
            // Silent hands-free: the reply is on screen, the loop keeps listening.
            if (_status.value == HandsFreeStatus.Thinking) {
                val shouldStop = stopAfterReply
                stopAfterReply = false
                if (shouldStop) {
                    stop()
                } else {
                    _status.value = HandsFreeStatus.Listening
                    listen()
                }
            }
            return
        }
        _status.value = HandsFreeStatus.Speaking
        speakJob?.cancel()
        speakJob = scope.launch {
            try {
                textToSpeech?.sayChunked(content.toSpeakableText(), true, true)
            } catch (_: TextToSpeechSynthesisInterruptedError) {
                // Interrupted on purpose — the barge-in path takes over from here.
            } catch (_: Exception) {
                // A speech failure must not break the dialogue loop.
            }
            // Only continue when the agent was not interrupted meanwhile.
            if (_status.value == HandsFreeStatus.Speaking) {
                val shouldStop = stopAfterReply
                stopAfterReply = false
                if (shouldStop) {
                    stop()
                } else {
                    _status.value = HandsFreeStatus.Listening
                    listen()
                }
            }
        }
    }

    /**
     * Follows the chat's speech toggle. Turning it off mid-sentence cuts the
     * speech and reopens the microphone, because the user asked for silence,
     * not for a half-spoken reply.
     */
    fun setSpeechOutputEnabled(enabled: Boolean) {
        if (speechOutputEnabled == enabled) return
        speechOutputEnabled = enabled
        if (!enabled && _status.value == HandsFreeStatus.Speaking) {
            textToSpeech?.stop()
            speakJob?.cancel()
            speakJob = null
            _status.value = HandsFreeStatus.Listening
            listen()
        }
    }

    private fun listen() {
        val recognizer = speechToText ?: return
        // A new id invalidates the callbacks of the session that is being replaced.
        val id = ++sessionId
        // Best-effort: `cancel()` is asynchronous and does not guarantee the
        // service has stopped by the time `start()` is called. The Android
        // recognizer therefore creates a fresh instance per session instead of
        // relying on this call (see AndroidSpeechToText.start).
        runCatching { recognizer.cancel() }
        // Each session has its own transcript: the previous text is already in
        // the composer, and appending to a stale flow would duplicate it.
        _transcript.value = ""
        _recording.value = HandsFreeRecording(
            phase = RecordingPhase.Listening,
            level = 0f,
            startedAt = TimeSource.Monotonic.markNow(),
        )
        startListenWatchdog(id)
        recognizer.start(
            object : SpeechToTextListener {
                override fun onPartial(text: String) {
                    if (id != sessionId) return
                    noteActivity()
                    _transcript.value = text
                    updateRecording { it.copy(phase = RecordingPhase.SpeechDetected) }
                }

                override fun onResult(text: String) {
                    if (id != sessionId) return
                    submit(text, appendDraft = true)
                }

                override fun onError(error: SpeechToTextError) {
                    if (id != sessionId) return
                    failSession(error)
                }

                override fun onLevel(level: Float) {
                    if (id != sessionId) return
                    val clamped = level.coerceIn(0f, 1f)
                    val speaking = clamped >= SPEECH_LEVEL_THRESHOLD
                    if (speaking) noteActivity()
                    updateRecording { current ->
                        current.copy(
                            level = clamped,
                            phase = if (speaking) RecordingPhase.SpeechDetected else current.phase,
                        )
                    }
                }

                override fun onSpeechStart() {
                    if (id != sessionId) return
                    noteActivity()
                    updateRecording { it.copy(phase = RecordingPhase.SpeechDetected) }
                }

                override fun onFinalising() {
                    if (id != sessionId) return
                    noteActivity()
                    updateRecording { it.copy(phase = RecordingPhase.Finalising, level = 0f) }
                }
            },
        )
    }

    /**
     * Fails a session visibly, keeping what was already recognised.
     *
     * The text is not cleared: it is in the composer, and an error is not a
     * reason to throw away what the user said. «No match» gets its own status
     * so the loop reopens the microphone by itself after the notice; every
     * other reason pauses the loop until the user retries.
     */
    private fun failSession(error: SpeechToTextError) {
        listenWatchdog?.cancel()
        listenWatchdog = null
        _error.value = error
        updateRecording { it.copy(phase = RecordingPhase.Error, level = 0f, error = error) }
        if (error == SpeechToTextError.NoMatch) {
            _status.value = HandsFreeStatus.NoMatch
            val id = sessionId
            scope.launch {
                delay(NO_MATCH_NOTICE_MS)
                if (id == sessionId) {
                    _status.value = HandsFreeStatus.Listening
                    listen()
                }
            }
        } else {
            _status.value = HandsFreeStatus.Error
        }
    }

    /**
     * Fails a session that stopped reporting (block 8): when neither a result
     * nor an error arrives for [LISTEN_TIMEOUT_MS] of inactivity, the hung
     * session is cancelled and the loop drops to Error with a retry, instead of
     * pretending to listen. A session in which the user keeps talking reports
     * partials and levels, which re-arm this loop — a long phrase is never cut.
     */
    private fun startListenWatchdog(id: Int) {
        listenWatchdog?.cancel()
        listenWatchdog = scope.launch {
            var seenActivity = activityCount
            var quietMs = 0L
            while (true) {
                delay(WATCHDOG_TICK_MS)
                if (id != sessionId) return@launch
                if (activityCount != seenActivity) {
                    seenActivity = activityCount
                    quietMs = 0
                } else {
                    quietMs += WATCHDOG_TICK_MS
                }
                if (quietMs < LISTEN_TIMEOUT_MS) continue
                // A session that already moved on (the agent started talking)
                // is not ours to fail.
                if (_status.value != HandsFreeStatus.Listening) return@launch
                runCatching { speechToText?.cancel() }
                failSession(SpeechToTextError.Timeout)
                return@launch
            }
        }
    }

    private fun stopSession() {
        listenWatchdog?.cancel()
        listenWatchdog = null
        sessionId++
        // A dead recognition service throws from cancel() (block 8: the mode
        // could not be switched off) — reaching Idle must not depend on it.
        runCatching { speechToText?.cancel() }
    }

    private fun noteActivity() {
        activityCount++
    }

    private inline fun updateRecording(block: (HandsFreeRecording) -> HandsFreeRecording) {
        val current = _recording.value ?: return
        _recording.value = block(current)
    }

    /**
     * Hands the recognised text to the chat.
     *
     * [appendDraft] is false for [sendNow], whose argument is already the whole
     * composer text; a recognised result is appended to the draft instead.
     */
    private fun submit(text: String, appendDraft: Boolean) {
        listenWatchdog?.cancel()
        listenWatchdog = null
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            // Nothing to send: keep listening instead of ending the mode.
            _status.value = HandsFreeStatus.Listening
            listen()
            return
        }
        val message = if (appendDraft) joinDictation(dictationDraft, trimmed) else trimmed
        updateRecording { it.copy(phase = RecordingPhase.Sending, level = 0f) }
        _transcript.value = ""
        _status.value = HandsFreeStatus.Thinking
        onSubmit(message)
        _recording.value = null
    }
}

/**
 * Joins the text that was in the composer before dictation with what was said,
 * without gluing words together or adding a space to an empty draft.
 */
internal fun joinDictation(draft: String, spoken: String): String {
    val said = spoken.trim()
    return when {
        draft.isBlank() -> said
        said.isEmpty() -> draft
        draft.last().isWhitespace() -> draft + said
        else -> "$draft $said"
    }
}

/**
 * One write of a running dictation into the composer.
 *
 * [text] is the whole field to show, [tail] the stretch of it that the next
 * partial replaces, and [draft] the field with the dictation taken out — that
 * is, what the user typed.
 *
 * [draft] is returned instead of being left for the caller to subtract:
 * [submit] joins the final transcript to it, and a caller that recomputed it
 * from [text] got it wrong once, which sent every dictated phrase twice
 * whenever the composer already held typed text.
 */
internal data class DictationUpdate(
    val text: String,
    val tail: String,
    val draft: String,
)

/**
 * Writes the running transcript into the composer without eating what the user
 * typed.
 *
 * [tail] is the part of [field] the previous update wrote, so a new transcript
 * replaces exactly that stretch and leaves everything else alone — text typed
 * during the session survives, wherever the cursor was.
 */
internal fun applyDictation(field: String, tail: String, spoken: String): DictationUpdate {
    val index = if (tail.isEmpty()) -1 else field.lastIndexOf(tail)
    val manual = if (index >= 0) field.removeRange(index, index + tail.length) else field
    val written = joinDictation(manual, spoken)
    return DictationUpdate(
        text = written,
        tail = written.removePrefix(manual),
        draft = manual,
    )
}
