package com.pusios.pots.ui.settings

import com.pusios.pots.data.SttEngine

/**
 * No speech recognizer on this platform — the section stays hidden.
 */
actual fun sttSettingsSupported(): Boolean = false

actual fun sttModelOptions(): List<SttModelOption> = emptyList()

actual fun sttInstallState(): SttInstallState? = null

actual fun deleteSttModel(modelId: String) = Unit

actual fun neuralSttModelOptions(): List<NeuralSttModelOption> = emptyList()

actual fun neuralSttDownloadState(): NeuralSttDownloadState? = null

actual fun downloadNeuralSttModel(modelId: String) = Unit

actual fun cancelNeuralSttDownload() = Unit

actual fun deleteNeuralSttModel(modelId: String) = Unit

actual suspend fun testSttOnMicrophone(
    engine: SttEngine,
    voskModelId: String,
    neuralModelId: String,
): SttMicTestResult = SttMicTestResult.Unsupported
