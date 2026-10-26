package com.pusios.pots.handsfree

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat

/**
 * Speech recognition for the hands-free dialogue loop, backed by Android's
 * [android.speech.SpeechRecognizer].
 *
 * The recognizer is created lazily on first use and destroyed with the controller. Android
 * delivers every callback on the main thread, which is also where [start] has to be called.
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
            onError(SpeechToTextError.PermissionDenied)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError(SpeechToTextError.Unavailable)
            return
        }

        val instance = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            recognizer = it
        }

        instance.setRecognitionListener(
            object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit

                override fun onBeginningOfSpeech() = Unit

                override fun onRmsChanged(rmsdB: Float) = Unit

                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() {
                    // The final result arrives in onResults; nothing to do here.
                }

                override fun onError(error: Int) {
                    onError(error.toSpeechToTextError())
                }

                override fun onResults(results: Bundle?) {
                    val transcript = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()
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
        } catch (_: Exception) {
            onError(SpeechToTextError.Failed)
        }
    }

    override fun stop() {
        recognizer?.stopListening()
    }

    override fun cancel() {
        recognizer?.cancel()
    }

    /** Releases the underlying recognizer. */
    override fun release() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun hasRecordPermission(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED
}

/** Maps the Android error codes onto the platform-independent ones. */
private fun Int.toSpeechToTextError(): SpeechToTextError = when (this) {
    SpeechRecognizer.ERROR_AUDIO,
    SpeechRecognizer.ERROR_CLIENT,
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
    SpeechRecognizer.ERROR_NETWORK,
    SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
    SpeechRecognizer.ERROR_SERVER,
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
    -> SpeechToTextError.Failed

    SpeechRecognizer.ERROR_NO_MATCH -> SpeechToTextError.NoMatch

    else -> SpeechToTextError.Failed
}
