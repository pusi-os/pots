package com.pusios.pots.ui.media

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.pusios.pots.media.PixelGrid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// The export of the canvas: one renderer, one scope, two callers.
//
// «Сохранить в галерею» and «скриншот в модель» (the next card) both come here;
// they differ only in [includeAnnotation] and [background], never in the code
// that draws. The preview in the export dialog is the *same* call at the same
// size, so the file cannot differ from what the user approved.
//
// The space is the *screen's* space, not a made-up one. The renderer takes two
// different quantities: a layer's position (`transform.x/y`) is canvas px and
// is NOT multiplied by the render factor, while its box is canvas units and IS
// multiplied by it (see `renderLayers`). The screen passes the display density
// as that factor, so a 232-unit card is 638 px wide at density 2.75 while it
// still sits at its stored px position. An export that passed 1 would draw the
// same positions with cards three times smaller — a different composition.
// Therefore the export passes the real [renderCanvas] density and only scales
// the whole scope by its own factor: the picture is the screen's, cropped and
// resampled, never a second layout.
//
// The bitmap comes from `ImageBitmap` + `CanvasDrawScope`, not from a
// `GraphicsLayer`: a `GraphicsLayer` can only be created inside a composition
// (`rememberGraphicsLayer`), it clips at its layout bounds unless outsets are
// set, and on Android `toImageBitmap` goes through a RenderNode. Drawing into
// an exactly sized bitmap has none of those problems, needs no outsets, and
// runs on any thread — an export of a 2048² scene must not touch the main one.

/** What an export covers. */
internal sealed interface ExportScope {
    /** Every visible layer, bounded by their content plus [EXPORT_PADDING]. */
    data object Whole : ExportScope

    /**
     * Exactly this rectangle of the screen, px — the frame the user dragged,
     * together with the canvas pan and zoom it was dragged at. Screen px and
     * not canvas units, because the frame was drawn over the screen and the
     * file must contain what was inside it, whatever the zoom.
     */
    data class Region(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val panX: Float = 0f,
        val panY: Float = 0f,
        val zoom: Float = 1f,
    ) : ExportScope {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
    }
}

/**
 * The frame in the renderer's own space. [ExportScope.Region] carries the pan it
 * was dragged at, because the crop must reproduce that view; a whole-canvas
 * render builds its view with pan = 0, so the frame has to lose the pan first —
 * otherwise it lands shifted by exactly that pan and drags the content box with
 * it. Zoom does not move positions (it grows a card around its centre), so only
 * the pan is subtracted.
 */
internal fun ExportScope.Region.toCanvasRect(): Rect =
    Rect(left - panX, top - panY, right - panX, bottom - panY)

/** Breathing room around the content of a whole-canvas export, density-independent. */
internal const val EXPORT_PADDING = 24f

/**
 * Longest side of an exported bitmap. 2048² ARGB is 16 MB — the budget the
 * researcher measured; a bigger canvas is downscaled instead of failing.
 */
internal const val EXPORT_MAX_SIDE_PX = 2048

/** Upscale limit: past 4× a 232-unit card would be a blur, not more detail. */
internal const val EXPORT_MAX_SCALE = 4f

/** A frame is dragged with a finger; anything smaller is a tap, not a region. */
internal const val MIN_EXPORT_REGION = 16f

/** The two opaque backgrounds the dialog offers; alpha is the third option. */
internal val EXPORT_DARK_BACKGROUND = Color(0xFF14141A)
internal val EXPORT_LIGHT_BACKGROUND = Color(0xFFF4F4F7)

/**
 * The frame the user dragged, baked into the model's picture: amber reads on
 * both offered backgrounds and on any photo.
 */
internal val CANVAS_PROMPT_FRAME_COLOR = Color(0xFFFFC107)

/** The frame's line width in *exported* pixels, whatever the export factor is. */
internal const val CANVAS_PROMPT_FRAME_WIDTH_PX = 2f

/** The frame's corner radius in exported pixels. */
internal const val CANVAS_PROMPT_FRAME_CORNER_RADIUS_PX = 12f

/**
 * What the export draws and how: the rectangle in the renderer's space, the
 * canvas pan and the zoom it was framed at. «Весь канвас» is the content box
 * at zoom 1 (an export must not depend on how far the user zoomed out); a
 * region is the screen rect at the pan and zoom the finger saw.
 */
