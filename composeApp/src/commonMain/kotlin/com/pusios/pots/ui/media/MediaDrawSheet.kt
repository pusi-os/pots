package com.pusios.pots.ui.media

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.pusios.pots.media.DrawingLayer
import com.pusios.pots.media.ImageEditException
import com.pusios.pots.media.PixelGrid
import com.pusios.pots.media.PlusVibeMediaProvider
import com.pusios.pots.media.Stroke
import com.pusios.pots.media.bakeStrokes
import com.pusios.pots.media.decodeImageToGrid
import com.pusios.pots.media.encodeGridToPng
import com.pusios.pots.media.trimToContent
import com.pusios.pots.readAppStorageBytes
import com.pusios.pots.ui.handCursor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Spec §5 brush thickness presets (thin / medium / thick). */
private val BRUSH_PRESETS = floatArrayOf(2f, 6f, 14f)

/**
 * Empty-canvas sketch sheet (spec §5): a fixed transparent square, sized for
 * generation references. The baked result is trimmed to the drawn bounding
 * box, so the PNG never carries empty margins.
 */
private const val SKETCH_SIZE = 1024

/** Spec §5 palette: black, white, red, orange, yellow, green, blue, purple. */
private val BRUSH_COLORS = listOf(
    Color.Black,
    Color.White,
    Color(0xFFE53935),
    Color(0xFFFB8C00),
    Color(0xFFFDD835),
    Color(0xFF43A047),
    Color(0xFF1E88E5),
    Color(0xFF8E24AA),
)

/**
 * Full-screen drawing mode (spec §5): pencil over a chosen image element or
 * over an empty canvas. Strokes stay a vector layer; «Применить» bakes them
 * into a copy (PNG) through [onResult] — the source is never touched.
 */
