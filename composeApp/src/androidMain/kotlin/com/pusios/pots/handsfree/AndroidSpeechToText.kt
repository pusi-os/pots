package com.pusios.pots.handsfree

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Speech recognition for the hands-free dialogue loop, backed by Android's
 * [android.speech.SpeechRecognizer].
 *
 * A **new** recognizer instance is created for every session and the previous
 * one is destroyed first. Keeping one instance across sessions is the bug the
 * owner hit as «первый запрос работает, второй не включает микрофон»:
 * `cancel()` only asks the recognition service to stop, so a `startListening()`
 * issued right after it lands on a service that is still tearing the previous
 * session down and answers `ERROR_RECOGNIZER_BUSY`. A fresh instance costs one
 * binder call and removes the race.
 *
 * Every failure is logged with its numeric code: the mapped enum cannot tell
 * "busy" from "no permission" from "service died", and the device's logcat is
 * the only place that distinction can be read later.
 */
class AndroidSpeechToText(
    private val context: Context,
) : SpeechToText {

    private var recognizer: SpeechRecognizer? = null

    /**
     * True when the device has a speech recognition service.
     *
     * The microphone permission is *not* part of this: the button must stay visible even
     * before the permission was granted, otherwise the user could never ask for it.
     */
    override val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    override fun start(
        onPartial: (String) -> Unit,
        onResult: (String) -> Unit,
        onError: (SpeechToTextError) -> Unit,
    ) {
        if (!hasRecordPermission()) {
            Log.w(TAG, "start refused: RECORD_AUDIO is not granted")
            onError(SpeechToTextError.PermissionDenied)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "start refused: no recognition service on this device")
            onError(SpeechToTextError.Unavailable)
            return
        }

        // The old instance is destroyed before the new one exists: a session
        // that is still running would otherwise answer the next start with
        // ERROR_RECOGNIZER_BUSY.
        release()
        val instance = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = instance

        instance.setRecognitionListener(
            object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    // The one callback that means the microphone really opened:
                    // a session that never reaches it is a session that will
                    // time out, and this line is where that is visible.
                    Log.i(TAG, "onReadyForSpeech")
                }

                override fun onBeginningOfSpeech() = Unit

                override fun onRmsChanged(rmsdB: Float) = Unit

                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() {
                    // The final result arrives in onResults; nothing to do here.
                }

                override fun onError(error: Int) {
                    // The numeric code is the only way to tell "busy" from
                    // "no permission" from "service died" after the fact.
                    Log.w(TAG, "onError code=$error (${error.errorName()})")
                    onError(error.toSpeechToTextError())
                }

                override fun onResults(results: Bundle?) {
                    val transcript = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()
                    Log.i(TAG, "onResults length=${transcript.length}")
                    if (transcript.isEmpty()) {
                        onError(SpeechToTextError.NoMatch)
                    } else {
                        onResult(transcript)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()
                    if (partial.isNotEmpty()) {
                        onPartial(partial)
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            },
        )

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }

        try {
            instance.startListening(intent)
            Log.i(TAG, "startListening")
        } catch (t: Exception) {
            Log.w(TAG, "startListening threw ${t.javaClass.simpleName}", t)
            onError(SpeechToTextError.Failed)
        }
    }

    override fun stop() {
        runCatching { recognizer?.stopListening() }
    }

    override fun cancel() {
        // A dead recognition service throws from cancel() (DeadObjectException,
        // block 8) — the controller must always be able to reach Idle.
        runCatching { recognizer?.cancel() }
    }

    /** Releases the underlying recognizer. */
    override fun release() {
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private fun hasRecordPermission(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    private companion object {
        /** The logcat tag the owner can filter on: `adb logcat -s PotsStt`. */
        const val TAG = "PotsStt"
    }
}

/** Maps the Android error codes onto the platform-independent ones. */
private fun Int.toSpeechToTextError(): SpeechToTextError = when (this) {
    // Its own case, not Failed: «нет доступа к микрофону» is what the pill has
    // to say when the system refuses the recording, not «не расслышал».
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechToTextError.PermissionDenied

    SpeechRecognizer.ERROR_AUDIO,
    SpeechRecognizer.ERROR_CLIENT,
    SpeechRecognizer.ERROR_NETWORK,
    SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
    SpeechRecognizer.ERROR_SERVER,
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
    -> SpeechToTextError.Failed

    SpeechRecognizer.ERROR_NO_MATCH -> SpeechToTextError.NoMatch

    else -> SpeechToTextError.Failed
}

/** The code as a readable name, for the logcat line. */
private fun Int.errorName(): String = when (this) {
    SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
    SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
    SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
    SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
    SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
    else -> "ERROR_UNKNOWN"
}
