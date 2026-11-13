package com.pusios.pots.ui.media

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.unit.dp
import com.pusios.pots.media.MediaCapValue
import com.pusios.pots.media.MediaVoice
import com.pusios.pots.media.VoiceStatus
import com.pusios.pots.ui.handCursor

/**
 * Voice picker for models that accept a cloned `voice_id` (spec §15): the
 * model's built-in voices on top, the cloned «Мой голос» list with a mic
 * mark below, and the «+ Мой голос» entry that opens the clone sheet.
 * Only ready voices can be selected; processing/error rows keep their
 * status badge. Deletion asks for confirmation with the voice name (§16).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MediaVoiceSheet(
    title: String,
    builtInOptions: List<MediaCapValue>,
    selected: String,
    myVoices: List<MediaVoice>?,
    onSelect: (String) -> Unit,
    onOpenCreate: () -> Unit,
    onDeleteVoice: (MediaVoice) -> Unit,
    onDismiss: () -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<MediaVoice?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(12.dp))

            if (builtInOptions.isNotEmpty()) {
                Text("Встроенные голоса", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(8.dp))
                builtInOptions.forEach { option ->
                    VoiceOptionRow(
                        label = option.label,
                        selected = option.value == selected,
                        onClick = {
                            onSelect(option.value)
                            onDismiss()
                        },
                    )
                    Spacer(Modifier.height(6.dp))
                }
                Spacer(Modifier.height(8.dp))
            }

            Text("Мой голос", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(8.dp))
            when {
                myVoices == null -> Text(
                    "Загружаю список голосов…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                myVoices.isEmpty() -> Text(
                    "Клонированных голосов пока нет — добавь свой голос записью ниже.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> myVoices.forEach { voice ->
                    ClonedVoiceRow(
                        voice = voice,
                        selected = voice.id == selected,
                        onClick = {
                            if (voice.status == VoiceStatus.Ready) {
                                onSelect(voice.id)
                                onDismiss()
                            }
                        },
                        onDelete = { pendingDelete = voice },
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }

            Spacer(Modifier.height(8.dp))
            Surface(
                onClick = onOpenCreate,
                modifier = Modifier.fillMaxWidth().handCursor(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("+ Мой голос", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    pendingDelete?.let { voice ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Удалить голос?") },
            text = { Text("Голос «${voice.name}» будет удалён у провайдера. Это действие нельзя отменить.") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteVoice(voice)
                    pendingDelete = null
                }) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Отмена") }
            },
        )
    }
}

@Composable
private fun VoiceOptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().handCursor().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "Выбрано",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun ClonedVoiceRow(voice: MediaVoice, selected: Boolean, onClick: () -> Unit, onDelete: () -> Unit) {
    val selectable = voice.status == VoiceStatus.Ready
    Surface(
        modifier = Modifier.fillMaxWidth().handCursor().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = when {
            selected -> MaterialTheme.colorScheme.primaryContainer
            selectable -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
        },
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Mic,
                contentDescription = "Клонированный голос",
                tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    voice.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                )
                val statusLabel = when (voice.status) {
                    VoiceStatus.Ready -> null
                    VoiceStatus.Processing -> "обрабатывается"
                    VoiceStatus.Error -> "ошибка создания"
                    VoiceStatus.Unknown -> "статус уточняется"
                }
                if (statusLabel != null) {
                    Text(
                        statusLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (voice.status == VoiceStatus.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "Выбрано",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp).handCursor()) {
                Icon(
                    Icons.Filled.DeleteOutline,
                    contentDescription = "Удалить голос",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