@Composable
internal fun MediaDrawSheet(
    target: CanvasElement?,
    onDismiss: () -> Unit,
    onResult: (ByteArray) -> Boolean,
) {
    val scope = rememberCoroutineScope()
    val layer = remember { DrawingLayer() }
    val currentPoints = remember { mutableStateListOf<Offset>() }
    var strokesSnapshot by remember { mutableStateOf<List<Stroke>>(emptyList()) }
    var brushColor by remember { mutableStateOf(BRUSH_COLORS.first()) }
    var brushWidth by remember { mutableStateOf(BRUSH_PRESETS[1]) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmClose by remember { mutableStateOf(false) }

    // Target decode: pixels are needed up front so strokes bake at native
    // resolution and the pointer maps display -> image coordinates exactly.
    var targetGrid by remember { mutableStateOf<PixelGrid?>(null) }
    var loadError by remember { mutableStateOf<ImageEditException?>(null) }
    LaunchedEffect(target) {
        val element = target ?: return@LaunchedEffect
        val bytes = withContext(Dispatchers.Default) { readDrawTargetBytes(element) }
        if (bytes == null) {
            loadError = ImageEditException.DecodeFailed()
            return@LaunchedEffect
        }
        targetGrid = try {
            withContext(Dispatchers.Default) { decodeImageToGrid(bytes) }
        } catch (e: ImageEditException) {
            loadError = e
            null
        }
    }

    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var isApplying by remember { mutableStateOf(false) }
    var applyError by remember { mutableStateOf<ImageEditException?>(null) }

    val hasContent = strokesSnapshot.isNotEmpty() || currentPoints.isNotEmpty()

    fun undoLast() {
        if (layer.undoLast()) strokesSnapshot = layer.strokes.toList()
    }

    // Desktop shortcuts: Ctrl+Z pops the last stroke, Esc closes the sheet
    // (confirming first when strokes are not applied yet) — spec §5.
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Surface(Modifier.fillMaxSize(), color = Color.Black.copy(alpha = 0.95f)) {
        Column(
            Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) {
                        false
                    } else if (event.isCtrlPressed && event.key == Key.Z) {
                        undoLast()
                        true
                    } else if (event.key == Key.Escape) {
                        if (hasContent) confirmClose = true else onDismiss()
                        true
                    } else {
                        false
                    }
                },
        ) {
            // Drawing surface: the target image fitted in the middle, or a
            // fixed transparent SKETCH_SIZE square for an empty-canvas sketch.
            Box(
                Modifier.weight(1f).fillMaxWidth().onSizeChanged { containerSize = it },
                contentAlignment = Alignment.Center,
            ) {
                when {
                    loadError != null -> ImageEditErrorCard(loadError!!, null, onDismiss, Modifier.padding(24.dp))

                    target != null && targetGrid == null -> CircularProgressIndicator()

                    target != null && targetGrid != null -> {
                        val grid = targetGrid!!
                        val fitted = fitSize(containerSize, grid.width, grid.height)
                        val displayScale = if (grid.width > 0) fitted.width / grid.width else 1f
                        DrawingSurface(
                            fitted = fitted,
                            brushColor = brushColor,
                            brushWidth = brushWidth,
                            displayWidthScale = displayScale,
                            strokes = strokesSnapshot,
                            currentPoints = currentPoints,
                            background = {
                                AsyncImage(
                                    model = rememberImageModel(target.url ?: target.localPath),
                                    contentDescription = "Изображение для аннотации",
                                    contentScale = ContentScale.FillBounds,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            },
                            onStrokeStart = { imagePoint ->
                                layer.startStroke(brushColor.toArgb(), brushWidth / displayScale.coerceAtLeast(0.01f), imagePoint)
                            },
                            onStrokePoint = { imagePoint -> layer.extendStroke(imagePoint) },
                            onStrokeEnd = {
                                layer.finishStroke()
                                strokesSnapshot = layer.strokes.toList()
                            },
                        )
                    }

                    else -> {
                        // Empty-canvas sketch: strokes are recorded in the
                        // SKETCH_SIZE pixel space; the checkerboard only marks
                        // transparency on screen — the baked PNG keeps alpha.
                        val fitted = fitSize(containerSize, SKETCH_SIZE, SKETCH_SIZE)
                        val displayScale = if (fitted.width > 0f) fitted.width / SKETCH_SIZE else 1f
                        DrawingSurface(
                            fitted = fitted,
                            brushColor = brushColor,
                            brushWidth = brushWidth,
                            displayWidthScale = displayScale,
                            strokes = strokesSnapshot,
                            currentPoints = currentPoints,
                            background = { SketchTransparencyBackground() },
                            onStrokeStart = { point ->
                                layer.startStroke(brushColor.toArgb(), brushWidth / displayScale.coerceAtLeast(0.01f), point)
                            },
                            onStrokePoint = { point -> layer.extendStroke(point) },
                            onStrokeEnd = {
                                layer.finishStroke()
                                strokesSnapshot = layer.strokes.toList()
                            },
                        )
                    }
                }
            }

            // Glass capsule with the tools (spec §5).
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                tonalElevation = 6.dp,
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Толщина", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        BRUSH_PRESETS.forEach { preset ->
                            FilterChip(
                                selected = brushWidth == preset,
                                onClick = { brushWidth = preset },
                                label = { Text(preset.toInt().toString() + " px", style = MaterialTheme.typography.labelSmall) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer),
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        Slider(
                            value = brushWidth,
                            onValueChange = { brushWidth = it },
                            valueRange = 1f..32f,
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        )
                        Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                            Box(
                                Modifier
                                    .size(brushWidth.dp.coerceAtMost(30.dp))
                                    .clip(CircleShape)
                                    .background(brushColor)
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BRUSH_COLORS.forEach { color ->
                            val selected = brushColor == color
                            Box(
                                Modifier
                                    .padding(end = 6.dp)
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .border(if (selected) 2.5.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                    .handCursor()
                                    .clickable { brushColor = color },
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = ::undoLast, enabled = strokesSnapshot.isNotEmpty(), modifier = Modifier.handCursor()) {
                            Icon(Icons.Filled.Undo, contentDescription = "Отменить штрих", tint = MaterialTheme.colorScheme.onSurface)
                        }
                        IconButton(onClick = { confirmClear = true }, enabled = hasContent, modifier = Modifier.handCursor()) {
                            Icon(Icons.Filled.Delete, contentDescription = "Очистить всё", tint = MaterialTheme.colorScheme.onSurface)
                        }
                        IconButton(onClick = { if (hasContent) confirmClose = true else onDismiss() }, modifier = Modifier.handCursor()) {
                            Icon(Icons.Filled.Close, contentDescription = "Закрыть рисование", tint = MaterialTheme.colorScheme.onSurface)
                        }
                        IconButton(
                            onClick = {
                                if (isApplying || loadError != null) return@IconButton
                                val grid = targetGrid
                                if (target != null && grid == null) return@IconButton
                                isApplying = true
                                applyError = null
                                scope.launch {
                                    try {
                                        val strokes = strokesSnapshot
                                        val result = withContext(Dispatchers.Default) {
                                            val base = grid
                                            if (base != null) {
                                                // Annotation: strokes flatten onto the full image.
                                                encodeGridToPng(base.bakeStrokes(strokes))
                                            } else {
                                                // Empty-canvas sketch: transparent sheet,
                                                // result trimmed to the drawn bounding box.
                                                val sketch = PixelGrid(SKETCH_SIZE, SKETCH_SIZE, IntArray(SKETCH_SIZE * SKETCH_SIZE))
                                                val trimmed = sketch.bakeStrokes(strokes).trimToContent()
                                                    ?: throw ImageEditException.EmptySketch()
                                                encodeGridToPng(trimmed)
                                            }
                                        }
                                        if (!onResult(result)) throw ImageEditException.ExportFailed()
                                        onDismiss()
                                    } catch (e: ImageEditException) {
                                        applyError = e
                                    } finally {
                                        isApplying = false
                                    }
                                }
                            },
                            enabled = hasContent && !isApplying && loadError == null,
                            modifier = Modifier.handCursor(),
                        ) {
                            if (isApplying) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Filled.Check, contentDescription = "Применить", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    applyError?.let { error ->
                        Spacer(Modifier.height(6.dp))
                        ImageEditErrorCard(error, onRetry = null, onDismiss = { applyError = null })
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Очистить все штрихи?") },
            text = { Text("Нарисованное будет удалено, изображение не изменится.") },
            confirmButton = {
                TextButton(onClick = {
                    layer.clear()
                    currentPoints.clear()
                    strokesSnapshot = emptyList()
                    confirmClear = false
                }) { Text("Очистить") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена") } },
        )
    }
    if (confirmClose) {
        AlertDialog(
            onDismissRequest = { confirmClose = false },
            title = { Text("Закрыть без применения?") },
            text = { Text("Штрихи не сохранены и будут потеряны.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClose = false
                    onDismiss()
                }) { Text("Закрыть") }
            },
            dismissButton = { TextButton(onClick = { confirmClose = false }) { Text("Продолжить рисовать") } },
        )
    }
}

/** The fitted image rect inside the container, in container pixels. */
private fun fitSize(container: IntSize, imageWidth: Int, imageHeight: Int): Size {
    if (container.width < 1 || container.height < 1 || imageWidth < 1 || imageHeight < 1) return Size.Zero
    val scale = minOf(container.width.toFloat() / imageWidth, container.height.toFloat() / imageHeight)
    return Size(imageWidth * scale, imageHeight * scale)
}

/** Display-only checkerboard behind the transparent sketch sheet. */
@Composable
private fun SketchTransparencyBackground() {
    Canvas(Modifier.fillMaxSize().background(Color(0xFFE6E6E6))) {
        val cell = 14f
        var row = 0
        var y = 0f
        while (y < size.height) {
            var x = if (row % 2 == 0) 0f else cell
            while (x < size.width) {
                drawRect(Color(0xFFC9C9C9), topLeft = Offset(x, y), size = Size(cell, cell))
                x += cell * 2f
            }
            y += cell
            row++
        }
    }
}

/**
 * The background (image or transparent sketch sheet) with a pointer-capturing stroke
 * canvas laid exactly on top, sized [fitted]. Pointer positions are mapped
 * to drawing-space coordinates by dividing by [displayWidthScale]; for an
 * image annotation that space is NATIVE IMAGE PIXELS, so baking needs no
 * further coordinate work.
 */
@Composable
private fun DrawingSurface(
    fitted: Size,
    brushColor: Color,
    brushWidth: Float,
    displayWidthScale: Float,
    strokes: List<Stroke>,
    currentPoints: List<Offset>,
    background: @Composable () -> Unit,
    onStrokeStart: (Offset) -> Unit,
    onStrokePoint: (Offset) -> Unit,
    onStrokeEnd: () -> Unit,
) {
    if (fitted.width < 1f || fitted.height < 1f) return
    val density = LocalDensity.current
    Box(
        Modifier.size(
            with(density) { fitted.width.toDp() },
            with(density) { fitted.height.toDp() },
        ),
    ) {
        background()
        Canvas(
            Modifier.fillMaxSize().pointerInput(displayWidthScale) {
                val scale = displayWidthScale.coerceAtLeast(0.01f)
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    onStrokeStart(Offset(down.position.x / scale, down.position.y / scale))
                    var pressed = true
                    while (pressed) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { change ->
                            if (change.pressed) {
                                onStrokePoint(Offset(change.position.x / scale, change.position.y / scale))
                                change.consume()
                            }
                        }
                        pressed = event.changes.any { it.pressed }
                    }
                    onStrokeEnd()
                }
            },
        ) {
            strokes.forEach { stroke ->
                drawStrokePath(stroke.points, stroke.colorArgb, stroke.widthPx * displayWidthScale, displayWidthScale)
            }
            if (currentPoints.isNotEmpty()) {
                drawStrokePath(currentPoints, brushColor.toArgb(), brushWidth, displayWidthScale)
            }
        }
    }
}

/** Quadratic-midpoint path through the points, matching the baked result. */
private fun DrawScope.drawStrokePath(
    points: List<Offset>,
    colorArgb: Int,
    displayWidth: Float,
    displayScale: Float,
) {
    if (points.isEmpty() || displayWidth <= 0f || displayScale <= 0f) return
    val color = Color(colorArgb)
    val scaled = points.map { Offset(it.x * displayScale, it.y * displayScale) }
    if (scaled.size == 1) {
        drawCircle(color, radius = displayWidth / 2f, center = scaled.first())
        return
    }
    val path = Path().apply { moveTo(scaled.first().x, scaled.first().y) }
    for (i in 1 until scaled.size) {
        val prev = scaled[i - 1]
        val next = scaled[i]
        path.quadraticBezierTo(prev.x, prev.y, (prev.x + next.x) / 2f, (prev.y + next.y) / 2f)
    }
    path.lineTo(scaled.last().x, scaled.last().y)
    drawPath(
        path,
        color = color,
        style = androidx.compose.ui.graphics.drawscope.Stroke(
            width = displayWidth.coerceAtLeast(0.5f),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        ),
    )
}

private suspend fun readDrawTargetBytes(element: CanvasElement): ByteArray? = when {
    element.localPath != null -> readAppStorageBytes(element.localPath!!)
    element.url != null -> runCatching { PlusVibeMediaProvider().downloadResult("", element.url!!) }.getOrNull()
    else -> null
}
