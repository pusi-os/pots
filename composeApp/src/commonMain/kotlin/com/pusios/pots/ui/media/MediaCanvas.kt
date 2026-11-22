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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.rememberAsyncImagePainter
import com.pusios.pots.media.MediaJobState
import com.pusios.pots.media.videoEditingPlatformSupported
import com.pusios.pots.ui.handCursor
import kotlin.math.roundToInt

/**
 * The card shape every pass shares: the renderer's rounded fill, the overlay
 * clip of the video player and the selection ring all use the same radius, so
 * they cannot drift apart.
 */
private val CanvasCardShape = RoundedCornerShape(CANVAS_CARD_CORNER_DP.dp)

/**
 * The creative surface: a dotted-grid canvas with pan/zoom, draggable rounded
 * layers, selection ring with quick-transform chips, generation skeletons
 * and an inviting empty state.
 *
 * The layers themselves are drawn by [renderLayers] on one [Canvas] — the same
 * renderer an export will call. The composition keeps what is not content: the
 * grid, the drop shadows, hit targets, gestures, the selection ring and the
 * chips. Frames are decoded by Coil and handed to the renderer as painters, so
 * the Coil cache stays the only copy of the bitmap (a decoded 2048² ImageBitmap
 * in state would be 16 MB per layer).
 *
 * The video player cannot live in a [DrawScope], so it stays a composable above
 * the canvas, clipped to [CanvasCardShape] to match the card the renderer
 * paints underneath. Its frame is therefore not part of the renderer yet and
 * does not reach an export (a separate card).
 *
 * [draw] turns the canvas into a drawing surface: the layers stay where they
 * are, the strokes land on top of them as layers of their own, and the gestures
 * are split by finger count — one finger inks, two fingers pan and zoom (see
 * [drawPointerInput]). The stroke under the finger is drawn on its own canvas,
 * so a finger move invalidates that draw alone: the layer pass, its decoded
 * frames and its cached stroke paths are left alone until the stroke lands.
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
    onEditImage: (ImageLayer) -> Unit,
    onEditVideo: (VideoLayer) -> Unit,
    /** A tap on a text layer opens the text editor (the card's rule: tap = edit). */
    onEditText: (TextLayer) -> Unit = {},
    /** Width factor of a text layer's box, from the «уже»/«шире» chips. */
    onTextWidth: (String, Float) -> Unit = { _, _ -> },
    /** Non-null while the draw mode is on: the active tool and where its strokes go. */
    draw: DrawSurface? = null,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current.density
    // Font() is @Composable, so families are resolved here and handed to the
    // renderer; it cannot load one itself.
    val fonts = rememberCanvasFonts()
    val measurer = rememberTextMeasurer()
    val frames = remember { mutableStateMapOf<String, Painter>() }
    val cardColor = MaterialTheme.colorScheme.surfaceVariant
    LayerFrames(layers, frames)

    // A finished stroke never changes, so its Path is built once per layer list
    // change and reused by every frame; the draw pass does not rebuild 20 paths
    // on each finger move.
    val strokePaths = remember(layers) {
        layers.filterIsInstance<StrokeLayer>().associate { it.id to strokePath(it.points) }
    }
    // Pixels per canvas unit on screen: density times the canvas zoom. The ink
    // is stored in canvas units, so this one number converts both ways — a
    // point under the finger and a stroke on the layer pass agree at any zoom.
    val pxPerUnit = density * canvasScale
    // The stroke under the finger. Read only inside a draw block, so a new point
    // invalidates that draw and nothing else; equal points are dropped by the
    // snapshot policy and cause no invalidation at all.
    var currentStroke by remember { mutableStateOf<CurrentStroke?>(null) }
    val drawNow = rememberUpdatedState(draw)
    val offsetNow = rememberUpdatedState(canvasOffset)
    val unitNow = rememberUpdatedState(pxPerUnit)
    val transformNow = rememberUpdatedState(onTransform)

    Box(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .semantics { contentDescription = "Медиа-канвас" }
            .then(
                if (draw == null) {
                    Modifier
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
                        }
                } else {
                    // Draw mode owns the gesture: a single finger inks, two
                    // fingers pan and zoom. `transformable` is left out on
                    // purpose — it pans with one finger too, and that is the
                    // conflict this mode has to resolve.
                    Modifier.pointerInput(Unit) {
                        drawPointerInput(
                            draw = drawNow,
                            pan = offsetNow,
                            pxPerUnit = unitNow,
                            onTransform = { zoom, panChange -> transformNow.value(zoom, panChange) },
                            currentStroke = { currentStroke },
                            onCurrentStroke = { currentStroke = it },
                        )
                    }
                },
            ),
    ) {
        CanvasDotGrid(canvasOffset)

        // Drop shadows sit under the frames: DrawScope has no shadow API in
        // common, so the silhouette stays a composable. A shadow of a higher
        // layer does not darken a lower layer's frame — the shadow-only delta
        // of this split, no content moves.
        LayerCardShadows(layers, canvasScale, canvasOffset, frames, measurer, fonts)

        Canvas(Modifier.fillMaxSize()) {
            withTransform({ translate(canvasOffset.x, canvasOffset.y) }) {
                renderLayers(
                    layers = layers,
                    scale = density,
                    fonts = fonts,
                    measurer = measurer,
                    frames = frames,
                    strokePaths = strokePaths,
                    cardBackground = cardColor,
                    zoom = canvasScale,
                )
            }
        }

        if (draw != null) {
            // The stroke under the finger, on top of the finished ones. Its own
            // Canvas: only this draw invalidates while the finger moves. Same
            // transform stack as the layer pass, so nothing jumps on commit.
            Canvas(Modifier.fillMaxSize()) {
                val ink = currentStroke ?: return@Canvas
                val path = strokePath(ink.points)
                withTransform({
                    translate(canvasOffset.x, canvasOffset.y)
                    scale(pxPerUnit, pxPerUnit, Offset.Zero)
                }) {
                    drawStrokeInk(
                        points = ink.points,
                        path = path,
                        colorArgb = ink.colorArgb,
                        widthPx = ink.widthPx,
                        alpha = 1f,
                        scale = 1f,
                    )
                }
            }
        }

        layers.forEach { layer ->
            CanvasLayerOverlay(
                layer = layer,
                box = canvasLayerBox(layer, frames[layer.id], measurer, fonts),
                // Draw mode shows no selection: the finger inks, and a border
                // left over from before would read as a target to hit.
                selected = draw == null && layer.id == selectedId,
                canvasScale = canvasScale,
                canvasOffset = canvasOffset,
                frame = frames[layer.id],
                interactive = draw == null,
                onSelect = { onSelect(layer.id) },
                onEditText = onEditText,
                onTextWidth = onTextWidth,
                onMove = onMove,
                onRotate = onRotate,
                onScale = onScale,
                onAction = onAction,
                onExportToGallery = onExportToGallery,
                onOpenLightbox = onOpenLightbox,
                onEditAudio = onEditAudio,
                onMakeClip = onMakeClip,
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

/** The stroke under the finger: canvas units, the ink it started with. */
private data class CurrentStroke(
    val points: List<Offset>,
    val colorArgb: Int,
    val widthPx: Float,
)

/**
 * Draw-mode gestures: one finger inks, two fingers pan and zoom.
 *
 * The split is by pointer count inside one handler rather than by two competing
 * detectors, because both alternatives fight: `transformable` pans with a
 * single finger (so a stroke would drag the canvas), and a drawing detector that
 * consumes moves cancels `transformable`'s gesture for the rest of the touch.
 * So:
 * - every move of a single pointer is consumed here — nothing pans under the pen;
 * - a second pointer that lands abandons the unfinished stroke (nothing half-drawn
 *   is committed) and the rest of the gesture is a pinch;
 * - a pinch that starts with two fingers never inks: the first event already
 *   carries two pointers.
 *
 * Positions arrive in screen px and are converted to canvas units here — the
 * canvas is panned by [pan] and one unit is [pxPerUnit] px on screen (density
 * times zoom), and the ink is stored in the units the layers are measured in.
 */
private suspend fun PointerInputScope.drawPointerInput(
    draw: State<DrawSurface?>,
    pan: State<Offset>,
    pxPerUnit: State<Float>,
    onTransform: (zoom: Float, panChange: Offset) -> Unit,
    currentStroke: () -> CurrentStroke?,
    onCurrentStroke: (CurrentStroke?) -> Unit,
) {
    awaitEachGesture {
        val surface = draw.value ?: return@awaitEachGesture
        val down = awaitFirstDown(requireUnconsumed = false)
        fun toCanvas(position: Offset): Offset = (position - pan.value) / pxPerUnit.value

        var inking = surface.tool == DrawTool.Brush
        if (inking) {
            onCurrentStroke(CurrentStroke(listOf(toCanvas(down.position)), surface.colorArgb, surface.widthPx))
        } else {
            surface.onErase(toCanvas(down.position))
        }
        down.consume()

        var pinching = false
        while (true) {
            val event = awaitPointerEvent()
            if (event.changes.count { it.pressed } >= 2) {
                if (inking) {
                    // Second finger: this is a pinch, not a stroke. The half-drawn
                    // line is dropped rather than committed with a straight jump.
                    onCurrentStroke(null)
                    inking = false
                }
                pinching = true
                val zoom = event.calculateZoom()
                val panChange = event.calculatePan()
                if (zoom != 1f || panChange != Offset.Zero) onTransform(zoom, panChange)
                event.changes.forEach { if (it.pressed) it.consume() }
                continue
            }
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            change.consume()
            if (pinching) continue
            val point = toCanvas(change.position)
            if (inking) {
                val stroke = currentStroke() ?: continue
                onCurrentStroke(stroke.copy(points = stroke.points.withThinnedPoint(point)))
            } else {
                surface.onErase(point)
            }
        }

        val finished = currentStroke()
        onCurrentStroke(null)
        if (inking && finished != null && finished.points.isNotEmpty()) {
            surface.onStroke(finished.points, finished.colorArgb, finished.widthPx)
        }
    }
}

/**
 * Decodes each image layer once through Coil and publishes the painter to
 * [frames]; the renderer draws those painters. `AsyncImage` used to draw the
 * frame itself — now it only loads.
 */
@Composable
private fun LayerFrames(layers: List<CanvasLayer>, frames: MutableMap<String, Painter>) {
    layers.forEach { layer ->
        if (layer !is ImageLayer || !layer.transform.visible) return@forEach
        key(layer.id) {
            val painter = rememberAsyncImagePainter(model = rememberImageModel(layer.url ?: layer.localPath))
            DisposableEffect(painter) {
                frames[layer.id] = painter
                onDispose { frames.remove(layer.id) }
            }
        }
    }
}

@Composable
private fun LayerCardShadows(
    layers: List<CanvasLayer>,
    canvasScale: Float,
    canvasOffset: Offset,
    frames: Map<String, Painter>,
    measurer: TextMeasurer,
    fonts: Map<String, FontFamily>,
) {
    layers.forEach { layer ->
        if (!layer.transform.visible) return@forEach
        // Only the kinds that own a card get a shadow: the renderer draws one
        // for image/video, the audio player paints its own. Text, strokes and
        // arrows draw no card here, and a shadow without a card is a grey
        // smudge on the canvas.
        if (layer is TextLayer || layer is StrokeLayer || layer is ArrowLayer) return@forEach
        Box(
            Modifier
                .layerBox(layer, canvasLayerBox(layer, frames[layer.id], measurer, fonts), canvasScale, canvasOffset)
                .shadow(6.dp, CanvasCardShape),
        )
    }
}

/**
 * The geometry every pass shares with the old composable element: offset by the
 * layer position plus the canvas pan (px), the layer's box (canvas units — a
 * card is 232.dp wide, a text layer is its measured box, a stroke's is its
 * bbox), then scale/rotate/alpha around the layout centre.
 *
 * A stroke's own offset is in canvas units, like its points and its thickness;
 * every other layer's position is in px. [density] is what turns one into the
 * other, so the selection box, the drag and the ink stay on top of each other.
 */
private fun Modifier.layerBox(
    layer: CanvasLayer,
    box: Size,
    canvasScale: Float,
    canvasOffset: Offset,
): Modifier = this
    .offset {
        val unit = if (layer is StrokeLayer) density else 1f
        IntOffset(
            (layer.transform.x * unit + canvasOffset.x).roundToInt(),
            (layer.transform.y * unit + canvasOffset.y).roundToInt(),
        )
    }
    .width(box.width.dp)
    .height(box.height.dp)
    .graphicsLayer(
        scaleX = layer.transform.scale * canvasScale,
        scaleY = layer.transform.scale * canvasScale,
        rotationZ = layer.transform.rotation,
        // Layer-wide alpha: the whole layer composites as one, so strokes
        // inside it never show through each other.
        alpha = layer.transform.opacity,
    )

@Composable
private fun CanvasLayerOverlay(
    layer: CanvasLayer,
    box: Size,
    selected: Boolean,
    canvasScale: Float,
    canvasOffset: Offset,
    frame: Painter?,
    /** False while the draw mode is on: the finger inks, it does not select or drag. */
    interactive: Boolean,
    onSelect: () -> Unit,
    onEditText: (TextLayer) -> Unit,
    onTextWidth: (String, Float) -> Unit,
    onMove: (String, Float, Float) -> Unit,
    onRotate: (String, Float) -> Unit,
    onScale: (String, Float) -> Unit,
    onAction: (String, CanvasAction) -> Unit,
    onExportToGallery: (String) -> Unit,
    onOpenLightbox: (MediaLayer) -> Unit,
    onEditAudio: (AudioLayer) -> Unit,
    onMakeClip: (AudioLayer) -> Unit,
    onEditImage: (ImageLayer) -> Unit,
    onEditVideo: (VideoLayer) -> Unit,
) {
    // A hidden layer keeps its data and its place in the draw order; it just
    // is not composited.
    if (!layer.transform.visible) return
    val density = LocalDensity.current.density
    val media = layer as? MediaLayer
    val url = media?.url
    val local = media?.localPath
    Box(
        Modifier
            .layerBox(layer, box, canvasScale, canvasOffset)
            .then(
                // The renderer paints the card fill and clips the frame to the
                // card, so the composable overlay must not paint outside it.
                // Audio draws its own fill; the video player paints a square
                // surface that would otherwise show its corners over the
                // rounded card. Same radius as the renderer, one constant.
                when (layer) {
                    is AudioLayer -> Modifier.clip(CanvasCardShape).background(MaterialTheme.colorScheme.surfaceVariant)
                    is VideoLayer -> Modifier.clip(CanvasCardShape)
                    else -> Modifier
                },
            )
            .then(
                if (selected) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CanvasCardShape)
                } else {
                    Modifier
                },
            )
            .then(
                if (!interactive) {
                    Modifier
                } else {
                    Modifier
                        .pointerInput(layer.id) {
                            detectTransformGestures { _, pan, zoom, rotation ->
                                // A stroke's position is in canvas units, every
                                // other layer's is in px: one px of drag has to
                                // move a stroke by 1/density units, or it runs
                                // away from the finger on a dense screen.
                                val unit = if (layer is StrokeLayer) 1f / density else 1f
                                onMove(layer.id, pan.x * unit, pan.y * unit)
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
                        // Tap = select, except for a text layer: a tap on it
                        // opens the editor (the card's rule — the caret lives
                        // in the input field).
                        .clickable(role = Role.Button) {
                            if (layer is TextLayer) onEditText(layer) else onSelect()
                        }
                },
            ),
    ) {
        when (layer) {
            is AudioLayer -> MediaAudioPlayer(
                source = layer.localPath ?: layer.url,
                modifier = Modifier.fillMaxSize().padding(vertical = 24.dp),
            )

            // The player cannot be drawn inside DrawScope, so it stays a
            // composable above the canvas: the video frame does not reach an
            // export yet (a separate card). The card under it is the renderer's.
            is VideoLayer -> MediaVideoPlayer(
                url = url ?: local ?: "",
                autoplay = false,
                fullControls = true,
                // The surface fills the proportional element box instead of a fixed height.
                surfaceHeight = Dp.Unspecified,
                modifier = Modifier.fillMaxSize(),
            )

            // Frames are painted by the renderer; text and strokes too (text is
            // measured there with the same TextMeasurer this overlay boxes it
            // with). Arrows are the next card. Nothing to compose here — the
            // overlay only carries the box, the ring and the chips.
            is ImageLayer, is TextLayer, is StrokeLayer, is ArrowLayer -> Unit
        }

        if (selected && interactive) {
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
                            // A text layer has no frame to open in the lightbox.
                            if (media != null) {
                                ElementChip(Icons.Filled.Fullscreen, "Открыть на весь экран") { onOpenLightbox(media) }
                            }
                        }
                        if (layer is TextLayer) {
                            // The box width is the wrapping width, so it is a
                            // control of its own: scale would move the text,
                            // narrowing it re-wraps.
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ElementChip(Icons.Filled.Edit, "Изменить текст") { onEditText(layer) }
                                ElementChip(Icons.Filled.ArrowBack, "Уже") { onTextWidth(layer.id, 0.85f) }
                                ElementChip(Icons.Filled.ArrowForward, "Шире") { onTextWidth(layer.id, 1.2f) }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (layer is ImageLayer) {
                                // Image-only action: the pixel editor. Drawing
                                // moved to the canvas itself — the old pencil
                                // sheet that baked strokes into a PNG is gone.
                                ElementChip(Icons.Filled.Edit, "Редактировать") { onEditImage(layer) }
                            } else if (layer is VideoLayer && videoEditingPlatformSupported()) {
                                // Video editor (trim/speed/audio track) runs on the
                                // desktop ffmpeg; other targets never show it.
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
