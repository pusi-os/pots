// Vault card detail view (card 2/5): fields with a mask, an eye toggle and a
// copy button. Copies are clipboard-marked sensitive and the clipboard is
// wiped after a short delay; values are only in composable state.

package com.pusios.pots.ui.vault

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.dp
import com.pusios.pots.data.vault.VaultField
import com.pusios.pots.data.vault.VaultItem
import com.pusios.pots.ui.clipEntryOfText
import com.pusios.pots.data.vault.isSecret
import com.pusios.pots.data.vault.kind
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.vault_common_cancel
import pots.composeapp.generated.resources.vault_delete_confirm_message
import pots.composeapp.generated.resources.vault_delete_confirm_title
import pots.composeapp.generated.resources.vault_delete_confirm_yes
import pots.composeapp.generated.resources.vault_detail_edit
import pots.composeapp.generated.resources.vault_field_copy
import pots.composeapp.generated.resources.vault_notes_label
import pots.composeapp.generated.resources.vault_tags_label
import pots.composeapp.generated.resources.vault_toggle_hide
import pots.composeapp.generated.resources.vault_toggle_show
import pots.composeapp.generated.resources.vault_url_label
import pots.composeapp.generated.resources.vault_username_label

/** The report's clipboard rule: wipe a copied value ~45 s after the copy. */
private val CLIPBOARD_CLEAR_AFTER = 45.seconds

/** Copy as sensitive and schedule a clipboard wipe; one call per composable. */
@Composable
internal fun rememberVaultCopy(): (String) -> Unit {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    return remember(clipboard, scope) {
        { text ->
            scope.launch {
                clipboard.setClipEntry(clipEntryOfText(text, sensitive = true))
                delay(CLIPBOARD_CLEAR_AFTER)
                clipboard.setClipEntry(clipEntryOfText("", sensitive = true))
            }
        }
    }
}

/** Card view: header, card trio, fields with mask/eye/copy, notes, tags, actions. */
@Composable
internal fun VaultDetailScreen(
    item: VaultItem,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmDelete by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(Res.string.vault_back),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.headlineSmall)
                val kind = item.kind
                if (kind != null) {
                    Text(
                        text = kindLabel(kind),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onEdit) {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = stringResource(Res.string.vault_detail_edit),
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (item.url.isNotEmpty()) {
                VaultValueRow(
                    label = stringResource(Res.string.vault_url_label),
                    value = item.url,
                )
            }
            if (item.username.isNotEmpty()) {
                VaultValueRow(
                    label = stringResource(Res.string.vault_username_label),
                    value = item.username,
                )
            }
            item.fields.forEach { field ->
                VaultFieldRow(field = field)
            }
            if (item.notes.isNotEmpty()) {
                Text(
                    text = stringResource(Res.string.vault_notes_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(item.notes, style = MaterialTheme.typography.bodyMedium)
            }
            if (item.tags.isNotEmpty()) {
                Text(
                    text = stringResource(Res.string.vault_tags_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(item.tags.joinToString(", "), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onEdit) {
                Text(stringResource(Res.string.vault_detail_edit))
            }
            Button(
                onClick = { confirmDelete = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text(stringResource(Res.string.vault_detail_delete))
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(Res.string.vault_delete_confirm_title)) },
            text = { Text(stringResource(Res.string.vault_delete_confirm_message, item.name)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) {
                    Text(stringResource(Res.string.vault_delete_confirm_yes))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(Res.string.vault_common_cancel))
                }
            },
        )
    }
}

/** Top-level card trio row (URL / username): no mask, plain copy. */
@Composable
private fun VaultValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val copy = rememberVaultCopy()
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(value, maxLines = 2)
        }
        IconButton(onClick = { copy(value) }) {
            Icon(
                imageVector = Icons.Outlined.ContentCopy,
                contentDescription = stringResource(Res.string.vault_field_copy),
            )
        }
    }
}

@Composable
private fun VaultFieldRow(
    field: VaultField,
    modifier: Modifier = Modifier,
) {
    var visible by remember(field.key) { mutableStateOf(false) }
    val copy = rememberVaultCopy()

    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = field.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (field.isSecret && !visible) {
                Text(
                    text = if (field.value.isEmpty()) "" else "••••••••",
                    maxLines = 1,
                )
            } else {
                Text(field.value, maxLines = if (field.kind.multiline) 8 else 2)
            }
        }
        if (field.isSecret) {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = stringResource(
                        if (visible) Res.string.vault_toggle_hide else Res.string.vault_toggle_show,
                    ),
                )
            }
        }
        IconButton(onClick = { copy(field.value) }) {
            Icon(
                imageVector = Icons.Outlined.ContentCopy,
                contentDescription = stringResource(Res.string.vault_field_copy),
            )
        }
    }
}
