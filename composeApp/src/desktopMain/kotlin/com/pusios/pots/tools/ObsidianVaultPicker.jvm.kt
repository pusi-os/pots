package com.pusios.pots.tools

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.stringResource
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.settings_obsidian_pick_vault
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

@Composable
actual fun ObsidianVaultPicker(currentUri: String, onPicked: (String?) -> Unit) {
    Button(onClick = {
        runCatching {
            SwingUtilities.invokeAndWait {
                val chooser = JFileChooser().apply {
                    dialogTitle = "Select an Obsidian vault folder"
                    fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                    isAcceptAllFileFilterUsed = false
                }
                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                    chooser.selectedFile?.absolutePath?.let(onPicked)
                }
            }
        }
    }) {
        Text(stringResource(Res.string.settings_obsidian_pick_vault))
    }
}
