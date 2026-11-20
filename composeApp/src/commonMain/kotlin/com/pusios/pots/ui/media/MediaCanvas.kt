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
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FlipToBack
import androidx.compose.material.icons.filled.FlipToFront
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MovieFilter
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.pusios.pots.media.MediaJobState
import com.pusios.pots.media.videoEditingPlatformSupported
import com.pusios.pots.ui.handCursor
import kotlin.math.roundToInt

/** Element footprint on the canvas (dp): the fixed WIDTH; also the menu anchor height. */
internal const val CANVAS_ELEMENT_SIZE_DP = 232f

/** Fallback frame for a video whose aspect is not probed yet: the common 16:9 generation output. */
internal const val DEFAULT_VIDEO_ASPECT_RATIO = 16f / 9f

/**
 * Element box height from the source aspect (width / height), @joni's block-4
 * contract: the width stays 232.dp, the height follows the ratio, clamped to
 * 16:9…9:16 — beyond the clamp the content renders Fit inside the clamped
 * frame. Unknown or junk ratios fall back to the old square, so a remote
 * image does not jump before its painter arrives.
 */
internal fun canvasElementHeightDp(aspectRatio: Float?): Float {
    val ratio = aspectRatio?.takeIf { it.isFinite() && it > 0f } ?: 1f
    return (CANVAS_ELEMENT_SIZE_DP / ratio).coerceIn(
        CANVAS_ELEMENT_SIZE_DP * 9f / 16f,
        CANVAS_ELEMENT_SIZE_DP * 16f / 9f,
    )
}

/**
 * The creative surface: a dotted-grid canvas with pan/zoom, draggable rounded
 * layers, selection ring with quick-transform chips, generation skeletons
 * and an inviting empty state.
 *
 * [layers] arrives in draw order (the list is the z-order); nothing here sorts.
 */
