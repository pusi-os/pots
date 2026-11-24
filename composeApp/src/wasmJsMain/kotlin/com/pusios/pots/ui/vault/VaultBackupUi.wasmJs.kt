// Vault export/import pickers on web: the vault itself is unavailable in v1
// (JCA-only crypto), so the manager screen never renders these buttons.

package com.pusios.pots.ui.vault

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

@Composable
actual fun VaultExportPickButton(
    label: String,
    enabled: Boolean,
    fileName: String,
    produceBytes: suspend () -> ByteArray,
    onDone: (Boolean) -> Unit,
) {
    TextButton(onClick = {}, enabled = false) {
        Text(label)
    }
}

@Composable
actual fun VaultImportPickButton(
    label: String,
    enabled: Boolean,
    onLoaded: (VaultImportSource?) -> Unit,
) {
    TextButton(onClick = {}, enabled = false) {
        Text(label)
    }
}
