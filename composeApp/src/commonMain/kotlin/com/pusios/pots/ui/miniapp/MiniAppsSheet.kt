@file:OptIn(ExperimentalMaterial3Api::class)

package com.pusios.pots.ui.miniapp

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pusios.pots.data.miniapp.MiniApp
import com.pusios.pots.data.miniapp.MiniAppRepository
import com.pusios.pots.saveFileToDevice
import com.pusios.pots.ui.handCursor
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.mini_app_cancel
import pots.composeapp.generated.resources.mini_app_close
import pots.composeapp.generated.resources.mini_app_delete
import pots.composeapp.generated.resources.mini_app_delete_confirm
import pots.composeapp.generated.resources.mini_app_delete_message
import pots.composeapp.generated.resources.mini_app_delete_title
import pots.composeapp.generated.resources.mini_app_description_label
import pots.composeapp.generated.resources.mini_app_edit
import pots.composeapp.generated.resources.mini_app_edit_metadata_title
import pots.composeapp.generated.resources.mini_app_export
import pots.composeapp.generated.resources.mini_app_export_base
import pots.composeapp.generated.resources.mini_app_export_failed
import pots.composeapp.generated.resources.mini_app_import
import pots.composeapp.generated.resources.mini_app_import_failed
import pots.composeapp.generated.resources.mini_app_import_success
import pots.composeapp.generated.resources.mini_app_library_empty
import pots.composeapp.generated.resources.mini_app_library_title
import pots.composeapp.generated.resources.mini_app_name_label
import pots.composeapp.generated.resources.mini_app_open
import pots.composeapp.generated.resources.mini_app_open_editor
import pots.composeapp.generated.resources.mini_app_restore
import pots.composeapp.generated.resources.mini_app_save
import pots.composeapp.generated.resources.mini_app_tap_to_open
import pots.composeapp.generated.resources.mini_app_toolbar_show
import pots.composeapp.generated.resources.mini_app_version_entry
import pots.composeapp.generated.resources.mini_app_versions

/**
 * The library of saved apps, with its CRUD, export/import and version tree.
 *
 * It is a sheet rather than a screen because it is used from two places — the
 * interactive editor and the chat's glass toolbar — and both want to come back
 * to exactly what they were doing.
 */
