package com.pusios.pots.ui.media

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PhotoSizeSelectLarge
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.pusios.pots.decodeToImageBitmap
import com.pusios.pots.media.ImageEditException
import com.pusios.pots.media.ImageEditProgress
import com.pusios.pots.media.PixelGrid
import com.pusios.pots.media.PlusVibeMediaProvider
import com.pusios.pots.media.adjustBrightnessContrast
import com.pusios.pots.media.cropGrid
import com.pusios.pots.media.decodeImageToGrid
import com.pusios.pots.media.encodeGridToPng
import com.pusios.pots.media.flipHorizontal
import com.pusios.pots.media.flipVertical
import com.pusios.pots.media.overlayGrid
import com.pusios.pots.media.resizeGrid
import com.pusios.pots.media.rotateQuadrant
import com.pusios.pots.readAppStorageBytes
import com.pusios.pots.ui.handCursor
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/** Editor operations (spec §6), one full-screen preview each. */
private enum class ImageEditorOp(val title: String) {
    Crop("Обрезка"),
    Resize("Размер"),
    Overlay("Наложение"),
    Adjust("Коррекция"),
}

/** Crop aspect chips (spec §6): free plus fixed ratios. */
private val CROP_ASPECTS = listOf("Свободно" to null, "1:1" to 1f, "4:3" to 4f / 3f, "3:4" to 3f / 4f, "16:9" to 16f / 9f, "9:16" to 9f / 16f)

/** Resize presets (spec §6): the longest side becomes the preset. */
private val RESIZE_PRESETS = listOf("512" to 512, "1024" to 1024, "2K" to 2048)

private data class NormRect(val x: Float, val y: Float, val w: Float, val h: Float)

/** Overlay source: another canvas element or an imported file. */
private class OverlaySource(val label: String, val bytes: ByteArray)

/**
 * Full-screen image editor (spec §6): crop with an interactive frame, resize,
 * non-destructive overlay of another image, brightness/contrast/rotate/flip.
 * Every operation renders a NEW PNG into app storage through [onResult]; the
 * source element is never touched.
 */
