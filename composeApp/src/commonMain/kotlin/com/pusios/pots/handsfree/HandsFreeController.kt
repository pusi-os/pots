package com.pusios.pots.handsfree

import androidx.compose.runtime.Stable
import com.pusios.pots.ui.dynamicui.toSpeakableText
import com.pusios.pots.ui.tts.sayChunked
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import nl.marc_apps.tts.TextToSpeechInstance
import nl.marc_apps.tts.errors.TextToSpeechSynthesisInterruptedError

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
}

/**
 * Drives the hands-free dialogue: listen → send → wait → speak → listen.
 *
 * The controller owns the *voice* side of the conversation only. Sending the transcript and
 * observing the reply stay with the chat layer, which knows the conversation state.
 *
 * Two design points worth spelling out:
 *
 * * The recognizer and the synthesizer never run at the same time. Running them together
 *   makes the recognizer transcribe the agent's own voice, which would make the agent
 *   interrupt itself. Barge-in is therefore handled by [interrupt], which stops the speech
 *   first and only then opens the microphone.
 * * Every recognition session carries an id. Callbacks of a session that has been superseded
 *   (cancelled because the user interrupted, or because a new session started) are ignored,
 *   so a late "no match" from the old session cannot derail the loop.
 */
@Stable
class HandsFreeController(
    private val scope: CoroutineScope,
    private val textToSpeech: TextToSpeechInstance?,
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

    private var speakJob: Job? = null
    private var spokenMessageId: String? = null
    private var stopAfterReply: Boolean = false
    private var sessionId = 0

    /**
     * Whether replies are read out loud. The loop follows the chat's speech
     * toggle: with it off the microphone still opens, the agent still answers,
     * and the loop simply goes back to listening without speaking.
     */
    private var speechOutputEnabled: Boolean = true

    /** True when the platform can run the loop at all. */
    val isSupported: Boolean get() = speechToText != null && textToSpeech != null

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
    fun start() {
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

    /** Stops the loop, silences the agent and releases the microphone. */
    fun stop() {
        speakJob?.cancel()
        speakJob = null
        sessionId++
        speechToText?.cancel()
        _transcript.value = ""
        _error.value = null
        _status.value = HandsFreeStatus.Idle
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
        recognizer.cancel()
        recognizer.start(
            onPartial = { partial ->
                if (id == sessionId) _transcript.value = partial
            },
            onResult = { text ->
                if (id == sessionId) submit(text)
            },
            onError = { error ->
                if (id != sessionId) return@start
                _error.value = error
                // "No match" is a normal part of a hands-free dialogue — the loop waits for
                // the next attempt instead of dropping into an error state.
                _status.value = if (error == SpeechToTextError.NoMatch) {
                    HandsFreeStatus.Listening
                } else {
                    HandsFreeStatus.Error
                }
                if (error == SpeechToTextError.NoMatch) listen()
            },
        )
    }

    private fun submit(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            _status.value = HandsFreeStatus.Listening
            listen()
            return
        }
        _transcript.value = ""
        _status.value = HandsFreeStatus.Thinking
        onSubmit(trimmed)
    }
}
