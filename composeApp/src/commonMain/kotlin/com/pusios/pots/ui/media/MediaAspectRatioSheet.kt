package com.pusios.pots.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import com.pusios.pots.media.MediaCapValue
import com.pusios.pots.ui.handCursor

/**
 * Picks the aspect ratio of generated images. Each option shows a small frame
 * drawn at the same proportion, so the result shape is visible before generating.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MediaAspectRatioSheet(
    options: List<MediaCapValue>,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text("Соотношение сторон", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(12.dp))
            options.forEach { option ->
                AspectRatioRow(
                    option = option,
                    selected = option.value == selected,
                    onClick = {
                        onSelect(option.value)
                        onDismiss()
                    },
                )
                Spacer(Modifier.height(6.dp))
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun AspectRatioRow(option: MediaCapValue, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().handCursor().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RatioFrame(option.value, selected)
            Spacer(Modifier.width(14.dp))
            Text(
                option.label,
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

/** A rectangle with the ratio's proportions, fitted inside a 48 dp box. */
@Composable
private fun RatioFrame(ratio: String, selected: Boolean) {
    val (w, h) = parseRatio(ratio)
    val maxSide = 40f
    val frameWidth = if (w >= h) maxSide else maxSide * w / h
    val frameHeight = if (h >= w) maxSide else maxSide * h / w
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(width = frameWidth.dp, height = frameHeight.dp)
                .border(1.5.dp, borderColor, RoundedCornerShape(4.dp))
                .background(borderColor.copy(alpha = 0.12f), RoundedCornerShape(4.dp)),
        )
    }
}

private fun parseRatio(ratio: String): Pair<Float, Float> {
    val parts = ratio.split(":")
    val w = parts.getOrNull(0)?.trim()?.toFloatOrNull()?.takeIf { it > 0f } ?: return 1f to 1f
    val h = parts.getOrNull(1)?.trim()?.toFloatOrNull()?.takeIf { it > 0f } ?: return 1f to 1f
    return w to h
}