@Composable
internal fun MediaImageEditorSheet(
    element: CanvasElement,
    imageElements: List<CanvasElement>,
    onDismiss: () -> Unit,
    onResult: (ByteArray) -> Boolean,
) {
    val scope = rememberCoroutineScope()

    // Source decode happens once; every operation works on the same grid.
    var grid by remember { mutableStateOf<PixelGrid?>(null) }
    var loadError by remember { mutableStateOf<ImageEditException?>(null) }
    LaunchedEffect(element) {
        val bytes = withContext(Dispatchers.Default) { readEditTargetBytes(element) }
        if (bytes == null) {
            loadError = ImageEditException.DecodeFailed()
            return@LaunchedEffect
        }
        grid = try {
            withContext(Dispatchers.Default) { decodeImageToGrid(bytes) }
        } catch (e: ImageEditException) {
            loadError = e
            null
        }
    }

    var op by remember { mutableStateOf(ImageEditorOp.Crop) }

    // Operation state: progress 0..1, cancel flag, §8 error card.
    var opRunning by remember { mutableStateOf(false) }
    var opProgress by remember { mutableFloatStateOf(0f) }
    var opCancel by remember { mutableStateOf(false) }
    var opError by remember { mutableStateOf<ImageEditException?>(null) }
    var opJob by remember { mutableStateOf<Job?>(null) }
    var lastOp by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun launchOp(block: suspend (ImageEditProgress) -> ByteArray) {
        if (opJob?.isActive == true) return
        opError = null
        opCancel = false
        opProgress = 0f
        opRunning = true
        lastOp = { launchOp(block) }
        opJob = scope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    block { fraction ->
                        opProgress = fraction
                        !opCancel
                    }
                }
                if (!onResult(result)) throw ImageEditException.ExportFailed()
                onDismiss()
            } catch (e: ImageEditException) {
                opError = e
            } catch (e: CancellationException) {
                opError = ImageEditException.Cancelled()
            } finally {
                opRunning = false
            }
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Редактор изображения", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        (element.localPath ?: element.url)?.substringAfterLast('/') ?: "изображение",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Закрыть редактор", tint = MaterialTheme.colorScheme.onSurface)
                }
            }

            when {
                loadError != null && grid == null -> ImageEditErrorCard(loadError!!, null, onDismiss)

                grid == null -> Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator()
                }

                else -> {
                    val source = grid!!
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ImageEditorOp.entries.forEach { candidate ->
                            FilterChip(
                                selected = op == candidate,
                                onClick = { op = candidate },
                                label = { Text(candidate.title) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer),
                            )
                        }
                    }

                    // Operation status: progress with cancel, or the §8 error card.
                    when {
                        opRunning -> Row(
                            Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LinearProgressIndicator(
                                progress = { opProgress },
                                modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)),
                            )
                            Spacer(Modifier.width(12.dp))
                            OutlinedButton(onClick = { opCancel = true }) { Text("Отмена") }
                        }

                        opError != null -> ImageEditErrorCard(opError!!, lastOp?.let { retry -> { retry() } }, onDismiss = { opError = null })
                    }

                    when (op) {
                        ImageEditorOp.Crop -> CropPane(
                            source = source,
                            displaySource = element,
                            enabled = !opRunning,
                            onApply = { rect ->
                                launchOp { _ ->
                                    val x0 = (rect.x * source.width).roundToInt().coerceIn(0, source.width - 1)
                                    val y0 = (rect.y * source.height).roundToInt().coerceIn(0, source.height - 1)
                                    val w = (rect.w * source.width).roundToInt().coerceIn(1, source.width - x0)
                                    val h = (rect.h * source.height).roundToInt().coerceIn(1, source.height - y0)
                                    encodeGridToPng(source.cropGrid(x0, y0, w, h))
                                }
                            },
                        )

                        ImageEditorOp.Resize -> ResizePane(
                            source = source,
                            enabled = !opRunning,
                            onApply = { width, height ->
                                launchOp { progress ->
                                    encodeGridToPng(source.resizeGrid(width, height, progress))
                                }
                            },
                        )

                        ImageEditorOp.Overlay -> OverlayPane(
                            source = source,
                            displaySource = element,
                            imageElements = imageElements,
                            enabled = !opRunning,
                            onApply = { overlay, normX, normY, scale, opacity ->
                                launchOp { progress ->
                                    val top = decodeImageToGrid(overlay.bytes)
                                    val layerScale = source.width * scale / top.width
                                    val offsetX = (normX * source.width).roundToInt()
                                    val offsetY = (normY * source.height).roundToInt()
                                    encodeGridToPng(
                                        overlayGrid(source, top, offsetX, offsetY, scale = layerScale, opacity = opacity, onProgress = progress),
                                    )
                                }
                            },
                        )

                        ImageEditorOp.Adjust -> AdjustPane(
                            source = source,
                            displaySource = element,
                            enabled = !opRunning,
                            onApply = { brightness, contrast, turns, flipH, flipV ->
                                launchOp { progress ->
                                    var result = source.adjustBrightnessContrast(brightness, contrast) { fraction ->
                                        progress(fraction * 0.8f)
                                    }
                                    if (turns != 0) result = result.rotateQuadrant(turns)
                                    if (flipH) result = result.flipHorizontal()
                                    if (flipV) result = result.flipVertical()
                                    if (!progress(1f)) throw ImageEditException.Cancelled()
                                    encodeGridToPng(result)
                                }
                            },
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

/** §8 error card: essence, cause and a rescue action on the error container. */
@Composable
internal fun ImageEditErrorCard(error: ImageEditException, onRetry: (() -> Unit)?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Error,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(error.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
            }
            Spacer(Modifier.height(4.dp))
            Text(error.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                onRetry?.let { retry -> Button(onClick = retry) { Text("Повторить") } }
                OutlinedButton(onClick = onDismiss) { Text("Скрыть") }
            }
        }
    }
}

/** The preview box: the image fitted into [available], aspect preserved. */
private fun fittedPreview(available: IntSize, imageWidth: Int, imageHeight: Int): IntSize {
    if (available.width < 2 || available.height < 2 || imageWidth < 1 || imageHeight < 1) return IntSize.Zero
    val scale = minOf(available.width.toFloat() / imageWidth, available.height.toFloat() / imageHeight)
    return IntSize((imageWidth * scale).roundToInt().coerceAtLeast(1), (imageHeight * scale).roundToInt().coerceAtLeast(1))
}

// -- Crop ------------------------------------------------------------------

/**
 * Interactive crop frame: corner handles, drag-to-move, rule-of-thirds grid
 * and aspect chips. The frame lives in normalized image coordinates 0..1.
 */
