package com.pusios.pots.ui.settings

import android.content.Context
import com.pusios.pots.speech.sherpa.SherpaSttModels
import com.pusios.pots.speech.vosk.VoskModels
import kotlin.concurrent.thread
import org.koin.java.KoinJavaComponent.inject

private fun contextOrNull(): Context? = try {
    val context: Context by inject(Context::class.java)
    context
} catch (_: Exception) {
    null
}

actual fun sttSettingsSupported(): Boolean = true

actual fun sttModelOptions(): List<SttModelOption> {
    val ctx = contextOrNull() ?: return VoskModels.MODELS.map {
        SttModelOption(it.id, it.label, it.sizeMb, it.assetPath != null, installed = false)
    }
    return VoskModels.MODELS.map { spec ->
        SttModelOption(
            id = spec.id,
            label = spec.label,
            sizeMb = spec.sizeMb,
            bundled = spec.assetPath != null,
            installed = VoskModels.modelPresent(ctx, spec.id),
        )
    }
}

actual fun sttInstallState(): SttInstallState? {
    val id = VoskModels.installingId ?: return null
    return SttInstallState(modelId = id, progress = VoskModels.progress)
}

actual fun deleteSttModel(modelId: String) {
    val ctx = contextOrNull() ?: return
    VoskModels.deleteModel(ctx, modelId)
}

actual fun neuralSttModelOptions(): List<NeuralSttModelOption> {
    val ctx = contextOrNull() ?: return SherpaSttModels.MODELS.map { spec ->
        NeuralSttModelOption(spec.id, spec.sizeMb, spec.punctuated, installed = false)
    }
    return SherpaSttModels.MODELS.map { spec ->
        NeuralSttModelOption(
            id = spec.id,
            sizeMb = spec.sizeMb,
            punctuated = spec.punctuated,
            installed = SherpaSttModels.isInstalled(ctx, spec.id),
        )
    }
}

actual fun neuralSttDownloadState(): NeuralSttDownloadState? {
    val id = SherpaSttModels.installingId ?: return null
    return NeuralSttDownloadState(modelId = id, progress = SherpaSttModels.progress)
}

/**
 * The download runs on its own thread: 214 MB is minutes of network, and the
 * card only polls [neuralSttDownloadState] — nothing here may block the frame.
 */
actual fun downloadNeuralSttModel(modelId: String) {
    val ctx = contextOrNull() ?: return
    thread(name = "sherpa-stt-download", isDaemon = true) {
        SherpaSttModels.download(ctx, modelId)
    }
}

actual fun cancelNeuralSttDownload() = SherpaSttModels.cancel()

actual fun deleteNeuralSttModel(modelId: String) {
    val ctx = contextOrNull() ?: return
    SherpaSttModels.delete(ctx, modelId)
}
