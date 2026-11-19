package com.pusios.pots.assistant

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * The recognition service the assistant metadata has to name.
 *
 * `android:recognitionService` is required by the metadata parser even though
 * the platform stopped using it in Android 12: leaving it out makes the system
 * reset the assistant on this app (see the AOSP `attrs.xml` note on
 * `VoiceInteractionService.recognitionService`). Pots listens with the
 * hands-free loop's own recognizer, so this service only has to exist and to
 * answer every request with an honest error instead of hanging.
 */
class PotsAssistRecognitionService : RecognitionService() {

    override fun onStartListening(recognizerIntent: Intent?, listener: RecognitionService.Callback?) {
        // ERROR_CLIENT is "the caller did something wrong", the closest thing to
        // "this service is a placeholder" in the recognizer's vocabulary.
        runCatching { listener?.error(SpeechRecognizer.ERROR_CLIENT) }
    }

    override fun onStopListening(listener: RecognitionService.Callback?) = Unit

    override fun onCancel(listener: RecognitionService.Callback?) = Unit
}
