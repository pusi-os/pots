package com.pusios.pots.ui.media

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.pusios.pots.media.MediaJobState
import kotlin.math.roundToInt

/** Element footprint on the canvas (dp); also the menu anchor height. */
internal const val CANVAS_ELEMENT_SIZE_DP = 232f

/**
 * The creative surface: a dotted-grid canvas with pan/zoom, draggable rounded
 * elements, selection ring with quick-transform chips, generation skeletons
 * and an inviting empty state.
 */
@Composable
internal fun MediaCanvas(
    elements: List<CanvasElement>,
    selectedId: String?,
    pendingJobs: List<MediaJobState>,
    canvasScale: Float,
    canvasOffset: Offset,
    onTransform: (zoomChange: Float, panChange: Offset) -> Unit,
    onTapBackground: () -> Unit,
    onSelect: (String) -> Unit,
    onMove: (String, Float, Float) -> Unit,
    onRotate: (String, Float) -> Unit,
    onScale: (String, Float) -> Unit,
    onOpenLightbox: (CanvasElement) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sorted = elements.sortedBy { it.zIndex }

    Box(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .semantics { contentDescription = "Медиа-канвас" }
            .transformable(
                state = rememberTransformableState { zoomChange, panChange, _ ->
                    onTransform(zoomChange, panChange)
                },
            )
            .clickable(onClick = onTapBackground),
    ) {
        CanvasDotGrid(canvasOffset)

        sorted.forEach { element ->
            CanvasElementView(
                element = element,
                selected = element.id == selectedId,
                canvasScale = canvasScale,
                canvasOffset = canvasOffset,
                onSelect = { onSelect(element.id) },
                onMove = onMove,
                onRotate = onRotate,
                onScale = onScale,
                onOpenLightbox = onOpenLightbox,
            )
        }

        if (pendingJobs.isNotEmpty()) {
            Row(Modifier.align(Alignment.Center)) {
                pendingJobs.forEach { job ->
                    GenerationSkeleton(job, Modifier.padding(8.dp))
                }
            }
        }

        if (sorted.isEmpty() && pendingJobs.isEmpty()) {
            Box(Modifier.align(Alignment.Center), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    Icon(
                        imageVector = Icons.Filled.PhotoLibrary,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Сгенерируй первое изображение или видео",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Опиши идею внизу и нажми кнопку со звёздочками",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun CanvasDotGrid(canvasOffset: Offset) {
    val dotColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
    Canvas(Modifier.fillMaxSize()) {
        val step = 24.dp.toPx()
        val dotRadius = 1.3.dp.toPx()
        val startX = canvasOffset.x % step - step
        val startY = canvasOffset.y % step - step
        var x = startX
        while (x < size.width + step) {
            var y = startY
            while (y < size.height + step) {
                drawCircle(dotColor, radius = dotRadius, center = Offset(x, y))
                y += step
            }
            x += step
        }
    }
}

@Composable
private fun CanvasElementView(
    element: CanvasElement,
    selected: Boolean,
    canvasScale: Float,
    canvasOffset: Offset,
    onSelect: () -> Unit,
    onMove: (String, Float, Float) -> Unit,
    onRotate: (String, Float) -> Unit,
    onScale: (String, Float) -> Unit,
    onOpenLightbox: (CanvasElement) -> Unit,
) {
    val url = element.url
    val local = element.localPath
    Box(
        Modifier
            .offset { IntOffset((element.x + canvasOffset.x).roundToInt(), (element.y + canvasOffset.y).roundToInt()) }
            .size(CANVAS_ELEMENT_SIZE_DP.dp)
            .graphicsLayer(
                scaleX = element.scale * canvasScale,
                scaleY = element.scale * canvasScale,
                rotationZ = element.rotation,
            )
            .shadow(6.dp, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(
                if (selected) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                } else {
                    Modifier
                },
            )
            .pointerInput(element.id) {
                detectTransformGestures { _, pan, zoom, rotation ->
                    onMove(element.id, pan.x, pan.y)
                    if (zoom != 1f) onScale(element.id, zoom)
                    if (rotation != 0f) onRotate(element.id, rotation)
                }
            }
            .semantics { contentDescription = if (element.isVideo) "Видео-элемент" else "Изображение" }
            .clickable(role = Role.Button, onClick = onSelect),
    ) {
        if (element.isVideo) {
            MediaVideoPlayer(url = url ?: local ?: "", autoplay = false, fullControls = true, modifier = Modifier.fillMaxSize())
        } else {
            AsyncImage(
                model = rememberImageModel(url ?: local),
                contentDescription = "Изображение на канвасе",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }

        if (selected) {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).padding(6.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                tonalElevation = 4.dp,
            ) {
                Row(Modifier.padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    ElementChip(Icons.Filled.RotateLeft, "Повернуть влево") { onRotate(element.id, -15f) }
                    ElementChip(Icons.Filled.RotateRight, "Повернуть вправо") { onRotate(element.id, 15f) }
                    ElementChip(Icons.Filled.Remove, "Уменьшить") { onScale(element.id, 0.85f) }
                    ElementChip(Icons.Filled.Add, "Увеличить") { onScale(element.id, 1.2f) }
                    ElementChip(Icons.Filled.Fullscreen, "Открыть на весь экран") { onOpenLightbox(element) }
                }
            }
        }
    }
}

@Composable
private fun ElementChip(icon: androidx.compose.ui.graphics.vector.ImageVector, contentDescription: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(30.dp)) {
        Icon(icon, contentDescription = contentDescription, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(16.dp))
    }
}

/** Skeleton card standing in for a result that is still generating. */
@Composable
private fun GenerationSkeleton(job: MediaJobState, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition()
    val alpha by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
    )
    Surface(
        modifier = modifier.size(width = (CANVAS_ELEMENT_SIZE_DP * 0.8).dp, height = (CANVAS_ELEMENT_SIZE_DP * 0.6).dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha),
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text(
                job.model ?: "Генерация…",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Создаю…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
            )
        }
    }
}
