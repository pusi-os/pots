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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.pusios.pots.media.MediaReference
import com.pusios.pots.ui.handCursor

/**
 * The studio dock: a single prompt field with the enhance and round create
 * buttons on its right. The enhance button shows a progress indicator while the
 * prompt is being rewritten; the create button shows one while a generation runs.
 */
@Composable
internal fun MediaDock(
    prompt: String,
    onPromptChange: (String) -> Unit,
    placeholder: String,
    isBusy: Boolean,
    isEnhancing: Boolean,
    onCreate: () -> Unit,
    onEnhancePrompt: () -> Unit,
    modifier: Modifier = Modifier,
    onExpand: (() -> Unit)? = null,
    createEnabled: Boolean = true,
    overLimitNote: String? = null,
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
                OutlinedTextField(
                    value = prompt,
                    onValueChange = onPromptChange,
                    modifier = Modifier.weight(1f).semantics { contentDescription = "Промпт генерации" },
                    placeholder = { Text(placeholder) },
                    singleLine = false,
                    minLines = 1,
                    maxLines = 3,
                    shape = RoundedCornerShape(16.dp),
                    isError = overLimitNote != null,
                    supportingText = overLimitNote?.let { note -> { Text(note) } },
                )
                Spacer(Modifier.width(6.dp))
                if (onExpand != null) {
                    Surface(
                        onClick = onExpand,
                        modifier = Modifier.size(32.dp).handCursor().semantics { contentDescription = "Развернуть промпт" },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.OpenInFull,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                }
                Surface(
                    onClick = onEnhancePrompt,
                    enabled = !isEnhancing,
                    modifier = Modifier.size(32.dp).handCursor().semantics { contentDescription = "Улучшить промпт" },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (isEnhancing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                Icons.Filled.AutoFixHigh,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.width(6.dp))
                Surface(
                    onClick = onCreate,
                    enabled = !isBusy && createEnabled,
                    modifier = Modifier.size(52.dp).handCursor().semantics { contentDescription = "Создать" },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (isBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                Icons.Filled.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
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

/**
 * Image/video/audio switch; shared by the studio top bar. Tapping the active
 * audio segment calls [onActiveAudioTap] (the flow picker, spec §13); other
 * segments keep the plain select behavior.
 */
@Composable
internal fun ModeSegment(
    mode: MediaMode,
    onSelect: (MediaMode) -> Unit,
    onActiveAudioTap: () -> Unit = {},
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
    ) {
        Row(Modifier.padding(2.dp), verticalAlignment = Alignment.CenterVertically) {
            ModeSegmentButton(Icons.Filled.Image, "Режим изображений", active = mode == MediaMode.Image, onClick = { onSelect(MediaMode.Image) })
            ModeSegmentButton(Icons.Filled.Movie, "Режим видео", active = mode == MediaMode.Video, onClick = { onSelect(MediaMode.Video) })
            ModeSegmentButton(
                Icons.Filled.MusicNote,
                "Режим аудио",
                active = mode == MediaMode.Audio,
                onClick = { if (mode == MediaMode.Audio) onActiveAudioTap() else onSelect(MediaMode.Audio) },
            )
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
