package com.pusios.pots.media

/**
 * Image editing core: straight-alpha ARGB pixels plus the pure operations the
 * editors use (crop, resize, rotate, flip, adjustments, overlay, stroke bake).
 * Everything here is deterministic common code, so the edit rules are
 * unit-tested without codecs or platforms. Decoding/encoding is the only
 * platform-dependent step ([decodeImageToGrid] / [encodeGridToPng]).
 */
class PixelGrid(
    val width: Int,
    val height: Int,
    /** Row-major 0xAARRGGBB, straight (unpremultiplied) alpha. */
    val argb: IntArray,
) {
    init {
        require(width > 0 && height > 0) { "dimensions must be positive" }
        require(argb.size == width * height) { "argb must hold width*height pixels" }
    }

    operator fun get(x: Int, y: Int): Int = argb[y * width + x]

    fun set(x: Int, y: Int, color: Int) {
        argb[y * width + x] = color
    }

    fun copy(): PixelGrid = PixelGrid(width, height, argb.copyOf())
}

/** Reports progress 0..1; returning false cancels the operation. */
typealias ImageEditProgress = (fraction: Float) -> Boolean

/**
 * Cuts the (x, y, w, h) region out of the grid. Coordinates may be negative
 * and the region may exceed the grid — both are clamped to the image bounds.
 * A region that ends up empty after clamping throws [ImageEditException.EmptyCrop].
 */
fun PixelGrid.cropGrid(x: Int, y: Int, w: Int, h: Int): PixelGrid {
    val sx = x.coerceAtLeast(0)
    val sy = y.coerceAtLeast(0)
    val ex = minOf(x + w, width)
    val ey = minOf(y + h, height)
    if (ex <= sx || ey <= sy) throw ImageEditException.EmptyCrop()
    val outW = ex - sx
    val outH = ey - sy
    val out = IntArray(outW * outH)
    for (row in 0 until outH) {
        argb.copyInto(out, row * outW, (sy + row) * width + sx, (sy + row) * width + ex)
    }
    return PixelGrid(outW, outH, out)
}

/**
 * Crops the grid to the bounding box of pixels whose alpha is above zero —
 * the "drawn" region of a transparent sketch. Returns null when every pixel
 * is fully transparent (nothing to export). The source grid is untouched.
 */
