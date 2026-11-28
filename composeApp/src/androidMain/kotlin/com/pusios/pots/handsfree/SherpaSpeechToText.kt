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
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.pusios.pots.speech.sherpa.SherpaSttModels
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread

/**
 * Speech recognition for the hands-free dialogue loop, backed by a sherpa-onnx
 * neural model (GigaAM v3 by default).
 *
 * Fully offline once the model is downloaded, and the most accurate of the
 * three engines on Russian — but it is an **offline** recognizer: it transcribes
 * a phrase that has already ended, so there are no word-by-word partials. The
 * session therefore listens exactly like [VoskSpeechToText] does (same
 * end-of-speech rules, same level reporting), keeps the PCM of the current
 * phrase, and decodes it once the phrase is over.
 *
 * The model is not in the APK: [SherpaSttModels] downloads and verifies it on
 * first use, and a missing model is a routing fallback (see [routeSttEngine]),
 * not a crash — a session started without one reports [SpeechToTextError.Failed]
 * instead of opening the microphone.
 */
class SherpaSpeechToText(
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
     * The model is downloadable and the native runtime ships with the app, so
     * the engine is always offered; a missing model or a load failure surfaces
     * as a session error. Microphone permission is deliberately not part of
     * this (the button must stay visible so the user can ask for it).
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
        worker = thread(name = "sherpa-stt", isDaemon = true) {
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
        val recognizer = SherpaSttModels.recognizer(context, modelId)
        if (recognizer == null) {
            if (!cancelRequested) post { listener.onError(SpeechToTextError.Failed) }
            worker = null
            return
        }

        val bufferSize = maxOf(
            AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, CHANNEL, ENCODING),
            READ_BUFFER_BYTES,
        )
        var recorder: AudioRecord? = null
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
            recorder.startRecording()

            var accumulated = ""
            // The PCM of the phrase being spoken right now: an offline model
            // needs the whole phrase, so it is kept until the phrase ends.
            val phrase = ByteArrayOutputStream()
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
                phrase.write(buffer, 0, n)

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

                val endOfSpeech = speechStarted && silentBytes >= END_OF_SPEECH_SILENCE_MS * BYTES_PER_MS
                val noSpeech = !speechStarted && silentBytes >= INITIAL_SILENCE_MS * BYTES_PER_MS
                val capped = sessionBytes >= MAX_SESSION_MS * BYTES_PER_MS
                if (endOfSpeech || capped) {
                    // The phrase is over: decode it, grow the transcript and
                    // keep listening — the sentence may go on after the pause.
                    val segment = decodeSegment(recognizer, phrase)
                    if (segment.isNotEmpty()) {
                        accumulated = combine(accumulated, segment)
                        val partial = accumulated
                        post { listener.onPartial(partial) }
                    }
                    silentBytes = 0
                    speechStarted = false
                }
                if (noSpeech) {
                    post { listener.onFinalising() }
                    post { listener.onError(SpeechToTextError.NoMatch) }
                    return
                }
                if (capped) {
                    finalise(recognizer, phrase, accumulated, listener)
                    return
                }
            }

            if (cancelRequested) return
            // stop(): decode whatever is still buffered and report the transcript.
            finalise(recognizer, phrase, accumulated, listener)
        } catch (t: Throwable) {
            if (!cancelRequested) post { listener.onError(SpeechToTextError.Failed) }
        } finally {
            try {
                recorder?.stop()
            } catch (_: Throwable) {
            }
            recorder?.release()
            worker = null
        }
    }

    /** Decodes and empties the buffered phrase; a decode failure is not a session failure. */
    private fun decodeSegment(recognizer: OfflineRecognizer, phrase: ByteArrayOutputStream): String {
        val bytes = phrase.toByteArray()
        phrase.reset()
        if (bytes.isEmpty()) return ""
        return runCatching { SherpaSttModels.transcribe(recognizer, pcm16ToFloats(bytes)) }.getOrDefault("")
    }

    /**
     * Ends the session: reports the final transcript, or «nothing heard» when
     * there is none. [SpeechToTextListener.onFinalising] goes out first so the
     * indicator freezes before the result arrives.
     */
    private fun finalise(
        recognizer: OfflineRecognizer,
        phrase: ByteArrayOutputStream,
        accumulated: String,
        listener: SpeechToTextListener,
    ) {
        post { listener.onFinalising() }
        val result = combine(accumulated, decodeSegment(recognizer, phrase))
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

        /** Real silence that ends the phrase — the same rule Vosk runs under. */
        const val END_OF_SPEECH_SILENCE_MS = 3_000L

        /** Silence before the first word: «ничего не расслышал», then listen again. */
        const val INITIAL_SILENCE_MS = 8_000L

        /** Upper bound on one session, so the microphone is not held forever. */
        const val MAX_SESSION_MS = 120_000L

        /** Long enough for one buffer read plus cleanup to finish. */
        const val SESSION_JOIN_TIMEOUT_MS = 1500L
    }
}
