package com.pusios.pots.handsfree

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.pusios.pots.speech.vosk.VoskModels
import org.json.JSONObject
import org.vosk.Recognizer
import kotlin.concurrent.thread

/**
 * Speech recognition for the hands-free dialogue loop, backed by Vosk.
 *
 * Fully offline: the Russian small model ships inside the app, so recognition
 * works in airplane mode. A worker thread owns the microphone and the Vosk
 * recognizer; callbacks are delivered on the main thread, matching the system
 * recognizer.
 *
 * Vosk's own endpointer is not the end of the session. It fires after a short
 * trailing pause (the bundled model is configured for 0.5–2 s) and used to end
 * the utterance — which cut long dictation at the first pause and closed the
 * microphone for the rest of it. Here a fired endpoint only means "this segment
 * is final": its text is appended to the running transcript and the session
 * keeps listening until [END_OF_SPEECH_SILENCE_MS] of real silence, the
 * [MAX_SESSION_MS] cap, or [stop]/[cancel].
 */
class VoskSpeechToText(
    private val context: Context,
    private val modelId: String,
) : SpeechToText {

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var stopRequested = false

    @Volatile
    private var cancelRequested = false

    /**
     * The model is bundled and the native lib ships with the app, so the
     * engine is always offered; a load failure surfaces as a session error.
     * Microphone permission is deliberately not part of this (the button must
     * stay visible so the user can ask for it).
     */
    override val isAvailable: Boolean
        get() = true

    override fun start(listener: SpeechToTextListener) {
        if (!hasRecordPermission()) {
            listener.onError(SpeechToTextError.PermissionDenied)
            return
        }
        // A superseded session may still be winding down; wait it out so two
        // microphones are never open at once.
        worker?.let { previous ->
            cancelRequested = true
            previous.join(SESSION_JOIN_TIMEOUT_MS)
        }
        stopRequested = false
        cancelRequested = false
        worker = thread(name = "vosk-stt", isDaemon = true) {
            runSession(listener)
        }
    }

    override fun stop() {
        stopRequested = true
    }

    override fun cancel() {
        cancelRequested = true
    }

    override fun release() {
        cancelRequested = true
        worker?.join(SESSION_JOIN_TIMEOUT_MS)
        worker = null
    }

    private fun runSession(listener: SpeechToTextListener) {
        val model = VoskModels.ensure(context, modelId)
        if (model == null) {
            if (!cancelRequested) post { listener.onError(SpeechToTextError.Failed) }
            worker = null
            return
        }

        val bufferSize = maxOf(
            AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, CHANNEL, ENCODING),
            READ_BUFFER_BYTES,
        )
        var recorder: AudioRecord? = null
        var recognizer: Recognizer? = null
        try {
            recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE_HZ,
                CHANNEL,
                ENCODING,
                bufferSize,
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                if (!cancelRequested) post { listener.onError(SpeechToTextError.Failed) }
                return
            }
            recognizer = Recognizer(model, SAMPLE_RATE_HZ.toFloat())
            recorder.startRecording()

            var accumulated = ""
            // Counted in bytes rather than wall-clock milliseconds: the read loop
            // already knows how much audio it consumed, and the device clock is
            // not what the user's silence is measured in.
            var sessionBytes = 0L
            var silentBytes = 0L
            var speechStarted = false
            val buffer = ByteArray(READ_BUFFER_BYTES)
            while (!stopRequested && !cancelRequested) {
                val n = recorder.read(buffer, 0, buffer.size)
                if (n < 0) {
                    if (!cancelRequested) post { listener.onError(SpeechToTextError.Failed) }
                    return
                }
                if (n == 0) continue
                sessionBytes += n

                val level = audioLevelOf(buffer, n)
                // The wave is drawn from these values, so they go out as often as
                // audio arrives (once per read, ≈ 64 ms) instead of on a timer.
                post { listener.onLevel(level) }
                if (level >= SPEECH_LEVEL_THRESHOLD) {
                    silentBytes = 0
                    if (!speechStarted) {
                        speechStarted = true
                        post { listener.onSpeechStart() }
                    }
                } else {
                    silentBytes += n
                }

                if (recognizer.acceptWaveForm(buffer, n)) {
                    val segment = textOf(recognizer.result)
                    if (segment.isNotEmpty()) {
                        // Vosk ended a segment. The phrase may continue after the
                        // pause, so the session keeps listening and only the
                        // transcript grows — this is the fix for the phrase that
                        // used to stop at the first pause.
                        accumulated = combine(accumulated, segment)
                        val partial = accumulated
                        post { listener.onPartial(partial) }
                    }
                } else {
                    val partial = combine(accumulated, textOf(recognizer.partialResult))
                    if (partial.isNotEmpty()) {
                        post { listener.onPartial(partial) }
                    }
                }

                val endOfSpeech = speechStarted &&
                    silentBytes >= END_OF_SPEECH_SILENCE_MS * BYTES_PER_MS
                val noSpeech = !speechStarted && silentBytes >= INITIAL_SILENCE_MS * BYTES_PER_MS
                val capped = sessionBytes >= MAX_SESSION_MS * BYTES_PER_MS
                if (endOfSpeech || noSpeech || capped) {
                    finalise(recognizer, accumulated, listener)
                    return
                }
            }

            if (cancelRequested) return
            // stop(): finalise whatever was said so far.
            finalise(recognizer, accumulated, listener)
        } catch (t: Throwable) {
            if (!cancelRequested) post { listener.onError(SpeechToTextError.Failed) }
        } finally {
            try {
                recorder?.stop()
            } catch (_: Throwable) {
            }
            recorder?.release()
            try {
                recognizer?.close()
            } catch (_: Throwable) {
            }
            worker = null
        }
    }

    /**
     * Ends the session: reports the final transcript, or «nothing heard» when
     * there is none. [SpeechToTextListener.onFinalising] goes out first so the
     * indicator freezes before the result arrives.
     */
    private fun finalise(
        recognizer: Recognizer,
        accumulated: String,
        listener: SpeechToTextListener,
    ) {
        post { listener.onFinalising() }
        val result = combine(accumulated, textOf(recognizer.finalResult))
        if (result.isEmpty()) {
            post { listener.onError(SpeechToTextError.NoMatch) }
        } else {
            post { listener.onResult(result) }
        }
    }

    private fun post(block: () -> Unit) {
        mainHandler.post(block)
    }

    private fun hasRecordPermission(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /** 16-bit mono at 16 kHz: 32 bytes per millisecond of audio. */
        const val BYTES_PER_MS = SAMPLE_RATE_HZ * 2 / 1_000

        /**
         * 2 KB ≈ 64 ms of audio: the granularity of one wave bar, and small
         * enough that the end-of-speech check is not a quarter-second late.
         */
        const val READ_BUFFER_BYTES = 2048

        /**
         * Real silence that ends the utterance. Longer than any pause inside a
         * sentence (Vosk's own rules fire at 0.5–2 s, which is why its endpoint
         * cannot be trusted for this) and short enough to feel immediate.
         */
        const val END_OF_SPEECH_SILENCE_MS = 3_000L

        /** Silence before the first word: «ничего не расслышал», then listen again. */
        const val INITIAL_SILENCE_MS = 8_000L

        /** Upper bound on one utterance, so the microphone is not held forever. */
        const val MAX_SESSION_MS = 120_000L

        /** Long enough for one buffer read plus cleanup to finish. */
        const val SESSION_JOIN_TIMEOUT_MS = 1500L
    }
}

/** Extracts the text field from a Vosk result JSON ({"text": ...} / {"partial": ...}). */
private fun textOf(json: String): String = try {
    val obj = JSONObject(json)
    (obj.optString("text") + " " + obj.optString("partial")).trim()
} catch (_: Throwable) {
    ""
}

/**
 * Joins the phrase just decoded to the transcript so far, so a sentence that
 * paused mid-way keeps its earlier words. Shared by both offline engines: they
 * both accumulate phrases across end-of-speech pauses.
 */
internal fun combine(accumulated: String, next: String): String = when {
    next.isEmpty() -> accumulated
    accumulated.isEmpty() -> next
    else -> "$accumulated $next"
}
