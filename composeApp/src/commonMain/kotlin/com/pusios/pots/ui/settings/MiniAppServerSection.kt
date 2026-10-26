package com.pusios.pots.ui.settings

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pusios.pots.data.miniapp.MiniApp
import com.pusios.pots.data.miniapp.MiniAppKind
import com.pusios.pots.openUrl
import com.pusios.pots.ui.handCursor
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.mini_app_server_add
import pots.composeapp.generated.resources.mini_app_server_add_record
import pots.composeapp.generated.resources.mini_app_server_apply
import pots.composeapp.generated.resources.mini_app_server_apps
import pots.composeapp.generated.resources.mini_app_server_background
import pots.composeapp.generated.resources.mini_app_server_background_description
import pots.composeapp.generated.resources.mini_app_server_bytes
import pots.composeapp.generated.resources.mini_app_server_clear
import pots.composeapp.generated.resources.mini_app_server_collections
import pots.composeapp.generated.resources.mini_app_server_database
import pots.composeapp.generated.resources.mini_app_server_delete
import pots.composeapp.generated.resources.mini_app_server_description
import pots.composeapp.generated.resources.mini_app_server_enable
import pots.composeapp.generated.resources.mini_app_server_enable_description
import pots.composeapp.generated.resources.mini_app_server_errors
import pots.composeapp.generated.resources.mini_app_server_fields_hint
import pots.composeapp.generated.resources.mini_app_server_file_count
import pots.composeapp.generated.resources.mini_app_server_no_apps
import pots.composeapp.generated.resources.mini_app_server_no_records
import pots.composeapp.generated.resources.mini_app_server_open_app
import pots.composeapp.generated.resources.mini_app_server_open_dashboard
import pots.composeapp.generated.resources.mini_app_server_port
import pots.composeapp.generated.resources.mini_app_server_record_hint
import pots.composeapp.generated.resources.mini_app_server_records
import pots.composeapp.generated.resources.mini_app_server_requests
import pots.composeapp.generated.resources.mini_app_server_restart
import pots.composeapp.generated.resources.mini_app_server_running
import pots.composeapp.generated.resources.mini_app_server_select_app
import pots.composeapp.generated.resources.mini_app_server_stopped
import pots.composeapp.generated.resources.mini_app_server_title
import pots.composeapp.generated.resources.mini_app_server_unavailable
import pots.composeapp.generated.resources.mini_app_server_uptime
import pots.composeapp.generated.resources.mini_app_server_url

@Composable
internal fun MiniAppServerSection(
    viewModel: MiniAppServerViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.onScreenVisible() }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(Res.string.mini_app_server_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(Res.string.mini_app_server_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HorizontalDivider()

                ToggleRow(
                    title = stringResource(Res.string.mini_app_server_enable),
                    description = stringResource(Res.string.mini_app_server_enable_description),
                    checked = state.enabled,
                    onCheckedChange = viewModel::toggleEnabled,
                )
                ToggleRow(
                    title = stringResource(Res.string.mini_app_server_background),
                    description = stringResource(Res.string.mini_app_server_background_description),
                    checked = state.background,
                    onCheckedChange = viewModel::toggleBackground,
                )

                HorizontalDivider()

                OutlinedTextField(
                    value = state.port,
                    onValueChange = viewModel::setPort,
                    label = { Text(stringResource(Res.string.mini_app_server_port)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = viewModel::applyPort,
                        modifier = Modifier.weight(1f).handCursor(),
                    ) {
                        Text(stringResource(Res.string.mini_app_server_apply))
                    }
                    OutlinedButton(
                        onClick = viewModel::restart,
                        modifier = Modifier.weight(1f).handCursor(),
                    ) {
                        Text(stringResource(Res.string.mini_app_server_restart))
                    }
                }

                ServerStatus(state)
            }
        }

        SettingsCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(Res.string.mini_app_server_database),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (state.apps.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.mini_app_server_no_apps),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    DatabaseContent(state, viewModel)
                }
                state.message?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun ServerStatus(state: MiniAppServerUiState) {
    val running = state.server.running
    val container = if (running) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    }
    val content = if (running) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        color = container,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .background(if (running) MaterialTheme.colorScheme.primary else content, CircleShape),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(
                        if (running) Res.string.mini_app_server_running else Res.string.mini_app_server_stopped,
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    color = content,
                    modifier = Modifier.weight(1f),
                )
            }

            Text(
                text = "${stringResource(Res.string.mini_app_server_url)}: ${state.server.url}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = content,
            )

            if (state.enabled && !running) {
                Text(
                    text = state.server.lastError ?: stringResource(Res.string.mini_app_server_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (running) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Counter(stringResource(Res.string.mini_app_server_uptime), formatUptime(state.server.uptimeMs()), Modifier.weight(1f))
                    Counter(stringResource(Res.string.mini_app_server_requests), state.server.requests.toString(), Modifier.weight(1f))
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    Counter(stringResource(Res.string.mini_app_server_bytes), formatBytes(state.server.bytesServed), Modifier.weight(1f))
                    Counter(stringResource(Res.string.mini_app_server_errors), state.server.errors.toString(), Modifier.weight(1f))
                }
                OutlinedButton(
                    onClick = { openUrl("${state.server.url}/") },
                    modifier = Modifier.fillMaxWidth().handCursor(),
                ) {
                    Text(stringResource(Res.string.mini_app_server_open_dashboard))
                }
            }
        }
    }
}