@Composable
internal fun MediaCanvas(
    layers: List<CanvasLayer>,
    selectedId: String?,
    pendingJobs: List<MediaJobState>,
    canvasScale: Float,
    canvasOffset: Offset,
    onTransform: (zoomChange: Float, panChange: Offset) -> Unit,
    onTapBackground: () -> Unit,
    /** Double-tap/double-click on the empty canvas recenters the view (moved off the toolbar). */
    onRecenter: () -> Unit,
    onSelect: (String) -> Unit,
    onMove: (String, Float, Float) -> Unit,
    onRotate: (String, Float) -> Unit,
    onScale: (String, Float) -> Unit,
    onAction: (String, CanvasAction) -> Unit,
    onExportToGallery: (String) -> Unit,
    onOpenLightbox: (MediaLayer) -> Unit,
    onEditAudio: (AudioLayer) -> Unit,
    onMakeClip: (AudioLayer) -> Unit,
    onDrawImage: (MediaLayer) -> Unit,
    onEditImage: (ImageLayer) -> Unit,
    onEditVideo: (VideoLayer) -> Unit,
    modifier: Modifier = Modifier,
) {
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
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { onRecenter() },
                    onTap = { onTapBackground() },
                )
            },
    ) {
        CanvasDotGrid(canvasOffset)

        // Hidden layers are neither drawn nor hit-tested: they are not in the
        // composition at all, so no pointer can land on one.
        layers.visibleLayers().forEach { layer ->
            CanvasLayerView(
                layer = layer,
                selected = layer.id == selectedId,
                canvasScale = canvasScale,
                canvasOffset = canvasOffset,
                onSelect = { onSelect(layer.id) },
                onMove = onMove,
                onRotate = onRotate,
                onScale = onScale,
                onAction = onAction,
                onExportToGallery = onExportToGallery,
                onOpenLightbox = onOpenLightbox,
                onEditAudio = onEditAudio,
                onMakeClip = onMakeClip,
                onDrawImage = onDrawImage,
                onEditImage = onEditImage,
                onEditVideo = onEditVideo,
            )
        }

        if (pendingJobs.isNotEmpty()) {
            Row(Modifier.align(Alignment.Center)) {
                pendingJobs.forEach { job ->
                    GenerationSkeleton(job, Modifier.padding(8.dp))
                }
            }
        }

        if (layers.isEmpty() && pendingJobs.isEmpty()) {
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
private fun CanvasLayerView(
    layer: CanvasLayer,
    selected: Boolean,
    canvasScale: Float,
    canvasOffset: Offset,
    onSelect: () -> Unit,
    onMove: (String, Float, Float) -> Unit,
    onRotate: (String, Float) -> Unit,
    onScale: (String, Float) -> Unit,
    onAction: (String, CanvasAction) -> Unit,
    onExportToGallery: (String) -> Unit,
    onOpenLightbox: (MediaLayer) -> Unit,
    onEditAudio: (AudioLayer) -> Unit,
    onMakeClip: (AudioLayer) -> Unit,
    onDrawImage: (MediaLayer) -> Unit,
    onEditImage: (ImageLayer) -> Unit,
    onEditVideo: (VideoLayer) -> Unit,
) {
    // The caller filters hidden layers out (`visibleLayers`); a hidden layer is
    // not composed, so it cannot be drawn and cannot take a tap.
    val media = layer as? MediaLayer
    val url = media?.url
    val local = media?.localPath
    // The image aspect arrives with the decoded painter (a remote URL is only
    // known after the load); a video carries its probed frame aspect on the
    // layer. Audio keeps the fixed square card (@joni: unchanged).
    var imageAspect by remember(url, local) { mutableStateOf<Float?>(null) }
    val aspect = when (layer) {
        is AudioLayer -> null
        is VideoLayer -> layer.aspectRatio ?: DEFAULT_VIDEO_ASPECT_RATIO
        is ImageLayer -> imageAspect
        else -> null
    }
    val heightDp = if (layer is AudioLayer) CANVAS_ELEMENT_SIZE_DP else canvasElementHeightDp(aspect)
    Box(
        Modifier
            .offset {
                IntOffset(
                    (layer.transform.x + canvasOffset.x).roundToInt(),
                    (layer.transform.y + canvasOffset.y).roundToInt(),
                )
            }
            .width(CANVAS_ELEMENT_SIZE_DP.dp)
            .height(heightDp.dp)
            .graphicsLayer(
                scaleX = layer.transform.scale * canvasScale,
                scaleY = layer.transform.scale * canvasScale,
                rotationZ = layer.transform.rotation,
                // Layer-wide alpha: the whole layer composites as one, so
                // strokes inside it never show through each other.
                alpha = layer.transform.opacity,
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
            .pointerInput(layer.id) {
                detectTransformGestures { _, pan, zoom, rotation ->
                    onMove(layer.id, pan.x, pan.y)
                    if (zoom != 1f) onScale(layer.id, zoom)
                    if (rotation != 0f) onRotate(layer.id, rotation)
                }
            }
            .semantics {
                contentDescription = when (layer) {
                    is AudioLayer -> "Аудио-элемент"
                    is VideoLayer -> "Видео-элемент"
                    is ImageLayer -> "Изображение"
                    is TextLayer -> "Текст"
                    is StrokeLayer -> "Штрих"
                    is ArrowLayer -> "Стрелка"
                }
            }
            .clickable(role = Role.Button, onClick = onSelect),
    ) {
        when (layer) {
            is AudioLayer -> MediaAudioPlayer(
                source = layer.localPath ?: layer.url,
                modifier = Modifier.fillMaxSize().padding(vertical = 24.dp),
            )

            is VideoLayer -> MediaVideoPlayer(
                url = url ?: local ?: "",
                autoplay = false,
                fullControls = true,
                // The surface fills the proportional element box instead of a fixed height.
                surfaceHeight = Dp.Unspecified,
                modifier = Modifier.fillMaxSize(),
            )

            is ImageLayer -> AsyncImage(
                model = rememberImageModel(url ?: local),
                contentDescription = "Изображение на канвасе",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                onState = { state ->
                    val size = (state as? AsyncImagePainter.State.Success)?.painter?.intrinsicSize
                    if (size != null && size.height > 0f) imageAspect = size.width / size.height
                },
            )

            // Text, strokes and arrows are painted by the DrawScope renderer
            // (t_00019f0b). No tool creates them yet, so an empty body here is
            // not a visible gap — the selection box and its chips still work.
            is TextLayer, is StrokeLayer, is ArrowLayer -> Unit
        }

        if (selected) {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).padding(6.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                tonalElevation = 4.dp,
            ) {
                Column(Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
                    if (layer is AudioLayer) {
                        // Audio card: playback lives in the card itself; the panel
                        // keeps layer/save/delete. No reference or transform actions.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ElementChip(Icons.Filled.FlipToFront, "Выше по слоям") { onAction(layer.id, CanvasAction.BringForward) }
                            ElementChip(Icons.Filled.FlipToBack, "Ниже по слоям") { onAction(layer.id, CanvasAction.SendBack) }
                            ElementChip(Icons.Filled.ContentCopy, "Дублировать") { onAction(layer.id, CanvasAction.Duplicate) }
                            ElementChip(Icons.Filled.Edit, "Редактировать") { onEditAudio(layer) }
                            if (videoEditingPlatformSupported()) {
                                // Audio-reactive clip (spec §11): desktop-only like the
                                // video editor — the render rides the system ffmpeg.
                                ElementChip(Icons.Filled.MovieFilter, "Сделать клип") { onMakeClip(layer) }
                            }
                            ElementChip(Icons.Filled.Fullscreen, "Открыть плеер") { media?.let(onOpenLightbox) }
                            ElementChip(Icons.Filled.SaveAlt, "Сохранить в галерею") { onExportToGallery(layer.id) }
                            ElementChip(Icons.Filled.Delete, "Удалить", tint = MaterialTheme.colorScheme.error) {
                                onAction(layer.id, CanvasAction.Delete)
                            }
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ElementChip(Icons.AutoMirrored.Filled.RotateLeft, "Повернуть влево") { onRotate(layer.id, -15f) }
                            ElementChip(Icons.AutoMirrored.Filled.RotateRight, "Повернуть вправо") { onRotate(layer.id, 15f) }
                            ElementChip(Icons.Filled.Remove, "Уменьшить") { onScale(layer.id, 0.85f) }
                            ElementChip(Icons.Filled.Add, "Увеличить") { onScale(layer.id, 1.2f) }
                            ElementChip(Icons.Filled.Fullscreen, "Открыть на весь экран") { media?.let(onOpenLightbox) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (layer is ImageLayer) {
                                // Image-only actions: video elements cannot be
                                // decoded for pixel editing.
                                ElementChip(Icons.Filled.Brush, "Карандаш") { onDrawImage(layer) }
                                ElementChip(Icons.Filled.Edit, "Редактировать") { onEditImage(layer) }
                            } else if (layer is VideoLayer && videoEditingPlatformSupported()) {
                                // Video editor (trim/speed/audio track) and the pencil
                                // capture run on the desktop ffmpeg; other targets never
                                // show them. Block 7: the pencil draws over a still frame
                                // taken off the track at native resolution.
                                ElementChip(Icons.Filled.Brush, "Кадр с карандашом") { onDrawImage(layer) }
                                ElementChip(Icons.Filled.Edit, "Редактировать") { onEditVideo(layer) }
                            }
                            ElementChip(Icons.Filled.FlipToFront, "Выше по слоям") { onAction(layer.id, CanvasAction.BringForward) }
                            ElementChip(Icons.Filled.FlipToBack, "Ниже по слоям") { onAction(layer.id, CanvasAction.SendBack) }
                            ElementChip(Icons.Filled.ContentCopy, "Дублировать") { onAction(layer.id, CanvasAction.Duplicate) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ElementChip(Icons.Filled.Link, "Использовать как референс") { onAction(layer.id, CanvasAction.UseAsReference) }
                            ElementChip(Icons.Filled.SaveAlt, "Сохранить в галерею") { onExportToGallery(layer.id) }
                            ElementChip(Icons.Filled.Delete, "Удалить", tint = MaterialTheme.colorScheme.error) {
                                onAction(layer.id, CanvasAction.Delete)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ElementChip(
    icon: ImageVector,
    contentDescription: String,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(34.dp).handCursor()) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(18.dp))
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