@Composable
private fun CropPane(
    source: PixelGrid,
    displaySource: CanvasElement,
    enabled: Boolean,
    onApply: (NormRect) -> Unit,
) {
    var rect by remember { mutableStateOf(NormRect(0f, 0f, 1f, 1f)) }
    var aspect by remember { mutableStateOf<Float?>(null) }
    var previewSize by remember { mutableStateOf(IntSize.Zero) }

    val fitted = fittedPreview(previewSize, source.width, source.height)

    Text("Обрезка", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
    Text(
        "Растяните рамку за углы или перетащите её целиком. Исходное изображение не изменяется.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        CROP_ASPECTS.forEach { (label, ratio) ->
            FilterChip(
                selected = aspect == ratio,
                onClick = {
                    aspect = ratio
                    rect = centeredAspectRect(ratio, source.width, source.height)
                },
                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer),
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Box(
        Modifier.fillMaxWidth().height(320.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).onSizeChanged { previewSize = it },
        contentAlignment = Alignment.Center,
    ) {
        if (fitted != IntSize.Zero) {
            Box(Modifier.size(fitted.width.dp, fitted.height.dp)) {
                AsyncImage(
                    model = rememberImageModel(displaySource.url ?: displaySource.localPath),
                    contentDescription = "Предпросмотр обрезки",
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize(),
                )
                CropFrame(
                    rect = rect,
                    preview = fitted,
                    enabled = enabled,
                    onRect = { rect = it },
                )
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Область: ${(rect.w * source.width).roundToInt()} × ${(rect.h * source.height).roundToInt()} px",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Button(onClick = { onApply(rect) }, enabled = enabled) {
            Icon(Icons.Filled.Crop, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Обрезать")
        }
    }
}

/** Largest centered rect with a pixel aspect [ratio]; the full frame when null. */
private fun centeredAspectRect(ratio: Float?, imageWidth: Int, imageHeight: Int): NormRect {
    if (ratio == null) return NormRect(0f, 0f, 1f, 1f)
    val imageAspect = imageWidth.toFloat() / imageHeight
    val wn: Float
    val hn: Float
    if (ratio >= imageAspect) {
        wn = 1f
        hn = imageAspect / ratio
    } else {
        hn = 1f
        wn = ratio / imageAspect
    }
    return NormRect((1f - wn) / 2f, (1f - hn) / 2f, wn, hn)
}

/** Frame border, rule-of-thirds grid, draggable interior and corner handles. */
@Composable
private fun CropFrame(
    rect: NormRect,
    preview: IntSize,
    enabled: Boolean,
    onRect: (NormRect) -> Unit,
) {
    val borderColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(rect, preview, enabled) {
                if (!enabled) return@pointerInput
                detectDragGestures { change, _ ->
                    change.consume()
                    val nx = (change.position.x / preview.width - rect.w / 2f).coerceIn(0f, 1f - rect.w)
                    val ny = (change.position.y / preview.height - rect.h / 2f).coerceIn(0f, 1f - rect.h)
                    onRect(rect.copy(x = nx, y = ny))
                }
            },
    ) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val left = rect.x * size.width
            val top = rect.y * size.height
            val right = (rect.x + rect.w) * size.width
            val bottom = (rect.y + rect.h) * size.height
            drawRect(borderColor, topLeft = Offset(left, top), size = androidx.compose.ui.geometry.Size(right - left, bottom - top), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f))
            for (i in 1..2) {
                val gx = left + (right - left) * i / 3f
                val gy = top + (bottom - top) * i / 3f
                drawLine(gridColor, Offset(gx, top), Offset(gx, bottom), strokeWidth = 1f)
                drawLine(gridColor, Offset(left, gy), Offset(right, gy), strokeWidth = 1f)
            }
        }
        // Corner handles: 0 = top-left, 1 = top-right, 2 = bottom-right, 3 = bottom-left.
        for (corner in 0..3) {
            val hx = if (corner == 0 || corner == 3) rect.x else rect.x + rect.w
            val hy = if (corner < 2) rect.y else rect.y + rect.h
            Box(
                Modifier
                    .offset { IntOffset((hx * preview.width).roundToInt() - 8, (hy * preview.height).roundToInt() - 8) }
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(borderColor)
                    .semantics { contentDescription = "Ручка рамки ${corner + 1}" }
                    .pointerInput(corner, rect, preview, enabled) {
                        if (!enabled) return@pointerInput
                        detectDragGestures { change, _ ->
                            change.consume()
                            val parentX = (change.position.x + (hx * preview.width) - 8f) / preview.width
                            val parentY = (change.position.y + (hy * preview.height) - 8f) / preview.height
                            onRect(dragCorner(rect, corner, parentX, parentY))
                        }
                    },
            )
        }
    }
}

