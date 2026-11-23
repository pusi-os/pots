// Vault card editor (card 2/5): card trio, tags and a field list where the
// user adds / removes / renames fields and picks their type. New cards pick a
// template; afterwards the layout is free-form (KeePassDX "template = layout").
// The working copy lives in plain `remember` — never `rememberSaveable` — so
// field values cannot land in saved instance state.

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
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.pusios.pots.data.vault.VaultField
import com.pusios.pots.data.vault.VaultFieldKind
import com.pusios.pots.data.vault.VaultItem
import com.pusios.pots.data.vault.VaultItemKind
import com.pusios.pots.data.vault.VaultTemplate
import com.pusios.pots.data.vault.VaultTemplates
import com.pusios.pots.ui.PotsOutlinedTextField
import org.jetbrains.compose.resources.stringResource
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.vault_back
import pots.composeapp.generated.resources.vault_common_cancel
import pots.composeapp.generated.resources.vault_common_save
import pots.composeapp.generated.resources.vault_editor_add_field
import pots.composeapp.generated.resources.vault_editor_edit_title
import pots.composeapp.generated.resources.vault_editor_field_label
import pots.composeapp.generated.resources.vault_editor_name
import pots.composeapp.generated.resources.vault_editor_new_title
import pots.composeapp.generated.resources.vault_editor_notes
import pots.composeapp.generated.resources.vault_editor_remove_field
import pots.composeapp.generated.resources.vault_editor_tags
import pots.composeapp.generated.resources.vault_editor_template
import pots.composeapp.generated.resources.vault_editor_url
import pots.composeapp.generated.resources.vault_editor_username
import pots.composeapp.generated.resources.vault_field_type_multiline
import pots.composeapp.generated.resources.vault_field_type_secret
import pots.composeapp.generated.resources.vault_field_type_secret_multiline
import pots.composeapp.generated.resources.vault_field_type_text
import pots.composeapp.generated.resources.vault_field_type_url
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Editor for an existing card, or a new card when [initial].id is blank. */
@Composable
internal fun VaultItemEditorScreen(
    initial: VaultItem,
    onSave: (VaultItem) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isNew = initial.id.isBlank()
    var name by remember { mutableStateOf(initial.name) }
    var url by remember { mutableStateOf(initial.url) }
    var username by remember { mutableStateOf(initial.username) }
    var notes by remember { mutableStateOf(initial.notes) }
    var tags by remember { mutableStateOf(initial.tags.joinToString(", ")) }
    var templateId by remember { mutableStateOf(initial.type) }
    var fields by remember { mutableStateOf(initial.fields) }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onCancel) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(Res.string.vault_back),
                )
            }
            Text(
                text = stringResource(
                    if (isNew) Res.string.vault_editor_new_title else Res.string.vault_editor_edit_title,
                ),
                style = MaterialTheme.typography.headlineSmall,
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PotsOutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(Res.string.vault_editor_name)) },
                singleLine = true,
            )
            if (isNew) {
                TemplateDropdown(
                    selectedId = templateId,
                    onSelect = { selected ->
                        templateId = selected.id
                        // A template change re-lays the empty card's fields.
                        fields = selected.fields.map {
                            VaultField(key = it.key, label = it.label, type = it.kind.id)
                        }
                    },
                )
            }
            PotsOutlinedTextField(
                value = url,
                onValueChange = { url = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(Res.string.vault_editor_url)) },
                singleLine = true,
            )
            PotsOutlinedTextField(
                value = username,
                onValueChange = { username = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(Res.string.vault_editor_username)) },
                singleLine = true,
            )
            fields.forEachIndexed { index, field ->
                VaultEditorFieldRow(
                    field = field,
                    onChange = { changed ->
                        fields = fields.toMutableList().also { it[index] = changed }
                    },
                    onRemove = {
                        fields = fields.filterIndexed { i, _ -> i != index }
                    },
                )
            }
            TextButton(onClick = {
                fields = fields + VaultField(
                    key = "custom_" + newFieldKey(),
                    label = "",
                    type = VaultFieldKind.TEXT.id,
                )
            }) {
                Text(stringResource(Res.string.vault_editor_add_field))
            }
            PotsOutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(Res.string.vault_editor_notes)) },
                minLines = 1,
                maxLines = 6,
            )
            PotsOutlinedTextField(
                value = tags,
                onValueChange = { tags = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(Res.string.vault_editor_tags)) },
                singleLine = true,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onCancel) {
                Text(stringResource(Res.string.vault_common_cancel))
            }
            Button(
                onClick = {
                    onSave(
                        initial.copy(
                            type = if (isNew) templateId else initial.type,
                            name = name.trim(),
                            url = url.trim(),
                            username = username.trim(),
                            notes = notes,
                            tags = tags.split(',')
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                                .distinct(),
                            fields = fields.map { it.copy(label = it.label.trim()) },
                        ),
                    )
                },
                enabled = name.isNotBlank(),
            ) {
                Text(stringResource(Res.string.vault_common_save))
            }
        }
    }
}

