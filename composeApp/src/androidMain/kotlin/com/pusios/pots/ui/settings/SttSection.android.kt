package com.pusios.pots.ui.settings

import android.content.Context
import com.pusios.pots.speech.vosk.VoskModels
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