/** Moves one corner of [rect] to (px, py), keeping it inside 0..1. */
private fun dragCorner(rect: NormRect, corner: Int, px: Float, py: Float): NormRect {
    // Anchor is the opposite corner.
    val ax = if (corner == 0 || corner == 3) rect.x + rect.w else rect.x
    val ay = if (corner == 0 || corner == 1) rect.y + rect.h else rect.y
    val rawW = (px - ax).coerceIn(-1f, 1f)
    val rawH = (py - ay).coerceIn(-1f, 1f)
    val minSide = 0.02f
    val w = maxOf(abs(rawW), minSide)
    val h = maxOf(abs(rawH), minSide)
    var nx = if (rawW >= 0) ax else ax - w
    var ny = if (rawH >= 0) ay else ay - h
    var nw = w
    var nh = h
    if (nx < 0f) {
        nw += nx
        nx = 0f
    }
    if (ny < 0f) {
        nh += ny
        ny = 0f
    }
    if (nx + nw > 1f) nw = 1f - nx
    if (ny + nh > 1f) nh = 1f - ny
    return NormRect(nx, ny, nw.coerceAtLeast(minSide), nh.coerceAtLeast(minSide))
}

// -- Resize -------------------------------------------------------------------

@Composable
private fun ResizePane(
    source: PixelGrid,
    enabled: Boolean,
    onApply: (Int, Int) -> Unit,
) {
    var widthText by remember { mutableStateOf(source.width.toString()) }
    var heightText by remember { mutableStateOf(source.height.toString()) }
    var keepAspect by remember { mutableStateOf(true) }
    val aspect = source.width.toFloat() / source.height

    Text("Размер", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
    Text(
        "Исходное разрешение ${source.width} × ${source.height} px. Результат — новое изображение.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = widthText,
            onValueChange = { value ->
                val digits = value.filter(Char::isDigit).take(5)
                widthText = digits
                val parsed = digits.toIntOrNull()
                if (keepAspect && parsed != null && parsed > 0) {
                    heightText = (parsed / aspect).roundToInt().coerceAtLeast(1).toString()
                }
            },
            label = { Text("Ширина, px") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = heightText,
            onValueChange = { value ->
                val digits = value.filter(Char::isDigit).take(5)
                heightText = digits
                val parsed = digits.toIntOrNull()
                if (keepAspect && parsed != null && parsed > 0) {
                    widthText = (parsed * aspect).roundToInt().coerceAtLeast(1).toString()
                }
            },
            label = { Text("Высота, px") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
    }
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = keepAspect, onCheckedChange = { keepAspect = it }, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Text("Сохранять пропорции", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.weight(1f))
        RESIZE_PRESETS.forEach { (label, side) ->
            FilterChip(
                selected = false,
                onClick = {
                    if (source.width >= source.height) {
                        widthText = side.toString()
                        heightText = (side / aspect).roundToInt().coerceAtLeast(1).toString()
                    } else {
                        heightText = side.toString()
                        widthText = (side * aspect).roundToInt().coerceAtLeast(1).toString()
                    }
                },
                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
            )
            Spacer(Modifier.width(4.dp))
        }
    }
    Spacer(Modifier.height(8.dp))
    val width = widthText.toIntOrNull()
    val height = heightText.toIntOrNull()
    val valid = width != null && height != null && width in 1..8192 && height in 1..8192
    Button(onClick = { onApply(width!!, height!!) }, enabled = enabled && valid) {
        Icon(Icons.Filled.PhotoSizeSelectLarge, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text("Изменить размер")
    }
}

// -- Overlay -------------------------------------------------------------------

@Composable
private fun OverlayPane(
    source: PixelGrid,
    displaySource: CanvasElement,
    imageElements: List<CanvasElement>,
    enabled: Boolean,
    onApply: (OverlaySource, Float, Float, Float, Float) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var overlay by remember { mutableStateOf<OverlaySource?>(null) }
    var overlayBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var overlayAspect by remember { mutableFloatStateOf(1f) }
    var decodeError by remember { mutableStateOf<ImageEditException?>(null) }
    var normX by remember { mutableFloatStateOf(0.1f) }
    var normY by remember { mutableFloatStateOf(0.1f) }
    var scale by remember { mutableFloatStateOf(0.5f) }
    var opacity by remember { mutableFloatStateOf(1f) }
    var previewSize by remember { mutableStateOf(IntSize.Zero) }
    var showPicker by remember { mutableStateOf(false) }

    suspend fun loadOverlay(label: String, bytes: ByteArray) {
        decodeError = null
        val decoded = try {
            withContext(Dispatchers.Default) { decodeImageToGrid(bytes) }
        } catch (e: ImageEditException) {
            decodeError = e
            null
        } ?: return
        overlay = OverlaySource(label, bytes)
        overlayBitmap = withContext(Dispatchers.Default) { decodeToImageBitmap(bytes) }
        overlayAspect = decoded.width.toFloat() / decoded.height
        normX = 0.1f
        normY = 0.1f
        scale = 0.5f
        opacity = 1f
    }

    val filePicker = rememberFilePickerLauncher(type = FileKitType.File(extensions = listOf("png", "jpg", "jpeg", "webp"))) { file: PlatformFile? ->
        file ?: return@rememberFilePickerLauncher
        scope.launch {
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@launch
            loadOverlay(file.name, bytes)
        }
    }

    val fitted = fittedPreview(previewSize, source.width, source.height)

    Text("Наложение", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
    Text(
        "Выберите изображение и перетащите его на предпросмотре. Результат — новое изображение.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
    Box {
        OutlinedButton(onClick = { showPicker = true }, enabled = enabled) {
            Icon(Icons.Filled.Layers, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(overlay?.label ?: "Выбрать изображение")
        }
        DropdownMenu(expanded = showPicker, onDismissRequest = { showPicker = false }) {
            if (imageElements.isEmpty()) {
                DropdownMenuItem(text = { Text("Других изображений на канвасе нет") }, onClick = { showPicker = false })
            }
            imageElements.forEach { candidate ->
                DropdownMenuItem(
                    text = { Text((candidate.localPath ?: candidate.url)?.substringAfterLast('/') ?: "изображение") },
                    onClick = {
                        showPicker = false
                        scope.launch {
                            val bytes = withContext(Dispatchers.Default) { readEditTargetBytes(candidate) }
                            if (bytes != null) {
                                loadOverlay((candidate.localPath ?: candidate.url)?.substringAfterLast('/') ?: "изображение", bytes)
                            } else {
                                decodeError = ImageEditException.DecodeFailed()
                            }
                        }
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("Из файла…") },
                onClick = {
                    showPicker = false
                    filePicker.launch()
                },
            )
        }
    }
    decodeError?.let { error ->
        ImageEditErrorCard(error, null, { decodeError = null })
    }
    Spacer(Modifier.height(8.dp))
    Box(
        Modifier.fillMaxWidth().height(320.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).onSizeChanged { previewSize = it },
        contentAlignment = Alignment.Center,
    ) {
        if (fitted != IntSize.Zero) {
            Box(Modifier.size(fitted.width.dp, fitted.height.dp)) {
                AsyncImage(
                    model = rememberImageModel(displaySource.url ?: displaySource.localPath),
                    contentDescription = "Предпросмотр наложения",
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize(),
                )
                val bitmap = overlayBitmap
                if (overlay != null && bitmap != null) {
                    val overlayW = scale * fitted.width
                    val overlayH = overlayW / overlayAspect
                    Box(
                        Modifier
                            .offset { IntOffset((normX * fitted.width).roundToInt(), (normY * fitted.height).roundToInt()) }
                            .size(overlayW.dp, overlayH.dp)
                            .graphicsLayer(alpha = opacity)
                            .pointerInput(fitted, enabled) {
                                if (!enabled) return@pointerInput
                                detectDragGestures { change, drag ->
                                    change.consume()
                                    normX = (normX + drag.x / fitted.width).coerceIn(-0.9f, 0.95f)
                                    normY = (normY + drag.y / fitted.height).coerceIn(-0.9f, 0.95f)
                                }
                            },
                    ) {
                        Image(bitmap, contentDescription = "Наложение", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Масштаб", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value = scale, onValueChange = { scale = it }, valueRange = 0.05f..2f, modifier = Modifier.weight(1f).padding(horizontal = 8.dp), enabled = enabled)
        Text("${(scale * 100).roundToInt()} %", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Прозрачность", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value = opacity, onValueChange = { opacity = it }, valueRange = 0f..1f, modifier = Modifier.weight(1f).padding(horizontal = 8.dp), enabled = enabled)
        Text("${(opacity * 100).roundToInt()} %", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
    }
    Spacer(Modifier.height(4.dp))
    Button(onClick = { overlay?.let { onApply(it, normX, normY, scale, opacity) } }, enabled = enabled && overlay != null) {
        Icon(Icons.Filled.Layers, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text("Наложить")
    }
}

// -- Adjust ---------------------------------------------------------------------

@Composable
private fun AdjustPane(
    source: PixelGrid,
    displaySource: CanvasElement,
    enabled: Boolean,
    onApply: (Float, Float, Int, Boolean, Boolean) -> Unit,
) {
    var brightness by remember { mutableFloatStateOf(0f) }
    var contrast by remember { mutableFloatStateOf(1f) }
    var turns by remember { mutableIntStateOf(0) }
    var flipH by remember { mutableStateOf(false) }
    var flipV by remember { mutableStateOf(false) }
    var previewSize by remember { mutableStateOf(IntSize.Zero) }

    // Live preview: the same math as the bake (contrast around 128, then brightness).
    val matrix = remember(brightness, contrast) {
        val offset = 128f * (1f - contrast) + brightness * 255f
        ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, offset,
                0f, contrast, 0f, 0f, offset,
                0f, 0f, contrast, 0f, offset,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
    }
    val swapped = ((turns % 4) + 4) % 4 % 2 == 1
    val imageAspect = if (swapped) source.height.toFloat() / source.width else source.width.toFloat() / source.height
    val fitted = fittedPreview(previewSize, if (swapped) source.height else source.width, if (swapped) source.width else source.height)
    val innerFitted = fittedPreview(previewSize, source.width, source.height)

    Text("Коррекция", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
    Text(
        "Яркость, контраст, поворот на 90° и отражение. Результат — новое изображение.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
    Box(
        Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).onSizeChanged { previewSize = it },
        contentAlignment = Alignment.Center,
    ) {
        if (fitted != IntSize.Zero && imageAspect > 0f) {
            Box(Modifier.size(fitted.width.dp, fitted.height.dp), contentAlignment = Alignment.Center) {
                AsyncImage(
                    model = rememberImageModel(displaySource.url ?: displaySource.localPath),
                    contentDescription = "Предпросмотр коррекции",
                    contentScale = ContentScale.FillBounds,
                    colorFilter = ColorFilter.colorMatrix(matrix),
                    modifier = Modifier
                        .size(innerFitted.width.dp, innerFitted.height.dp)
                        .graphicsLayer(
                            rotationZ = turns * 90f,
                            scaleX = if (flipH) -1f else 1f,
                            scaleY = if (flipV) -1f else 1f,
                        ),
                )
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Яркость", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value = brightness, onValueChange = { brightness = it }, valueRange = -1f..1f, modifier = Modifier.weight(1f).padding(horizontal = 8.dp), enabled = enabled)
        Text("${(brightness * 100).roundToInt()} %", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Контраст", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value = contrast, onValueChange = { contrast = it }, valueRange = 0f..4f, modifier = Modifier.weight(1f).padding(horizontal = 8.dp), enabled = enabled)
        Text("${(contrast * 100).roundToInt()} %", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { turns -= 1 }, enabled = enabled, modifier = Modifier.handCursor()) {
            Icon(Icons.AutoMirrored.Filled.RotateLeft, contentDescription = "Повернуть влево", tint = MaterialTheme.colorScheme.onSurface)
        }
        IconButton(onClick = { turns += 1 }, enabled = enabled, modifier = Modifier.handCursor()) {
            Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = "Повернуть вправо", tint = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.width(8.dp))
        FilterChip(selected = flipH, onClick = { flipH = !flipH }, label = { Text("Отразить по горизонтали") }, enabled = enabled)
        Spacer(Modifier.width(6.dp))
        FilterChip(selected = flipV, onClick = { flipV = !flipV }, label = { Text("Отразить по вертикали") }, enabled = enabled)
        Spacer(Modifier.weight(1f))
        Icon(Icons.Filled.Flip, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
    Spacer(Modifier.height(8.dp))
    Button(onClick = { onApply(brightness, contrast, turns, flipH, flipV) }, enabled = enabled) {
        Icon(Icons.Filled.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text("Применить коррекцию")
    }
}

private suspend fun readEditTargetBytes(element: CanvasElement): ByteArray? = when {
    element.localPath != null -> readAppStorageBytes(element.localPath!!)
    element.url != null -> runCatching { PlusVibeMediaProvider().downloadResult("", element.url!!) }.getOrNull()
    else -> null
}
