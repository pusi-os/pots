package com.pusios.pots.ui.media

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FlipToBack
import androidx.compose.material.icons.filled.FlipToFront
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.pusios.pots.ui.handCursor

/** One row of [MediaContextMenu]: icon plus label, optional destructive tint. */
private data class MenuEntry(
    val label: String,
    val icon: ImageVector,
    val action: CanvasAction?,
    val destructive: Boolean = false,
)

/**
 * Glass context menu anchored under the tapped element. The caller positions it
 * (element position + canvas pan) so the menu follows the canvas instead of
 * drifting away from it.
 */
@Composable
internal fun MediaContextMenu(
    modifier: Modifier = Modifier,
    onAction: (CanvasAction) -> Unit,
    onExportToGallery: () -> Unit,
    onDismiss: () -> Unit,
) {
    val entries = listOf(
        MenuEntry("Вперёд", Icons.Filled.FlipToFront, CanvasAction.BringForward),
        MenuEntry("Назад", Icons.Filled.FlipToBack, CanvasAction.SendBack),
        MenuEntry("Дублировать", Icons.Filled.ContentCopy, CanvasAction.Duplicate),
        MenuEntry("Как референс", Icons.Filled.Link, CanvasAction.UseAsReference),
        MenuEntry("В галерею", Icons.Filled.SaveAlt, action = null),
        MenuEntry("Удалить", Icons.Filled.Delete, CanvasAction.Delete, destructive = true),
    )
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        tonalElevation = 8.dp,
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.padding(vertical = 6.dp)) {
            entries.forEach { entry ->
                MenuRow(entry = entry) {
                    if (entry.action == null) onExportToGallery() else onAction(entry.action)
                    onDismiss()
                }
            }
        }
    }
}

@Composable
private fun MenuRow(entry: MenuEntry, onClick: () -> Unit) {
    val tint = if (entry.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier
            .handCursor()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(entry.icon, contentDescription = entry.label, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(entry.label, style = MaterialTheme.typography.bodyMedium, color = tint)
    }
}
