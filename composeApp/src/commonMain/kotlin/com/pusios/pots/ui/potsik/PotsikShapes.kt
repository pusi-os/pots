package com.pusios.pots.ui.potsik

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.sqrt

/**
 * The drawing primitives the firmware draws Потсик with.
 *
 * The firmware draws him on an RGB565 screen with `TFT_eSPI`'s fills —
 * rectangles, round rectangles, circles, ellipses, triangles and lines — and
 * every one of those calls is a straight-edged shape with no antialiasing.
 * These are the same calls, on Compose's canvas, so the port draws the same
 * shapes in the same places rather than something that merely resembles them.
 *
 * All of them take the coordinates the firmware uses: whole pixels in the
 * character's own space, with the origin at the top of his head and y growing
 * downwards.
 */
internal fun DrawScope.fillRect(x: Number, y: Number, w: Number, h: Number, color: Color) {
    val fw = w.toFloat()
    val fh = h.toFloat()
    if (fw <= 0f || fh <= 0f) return
    drawRect(color = color, topLeft = Offset(x.toFloat(), y.toFloat()), size = Size(fw, fh))
}

/** `TFT_eSPI::fillRoundRect` — a rectangle with rounded corners. */
internal fun DrawScope.fillRoundRect(x: Number, y: Number, w: Number, h: Number, r: Number, color: Color) {
    val fw = w.toFloat()
    val fh = h.toFloat()
    if (fw <= 0f || fh <= 0f) return
    val radius = r.toFloat().coerceAtMost(minOf(fw, fh) / 2f)
    drawRoundRect(
        color = color,
        topLeft = Offset(x.toFloat(), y.toFloat()),
        size = Size(fw, fh),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
    )
}

/** `TFT_eSPI::fillCircle`. */
internal fun DrawScope.fillCircle(cx: Number, cy: Number, r: Number, color: Color) {
    val fr = r.toFloat()
    if (fr <= 0f) return
    drawCircle(color = color, radius = fr, center = Offset(cx.toFloat(), cy.toFloat()))
}

/** `TFT_eSPI::drawCircle` — the outline of a circle. */
internal fun DrawScope.drawCircleOutline(cx: Number, cy: Number, r: Number, color: Color, width: Number = 1f) {
    val fr = r.toFloat()
    if (fr <= 0f) return
    drawCircle(color = color, radius = fr, center = Offset(cx.toFloat(), cy.toFloat()), style = Stroke(width = width.toFloat()))
}

/** `TFT_eSPI::fillEllipse`. */
internal fun DrawScope.fillEllipse(cx: Number, cy: Number, rx: Number, ry: Number, color: Color) {
    val frx = rx.toFloat()
    val fry = ry.toFloat()
    if (frx <= 0f || fry <= 0f) return
    drawOval(color = color, topLeft = Offset(cx.toFloat() - frx, cy.toFloat() - fry), size = Size(frx * 2f, fry * 2f))
}

/** `TFT_eSPI::drawEllipse` — the outline of an ellipse. */
internal fun DrawScope.drawEllipseOutline(cx: Float, cy: Float, rx: Float, ry: Float, color: Color, width: Float = 1f) {
    if (rx <= 0f || ry <= 0f) return
    drawOval(
        color = color,
        topLeft = Offset(cx - rx, cy - ry),
        size = Size(rx * 2f, ry * 2f),
        style = Stroke(width = width),
    )
}

/** `TFT_eSPI::fillTriangle`. */
internal fun DrawScope.fillTriangle(
    x1: Number,
    y1: Number,
    x2: Number,
    y2: Number,
    x3: Number,
    y3: Number,
    color: Color,
) {
    val path = Path().apply {
        moveTo(x1.toFloat(), y1.toFloat())
        lineTo(x2.toFloat(), y2.toFloat())
        lineTo(x3.toFloat(), y3.toFloat())
        close()
    }
    drawPath(path = path, color = color, style = Fill)
}

/** `TFT_eSPI::drawLine` — a one-pixel line. */
internal fun DrawScope.drawLine(x1: Number, y1: Number, x2: Number, y2: Number, color: Color) {
    drawLine(color = color, start = Offset(x1.toFloat(), y1.toFloat()), end = Offset(x2.toFloat(), y2.toFloat()), strokeWidth = 1f)
}

