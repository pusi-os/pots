package com.pusios.pots.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.pusios.pots.media.MediaParamDef
import com.pusios.pots.media.MediaReference
import com.pusios.pots.ui.handCursor

/**
 * The studio dock: reference strip, single prompt field, round create button
 * and an icon row (speech toggle, ask agent, image/video mode, current model).
 */
@Composable
internal fun MediaDock(
    prompt: String,
    onPromptChange: (String) -> Unit,
    isVideoMode: Boolean,
    onToggleMode: () -> Unit,
    references: List<MediaReference>,
    onRemoveReference: (Int) -> Unit,
    onAddReference: () -> Unit,
    currentModelLabel: String?,
    onOpenModels: () -> Unit,
    speechEnabled: Boolean,
    onToggleSpeech: () -> Unit,
    onAskAgent: () -> Unit,
    onCreate: () -> Unit,
    paramDefs: List<MediaParamDef>,
    paramValues: Map<String, String>,
    onParamSelect: (String, String) -> Unit,
    onEnhancePrompt: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 8.dp,
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DockIconButton(
                    icon = if (speechEnabled) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                    contentDescription = if (speechEnabled) "Озвучка включена" else "Озвучка выключена",
                    active = speechEnabled,
                    onClick = onToggleSpeech,
                )
                Spacer(Modifier.width(4.dp))
                OutlinedTextField(
                    value = prompt,
                    onValueChange = onPromptChange,
                    modifier = Modifier.weight(1f).semantics { contentDescription = "Промпт генерации" },
                    placeholder = { Text(if (isVideoMode) "Опиши видео…" else "Опиши изображение…") },
                    singleLine = false,
                    minLines = 1,
                    maxLines = 3,
                    shape = RoundedCornerShape(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Surface(
                    onClick = onCreate,
                    modifier = Modifier.size(52.dp).handCursor().semantics { contentDescription = "Создать" },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 40.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onEnhancePrompt, modifier = Modifier.handCursor()) {
                    Icon(Icons.Filled.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Улучшить промпт", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }

    Spacer(Modifier.height(6.dp))

    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        DockIconButton(
            icon = Icons.AutoMirrored.Filled.Send,
            contentDescription = "Спросить агента",
            onClick = onAskAgent,
        )
        Spacer(Modifier.width(4.dp))
        ModeSegment(isVideoMode = isVideoMode, onToggle = onToggleMode)
        Spacer(Modifier.width(8.dp))
        currentModelLabel?.let { label ->
            Surface(
                onClick = onOpenModels,
                modifier = Modifier.handCursor(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    maxLines = 1,
                )
            }
        }
    }

    if (paramDefs.isNotEmpty()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            paramDefs.forEach { def ->
                val selected = paramValues[def.key] ?: def.defaultValue
                def.options.take(6).forEach { opt ->
                    FilterChip(
                        selected = opt.value == selected,
                        onClick = { onParamSelect(def.key, opt.value) },
                        label = { Text("${def.label}: ${opt.label}", style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
        }
    }
}

/** Reference thumbnails with a remove badge; "+" opens the file picker. */
@Composable
internal fun ReferenceStrip(
    references: List<MediaReference>,
    onRemoveReference: (Int) -> Unit,
    onAddReference: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        references.forEachIndexed { index, reference ->
            Box(Modifier.size(44.dp)) {
                AsyncImage(
                    model = rememberImageModel(reference.url ?: reference.localPath ?: reference.base64DataUri),
                    contentDescription = "Референс ${index + 1}",
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                    contentScale = ContentScale.Crop,
                )
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .semantics { contentDescription = "Убрать референс" },
                ) {
                    IconButton(onClick = { onRemoveReference(index) }, modifier = Modifier.size(16.dp).align(Alignment.Center)) {
                        Icon(Icons.Filled.Close, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(12.dp))
                    }
                }
            }
        }
        Surface(
            onClick = onAddReference,
            modifier = Modifier.size(44.dp).handCursor().semantics { contentDescription = "Добавить референс" },
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun ModeSegment(isVideoMode: Boolean, onToggle: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
    ) {
        Row(Modifier.padding(2.dp), verticalAlignment = Alignment.CenterVertically) {
            ModeSegmentButton(Icons.Filled.Image, "Режим изображений", active = !isVideoMode, onClick = { if (isVideoMode) onToggle() })
            ModeSegmentButton(Icons.Filled.Movie, "Режим видео", active = isVideoMode, onClick = { if (!isVideoMode) onToggle() })
        }
    }
}

@Composable
private fun ModeSegmentButton(icon: ImageVector, contentDescription: String, active: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.handCursor(),
        shape = RoundedCornerShape(11.dp),
        color = if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(7.dp).size(18.dp),
        )
    }
}

@Composable
private fun DockIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, active: Boolean = false) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp).handCursor()) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}