@Composable
fun MiniAppsSheet(
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
    onEdit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val repository: MiniAppRepository = koinInject()
    val apps by repository.apps.collectAsStateWithLifecycle()
    val toolbarIds by repository.toolbarIds.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isPreview = LocalInspectionMode.current

    var editing by remember { mutableStateOf<MiniApp?>(null) }
    var deleting by remember { mutableStateOf<MiniApp?>(null) }
    var expandedHistory by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    val importLauncher = if (!isPreview) {
        rememberFilePickerLauncher(type = FileKitType.File(extensions = listOf("json"))) { file ->
            if (file != null) {
                scope.launch {
                    val json = runCatching { file.readBytes().decodeToString() }.getOrNull()
                    val result = json?.let { repository.import(it) }
                    message = if (result?.isSuccess == true) {
                        getString(Res.string.mini_app_import_success)
                    } else {
                        getString(Res.string.mini_app_import_failed)
                    }
                }
            }
        }
    } else {
        null
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(Res.string.mini_app_library_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { importLauncher?.launch() }, modifier = Modifier.handCursor()) {
                    Icon(Icons.Default.Upload, contentDescription = stringResource(Res.string.mini_app_import))
                }
                IconButton(onClick = onDismiss, modifier = Modifier.handCursor()) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(Res.string.mini_app_close))
                }
            }

            message?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            if (apps.isEmpty()) {
                Text(
                    text = stringResource(Res.string.mini_app_library_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 32.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(apps, key = { it.id }) { app ->
                        val pinned = toolbarIds?.contains(app.id) ?: app.visible
                        val versions = remember(app.id, app.updatedAt) { repository.versions(app.id) }
                        MiniAppRow(
                            app = app,
                            pinned = pinned,
                            onTogglePinned = {
                                val current = toolbarIds ?: apps.map { it.id }
                                repository.setToolbarIds(
                                    if (pinned) current - app.id else (current + app.id).distinct(),
                                )
                            },
                            onOpen = {
                                onOpen(app.id)
                                onDismiss()
                            },
                            onEdit = {
                                onEdit(app.id)
                                onDismiss()
                            },
                            onEditMetadata = { editing = app },
                            onExport = {
                                scope.launch {
                                    val json = repository.export(app.id)
                                    if (json == null) {
                                        message = getString(Res.string.mini_app_export_failed)
                                    } else {
                                        saveFileToDevice(
                                            bytes = json.encodeToByteArray(),
                                            baseName = "${getString(Res.string.mini_app_export_base)}-${app.name}",
                                            extension = "json",
                                        )
                                    }
                                }
                            },
                            onDelete = { deleting = app },
                            onShowHistory = { expandedHistory = if (expandedHistory == app.id) null else app.id },
                        )
                        AnimatedVisibility(visible = expandedHistory == app.id) {
                            VersionTree(
                                versions = versions,
                                onRestore = { version ->
                                    repository.restoreVersion(app.id, version)
                                    expandedHistory = null
                                },
                            )
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }

    editing?.let { app ->
        EditMetadataDialog(
            app = app,
            onDismiss = { editing = null },
            onSave = { name, description ->
                repository.updateMetadata(app.id, name, description)
                editing = null
            },
        )
    }

    deleting?.let { app ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(Res.string.mini_app_delete_title)) },
            text = { Text(stringResource(Res.string.mini_app_delete_message, app.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        repository.delete(app.id)
                        deleting = null
                    },
                ) { Text(stringResource(Res.string.mini_app_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(Res.string.mini_app_cancel)) }
            },
        )
    }
}

@Composable
private fun MiniAppRow(
    app: MiniApp,
    pinned: Boolean,
    onTogglePinned: () -> Unit,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onEditMetadata: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onShowHistory: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onOpen,
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(accentColor(app.accent).copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(app.icon.ifBlank { "🧩" }, style = MaterialTheme.typography.titleLarge)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = app.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = app.description.ifBlank { stringResource(Res.string.mini_app_tap_to_open) },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (app.kind == com.pusios.pots.data.miniapp.MiniAppKind.WEB) {
                            "web · ${app.files.size} · ${app.collections.size}"
                        } else {
                            "${app.screens.size} · ${app.collections.size}"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onTogglePinned, modifier = Modifier.handCursor()) {
                    Icon(
                        imageVector = if (pinned) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = stringResource(Res.string.mini_app_toolbar_show),
                        tint = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = onOpen, modifier = Modifier.handCursor()) {
                    Icon(Icons.Default.PlayArrow, contentDescription = stringResource(Res.string.mini_app_open))
                }
                IconButton(onClick = onEdit, modifier = Modifier.handCursor()) {
                    Icon(Icons.Default.Edit, contentDescription = stringResource(Res.string.mini_app_open_editor))
                }
                IconButton(onClick = onEditMetadata, modifier = Modifier.handCursor()) {
                    Icon(Icons.Default.Edit, contentDescription = stringResource(Res.string.mini_app_edit))
                }
                IconButton(onClick = onShowHistory, modifier = Modifier.handCursor()) {
                    Icon(Icons.Default.History, contentDescription = stringResource(Res.string.mini_app_versions))
                }
                IconButton(onClick = onExport, modifier = Modifier.handCursor()) {
                    Icon(Icons.Default.Download, contentDescription = stringResource(Res.string.mini_app_export))
                }
                IconButton(onClick = onDelete, modifier = Modifier.handCursor()) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(Res.string.mini_app_delete))
                }
            }
        }
    }
}

@Composable
private fun VersionTree(
    versions: List<com.pusios.pots.data.miniapp.MiniAppVersion>,
    onRestore: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        versions.forEach { version ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(Res.string.mini_app_version_entry, version.version, version.message),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    version.parentVersion?.let {
                        Text(
                            text = "← v$it",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TextButton(onClick = { onRestore(version.version) }) {
                    Text(stringResource(Res.string.mini_app_restore))
                }
            }
        }
    }
}

@Composable
private fun EditMetadataDialog(
    app: MiniApp,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var name by remember(app.id) { mutableStateOf(app.name) }
    var description by remember(app.id) { mutableStateOf(app.description) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.mini_app_edit_metadata_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(Res.string.mini_app_name_label)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(Res.string.mini_app_description_label)) },
                    minLines = 2,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(name, description) }) {
                Text(stringResource(Res.string.mini_app_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.mini_app_cancel)) }
        },
    )
}

internal fun accentColor(value: String): Color = runCatching {
    Color(value.removePrefix("#").toLong(16) or 0xFF000000)
}.getOrDefault(Color(0xFF7C5CFF))