/** `TFT_eSPI::drawFastHLine` — a filled horizontal run. */
internal fun DrawScope.drawFastHLine(x: Number, y: Number, w: Number, color: Color) {
    val fw = w.toFloat()
    if (fw <= 0f) return
    drawRect(color = color, topLeft = Offset(x.toFloat(), y.toFloat()), size = Size(fw, 1f))
}

/** `TFT_eSPI::drawFastVLine` — a filled vertical run. */
internal fun DrawScope.drawFastVLine(x: Number, y: Number, h: Number, color: Color) {
    val fh = h.toFloat()
    if (fh <= 0f) return
    drawRect(color = color, topLeft = Offset(x.toFloat(), y.toFloat()), size = Size(1f, fh))
}

/**
 * The firmware's `wideLine` — a thick line with round caps.
 *
 * It is two triangles forming the body of the line and a disc at each end, so
 * the caps are round rather than square. This is how every arm and leg in the
 * firmware is drawn, which is why he has no elbows: an arm is one line from
 * the shoulder to the hand.
 */
internal fun DrawScope.wideLine(
    ax: Number,
    ay: Number,
    bx: Number,
    by: Number,
    width: Number,
    color: Color,
) {
    val axf = ax.toFloat()
    val ayf = ay.toFloat()
    val bxf = bx.toFloat()
    val byf = by.toFloat()
    val dx = bxf - axf
    val dy = byf - ayf
    val len = sqrt(dx * dx + dy * dy)
    val r = (width.toFloat() / 2f).toInt()
    if (len < 0.01f) {
        fillCircle(axf, ayf, r.toFloat(), color)
        return
    }
    val nx = -dy / len * (width.toFloat() / 2f)
    val ny = dx / len * (width.toFloat() / 2f)
    fillTriangle(axf + nx, ayf + ny, axf - nx, ayf - ny, bxf + nx, byf + ny, color)
    fillTriangle(bxf + nx, byf + ny, bxf - nx, byf - ny, axf - nx, ayf - ny, color)
    fillCircle(axf, ayf, r.toFloat(), color)
    fillCircle(bxf, byf, r.toFloat(), color)
}

/**
 * The firmware's `inked` — a shape drawn once in ink, four times, one pixel
 * to each side, and then once in the fill colour.
 *
 * It is how every costume gets its outline: the four offset copies make a
 * border round the whole shape without a second shape to keep in step.
 */
internal inline fun DrawScope.inked(ink: Color, col: Color, shape: (ox: Int, oy: Int, c: Color) -> Unit) {
    shape(-1, 0, ink)
    shape(1, 0, ink)
    shape(0, -1, ink)
    shape(0, 1, ink)
    shape(0, 0, col)
}

/**
 * The drawing surface the character and his costumes are drawn on.
 *
 * The firmware's renderer is a set of free functions over the screen; the
 * port's is a set of members over this class, so that the state the two
 * passes share — the scale, the colours, where the limbs ended up — travels
 * with the drawing rather than being threaded through every call.
 */
