package com.pusios.pots.ui.settings

/**
 * No speech recognizer on this platform — the section stays hidden.
 */
actual fun sttSettingsSupported(): Boolean = false

actual fun sttModelOptions(): List<SttModelOption> = emptyList()

actual fun sttInstallState(): SttInstallState? = null

actual fun deleteSttModel(modelId: String) = Unit
