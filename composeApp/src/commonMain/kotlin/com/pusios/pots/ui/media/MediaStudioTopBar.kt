package com.pusios.pots.ui.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Theaters
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Badge
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.pusios.pots.media.AudioFlow
import com.pusios.pots.ui.handCursor

/**
 * The studio's top overlay: a glass capsule with compact icon buttons
 * (recenter canvas, import a file, pick a model, generation journal, and —
 * where local video processing exists — the sequence assembly sheet),
 * and a second row beneath it with the image/video switch, the current model,
 * the aspect ratio (images) and the generation parameters such as resolution.
 *
 * In audio mode a re-tap of the active segment opens the two-flow picker
 * («Озвучить текст» / «Создать музыку», spec §13).
 */
@Composable
internal fun MediaStudioTopBar(
    modifier: Modifier = Modifier,
    onRecenter: () -> Unit,
    onImport: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenHistory: () -> Unit,
    onDraw: () -> Unit,
    historyBadge: Int,
    aspectRatioLabel: String?,
    onOpenAspectRatio: () -> Unit,
    paramChips: List<MediaChipUi>,
    onOpenParam: (String) -> Unit,
    mode: MediaMode,
    onSelectMode: (MediaMode) -> Unit,
    currentModelLabel: String?,
    audioFlow: AudioFlow = AudioFlow.Tts,
    onSelectAudioFlow: (AudioFlow) -> Unit = {},
    showSequence: Boolean = false,
    onOpenSequence: () -> Unit = {},
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
            tonalElevation = 6.dp,
            shadowElevation = 4.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TopBarIconButton(Icons.Filled.AutoAwesome, "Медиа-канвас: в центр", onRecenter)
                TopBarIconButton(Icons.Filled.AddPhotoAlternate, "Импортировать файл", onImport)
                TopBarIconButton(Icons.Filled.Brush, "Рисовать на канвасе", onDraw)
                TopBarIconButton(Icons.Filled.Tune, "Выбрать модель", onOpenModels)
                TopBarIconButton(Icons.Filled.History, "Журнал генераций", onOpenHistory, badge = historyBadge)
                if (showSequence) {
                    TopBarIconButton(Icons.Filled.Theaters, "Собрать видео из последовательности", onOpenSequence)
                }
            }
        }

        Spacer(Modifier.size(6.dp))

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            var showFlowMenu by remember { mutableStateOf(false) }
            Box {
                ModeSegment(
                    mode = mode,
                    onSelect = onSelectMode,
                    onActiveAudioTap = { showFlowMenu = true },
                )
                DropdownMenu(expanded = showFlowMenu, onDismissRequest = { showFlowMenu = false }) {
                    AudioFlow.entries.forEach { flow ->
                        DropdownMenuItem(
                            text = { Text(flow.title) },
                            leadingIcon = {
                                Icon(
                                    if (flow == AudioFlow.Tts) Icons.Filled.RecordVoiceOver else Icons.Filled.LibraryMusic,
                                    contentDescription = null,
                                )
                            },
                            trailingIcon = if (flow == audioFlow) {
                                { Icon(Icons.Filled.Check, contentDescription = "Активный флоу") }
                            } else {
                                null
                            },
                            onClick = {
                                showFlowMenu = false
                                onSelectAudioFlow(flow)
                            },
                        )
                    }
                }
            }
            currentModelLabel?.let { label ->
                Spacer(Modifier.width(8.dp))
                TopBarChip(label, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer, onOpenModels)
            }
            aspectRatioLabel?.let { ratio ->
                Spacer(Modifier.width(6.dp))
                TopBarChip(ratio, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer, onOpenAspectRatio)
            }
            paramChips.forEach { chip ->
                Spacer(Modifier.width(6.dp))
                when (chip) {
                    is MediaChipUi.Sheet -> TopBarChip(chip.label, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer) {
                        onOpenParam(chip.key)
                    }

                    is MediaChipUi.QualityDropdown -> QualityChip(chip)

                    is MediaChipUi.AudioToggle -> AudioToggleChip(chip)
                }
            }
        }
    }
}

/** One toolbar chip of the image/video/audio modes (spec §19.3). */
internal sealed class MediaChipUi {
    abstract val key: String

    /** Existing behaviour: the chip opens `MediaParamSheet`. */
    data class Sheet(override val key: String, val label: String) : MediaChipUi()

    /** The quality dropdown with prices (§19.1): one control for generation and editing. */
    data class QualityDropdown(
        override val key: String,
        val label: String,
        val options: List<QualityOptionUi>,
        val footerNote: String?,
        val onSelect: (String) -> Unit,
    ) : MediaChipUi()

    /** The «со звуком / без звука» toggle (§19.2). */
    data class AudioToggle(override val key: String, val withSound: Boolean, val onToggle: () -> Unit) : MediaChipUi()
}

/** One line of the quality dropdown: Russian label plus the exact price when known. */
internal data class QualityOptionUi(
    val value: String,
    val label: String,
    val priceLabel: String?,
    val selected: Boolean,
)

@Composable
private fun TopBarChip(
    text: String,
    containerColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.handCursor(),
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            maxLines = 1,
        )
    }
}

/** The quality selector chip: current level indicator, tap opens the dropdown (spec §19.1). */
@Composable
private fun QualityChip(chip: MediaChipUi.QualityDropdown) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TopBarChip(
            chip.label,
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            onClick = { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            chip.options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            buildString {
                                append(option.label)
                                option.priceLabel?.let {
                                    append(" — ")
                                    append(it)
                                }
                            },
                        )
                    },
                    leadingIcon = if (option.selected) {
                        { Icon(Icons.Filled.Check, contentDescription = "Выбрано") }
                    } else {
                        null
                    },
                    onClick = {
                        expanded = false
                        chip.onSelect(option.value)
                    },
                )
            }
            chip.footerNote?.let { note ->
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** The «со звуком / без звука» toggle chip (spec §19.2). */
@Composable
private fun AudioToggleChip(chip: MediaChipUi.AudioToggle) {
    Surface(
        onClick = chip.onToggle,
        modifier = Modifier.handCursor(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (chip.withSound) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                if (chip.withSound) "Со звуком" else "Без звука",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun TopBarIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    badge: Int = 0,
) {
    Box(modifier = Modifier.size(36.dp)) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.size(36.dp).handCursor(),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp),
            )
        }
        if (badge > 0) {
            Badge(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp),
            ) {
                Text(if (badge > 9) "9+" else badge.toString())
            }
        }
    }
}
