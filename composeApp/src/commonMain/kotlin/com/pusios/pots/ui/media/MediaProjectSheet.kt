package com.pusios.pots.ui.media

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pusios.pots.media.MediaProjectMeta
import com.pusios.pots.ui.handCursor
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * The project sheet (spec §12): save the current state, open a named
 * project, export/import `.potsproj` files, delete. Every stored project
 * is local — no cloud sync in v1.
 */
@Composable
internal fun MediaProjectSheet(
    projects: List<MediaProjectMeta>,
    onSaveNow: () -> Unit,
    onImport: () -> Unit,
    onOpen: (String) -> Unit,
    onExport: (String) -> Unit,
    onDelete: (String) -> Unit,
    onExportCurrent: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Проекты", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss, modifier = Modifier.handCursor()) {
                    Icon(Icons.Filled.Close, contentDescription = "Закрыть")
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSaveNow, modifier = Modifier.handCursor()) {
                    Text("Сохранить текущий")
                }
                OutlinedButton(onClick = onExportCurrent, modifier = Modifier.handCursor()) {
                    Text("Экспорт .potsproj")
                }
                OutlinedButton(onClick = onImport, modifier = Modifier.handCursor()) {
                    Text("Импорт")
                }
            }
            Spacer(Modifier.height(12.dp))
            if (projects.isEmpty()) {
                Text(
                    "Сохранённых проектов пока нет. Изменения на канвасе сохраняются автоматически.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(modifier = Modifier.height(280.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(projects, key = { it.id }) { project ->
                        ProjectRow(
                            project = project,
                            onOpen = { onOpen(project.id) },
                            onExport = { onExport(project.id) },
                            onDelete = { onDelete(project.id) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Автосохранение включено: канвас, дорожки и параметры пишутся в проект автоматически.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProjectRow(
    project: MediaProjectMeta,
    onOpen: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(project.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                Text(
                    "${project.elementCount} эл. · ${formatProjectDate(project.updatedAtMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onExport, modifier = Modifier.handCursor().size(32.dp)) {
                Icon(Icons.Filled.IosShare, contentDescription = "Экспортировать проект", modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.handCursor().size(32.dp)) {
                Icon(Icons.Filled.Delete, contentDescription = "Удалить проект", modifier = Modifier.size(18.dp))
            }
        }
    }
}

private fun formatProjectDate(epochMs: Long): String {
    val date = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.currentSystemDefault())
    val day = date.dayOfMonth.toString().padStart(2, '0')
    val month = date.monthNumber.toString().padStart(2, '0')
    val hour = date.hour.toString().padStart(2, '0')
    val minute = date.minute.toString().padStart(2, '0')
    return "$day.$month $hour:$minute"
}
