package com.pusios.pots.ui.chat.composables

import androidx.compose.runtime.Composable
import io.github.vinceglb.filekit.PlatformFile

/** Opens the platform's attachment source picker. */
internal fun interface AttachmentPickerLauncher {
    fun launch()
}

/**
 * Android opens a native chooser that combines the document picker with the
 * camera app. Other platforms open their document picker; the camera branch is
 * Android-only for now.
 */
@Composable
internal expect fun rememberAttachmentPickerLauncher(
    extensions: List<String>,
    chooserTitle: String,
    includeCamera: Boolean,
    onFile: (PlatformFile?) -> Unit,
): AttachmentPickerLauncher
