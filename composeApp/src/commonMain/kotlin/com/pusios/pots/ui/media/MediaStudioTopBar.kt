package com.pusios.pots.ui.media

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.pusios.pots.ui.handCursor

/**
 * The studio's top overlay: four compact icon buttons in one glass capsule —
 * back to chat, recenter canvas, import a file, pick a model. No text labels.
 */
@Composable
internal fun MediaStudioTopBar(
    modifier: Modifier = Modifier,
    onExit: () -> Unit,
    onRecenter: () -> Unit,
    onImport: () -> Unit,
    onOpenModels: () -> Unit,
) {
    Surface(
        modifier = modifier,
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
        }
    }
}

@Composable
private fun TopBarIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
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
}
