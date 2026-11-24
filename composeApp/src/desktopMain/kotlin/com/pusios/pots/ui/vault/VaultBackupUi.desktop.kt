// Vault export/import file pickers on desktop: Swing save/open dialogs.
// The heavy sealing (PBKDF2) runs in a coroutine after the dialog closes.

package com.pusios.pots.ui.vault

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
actual fun VaultExportPickButton(
    label: String,
    enabled: Boolean,
    fileName: String,
    produceBytes: suspend () -> ByteArray,
    onDone: (Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    TextButton(
        onClick = {
            var picked: File? = null
            SwingUtilities.invokeAndWait {
                val chooser = JFileChooser().apply {
                    dialogTitle = "Export vault"
                    selectedFile = File(fileName)
                    isAcceptAllFileFilterUsed = false
                }
                if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                    picked = chooser.selectedFile
                }
            }
            val file = picked
            if (file == null) {
                onDone(false)
            } else {
                scope.launch(Dispatchers.IO) {
                    val bytes = runCatching { produceBytes() }.getOrNull()
                    val written = bytes != null && runCatching {
                        file.writeBytes(bytes)
                        true
                    }.getOrDefault(false)
                    onDone(written)
                }
            }
        },
        enabled = enabled,
    ) {
        Text(label)
    }
}

@Composable
actual fun VaultImportPickButton(
    label: String,
    enabled: Boolean,
    onLoaded: (VaultImportSource?) -> Unit,
) {
    TextButton(
        onClick = {
            var picked: File? = null
            SwingUtilities.invokeAndWait {
                val chooser = JFileChooser().apply {
                    dialogTitle = "Import vault"
                    fileFilter = FileNameExtensionFilter("Pots vault (*.pvault)", "pvault")
                    isAcceptAllFileFilterUsed = true
                }
                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                    picked = chooser.selectedFile
                }
            }
            onLoaded(
                picked?.let { path ->
                    object : VaultImportSource {
                        override fun read(): ByteArray? = runCatching {
                            path.takeIf(File::isFile)?.readBytes()
                        }.getOrNull()
                    }
                },
            )
        },
        enabled = enabled,
    ) {
        Text(label)
    }
}
