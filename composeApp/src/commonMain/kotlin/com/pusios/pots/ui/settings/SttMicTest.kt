package com.pusios.pots.ui.settings

import com.pusios.pots.data.SttEngine

/** Outcome of the «проверить на микрофоне» recording in the recognition card. */
sealed interface SttMicTestResult {
    /** The engine understood something; [text] is what it heard. */
    data class Recognized(val text: String) : SttMicTestResult

    /** The microphone worked, but the engine heard no words in the three seconds. */
    data object NothingHeard : SttMicTestResult

    /** This platform has no local engine to test. */
    data object Unsupported : SttMicTestResult

    /** The test could not run at all; [reason] says why. */
    data class Failed(val reason: SttMicTestError) : SttMicTestResult
}

/** Why the microphone test could not run. */
enum class SttMicTestError {
    /** No microphone permission: the recording never started. */
    PermissionDenied,

    /** The selected engine's model is not on disk. */
    ModelMissing,

    /** The microphone could not be opened or stopped returning audio. */
    RecordingFailed,
}

/**
 * Records roughly three seconds from the microphone and transcribes it with the
 * engine the dialogue will actually use — the same routing, the same model, the
 * same decode path as [com.pusios.pots.handsfree.createSpeechToText].
 *
 * A test that ran a different engine would prove nothing: the point of the
 * button is «will this work when I speak to Потсик».
 *
 * Blocking (record + decode) — call it from a coroutine on a background
 * dispatcher; the actuals take care of that themselves.
 */
expect suspend fun testSttOnMicrophone(
    engine: SttEngine,
    voskModelId: String,
    neuralModelId: String,
): SttMicTestResult