internal data class ExportView(val rect: Rect, val pan: Offset, val zoom: Float)

/**
 * Renders [layers] into a new bitmap, or null when there is nothing to export
 * (no visible layers, a region with no area, a picture that came out fully
 * transparent).
 *
 * @param scope whole canvas (content bounds + padding) or the screen rectangle
 *   the user dragged.
 * @param includeAnnotation true for «в модель»: annotation layers (arrows) are
 *   part of the picture; false for the gallery, where the UI markup must not
 *   leak into the file.
 * @param background null = transparent PNG; a colour fills the whole bitmap
 *   first (the model path needs an opaque image).
 * @param frame the zone the user dragged, in the renderer's own space — canvas
 *   px, no pan; use [toCanvasRect] to get it from a dragged [ExportScope.Region].
 *   When [includeAnnotation] is on it is drawn over the layers so the model can
 *   see *where* the edit goes. Null = no frame. The zone's own crop passes null:
 *   a frame inside the crop would be litter.
 * @param frames decoded frames by layer id, exactly as the screen has them: an
 *   image's bytes or a video's still frame. A layer without a frame draws only
 *   its card.
 * @param density display density the screen renders at: the factor that turns
 *   a layer's box from canvas units into canvas px. Layer positions are px and
 *   stay untouched, exactly as on the screen.
 */
