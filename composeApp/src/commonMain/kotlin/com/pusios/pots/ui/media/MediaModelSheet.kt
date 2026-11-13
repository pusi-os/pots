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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pusios.pots.media.AudioFlow
import com.pusios.pots.media.AudioSheetGroup
import com.pusios.pots.media.MediaKind
import com.pusios.pots.media.MediaModel
import com.pusios.pots.media.audioFlowGroups
import com.pusios.pots.media.audioPriceLabel
import com.pusios.pots.ui.handCursor

/**
 * Bottom sheet listing image and video models with price badges. The selected
 * row uses the container colors the theme defines for it — `primaryContainer`
 * background with `onPrimaryContainer` text.
 *
 * The audio sheet follows the active flow (spec §14): TTS shows the Voice
 * group, music shows Music + Effects. The selected audio row recomputes its
 * price badge from the selected chips when the model has tiers (spec §2).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MediaModelSheet(
    models: List<MediaModel>,
    kind: MediaKind,
    audioFlow: AudioFlow,
    hasSunoHistory: Boolean,
    selectedAudioOpts: Map<String, String>,
    selectedImageModelId: String,
    selectedVideoModelId: String,
    selectedTtsModelId: String,
    selectedMusicModelId: String,
    onSelectImage: (String) -> Unit,
    onSelectVideo: (String) -> Unit,
    onSelectAudio: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val isAudioKind = kind == MediaKind.Audio || kind == MediaKind.Music
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            val imageModels = models.filter { it.kind == MediaKind.Image }
            val videoModels = models.filter { it.kind == MediaKind.Video }

            if (kind == MediaKind.Image && imageModels.isNotEmpty()) {
                Text("Изображения", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(8.dp))
                imageModels.forEach { model ->
                    ModelSheetRow(
                        model = model,
                        selected = model.id == selectedImageModelId,
                        onClick = {
                            onSelectImage(model.id)
                            onDismiss()
                        },
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }

            if (kind == MediaKind.Video && videoModels.isNotEmpty()) {
                Text("Видео", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(8.dp))
                videoModels.forEach { model ->
                    ModelSheetRow(
                        model = model,
                        selected = model.id == selectedVideoModelId,
                        onClick = {
                            onSelectVideo(model.id)
                            onDismiss()
                        },
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }

            if (isAudioKind) {
                // Groups of the active flow; STT and audio-input models never
                // reach this list (audioFlowGroups filters them out).
                audioFlowGroups(models, audioFlow, hasSunoHistory).forEach { (group, groupModels) ->
                    Text(group.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(8.dp))
                    val selectedId = when (group) {
                        AudioSheetGroup.Voice -> selectedTtsModelId
                        else -> selectedMusicModelId
                    }
                    groupModels.forEach { model ->
                        val selected = model.id == selectedId
                        ModelSheetRow(
                            model = model,
                            selected = selected,
                            // Tier models recompute the badge for the selected chips.
                            priceLabel = audioPriceLabel(model, if (selected) selectedAudioOpts else emptyMap()),
                            onClick = {
                                onSelectAudio(model.id)
                                onDismiss()
                            },
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }

            val nothingToShow = if (isAudioKind) {
                audioFlowGroups(models, audioFlow, hasSunoHistory).isEmpty()
            } else {
                models.none { it.kind == kind }
            }
            if (nothingToShow) {
                Text(
                    "Каталог пуст — обнови его в настройках сервисов или проверь сеть",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ModelSheetRow(model: MediaModel, selected: Boolean, onClick: () -> Unit, priceLabel: String? = null) {
    Surface(
        modifier = Modifier.fillMaxWidth().handCursor().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    model.id,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                )
                if (!model.note.isNullOrBlank()) {
                    Text(
                        model.note,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            PriceBadge(model, emphasized = selected, labelOverride = priceLabel)
            if (selected) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "Выбрана",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun PriceBadge(model: MediaModel, emphasized: Boolean, labelOverride: String? = null) {
    val label = labelOverride ?: when {
        model.priceUnavailable -> "цена недоступна"
        model.fromRub != null -> "от ${model.fromRub} ₽" + (model.unit?.let { " · $it" } ?: "")
        else -> model.unit ?: ""
    }
    if (label.isEmpty()) return
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (emphasized) {
            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f)
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (emphasized) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}