@OptIn(ExperimentalUuidApi::class)
private fun newFieldKey(): String = Uuid.random().toString().take(8)

/** Template picker for new cards; labels come from the kind strings. */
@Composable
private fun TemplateDropdown(
    selectedId: String,
    onSelect: (VaultTemplate) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = VaultTemplates.byId(selectedId) ?: VaultTemplates.login

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(Res.string.vault_editor_template),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = { expanded = true }) {
            Text(templateLabel(selected))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            VaultTemplates.all.forEach { template ->
                DropdownMenuItem(
                    text = { Text(templateLabel(template)) },
                    onClick = {
                        expanded = false
                        onSelect(template)
                    },
                )
            }
        }
    }
}

@Composable
private fun templateLabel(template: VaultTemplate) = when (template.id) {
    VaultItemKind.LOGIN.id -> kindLabel(VaultItemKind.LOGIN)
    VaultItemKind.CARD.id -> kindLabel(VaultItemKind.CARD)
    VaultItemKind.IDENTITY.id -> kindLabel(VaultItemKind.IDENTITY)
    VaultItemKind.NOTE.id -> kindLabel(VaultItemKind.NOTE)
    VaultItemKind.SSH_KEY.id -> kindLabel(VaultItemKind.SSH_KEY)
    VaultItemKind.HOST.id -> kindLabel(VaultItemKind.HOST)
    VaultItemKind.TOKEN.id -> kindLabel(VaultItemKind.TOKEN)
    else -> template.label
}

/** One field row: label, type dropdown, value (masked per type), remove. */
@Composable
private fun VaultEditorFieldRow(
    field: VaultField,
    onChange: (VaultField) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val kind = VaultFieldKind.fromId(field.type) ?: VaultFieldKind.TEXT
    var visible by remember(field.key) { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PotsOutlinedTextField(
                value = field.label,
                onValueChange = { onChange(field.copy(label = it)) },
                modifier = Modifier.weight(1f),
                label = { Text(stringResource(Res.string.vault_editor_field_label)) },
                singleLine = true,
            )
            FieldTypeDropdown(
                selected = kind,
                onSelect = { onChange(field.copy(type = it.id)) },
            )
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = stringResource(Res.string.vault_editor_remove_field),
                )
            }
        }
        PotsOutlinedTextField(
            value = field.value,
            onValueChange = { onChange(field.copy(value = it)) },
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (kind.masked && !visible) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            trailingIcon = if (kind.masked) {
                {
                    IconButton(onClick = { visible = !visible }) {
                        Icon(
                            imageVector = if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = null,
                        )
                    }
                }
            } else {
                null
            },
            singleLine = !kind.multiline,
            minLines = 1,
            maxLines = if (kind.multiline) 6 else 1,
        )
    }
}

@Composable
private fun FieldTypeDropdown(
    selected: VaultFieldKind,
    onSelect: (VaultFieldKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        OutlinedButton(onClick = { expanded = true }) {
            Text(fieldKindLabel(selected))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf(
                VaultFieldKind.TEXT,
                VaultFieldKind.SECRET,
                VaultFieldKind.MULTILINE,
                VaultFieldKind.SECRET_MULTILINE,
                VaultFieldKind.URL,
            ).forEach { kind ->
                DropdownMenuItem(
                    text = { Text(fieldKindLabel(kind)) },
                    onClick = {
                        expanded = false
                        onSelect(kind)
                    },
                )
            }
        }
    }
}

@Composable
private fun fieldKindLabel(kind: VaultFieldKind) = when (kind) {
    VaultFieldKind.TEXT -> stringResource(Res.string.vault_field_type_text)
    VaultFieldKind.SECRET -> stringResource(Res.string.vault_field_type_secret)
    VaultFieldKind.MULTILINE -> stringResource(Res.string.vault_field_type_multiline)
    VaultFieldKind.SECRET_MULTILINE -> stringResource(Res.string.vault_field_type_secret_multiline)
    VaultFieldKind.URL -> stringResource(Res.string.vault_field_type_url)
}