internal suspend fun renderCanvas(
    layers: List<CanvasLayer>,
    scope: ExportScope,
    includeAnnotation: Boolean = false,
    background: Color? = null,
    frame: Rect? = null,
    frames: Map<String, Painter> = emptyMap(),
    fonts: Map<String, FontFamily> = emptyMap(),
    measurer: TextMeasurer? = null,
    density: Float = 1f,
    maxSidePx: Int = EXPORT_MAX_SIDE_PX,
): ImageBitmap? {
    val content = canvasContentBounds(layers, frames, measurer, fonts, includeAnnotation, density) ?: return null
    // The frame is part of the model's picture, so a whole-canvas view has to
    // contain it: a zone dragged in the empty part of the screen would otherwise
    // be cut off, and «зона обведена» would point at nothing.
    val framedContent = if (includeAnnotation && frame != null && scope is ExportScope.Whole) {
        content.union(frame)
    } else {
        content
    }
    val view = exportView(scope, framedContent, density) ?: return null
    val scale = canvasExportScale(view, layers, frames, density, maxSidePx)
    val width = (view.rect.width * scale).roundToInt().coerceAtLeast(1)
    val height = (view.rect.height * scale).roundToInt().coerceAtLeast(1)
    val strokePaths = layers.filterIsInstance<StrokeLayer>().associate { it.id to strokePath(it.points) }

    return withContext(Dispatchers.Default) {
        val bitmap = ImageBitmap(width, height)
        CanvasDrawScope().draw(
            density = Density(1f, 1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(bitmap),
            size = Size(width.toFloat(), height.toFloat()),
        ) {
            if (background != null) drawRect(background)
            // The scope is the screen's: pan, then the crop, then the export
            // factor — the renderer itself gets the display density for its
            // boxes and the zoom the frame was drawn at. A layer's position
            // (px) and its box (units × density) therefore keep the ratio they
            // have on the screen, and the factor only resamples the result.
            withTransform({
                scale(scale, scale, Offset.Zero)
                translate(-view.rect.left, -view.rect.top)
                translate(view.pan.x, view.pan.y)
            }) {
                renderLayers(
                    layers = layers,
                    scale = density,
                    fonts = fonts,
                    measurer = measurer,
                    frames = frames,
                    strokePaths = strokePaths,
                    cardBackground = null,
                    zoom = view.zoom,
                    includeAnnotations = includeAnnotation,
                )
                if (includeAnnotation && frame != null) drawPromptFrame(frame, scale)
            }
        }
        // A video the platform cannot capture a frame for, an image that failed
        // to decode: the card is drawn with no content inside it. If nothing at
        // all landed on the bitmap, the file would be a fully transparent PNG —
        // a silent loss. Refuse instead: null means «нечего сохранять».
        if (background == null && !bitmap.hasVisiblePixels()) return@withContext null
        bitmap
    }
}

/**
 * The dragged frame, baked into the picture: [CANVAS_PROMPT_FRAME_COLOR] over
 * the layers, in the export's own transform. The width is divided by the export
 * factor, so the line is two *exported* pixels wide whatever the factor is
 * (the transform would otherwise thicken it with the picture).
 */
private fun DrawScope.drawPromptFrame(frame: Rect, scale: Float) {
    drawRoundRect(
        color = CANVAS_PROMPT_FRAME_COLOR,
        topLeft = Offset(frame.left, frame.top),
        size = Size(frame.width, frame.height),
        cornerRadius = CornerRadius(CANVAS_PROMPT_FRAME_CORNER_RADIUS_PX / scale),
        style = Stroke(width = CANVAS_PROMPT_FRAME_WIDTH_PX / scale),
    )
}

/** The view an export covers; null when the scope has no area. */
internal fun exportView(scope: ExportScope, content: Rect?, density: Float): ExportView? = when (scope) {
    ExportScope.Whole -> content?.let { ExportView(it.inflate(EXPORT_PADDING * density), Offset.Zero, 1f) }

    is ExportScope.Region -> if (scope.width > 0f && scope.height > 0f) {
        ExportView(
            rect = Rect(scope.left, scope.top, scope.right, scope.bottom),
            pan = Offset(scope.panX, scope.panY),
            zoom = scope.zoom.coerceAtLeast(0.01f),
        )
    } else {
        null
    }
}

/**
 * The factor from the renderer's space to exported pixels.
 *
 * «Полное разрешение, по исходнику»: a card is [CANVAS_ELEMENT_SIZE_DP] units
 * wide — [CANVAS_ELEMENT_SIZE_DP] × density × zoom px in the renderer's space —
 * and shows an image of a known pixel width, so the factor that maps the widest
 * image 1:1 is the honest one. It is clamped to 1…[EXPORT_MAX_SCALE] and then
 * capped so the bitmap never exceeds [maxSidePx].
 */
internal fun canvasExportScale(
    view: ExportView,
    layers: List<CanvasLayer>,
    frames: Map<String, Painter>,
    density: Float,
    maxSidePx: Int = EXPORT_MAX_SIDE_PX,
): Float {
    val sourceWidth = layers.asSequence()
        .filter { it.transform.visible && (it is ImageLayer || it is VideoLayer) }
        .mapNotNull { layer -> frames[layer.id]?.intrinsicSize?.takeIf { it.isSpecified && it.width > 0f }?.width }
        .maxOrNull()
        ?: 0f
    val cardPx = CANVAS_ELEMENT_SIZE_DP * density * view.zoom
    val wanted = (sourceWidth / cardPx).coerceIn(1f, EXPORT_MAX_SCALE)
    val fits = maxSidePx / max(view.rect.width, view.rect.height)
    return min(wanted, fits).coerceAtLeast(0.01f)
}

/**
 * The bounding box of everything the export would draw, in the renderer's
 * space (layer positions px, layer boxes units × [density]), or null when
 * nothing is drawn.
 *
 * A layer contributes the *transformed* box: its own scale and rotation turn
 * the box around its layout centre, exactly as the renderer does, so a layer
 * rotated by 45° asks for a canvas that fits the rotated square instead of
 * being cut at the unrotated one. Strokes and arrows are in units already
 * (points, thickness and offset), so their whole space — position included —
 * is scaled by [density]; a card's position is px and is not.
 */
internal fun canvasContentBounds(
    layers: List<CanvasLayer>,
    frames: Map<String, Painter> = emptyMap(),
    measurer: TextMeasurer? = null,
    fonts: Map<String, FontFamily> = emptyMap(),
    includeAnnotation: Boolean = false,
    density: Float = 1f,
): Rect? {
    var bounds: Rect? = null
    layers.forEach { layer ->
        val box = exportedLayerBounds(layer, frames[layer.id], measurer, fonts, includeAnnotation, density)
            ?: return@forEach
        bounds = bounds?.union(box) ?: box
    }
    return bounds
}

/** The smallest rectangle holding both. */
private fun Rect.union(other: Rect): Rect = Rect(
    min(left, other.left),
    min(top, other.top),
    max(right, other.right),
    max(bottom, other.bottom),
)

/** One layer's footprint in the renderer's space; null when it draws nothing. */
private fun exportedLayerBounds(
    layer: CanvasLayer,
    frame: Painter?,
    measurer: TextMeasurer?,
    fonts: Map<String, FontFamily>,
    includeAnnotation: Boolean,
    density: Float,
): Rect? {
    if (!layer.transform.visible) return null
    if (layer is AudioLayer) return null
    if (layer is ArrowLayer) {
        if (!includeAnnotation) return null
        val ink = Rect(
            min(layer.from.x, layer.to.x),
            min(layer.from.y, layer.to.y),
            max(layer.from.x, layer.to.x),
            max(layer.from.y, layer.to.y),
        ).inflate(layer.widthPx * 2f)
        return transformedBounds(ink.topLeft * density, ink.size * density, layer.transform)
            .translate(layer.transform.x * density, layer.transform.y * density)
    }
    if (layer is StrokeLayer) {
        if (layer.points.isEmpty()) return null
        val ink = pointsBounds(layer.points).inflate(layer.widthPx / 2f)
        return transformedBounds(ink.topLeft * density, ink.size * density, layer.transform)
            .translate(layer.transform.x * density, layer.transform.y * density)
    }
    if (layer is TextLayer && layer.text.isBlank()) return null
    val box = canvasLayerBox(layer, frame, measurer, fonts)
    if (box.width <= 0f || box.height <= 0f) return null
    // A card's position is canvas px (the renderer leaves it unscaled); only
    // its box is in units and follows the density.
    return transformedBounds(
        Offset(layer.transform.x, layer.transform.y),
        Size(box.width * density, box.height * density),
        layer.transform,
    )
}

/** The axis-aligned box of a layer box after its own scale and rotation. */
private fun transformedBounds(topLeft: Offset, size: Size, transform: LayerTransform): Rect {
    val pivot = Offset(topLeft.x + size.width / 2f, topLeft.y + size.height / 2f)
    val radians = transform.rotation * PI.toFloat() / 180f
    val cos = cos(radians)
    val sin = sin(radians)
    fun corner(x: Float, y: Float): Offset {
        val dx = (x - pivot.x) * transform.scale
        val dy = (y - pivot.y) * transform.scale
        return Offset(pivot.x + dx * cos - dy * sin, pivot.y + dx * sin + dy * cos)
    }
    val corners = listOf(
        corner(topLeft.x, topLeft.y),
        corner(topLeft.x + size.width, topLeft.y),
        corner(topLeft.x + size.width, topLeft.y + size.height),
        corner(topLeft.x, topLeft.y + size.height),
    )
    return Rect(
        corners.minOf { it.x },
        corners.minOf { it.y },
        corners.maxOf { it.x },
        corners.maxOf { it.y },
    )
}

/** The box of a stroke's points (the path stays inside their hull). */
private fun pointsBounds(points: List<Offset>): Rect = Rect(
    points.minOf { it.x },
    points.minOf { it.y },
    points.maxOf { it.x },
    points.maxOf { it.y },
)

/** True when at least one pixel is not fully transparent. */
private fun ImageBitmap.hasVisiblePixels(): Boolean {
    val map = toPixelMap()
    val buffer = map.buffer
    for (y in 0 until map.height) {
        val row = map.bufferOffset + y * map.stride
        for (index in row until row + map.width) {
            if (buffer[index] ushr 24 != 0) return true
        }
    }
    return false
}

/**
 * The rendered bitmap as straight-alpha ARGB, the shape [PixelGrid] and
 * `encodeGridToPng` take. The fast path reuses the pixel map's buffer (a 2048²
 * export would otherwise copy 16 MB twice).
 */
internal fun ImageBitmap.toPixelGrid(): PixelGrid {
    val map = toPixelMap()
    val pixels = map.width * map.height
    if (map.stride == map.width && map.bufferOffset == 0 && map.buffer.size == pixels) {
        return PixelGrid(map.width, map.height, map.buffer)
    }
    val argb = IntArray(pixels)
    for (y in 0 until map.height) {
        val from = map.bufferOffset + y * map.stride
        map.buffer.copyInto(argb, y * map.width, from, from + map.width)
    }
    return PixelGrid(map.width, map.height, argb)
}

/** «pots-canvas-2026-11-22-1435» — the name carries the export time. */
internal fun canvasExportFileName(epochMs: Long): String {
    val time = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.currentSystemDefault())
    fun Int.pad(): String = toString().padStart(2, '0')
    return "pots-canvas-" + time.year + "-" + time.monthNumber.pad() + "-" + time.dayOfMonth.pad() +
        "-" + time.hour.pad() + time.minute.pad()
}