internal abstract class PotsikCanvas(
    protected val scope: DrawScope,
    /** Pixels per character unit. */
    protected val scale: Float,
) {
    /**
     * The firmware's scale lambda: a character unit turned into pixels.
     *
     * The firmware truncates to a whole pixel, and so does this — the
     * character's shapes are drawn on a pixel grid and a rounded one would
     * not be the same shape.
     */
    fun S(v: Number): Float = (v.toFloat() * scale).toInt().toFloat()

    /** The same, for a value that is not a whole number of units. */
    fun Sf(v: Number): Float = (v.toFloat() * scale).toInt().toFloat()

    fun fillRect(x: Number, y: Number, w: Number, h: Number, color: Color) = scope.fillRect(x, y, w, h, color)

    fun fillRoundRect(x: Number, y: Number, w: Number, h: Number, r: Number, color: Color) = scope.fillRoundRect(x, y, w, h, r, color)

    fun fillCircle(cx: Number, cy: Number, r: Number, color: Color) = scope.fillCircle(cx, cy, r, color)

    fun drawCircleOutline(cx: Number, cy: Number, r: Number, color: Color, width: Number = 1f) = scope.drawCircleOutline(cx, cy, r, color, width)

    fun drawEllipseOutline(cx: Float, cy: Float, rx: Float, ry: Float, color: Color, width: Float = 1f) = scope.drawEllipseOutline(cx, cy, rx, ry, color, width)

    fun fillEllipse(cx: Number, cy: Number, rx: Number, ry: Number, color: Color) = scope.fillEllipse(cx, cy, rx, ry, color)

    fun fillTriangle(x1: Number, y1: Number, x2: Number, y2: Number, x3: Number, y3: Number, color: Color) = scope.fillTriangle(x1, y1, x2, y2, x3, y3, color)

    fun drawLine(x1: Number, y1: Number, x2: Number, y2: Number, color: Color) = scope.drawLine(x1, y1, x2, y2, color)

    fun drawFastHLine(x: Number, y: Number, w: Number, color: Color) = scope.drawFastHLine(x, y, w, color)

    fun drawFastVLine(x: Number, y: Number, h: Number, color: Color) = scope.drawFastVLine(x, y, h, color)

    fun wideLine(ax: Number, ay: Number, bx: Number, by: Number, width: Number, color: Color) = scope.wideLine(ax, ay, bx, by, width, color)

    inline fun inked(ink: Color, col: Color, shape: (ox: Int, oy: Int, c: Color) -> Unit) = scope.inked(ink, col, shape)

    /**
     * The firmware's `fillPoly`: one filled polygon through the points in
     * order. The points are truncated to whole pixels first, as the firmware
     * stores them in int16 arrays.
     */
    fun fillPoly(xs: FloatArray, ys: FloatArray, n: Int, color: Color) {
        if (n < 3) return
        val path = Path()
        path.moveTo(xs[0].toInt().toFloat(), ys[0].toInt().toFloat())
        for (i in 1 until n) path.lineTo(xs[i].toInt().toFloat(), ys[i].toInt().toFloat())
        path.close()
        scope.drawPath(path = path, color = color, style = Fill)
    }

    /**
     * The firmware's `yzAcross`: a flat band across a limb, between two
     * percentages of its length, each end with its own half-width. Every
     * coordinate is truncated to an int the way the firmware's casts do.
     */
    fun yzAcross(
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        u0: Int,
        u1: Int,
        h0: Int,
        h1: Int,
        c: Color,
    ) {
        val dx = (x1 - x0).toFloat()
        val dy = (y1 - y0).toFloat()
        var len = sqrt(dx * dx + dy * dy)
        if (len < 1f) len = 1f
        val nx = -dy / len
        val ny = dx / len
        val ax = x0 + dx * u0 / 100f
        val ay = y0 + dy * u0 / 100f
        val bx = x0 + dx * u1 / 100f
        val by = y0 + dy * u1 / 100f
        val a0x = (ax + nx * h0).toInt().toFloat()
        val a0y = (ay + ny * h0).toInt().toFloat()
        val a1x = (ax - nx * h0).toInt().toFloat()
        val a1y = (ay - ny * h0).toInt().toFloat()
        val b0x = (bx + nx * h1).toInt().toFloat()
        val b0y = (by + ny * h1).toInt().toFloat()
        val b1x = (bx - nx * h1).toInt().toFloat()
        val b1y = (by - ny * h1).toInt().toFloat()
        fillTriangle(a0x, a0y, a1x, a1y, b0x, b0y, c)
        fillTriangle(a1x, a1y, b0x, b0y, b1x, b1y, c)
    }

    /** The firmware's `yzStar`: a four-pointed sparkle, two bars and two spikes. */
    fun yzStar(x: Int, y: Int, r: Int, c: Color) {
        val n = if (r / 3 > 0) r / 3 else 1
        fillTriangle(x - r, y, x + r, y, x, y - n, c)
        fillTriangle(x - r, y, x + r, y, x, y + n, c)
        fillTriangle(x, y - r, x, y + r, x - n, y, c)
        fillTriangle(x, y - r, x, y + r, x + n, y, c)
    }

    /** The firmware's `yzOrb`: a ball with a lit spot, outlined in black. */
    fun yzOrb(x: Int, y: Int, r: Int, c: Color, hi: Color) {
        fillCircle(x, y, r + 1, PotsikPalette.BLACK)
        fillCircle(x, y, r, c)
        val hr = if (r / 3 > 0) r / 3 else 1
        fillCircle(x - r / 3, y - r / 3, hr, hi)
    }
}
