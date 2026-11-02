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
 * recognizer. Recognition ends when Vosk detects the end of an utterance
 * (endpointing), when [stop] is called, or when the session is [cancel]led.
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

    override fun start(
        onPartial: (String) -> Unit,
        onResult: (String) -> Unit,
        onError: (SpeechToTextError) -> Unit,
    ) {
        if (!hasRecordPermission()) {
            onError(SpeechToTextError.PermissionDenied)
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
            runSession(onPartial, onResult, onError)
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

    private fun runSession(
        onPartial: (String) -> Unit,
        onResult: (String) -> Unit,
        onError: (SpeechToTextError) -> Unit,
    ) {
        val model = VoskModels.ensure(context, modelId)
        if (model == null) {
            if (!cancelRequested) post { onError(SpeechToTextError.Failed) }
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
                if (!cancelRequested) post { onError(SpeechToTextError.Failed) }
                return
            }
            recognizer = Recognizer(model, SAMPLE_RATE_HZ.toFloat())
            recorder.startRecording()

            var accumulated = ""
            val buffer = ByteArray(READ_BUFFER_BYTES)
            while (!stopRequested && !cancelRequested) {
                val n = recorder.read(buffer, 0, buffer.size)
                if (n < 0) {
                    if (!cancelRequested) post { onError(SpeechToTextError.Failed) }
                    return
                }
                if (n == 0) continue
                if (recognizer.acceptWaveForm(buffer, n)) {
                    val segment = textOf(recognizer.result)
                    if (segment.isNotEmpty()) {
                        // Endpoint reached after speech: the utterance is over.
                        accumulated = combine(accumulated, segment)
                        val result = accumulated
                        if (!cancelRequested) post { onResult(result) }
                        return
                    }
                } else {
                    val partial = combine(accumulated, textOf(recognizer.partialResult))
                    if (partial.isNotEmpty()) {
                        post { onPartial(partial) }
                    }
                }
            }

            if (cancelRequested) return
            // stop(): finalise whatever was said so far.
            val result = combine(accumulated, textOf(recognizer.finalResult))
            if (result.isEmpty()) {
                post { onError(SpeechToTextError.NoMatch) }
            } else {
                post { onResult(result) }
            }
        } catch (t: Throwable) {
            if (!cancelRequested) post { onError(SpeechToTextError.Failed) }
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

        /** 8 KB ≈ 256 ms of 16 kHz mono 16-bit audio — one recognition step. */
        const val READ_BUFFER_BYTES = 8192

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

private fun combine(accumulated: String, next: String): String = when {
    next.isEmpty() -> accumulated
    accumulated.isEmpty() -> next
    else -> "$accumulated $next"
}
