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
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.pusios.pots.ui.handCursor

/**
 * The studio's top overlay: a glass capsule with five compact icon buttons
 * (back to chat, recenter canvas, import a file, pick a model, generation journal),
 * and a second row beneath it with the image/video switch and the current model.
 */
@Composable
internal fun MediaStudioTopBar(
    modifier: Modifier = Modifier,
    onExit: () -> Unit,
    onRecenter: () -> Unit,
    onImport: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenHistory: () -> Unit,
    historyBadge: Int,
    isVideoMode: Boolean,
    onToggleMode: () -> Unit,
    currentModelLabel: String?,
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
                TopBarIconButton(Icons.AutoMirrored.Filled.Chat, "Вернуться в чат", onExit)
                TopBarIconButton(Icons.Filled.AutoAwesome, "Медиа-канвас: в центр", onRecenter)
                TopBarIconButton(Icons.Filled.AddPhotoAlternate, "Импортировать файл", onImport)
                TopBarIconButton(Icons.Filled.Tune, "Выбрать модель", onOpenModels)
                TopBarIconButton(Icons.Filled.History, "Журнал генераций", onOpenHistory, badge = historyBadge)
            }
        }

        Spacer(Modifier.size(6.dp))

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            ModeSegment(isVideoMode = isVideoMode, onToggle = onToggleMode)
            currentModelLabel?.let { label ->
                Spacer(Modifier.width(8.dp))
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
