package com.pusios.pots.ui.media

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.pusios.pots.media.MediaHistoryEntry
import com.pusios.pots.media.MediaHistoryStatus
import com.pusios.pots.ui.handCursor
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/**
 * Journal of generations: a bottom sheet with one card per request, newest first.
 * Active cards show progress, the model, the provider and the price; finished cards
 * open in the lightbox and can be saved, repeated, placed on the canvas or deleted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MediaHistorySheet(
    entries: List<MediaHistoryEntry>,
    onDismiss: () -> Unit,
    onOpen: (MediaHistoryEntry) -> Unit,
    onSave: (String) -> Unit,
    onRepeat: (String) -> Unit,
    onPlaceOnCanvas: (String) -> Unit,
    onDelete: (String) -> Unit,
    onClearFinished: () -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<MediaHistoryEntry?>(null) }
    var showClearDialog by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Журнал генераций", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        historySummary(entries),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (entries.any { !it.isActive }) {
                    TextButton(onClick = { showClearDialog = true }, modifier = Modifier.handCursor()) {
                        Text("Очистить")
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            if (entries.isEmpty()) {
                HistoryEmptyState()
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth().height(520.dp),
                ) {
                    items(entries, key = { it.localId }) { entry ->
                        HistoryCard(
                            entry = entry,
                            onOpen = { onOpen(entry) },
                            onSave = { onSave(entry.localId) },
                            onRepeat = { onRepeat(entry.localId) },
                            onPlaceOnCanvas = { onPlaceOnCanvas(entry.localId) },
                            onDelete = { pendingDelete = entry },
                        )
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }

    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Удалить из журнала?") },
            text = {
                Text(
                    if (entry.isActive) {
                        "Генерация ещё идёт. Запись исчезнет из журнала, но задача у провайдера продолжится и может списать деньги."
                    } else {
                        "Запись пропадёт из журнала. Файл на телефоне и элемент на холсте останутся."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(entry.localId)
                    pendingDelete = null
                }) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Отмена") }
            },
        )
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Очистить журнал?") },
            text = { Text("Все завершённые записи исчезнут. Генерации в процессе останутся.") },
            confirmButton = {
                TextButton(onClick = {
                    onClearFinished()
                    showClearDialog = false
                }) { Text("Очистить") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Отмена") }
            },
        )
    }
}

@Composable
private fun HistoryEmptyState() {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.History,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text("Пока пусто", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(
            "Здесь появятся все генерации: в очереди, в работе и готовые.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HistoryCard(
    entry: MediaHistoryEntry,
    onOpen: () -> Unit,
    onSave: () -> Unit,
    onRepeat: () -> Unit,
    onPlaceOnCanvas: () -> Unit,
    onDelete: () -> Unit,
) {
    val background by animateColorAsState(
        if (entry.status == MediaHistoryStatus.Failed) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        },
    )
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = background,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
            HistoryThumb(entry, onOpen)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusChip(entry.status)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        historyTime(entry.createdAtMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    entry.prompt.ifBlank { "Без описания" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    buildMetaLine(entry),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (entry.isActive) {
                    Spacer(Modifier.height(8.dp))
                    if (entry.progress != null) {
                        LinearProgressIndicator(
                            progress = { entry.progress ?: 0f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (entry.status == MediaHistoryStatus.Sending) "Отправляется…" else "Генерируется у провайдера",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (entry.status == MediaHistoryStatus.Failed && !entry.failMsg.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        entry.failMsg,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (entry.status == MediaHistoryStatus.Success) {
                        HistoryAction(Icons.Filled.SaveAlt, "Сохранить в галерею", onSave)
                        HistoryAction(Icons.Filled.AddPhotoAlternate, "Поставить на холст", onPlaceOnCanvas)
                    }
                    if (entry.canRepeat || entry.canRetrySubmit) {
                        HistoryAction(
                            if (entry.canRetrySubmit) Icons.AutoMirrored.Filled.Undo else Icons.Filled.Refresh,
                            if (entry.canRetrySubmit) "Повторить отправку" else "Перегенерировать",
                            onRepeat,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    HistoryAction(Icons.Filled.DeleteOutline, "Удалить из журнала", onDelete, tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun HistoryThumb(entry: MediaHistoryEntry, onOpen: () -> Unit) {
    val source = entry.localPath ?: entry.resultUrl
    Box(
        Modifier
            .size(72.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = entry.hasPreview, onClick = onOpen)
            .handCursor(),
        contentAlignment = Alignment.Center,
    ) {
        when {
            entry.hasPreview && !entry.isVideo && source != null -> AsyncImage(
                model = rememberImageModel(source),
                contentDescription = "Открыть результат",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            entry.hasPreview -> Icon(
                Icons.Filled.Movie,
                contentDescription = "Открыть видео",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            entry.status == MediaHistoryStatus.Failed -> Icon(
                Icons.Filled.Error,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )

            else -> Icon(
                if (entry.isVideo) Icons.Filled.Movie else Icons.Filled.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusChip(status: MediaHistoryStatus) {
    val (label, icon, container, content) = when (status) {
        MediaHistoryStatus.Sending -> StatusStyle(
            "В очереди",
            Icons.Filled.Schedule,
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
        )

        MediaHistoryStatus.Processing -> StatusStyle(
            "Генерируется",
            Icons.Filled.Schedule,
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
        )

        MediaHistoryStatus.Success -> StatusStyle(
            "Готово",
            Icons.Filled.CheckCircle,
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )

        MediaHistoryStatus.Failed -> StatusStyle(
            "Ошибка",
            Icons.Filled.Error,
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )
    }
    Surface(shape = RoundedCornerShape(8.dp), color = container) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = content, fontWeight = FontWeight.SemiBold)
        }
    }
}

private data class StatusStyle(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val container: Color,
    val content: Color,
)

@Composable
private fun HistoryAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp).handCursor()) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(18.dp))
    }
}

private fun buildMetaLine(entry: MediaHistoryEntry): String {
    val parts = mutableListOf(entry.model, entry.provider)
    val price = entry.priceRub ?: entry.estimateRub
    if (price != null) {
        val label = if (entry.priceRub != null) "списано" else "оценка"
        parts += "${formatRub(price)} ($label)"
    }
    return parts.joinToString(" · ")
}

private fun formatRub(value: Double): String {
    val rounded = kotlin.math.round(value * 100) / 100
    return "$rounded ₽"
}

private fun historySummary(entries: List<MediaHistoryEntry>): String {
    val active = entries.count { it.isActive }
    val done = entries.count { it.status == MediaHistoryStatus.Success }
    val spent = entries.sumOf { it.priceRub ?: 0.0 }
    val parts = mutableListOf<String>()
    if (active > 0) parts += "в работе: $active"
    parts += "готово: $done"
    if (spent > 0) parts += "списано: ${formatRub(spent)}"
    return parts.joinToString(" · ")
}

private fun historyTime(epochMs: Long): String {
    val nowMs = Clock.System.now().toEpochMilliseconds()
    val diffMin = (nowMs - epochMs) / 60_000
    return when {
        diffMin < 1 -> "только что"
        diffMin < 60 -> "$diffMin мин назад"
        diffMin < 60 * 24 -> "${diffMin / 60} ч назад"
        else -> Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()
    }
}
