package com.pusios.pots.ui.media

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
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
// The space is canvas units — what the project file stores: a layer's box is
// 232 units wide, a text layer's box is its measurement, a stroke is in units.
// A factor scales the whole space uniformly, which is why 1× and 2× give the
// same picture at two resolutions (the parity test pins it).
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

    /** Exactly this rectangle, canvas units — a frame the user dragged. */
    data class Region(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) : ExportScope {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
    }
}

/** Breathing room around the content of a whole-canvas export, canvas units. */
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
 * Renders [layers] into a new bitmap, or null when there is nothing to export
 * (no visible layers, a region with no area).
 *
 * @param scope whole canvas (content bounds + padding) or an exact rectangle.
 * @param includeAnnotation true for «в модель»: annotation layers (arrows) are
 *   part of the picture; false for the gallery, where the UI markup must not
 *   leak into the file.
 * @param background null = transparent PNG; a colour fills the whole bitmap
 *   first (the model path needs an opaque image).
 * @param frames decoded frames by layer id, exactly as the screen has them: an
 *   image's bytes or a video's still frame. A layer without a frame draws only
 *   its card.
 */
internal suspend fun renderCanvas(
    layers: List<CanvasLayer>,
    scope: ExportScope,
    includeAnnotation: Boolean = false,
    background: Color? = null,
    frames: Map<String, Painter> = emptyMap(),
    fonts: Map<String, FontFamily> = emptyMap(),
    measurer: TextMeasurer? = null,
    maxSidePx: Int = EXPORT_MAX_SIDE_PX,
): ImageBitmap? {
    val content = canvasContentBounds(layers, frames, measurer, fonts, includeAnnotation) ?: return null
    val rect = exportRect(scope, content) ?: return null
    val scale = canvasExportScale(rect, layers, frames, maxSidePx)
    val width = (rect.width * scale).roundToInt().coerceAtLeast(1)
    val height = (rect.height * scale).roundToInt().coerceAtLeast(1)
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
            // The renderer always works in canvas units (scale = 1); the export
            // factor is one uniform transform of that space, so a layer's
            // position and its box scale together — the geometry of 1× and 2×
            // is the same picture, not a different layout.
            withTransform({
                scale(scale, scale, Offset.Zero)
                translate(-rect.left, -rect.top)
            }) {
                renderLayers(
                    layers = layers,
                    scale = 1f,
                    fonts = fonts,
                    measurer = measurer,
                    frames = frames,
                    strokePaths = strokePaths,
                    cardBackground = null,
                    zoom = 1f,
                    includeAnnotations = includeAnnotation,
                )
            }
        }
        bitmap
    }
}

/** The rectangle an export covers; null when the scope has no area. */
internal fun exportRect(scope: ExportScope, content: Rect?): Rect? = when (scope) {
    ExportScope.Whole -> content?.inflate(EXPORT_PADDING)
    is ExportScope.Region -> if (scope.width > 0f && scope.height > 0f) {
        Rect(scope.left, scope.top, scope.right, scope.bottom)
    } else {
        null
    }
}

/**
 * The factor from canvas units to exported pixels.
 *
 * «Полное разрешение, по исходнику»: a card is [CANVAS_ELEMENT_SIZE_DP] units
 * wide and shows an image of a known pixel width, so the factor that maps the
 * widest image 1:1 is the honest one. It is clamped to 1…[EXPORT_MAX_SCALE] and
 * then capped so the bitmap never exceeds [maxSidePx].
 */
internal fun canvasExportScale(
    rect: Rect,
    layers: List<CanvasLayer>,
    frames: Map<String, Painter>,
    maxSidePx: Int = EXPORT_MAX_SIDE_PX,
): Float {
    val sourceWidth = layers.asSequence()
        .filter { it.transform.visible && (it is ImageLayer || it is VideoLayer) }
        .mapNotNull { layer -> frames[layer.id]?.intrinsicSize?.takeIf { it.isSpecified && it.width > 0f }?.width }
        .maxOrNull()
        ?: 0f
    val wanted = (sourceWidth / CANVAS_ELEMENT_SIZE_DP).coerceIn(1f, EXPORT_MAX_SCALE)
    val fits = maxSidePx / max(rect.width, rect.height)
    return min(wanted, fits).coerceAtLeast(0.01f)
}

/**
 * The bounding box of everything the export would draw, canvas units, or null
 * when nothing is drawn.
 *
 * A layer contributes the *transformed* box: its own scale and rotation turn
 * the box around its layout centre, exactly as the renderer does, so a layer
 * rotated by 45° asks for a canvas that fits the rotated square instead of
 * being cut at the unrotated one. Strokes and arrows are in units already
 * (points, thickness and offset), so the whole layer scales with the export
 * factor — their bounds are the polyline box grown by the ink width.
 */
internal fun canvasContentBounds(
    layers: List<CanvasLayer>,
    frames: Map<String, Painter> = emptyMap(),
    measurer: TextMeasurer? = null,
    fonts: Map<String, FontFamily> = emptyMap(),
    includeAnnotation: Boolean = false,
): Rect? {
    var bounds: Rect? = null
    layers.forEach { layer ->
        val box = exportedLayerBounds(layer, frames[layer.id], measurer, fonts, includeAnnotation) ?: return@forEach
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

/** One layer's footprint in canvas units; null when it draws nothing. */
private fun exportedLayerBounds(
    layer: CanvasLayer,
    frame: Painter?,
    measurer: TextMeasurer?,
    fonts: Map<String, FontFamily>,
    includeAnnotation: Boolean,
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
        return transformedBounds(ink.topLeft, ink.size, layer.transform).translate(layer.transform.x, layer.transform.y)
    }
    if (layer is StrokeLayer) {
        if (layer.points.isEmpty()) return null
        val ink = pointsBounds(layer.points).inflate(layer.widthPx / 2f)
        return transformedBounds(ink.topLeft, ink.size, layer.transform).translate(layer.transform.x, layer.transform.y)
    }
    if (layer is TextLayer && layer.text.isBlank()) return null
    val box = canvasLayerBox(layer, frame, measurer, fonts)
    if (box.width <= 0f || box.height <= 0f) return null
    return transformedBounds(Offset(layer.transform.x, layer.transform.y), box, layer.transform)
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