@Composable
private fun DatabaseContent(state: MiniAppServerUiState, viewModel: MiniAppServerViewModel) {
    AppPicker(
        apps = state.apps,
        selectedAppId = state.selectedAppId,
        onSelect = viewModel::selectApp,
    )
    val selected = state.apps.firstOrNull { it.id == state.selectedAppId } ?: return
    Text(
        text = "${selected.icon.ifBlank { "🧩" }} ${selected.name} · " +
            stringResource(Res.string.mini_app_server_file_count, selected.files.size),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (selected.kind == MiniAppKind.WEB && !selected.entryPoint.isNullOrBlank()) {
        OutlinedButton(
            onClick = { openUrl("${state.server.url}/apps/${selected.id}/${selected.entryPoint}") },
            modifier = Modifier.fillMaxWidth().handCursor(),
        ) {
            Text(stringResource(Res.string.mini_app_server_open_app))
        }
    }

    HorizontalDivider()

    Text(
        text = stringResource(Res.string.mini_app_server_collections),
        style = MaterialTheme.typography.titleSmall,
    )
    CollectionPicker(
        collections = state.collections,
        selected = state.selectedCollection,
        onSelect = viewModel::selectCollection,
    )
    AddCollectionRow(
        onAdd = viewModel::addCollection,
        onDelete = viewModel::deleteCollection,
        canDelete = state.selectedCollection != null,
    )

    state.selectedCollection?.let { collection ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${stringResource(Res.string.mini_app_server_records)}: ${state.records.size}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = viewModel::clearCollection, modifier = Modifier.handCursor()) {
                Text(stringResource(Res.string.mini_app_server_clear))
            }
        }
        if (state.records.isEmpty()) {
            Text(
                text = stringResource(Res.string.mini_app_server_no_records),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                state.records.forEach { record ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = record.value.toString(),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = { viewModel.deleteRecord(record.recordId) },
                                modifier = Modifier.handCursor(),
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = stringResource(Res.string.mini_app_server_delete))
                            }
                        }
                    }
                }
            }
        }
        AddRecordField(collection = collection, onAdd = viewModel::addRecord)
    }
}

@Composable
private fun ToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = Modifier.handCursor())
    }
}

@Composable
private fun Counter(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(value, style = MaterialTheme.typography.titleSmall)
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AppPicker(
    apps: List<MiniApp>,
    selectedAppId: String?,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().handCursor()) {
            Text(
                text = apps.firstOrNull { it.id == selectedAppId }?.name
                    ?: stringResource(Res.string.mini_app_server_select_app),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Start,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            apps.forEach { app ->
                DropdownMenuItem(
                    text = { Text("${app.icon.ifBlank { "🧩" }} ${app.name}") },
                    onClick = {
                        expanded = false
                        onSelect(app.id)
                    },
                    modifier = Modifier.handCursor(),
                )
            }
        }
    }
}

@Composable
private fun CollectionPicker(
    collections: List<MiniAppCollectionRow>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().handCursor()) {
            Text(
                text = selected ?: stringResource(Res.string.mini_app_server_collections),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Start,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            collections.forEach { collection ->
                DropdownMenuItem(
                    text = { Text("${collection.name} (${collection.count})") },
                    onClick = {
                        expanded = false
                        onSelect(collection.name)
                    },
                    modifier = Modifier.handCursor(),
                )
            }
        }
    }
}

@Composable
private fun AddCollectionRow(
    onAdd: (String, String) -> Unit,
    onDelete: () -> Unit,
    canDelete: Boolean,
) {
    var name by remember { mutableStateOf("") }
    var fields by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(Res.string.mini_app_server_collections)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = fields,
            onValueChange = { fields = it },
            label = { Text(stringResource(Res.string.mini_app_server_fields_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onAdd(name, fields) }, modifier = Modifier.weight(1f).handCursor()) {
                Text(stringResource(Res.string.mini_app_server_add))
            }
            if (canDelete) {
                OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f).handCursor()) {
                    Text(stringResource(Res.string.mini_app_server_delete))
                }
            }
        }
    }
}

@Composable
private fun AddRecordField(
    collection: String,
    onAdd: (String) -> Unit,
) {
    var json by remember(collection) { mutableStateOf("{}") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = json,
            onValueChange = { json = it },
            label = { Text(stringResource(Res.string.mini_app_server_add_record)) },
            supportingText = { Text(stringResource(Res.string.mini_app_server_record_hint)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )
        Button(onClick = { onAdd(json) }, modifier = Modifier.fillMaxWidth().handCursor()) {
            Text(stringResource(Res.string.mini_app_server_add))
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> "${bytes / (1024L * 1024)} MB"
    bytes >= 1024L -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

private fun formatUptime(ms: Long): String {
    val seconds = ms / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    return when {
        hours > 0 -> "${hours}h ${minutes % 60}m"
        minutes > 0 -> "${minutes}m ${seconds % 60}s"
        else -> "${seconds}s"
    }
}
