package com.pusios.pots.ui.chat.composables

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher

@Composable
internal actual fun rememberAttachmentPickerLauncher(
    extensions: List<String>,
    @Suppress("UNUSED_PARAMETER") chooserTitle: String,
    @Suppress("UNUSED_PARAMETER") includeCamera: Boolean,
    onFile: (PlatformFile?) -> Unit,
): AttachmentPickerLauncher {
    val launcher = rememberFilePickerLauncher(
        type = FileKitType.File(extensions = extensions),
    ) { onFile(it) }
    return remember(launcher) { AttachmentPickerLauncher { launcher.launch() } }
}
