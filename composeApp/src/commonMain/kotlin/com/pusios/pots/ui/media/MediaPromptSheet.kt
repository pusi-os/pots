package com.pusios.pots.ui.media

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pusios.pots.ui.handCursor

/**
 * Expanded prompt form (spec §17): near-full-screen sheet for long requests
 * and song lyrics. Line breaks stay in the text, the counter shows
 * `{введено}/{лимит модели}`, the enhance and create buttons are inherited
 * from the dock, and on desktop Ctrl+Enter sends while Enter wraps.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MediaPromptSheet(
    prompt: String,
    onPromptChange: (String) -> Unit,
    placeholder: String,
    promptLimit: Int?,
    isBusy: Boolean,
    isEnhancing: Boolean,
    createEnabled: Boolean,
    onCreate: () -> Unit,
    onEnhancePrompt: () -> Unit,
    onDismiss: () -> Unit,
) {
    val overLimit = promptLimit != null && prompt.length > promptLimit
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Промпт", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDismiss, modifier = Modifier.handCursor()) {
                    Icon(Icons.Filled.Close, contentDescription = "Свернуть", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.size(8.dp))
            OutlinedTextField(
                value = prompt,
                onValueChange = onPromptChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 240.dp)
                    .semantics { contentDescription = "Расширенный промпт" }
                    .onPreviewKeyEvent { event ->
                        // Desktop: Enter wraps, Ctrl+Enter sends.
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && event.isCtrlPressed) {
                            if (createEnabled && !isBusy) onCreate()
                            true
                        } else {
                            false
                        }
                    },
                placeholder = { Text(placeholder) },
                singleLine = false,
                minLines = 10,
                isError = overLimit,
                supportingText = {
                    val counter = if (promptLimit != null) "${prompt.length}/$promptLimit" else prompt.length.toString()
                    Text(
                        counter,
                        color = if (overLimit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
            Spacer(Modifier.size(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    onClick = onEnhancePrompt,
                    enabled = !isEnhancing,
                    modifier = Modifier.size(40.dp).handCursor().semantics { contentDescription = "Улучшить промпт" },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (isEnhancing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                Icons.Filled.AutoFixHigh,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    if (overLimit) "Текст длиннее лимита модели" else "Переносы строк сохраняются",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (overLimit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
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
            Spacer(Modifier.size(24.dp))
        }
    }
}