fun PixelGrid.trimToContent(): PixelGrid? {
    var minX = width
    var minY = height
    var maxX = -1
    var maxY = -1
    for (y in 0 until height) {
        val row = y * width
        for (x in 0 until width) {
            if (argb[row + x] ushr 24 != 0) {
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
    }
    if (maxX < 0) return null
    return cropGrid(minX, minY, maxX - minX + 1, maxY - minY + 1)
}

/**
 * Bilinear resample to the requested pixel dimensions. Corner pixels map to
 * corners; a progress hook reports per-row fractions and cancels on false.
 */
fun PixelGrid.resizeGrid(targetWidth: Int, targetHeight: Int, onProgress: ImageEditProgress? = null): PixelGrid {
    if (targetWidth < 1 || targetHeight < 1) throw ImageEditException.BadSize()
    val out = IntArray(targetWidth * targetHeight)
    val lastX = width - 1
    val lastY = height - 1
    var reportedPct = -1
    for (y in 0 until targetHeight) {
        val srcY = ((y + 0.5f) * height / targetHeight - 0.5f).coerceAtLeast(0f)
        val y0 = srcY.toInt().coerceAtMost(lastY)
        val y1 = if (y0 < lastY) y0 + 1 else y0
        val fy = srcY - y0
        for (x in 0 until targetWidth) {
            val srcX = ((x + 0.5f) * width / targetWidth - 0.5f).coerceAtLeast(0f)
            val x0 = srcX.toInt().coerceAtMost(lastX)
            val x1 = if (x0 < lastX) x0 + 1 else x0
            val fx = srcX - x0
            val p00 = argb[y0 * width + x0]
            val p10 = argb[y0 * width + x1]
            val p01 = argb[y1 * width + x0]
            val p11 = argb[y1 * width + x1]
            out[y * targetWidth + x] = bilinear(p00, p10, p01, p11, fx, fy)
        }
        if (onProgress != null) {
            val pct = ((y + 1).toLong() * 100 / targetHeight).toInt()
            if (pct != reportedPct) {
                reportedPct = pct
                if (!onProgress(pct / 100f)) throw ImageEditException.Cancelled()
            }
        }
    }
    if (onProgress != null && !onProgress(1f)) throw ImageEditException.Cancelled()
    return PixelGrid(targetWidth, targetHeight, out)
}

/** Per-channel bilinear mix of four ARGB pixels; alpha included as data. */
private fun bilinear(p00: Int, p10: Int, p01: Int, p11: Int, fx: Float, fy: Float): Int {
    fun chan(shift: Int): Int {
        val c00 = (p00 ushr shift and 0xFF).toFloat()
        val c10 = (p10 ushr shift and 0xFF).toFloat()
        val c01 = (p01 ushr shift and 0xFF).toFloat()
        val c11 = (p11 ushr shift and 0xFF).toFloat()
        val top = c00 + (c10 - c00) * fx
        val bottom = c01 + (c11 - c01) * fx
        return (top + (bottom - top) * fy + 0.5f).toInt().coerceIn(0, 255)
    }
    return (chan(24) shl 24) or (chan(16) shl 16) or (chan(8) shl 8) or chan(0)
}

/** Rotates by [turns] quarter-turns clockwise (negative = counter-clockwise). */
fun PixelGrid.rotateQuadrant(turns: Int): PixelGrid {
    val steps = ((turns % 4) + 4) % 4
    if (steps == 0) return copy()
    var result = this
    repeat(steps) { result = result.rotate90Clockwise() }
    return result
}

private fun PixelGrid.rotate90Clockwise(): PixelGrid {
    val out = IntArray(width * height)
    for (y in 0 until height) {
        for (x in 0 until width) {
            // (x, y) -> (height - 1 - y, x); new size is (height, width).
            out[x * height + (height - 1 - y)] = argb[y * width + x]
        }
    }
    return PixelGrid(height, width, out)
}

fun PixelGrid.flipHorizontal(): PixelGrid {
    val out = IntArray(width * height)
    for (y in 0 until height) {
        for (x in 0 until width) {
            out[y * width + (width - 1 - x)] = argb[y * width + x]
        }
    }
    return PixelGrid(width, height, out)
}

fun PixelGrid.flipVertical(): PixelGrid {
    val out = IntArray(width * height)
    for (y in 0 until height) {
        val targetRow = (height - 1 - y) * width
        argb.copyInto(out, targetRow, y * width, (y + 1) * width)
    }
    return PixelGrid(width, height, out)
}

/**
 * Brightness in -1..1 (additive, ±255) and contrast in 0..4 (scaled around
 * the 128 midpoint); alpha is untouched and channels clamp to 0..255.
 */
fun PixelGrid.adjustBrightnessContrast(
    brightness: Float,
    contrast: Float,
    onProgress: ImageEditProgress? = null,
): PixelGrid {
    if (brightness < -1f || brightness > 1f || contrast < 0f || contrast > 4f) {
        throw ImageEditException.BadAdjust()
    }
    val add = brightness * 255f
    val table = IntArray(256) { v ->
        ((v - 128) * contrast + 128 + add).toInt().coerceIn(0, 255)
    }
    val out = IntArray(width * height)
    var reportedPct = -1
    for (y in 0 until height) {
        val row = y * width
        for (x in 0 until width) {
            val pixel = argb[row + x]
            out[row + x] = (pixel and 0xFF000000.toInt()) or
                (table[pixel shr 16 and 0xFF] shl 16) or
                (table[pixel shr 8 and 0xFF] shl 8) or
                table[pixel and 0xFF]
        }
        if (onProgress != null) {
            val pct = ((y + 1).toLong() * 100 / height).toInt()
            if (pct != reportedPct) {
                reportedPct = pct
                if (!onProgress(pct / 100f)) throw ImageEditException.Cancelled()
            }
        }
    }
    return PixelGrid(width, height, out)
}

/**
 * Composites [top] onto a copy of [base] at (offsetX, offsetY) with straight
 * alpha "over" blending. The top is resampled by [scale] first. Pixels of the
 * top outside the base are clipped; the base itself is never modified.
 */
fun overlayGrid(
    base: PixelGrid,
    top: PixelGrid,
    offsetX: Int,
    offsetY: Int,
    scale: Float = 1f,
    opacity: Float = 1f,
    onProgress: ImageEditProgress? = null,
): PixelGrid {
    if (opacity < 0f || opacity > 1f || scale <= 0f) throw ImageEditException.BadOverlay()
    val layer = if (scale == 1f) {
        top
    } else {
        top.resizeGrid(
            (top.width * scale).toInt().coerceAtLeast(1),
            (top.height * scale).toInt().coerceAtLeast(1),
        )
    }
    val out = base.copy()
    val x0 = maxOf(offsetX, 0)
    val y0 = maxOf(offsetY, 0)
    val x1 = minOf(offsetX + layer.width, base.width)
    val y1 = minOf(offsetY + layer.height, base.height)
    if (x1 <= x0 || y1 <= y0) return out
    var reportedPct = -1
    for (y in y0 until y1) {
        val layerY = y - offsetY
        for (x in x0 until x1) {
            val layerX = x - offsetX
            val src = layer.argb[layerY * layer.width + layerX]
            val srcA = (src ushr 24 and 0xFF) / 255f * opacity
            if (srcA <= 0f) continue
            val dst = out.argb[y * base.width + x]
            out.argb[y * base.width + x] = over(src, srcA, dst)
        }
        if (onProgress != null) {
            val pct = ((y - y0 + 1).toLong() * 100 / (y1 - y0)).toInt()
            if (pct != reportedPct) {
                reportedPct = pct
                if (!onProgress(pct / 100f)) throw ImageEditException.Cancelled()
            }
        }
    }
    return out
}

/** Straight-alpha "over" composite of one source pixel onto a destination. */
private fun over(src: Int, srcA: Float, dst: Int): Int {
    val dstA = (dst ushr 24 and 0xFF) / 255f
    val outA = srcA + dstA * (1f - srcA)
    if (outA <= 0f) return 0
    fun chan(shift: Int): Int {
        val s = (src ushr shift and 0xFF).toFloat()
        val d = (dst ushr shift and 0xFF).toFloat()
        return ((s * srcA + d * dstA * (1f - srcA)) / outA + 0.5f).toInt().coerceIn(0, 255)
    }
    return ((outA * 255f + 0.5f).toInt().coerceIn(0, 255) shl 24) or
        (chan(16) shl 16) or (chan(8) shl 8) or chan(0)
}

/** One drawn stroke: an ordered path, its color and its thickness in pixels. */
data class Stroke(
    val points: List<androidx.compose.ui.geometry.Offset>,
    val colorArgb: Int,
    val widthPx: Float,
)

/**
 * In-progress vector drawing state (spec §5). Strokes stay separate from the
 * image until they are baked with [bakeStrokes]; undo pops whole strokes.
 */
class DrawingLayer {
    private val finished = mutableListOf<Stroke>()
    private var current: MutableList<androidx.compose.ui.geometry.Offset>? = null
    private var currentColor = 0
    private var currentWidth = 0f

    val strokes: List<Stroke> get() = finished
    val isEmpty: Boolean get() = finished.isEmpty() && current == null

    fun startStroke(colorArgb: Int, widthPx: Float, at: androidx.compose.ui.geometry.Offset) {
        currentColor = colorArgb
        currentWidth = widthPx
        current = mutableListOf(at)
    }

    fun extendStroke(point: androidx.compose.ui.geometry.Offset) {
        val path = current ?: return
        if (path.last() == point) return
        path.add(point)
    }

    fun finishStroke() {
        val path = current ?: return
        finished.add(Stroke(path.toList(), currentColor, currentWidth))
        current = null
    }

    fun discardCurrentStroke() {
        current = null
    }

    /** Removes the most recent finished stroke; false when there is none. */
    fun undoLast(): Boolean = finished.removeLastOrNull() != null

    fun clear() {
        finished.clear()
        current = null
    }
}

/**
 * Rasterizes [strokes] onto a copy of the canvas with round caps and joints,
 * blending each stroke's color over the existing pixels. Smoothing comes from
 * the quadratic midpoint subdivision, matching the on-screen preview.
 */
fun PixelGrid.bakeStrokes(strokes: List<Stroke>): PixelGrid {
    val out = copy()
    for (stroke in strokes) {
        val path = smoothedPath(stroke.points)
        val radius = stroke.widthPx / 2f
        out.paintPath(path, radius, stroke.colorArgb)
    }
    return out
}

/** Quadratic midpoint smoothing; a single point stays a point (a dot). */
private fun smoothedPath(points: List<androidx.compose.ui.geometry.Offset>): List<androidx.compose.ui.geometry.Offset> {
    if (points.size <= 2) return points
    val out = ArrayList<androidx.compose.ui.geometry.Offset>(points.size * 2)
    out.add(points.first())
    for (i in 1 until points.lastIndex) {
        val prev = points[i - 1]
        val ctrl = points[i]
        val next = points[i + 1]
        val start = androidx.compose.ui.geometry.Offset((prev.x + ctrl.x) / 2f, (prev.y + ctrl.y) / 2f)
        val end = androidx.compose.ui.geometry.Offset((ctrl.x + next.x) / 2f, (ctrl.y + next.y) / 2f)
        out.add(start)
        // Sample the quadratic Bezier a few times for a smooth join.
        for (step in 1..3) {
            val t = step / 4f
            val inv = 1f - t
            val x = inv * inv * start.x + 2 * inv * t * ctrl.x + t * t * end.x
            val y = inv * inv * start.y + 2 * inv * t * ctrl.y + t * t * end.y
            out.add(androidx.compose.ui.geometry.Offset(x, y))
        }
        out.add(end)
    }
    out.add(points.last())
    return out
}

private fun PixelGrid.paintPath(path: List<androidx.compose.ui.geometry.Offset>, radius: Float, color: Int) {
    if (path.isEmpty()) return
    if (path.size == 1) {
        paintSegment(path[0], path[0], radius, color)
        return
    }
    for (i in 0 until path.lastIndex) {
        paintSegment(path[i], path[i + 1], radius, color)
    }
}

private fun PixelGrid.paintSegment(
    a: androidx.compose.ui.geometry.Offset,
    b: androidx.compose.ui.geometry.Offset,
    radius: Float,
    color: Int,
) {
    val minX = (minOf(a.x, b.x) - radius).toInt().coerceAtLeast(0)
    val maxX = (maxOf(a.x, b.x) + radius).toInt().coerceAtMost(width - 1)
    val minY = (minOf(a.y, b.y) - radius).toInt().coerceAtLeast(0)
    val maxY = (maxOf(a.y, b.y) + radius).toInt().coerceAtMost(height - 1)
    if (maxX < minX || maxY < minY) return
    val srcA = (color ushr 24 and 0xFF) / 255f
    for (y in minY..maxY) {
        for (x in minX..maxX) {
            if (distanceToSegment(x + 0.5f, y + 0.5f, a, b) <= radius) {
                val index = y * width + x
                argb[index] = if (srcA >= 1f && (color ushr 24 and 0xFF) == 255) {
                    color
                } else {
                    over(color, srcA, argb[index])
                }
            }
        }
    }
}

private fun distanceToSegment(
    px: Float,
    py: Float,
    a: androidx.compose.ui.geometry.Offset,
    b: androidx.compose.ui.geometry.Offset,
): Float {
    val abx = b.x - a.x
    val aby = b.y - a.y
    val lengthSq = abx * abx + aby * aby
    var t = if (lengthSq <= 0f) 0f else ((px - a.x) * abx + (py - a.y) * aby) / lengthSq
    t = t.coerceIn(0f, 1f)
    val cx = a.x + abx * t
    val cy = a.y + aby * t
    val dx = px - cx
    val dy = py - cy
    return kotlin.math.sqrt(dx * dx + dy * dy)
}

/**
 * Editor failures in the §8 shape: a Russian title, one human sentence of
 * detail and a rescue ([fix]) the UI turns into an action button.
 */
sealed class ImageEditException(
    val title: String,
    val detail: String,
    val fix: String,
) : Exception("$title. $detail") {

    class DecodeFailed :
        ImageEditException(
            "Не удалось прочитать изображение",
            "Файл повреждён или его формат не поддерживается.",
            "Выбрать другой файл",
        )

    class UnsupportedFormat(formats: String) :
        ImageEditException(
            "Формат не поддерживается",
            "Этот файл нельзя обработать. Поддерживаются: $formats.",
            "Выбрать другой файл",
        )

    class EmptyCrop :
        ImageEditException(
            "Пустая область обрезки",
            "Рамка не захватывает ни одного пикселя изображения. Растяните рамку на область внутри кадра.",
            "Изменить рамку",
        )

    class EmptySketch :
        ImageEditException(
            "Нечего сохранять",
            "На прозрачном слое не видно ни одного штриха.",
            "Нарисовать штрих",
        )

    class BadSize :
        ImageEditException(
            "Недопустимый размер",
            "Ширина и высота должны быть не меньше 1 пикселя.",
            "Сбросить параметры",
        )

    class BadAdjust :
        ImageEditException(
            "Недопустимые корректировки",
            "Яркость должна быть в диапазоне −100…100 %, контраст — 0…400 %.",
            "Сбросить параметры",
        )

    class BadOverlay :
        ImageEditException(
            "Недопустимые параметры наложения",
            "Прозрачность должна быть 0–100 %, масштаб — больше нуля.",
            "Сбросить параметры",
        )

    class ExportFailed :
        ImageEditException(
            "Не удалось сохранить изображение",
            "Файл не сохранился. Проверьте свободное место и повторите.",
            "Повторить экспорт",
        )

    class Cancelled :
        ImageEditException(
            "Обработка остановлена",
            "Операция отменена до завершения, исходное изображение не изменено.",
            "Закрыть",
        )
}

/** Snackbar-friendly one-liner: title plus the human detail. */
fun ImageEditException.userMessageRu(): String = "$title $detail"
