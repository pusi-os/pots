// Vault backup dialogs (card 5/5): export the whole vault as an encrypted
// *.pvault file and import one back with merge/replace. Passwords live in
// plain `remember` state — never in `rememberSaveable` — like the lock
// screens. The file chooser itself is the expect/actual pair in
// VaultBackupUi.kt; everything shared lives here.

package com.pusios.pots.ui.vault

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.pusios.pots.data.vault.VaultImportMode
import com.pusios.pots.data.vault.VaultImportResult
import com.pusios.pots.ui.PotsOutlinedTextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.vault_common_cancel
import pots.composeapp.generated.resources.vault_error_corrupted
import pots.composeapp.generated.resources.vault_error_tampered
import pots.composeapp.generated.resources.vault_error_unsupported_version
import pots.composeapp.generated.resources.vault_error_wrong_password
import pots.composeapp.generated.resources.vault_export_confirm
import pots.composeapp.generated.resources.vault_export_done
import pots.composeapp.generated.resources.vault_export_failed
import pots.composeapp.generated.resources.vault_export_hint
import pots.composeapp.generated.resources.vault_export_password
import pots.composeapp.generated.resources.vault_export_title
import pots.composeapp.generated.resources.vault_import_done
import pots.composeapp.generated.resources.vault_import_hint
import pots.composeapp.generated.resources.vault_import_merge
import pots.composeapp.generated.resources.vault_import_merge_hint
import pots.composeapp.generated.resources.vault_import_password
import pots.composeapp.generated.resources.vault_import_replace
import pots.composeapp.generated.resources.vault_import_replace_hint
import pots.composeapp.generated.resources.vault_import_title
import pots.composeapp.generated.resources.vault_password_mismatch
import pots.composeapp.generated.resources.vault_pick_file
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Which backup dialog is open on the list screen, if any. */
internal sealed interface VaultBackupDialog {
    data object Export : VaultBackupDialog

    data object Import : VaultBackupDialog
}

@Composable
internal fun VaultBackupExportDialog(
    model: VaultViewModel,
    onNotice: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val valid = password.isNotEmpty() && password == confirm
    val exportedMessage = stringResource(Res.string.vault_export_done)
    val failedMessage = stringResource(Res.string.vault_export_failed)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.vault_export_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(Res.string.vault_export_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                VaultPasswordField(
                    value = password,
                    onValueChange = { password = it },
                    label = stringResource(Res.string.vault_export_password),
                )
                VaultPasswordField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = stringResource(Res.string.vault_export_confirm),
                )
                if (confirm.isNotEmpty() && password != confirm) {
                    Text(
                        text = stringResource(Res.string.vault_password_mismatch),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            VaultExportPickButton(
                label = stringResource(Res.string.vault_pick_file),
                enabled = valid,
                fileName = backupFileName(),
                produceBytes = {
                    // Null means the session locked meanwhile; the pick buttons
                    // catch the failure and report it through [onDone].
                    model.exportVault(password) ?: error("vault session locked")
                },
                onDone = { written ->
                    if (written) {
                        onNotice(exportedMessage)
                        onDismiss()
                    } else {
                        onNotice(failedMessage)
                    }
                },
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.vault_common_cancel))
            }
        },
    )
}

@Composable
internal fun VaultBackupImportDialog(
    model: VaultViewModel,
    onNotice: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(VaultImportMode.MERGE) }
    var error by remember { mutableStateOf<StringResource?>(null) }
    val importedMessage = stringResource(Res.string.vault_import_done)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.vault_import_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(Res.string.vault_import_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                VaultPasswordField(
                    value = password,
                    onValueChange = { password = it },
                    label = stringResource(Res.string.vault_import_password),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = mode == VaultImportMode.MERGE,
                        onClick = { mode = VaultImportMode.MERGE },
                        label = { Text(stringResource(Res.string.vault_import_merge)) },
                    )
                    FilterChip(
                        selected = mode == VaultImportMode.REPLACE,
                        onClick = { mode = VaultImportMode.REPLACE },
                        label = { Text(stringResource(Res.string.vault_import_replace)) },
                    )
                }
                Text(
                    text = stringResource(
                        if (mode == VaultImportMode.MERGE) {
                            Res.string.vault_import_merge_hint
                        } else {
                            Res.string.vault_import_replace_hint
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let { resource ->
                    Text(
                        text = stringResource(resource),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            VaultImportPickButton(
                label = stringResource(Res.string.vault_pick_file),
                enabled = password.isNotEmpty(),
                onLoaded = { source ->
                    if (source == null) return@VaultImportPickButton
                    scope.launch {
                        error = null
                        val bytes = withContext(Dispatchers.IO) { source.read() }
                        if (bytes == null) {
                            error = Res.string.vault_error_corrupted
                        } else {
                            when (model.importVault(bytes, password, mode)) {
                                is VaultImportResult.Success -> {
                                    onNotice(importedMessage)
                                    onDismiss()
                                }

                                VaultImportResult.WrongPassword -> error = Res.string.vault_error_wrong_password

                                VaultImportResult.Tampered -> error = Res.string.vault_error_tampered

                                VaultImportResult.Corrupted -> error = Res.string.vault_error_corrupted

                                is VaultImportResult.UnsupportedVersion ->
                                    error = Res.string.vault_error_unsupported_version

                                VaultImportResult.Locked -> onDismiss()

                                VaultImportResult.NotSupported -> onDismiss()
                            }
                        }
                    }
                },
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.vault_common_cancel))
            }
        },
    )
}

@OptIn(ExperimentalTime::class)
private fun backupFileName(): String = "pots-vault-${Clock.System.now().toEpochMilliseconds()}.pvault"

@Composable
private fun VaultPasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
) {
    PotsOutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
    )
}
