package com.pusios.pots.ui.background

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

/*
 * "Toasters" from the PotSquad firmware (theme.cpp drawFlyingToasters): five
 * chrome toasters with swept wings climbing up and to the right, six flying
 * slices of toast, a quiet starfield, a rare shooting star and comet, and the
 * three ambient cameos -- Boris after a slice, Mowin' Man with his grass strip
 * and the lil guy walking the same line.
 * Purely visual: nothing here takes input, catches anything or reacts.
 *
 * Omitted on purpose: the gold toaster's tap target and the flock/lil-guy hit
 * boxes the firmware publishes, Potsy's duck reaction, and the toaster-to-pet
 * unlock. Those are interaction, not scenery.
 *
 * Simulation runs in 320-wide virtual units at the firmware's 50 ms reference
 * frame; draw() scales by size.width / 320. The band height is only known at
 * draw time, so step() uses the last height seen (240 until the first draw).
 */

private const val TS_SEED = 0x7057E5
private const val TS_N = 5
private const val TS_NT = 6
private const val TS_NSTAR = 44
private const val TS_CMTRAIL = 30
private const val TS_GRASSW = 80
private const val TS_LG_W = 10
private const val TS_LG_H = 10
private const val TS_LG_FRAMES = 8
private const val TS_FRAME_MS = 50f
private const val TS_MAX_K = 3f
private const val TS_DEFAULT_BAND = 240f
private const val TS_DEG = 0.017453292f
private const val TS_TWO_PI = 6.2831855f

// Firmware palette, quantised to RGB565 and then expanded to packed RGB888.
private val TS_BG = bpkRgb565(0x0801)
private val TS_WHITE = 0xFFFFFF
private val TS_CHROME = bpkRgb565(0xBDF2)
private val TS_GOLD = bpkRgb565(0xFEA7)
private val TS_ICE = bpkRgb565(0xBF1F)
private val TS_CHROME_HI = bpkRgb565(0xFFDF)
private val TS_SHADE_TO = bpkRgb565(0x3195)
private val TS_EDGE = bpkRgb565(0x31AC)
private val TS_SLOT = bpkRgb565(0x1083)
private val TS_GLOW = bpkRgb565(0x92C3)
private val TS_GLOW2 = bpkRgb565(0x6982)
private val TS_WING_EDGE = bpkRgb565(0xAD77)
private val TS_WING_SHADE = bpkRgb565(0xD6DB)
private val TS_INK = bpkRgb565(0x3921)
private val TS_TOAST_TOP = bpkRgb565(0xEDEF)
private val TS_TOAST_CRUMB = bpkRgb565(0xF6B3)
private val TS_TOAST_CRUST = bpkRgb565(0xC427)
private val TS_TOAST_EDGE = bpkRgb565(0x92C4)
private val TS_BURNT_TOP = bpkRgb565(0x7AC2)
private val TS_BURNT_CRUMB = bpkRgb565(0x9383)
private val TS_BURNT_CRUST = bpkRgb565(0x5181)
private val TS_BURNT_EDGE = bpkRgb565(0x2861)

private val TS_GRASS_A = bpkRgb565(0x3CA8)
private val TS_GRASS_B = bpkRgb565(0x2B66)
private val TS_BORIS_DARK = bpkRgb565(0x630E)
private val TS_BORIS_LIGHT = bpkRgb565(0x94B5)
private val TS_BORIS_EYE = bpkRgb565(0xD72B)
private val TS_BORIS_PINK = bpkRgb565(0xE474)
private val TS_MOW_SKIN = bpkRgb565(0xEDD1)
private val TS_MOW_SHIRT = bpkRgb565(0x43D9)
private val TS_MOW_TROUSERS = bpkRgb565(0x39EB)
private val TS_MOW_DECK = bpkRgb565(0xC9E6)
private val TS_MOW_METAL = bpkRgb565(0x94B5)
private val TS_MOW_HAT = bpkRgb565(0x2947)
private val TS_LG_HAIR = bpkRgb565(0x07FE)
private val TS_LG_SKIN = bpkRgb565(0xFE9E)
private val TS_LG_BODY = bpkRgb565(0xBB3F)

// The lil guy's walk cycle, 8 frames of 10x10 art pixels, two bits per pixel
// packed low-first along each row: 0 clear, 1 hair, 2 skin, 3 body.
private val TS_LILGUY = intArrayOf(
    0x000000, 0x001540, 0x000950, 0x002A40, 0x000E40, 0x000E40, 0x000E00, 0x000F00,
    0x000F80, 0x000A00, 0x001100, 0x000540, 0x000950, 0x002A40, 0x000E40, 0x000F90,
    0x008F80, 0x008F00, 0x0023C0, 0x000280, 0x000000, 0x000440, 0x000550, 0x000940,
    0x002A40, 0x000E90, 0x000F80, 0x003FA0, 0x003FC0, 0x00A0A0, 0x000000, 0x000500,
    0x001950, 0x002A50, 0x000E40, 0x000E40, 0x000F80, 0x000F00, 0x003FE0, 0x002820,
    0x000000, 0x001540, 0x000950, 0x002A50, 0x000E40, 0x000B40, 0x000E00, 0x000F00,
    0x000F80, 0x000A00, 0x001100, 0x000540, 0x000950, 0x002A40, 0x000E40, 0x000B40,
    0x008B00, 0x008F00, 0x0023C0, 0x000280, 0x000000, 0x000440, 0x000550, 0x000940,
    0x002A40, 0x000B40, 0x000B00, 0x002F00, 0x003FC0, 0x00A0A0, 0x000000, 0x000500,
    0x001950, 0x002A50, 0x000E40, 0x000E40, 0x000B00, 0x000F00, 0x003FE0, 0x002820,
)

private val TS_HAIR = Stroke(width = 1f)

// Wing width along its length, baked from the firmware's profile.
private val TS_WING_PROFILE = floatArrayOf(
    0.400f, 0.650f, 0.900f, 0.997f, 0.986f, 0.956f, 0.885f, 0.724f, 0.0f,
)

/**
 * Scratch state and the shape drawing for toasters and toast. Holds the
 * reused Path and the wing buffers so drawing does not allocate per frame.
 */
private class ToasterPainter {
    private val poly = Path()

    private val wTx = FloatArray(9)
    private val wTy = FloatArray(9)
    private val wBx = FloatArray(9)
    private val wBy = FloatArray(9)
    private val wCx = FloatArray(9)
    private val wCy = FloatArray(9)
    private val wHx = FloatArray(18)
    private val wHy = FloatArray(18)
    private val wSx = FloatArray(18)
    private val wSy = FloatArray(18)

    private fun DrawScope.fillPoly(xs: FloatArray, ys: FloatArray, n: Int, color: Int) {
        poly.reset()
        poly.moveTo(xs[0], ys[0])
        for (i in 1 until n) poly.lineTo(xs[i], ys[i])
        poly.close()
        drawPath(poly, bpkColor(color))
    }

    private fun DrawScope.fillQuad(
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        x3: Float,
        y3: Float,
        color: Int,
    ) {
        poly.reset()
        poly.moveTo(x0, y0)
        poly.lineTo(x1, y1)
        poly.lineTo(x2, y2)
        poly.lineTo(x3, y3)
        poly.close()
        drawPath(poly, bpkColor(color))
    }

    private fun DrawScope.fillTri(
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        color: Int,
    ) {
        poly.reset()
        poly.moveTo(x0, y0)
        poly.lineTo(x1, y1)
        poly.lineTo(x2, y2)
        poly.close()
        drawPath(poly, bpkColor(color))
    }

    /** One rounded bird wing with feather divisions, after firmware drawWing(). */
    private fun DrawScope.drawWing(
        sx: Float,
        sy: Float,
        len: Float,
        angDeg: Float,
        flap: Float,
        width: Float,
        ndiv: Int,
        body: Int,
        edge: Int,
        curl: Float,
        lift: Float,
        shade: Int,
    ) {
        val n = 8
        val step = len / n
        val da = curl * 60f * TS_DEG / n
        var a = (angDeg + flap * lift) * TS_DEG
        var px = sx
        var py = sy
        for (i in 0..n) {
            val wgt = TS_WING_PROFILE[i] * width * len
            val nx = -sin(a)
            val ny = cos(a)
            wCx[i] = px
            wCy[i] = py
            wTx[i] = px + nx * wgt * 0.58f
            wTy[i] = py + ny * wgt * 0.58f
            wBx[i] = px - nx * wgt * 0.44f
            wBy[i] = py - ny * wgt * 0.44f
            px += cos(a) * step
            py += sin(a) * step
            a += da
        }

        // Body outline: top edge out, bottom edge back.
        var m = 0
        for (i in 0..n) {
            wHx[m] = wTx[i]
            wHy[m] = wTy[i]
            m++
        }
        for (i in n downTo 0) {
            wHx[m] = wBx[i]
            wHy[m] = wBy[i]
            m++
        }
        fillPoly(wHx, wHy, m, body)
        val ec = bpkColor(edge)
        for (i in 0 until m) {
            val j = (i + 1) % m
            drawLine(ec, Offset(wHx[i], wHy[i]), Offset(wHx[j], wHy[j]), 1f)
        }

        // Shade whichever edge ends up lower on screen.
        val shadeTop = wTy[n / 2] > wBy[n / 2]
        val sxA = if (shadeTop) wTx else wBx
        val syA = if (shadeTop) wTy else wBy
        var sn = 0
        for (i in 2..n) {
            wSx[sn] = wCx[i]
            wSy[sn] = wCy[i]
            sn++
        }
        for (i in n downTo 2) {
            wSx[sn] = sxA[i]
            wSy[sn] = syA[i]
            sn++
        }
        fillPoly(wSx, wSy, sn, shade)

        for (d in 1..ndiv) {
            val i0 = (n.toFloat() * (0.26f + 0.16f * d)).toInt()
            var i1 = i0 + 2
            if (i1 > n) i1 = n
            if (i1 <= i0) break
            drawLine(ec, Offset(wCx[i0], wCy[i0]), Offset(sxA[i1], syA[i1]), 1f)
        }
    }

    /** Chrome toaster with two slots and two wings, after firmware drawToasterAt(). */
    fun DrawScope.drawToaster(x: Float, y: Float, nowMs: Long, body: Int, s: Float) {
        val bw = 44f * s
        val bh = 30f * s
        // 500 ms flap beat, offset per sprite so a flock does not pulse in unison.
        val phase = ((nowMs + floor(x).toLong() * 37L) % 500L + 500L) % 500L
        val flap = sin(phase.toFloat() / 500f * TS_TWO_PI)

        drawWing(
            x + bw - 13f * s, y + 15.5f * s, 28f * s, 248f, flap, 0.47f, 3,
            TS_WHITE, TS_WING_EDGE, -0.55f, 20f, TS_WING_SHADE,
        )

        val chMid = bpkBlend(body, TS_SHADE_TO, 70)
        val chLo = bpkBlend(body, TS_SHADE_TO, 130)
        val chDk = bpkBlend(body, TS_SHADE_TO, 200)
        drawRoundRect(
            bpkColor(body),
            Offset(x, y),
            Size(bw, bh),
            CornerRadius(8f * s),
        )
        drawRect(bpkColor(chMid), Offset(x + 4f * s, y + bh - 9f * s), Size(bw - 8f * s, 4f * s))
        drawRect(bpkColor(chLo), Offset(x + 4f * s, y + bh - 5f * s), Size(bw - 8f * s, 3f * s))
        drawRect(bpkColor(TS_CHROME_HI), Offset(x + 7f * s, y + bh - 16f * s), Size(bw - 15f * s, 2f * s))

        for (i in 0..1) {
            val yy = y + (5f + i * 7f) * s
            drawRect(bpkColor(TS_SLOT), Offset(x + 9f * s, yy), Size(bw - 17f * s, 4f * s))
            drawRect(
                bpkColor(if (i == 1) TS_GLOW2 else TS_GLOW),
                Offset(x + 11f * s, yy + s),
                Size(bw - 21f * s, 2f * s),
            )
        }

        drawRect(bpkColor(chDk), Offset(x + bw - 7f * s, y + bh - 16f * s), Size(4f * s, 10f * s))
        drawRect(bpkColor(TS_SLOT), Offset(x + bw - 6f * s, y + bh - 15f * s), Size(2f * s, 8f * s))

        drawRoundRect(
            bpkColor(TS_EDGE),
            Offset(x, y),
            Size(bw, bh),
            CornerRadius(8f * s),
            style = TS_HAIR,
        )

        drawWing(
            x + bw - 17f * s, y + 16.5f * s, 35f * s, 214f, flap, 0.51f, 3,
            TS_WHITE, TS_WING_EDGE, -0.55f, 20f, TS_WING_SHADE,
        )
    }

    /** Isometric slice of toast, after firmware drawToastAt(). */
    fun DrawScope.drawToast(
        x: Float,
        y: Float,
        nowMs: Long,
        hasFace: Boolean,
        s: Float,
        burnt: Boolean,
    ) {
        val hw = 26f * s
        val hh = 14f * s
        val th = 7f * s
        val cx = x + hw
        val cy = y + hh

        val top = if (burnt) TS_BURNT_TOP else TS_TOAST_TOP
        val crumb = if (burnt) TS_BURNT_CRUMB else TS_TOAST_CRUMB
        val crust = if (burnt) TS_BURNT_CRUST else TS_TOAST_CRUST
        val edge = if (burnt) TS_BURNT_EDGE else TS_TOAST_EDGE

        // Front-left crust wall, front-right crust wall, top face, crumb inset.
        fillQuad(cx - hw, cy, cx, cy + hh, cx, cy + hh + th, cx - hw, cy + th, edge)
        fillQuad(cx, cy + hh, cx + hw, cy, cx + hw, cy + th, cx, cy + hh + th, crust)
        fillQuad(cx, cy - hh, cx + hw, cy, cx, cy + hh, cx - hw, cy, top)
        fillQuad(
            cx, cy - hh + 4f * s, cx + hw - 8f * s, cy,
            cx, cy + hh - 4f * s, cx - hw + 8f * s, cy, crumb,
        )

        val ec = bpkColor(edge)
        drawLine(ec, Offset(cx, cy - hh), Offset(cx + hw, cy), 1f)
        drawLine(ec, Offset(cx + hw, cy), Offset(cx, cy + hh), 1f)
        drawLine(ec, Offset(cx, cy + hh), Offset(cx - hw, cy), 1f)
        drawLine(ec, Offset(cx - hw, cy), Offset(cx, cy - hh), 1f)

        // Smoke thread off a burnt slice, drifting and thinning as it rises.
        if (burnt) {
            for (k in 0 until 5) {
                val up = k * 3.5f * s
                val sway = sin(nowMs.toDouble() / 240.0 + k * 0.9 + x * 0.13).toFloat() *
                    2.6f * s * (k * 0.35f)
                val a = 120 - k * 22
                drawRect(
                    bpkColor(bpkBlend(TS_BG, TS_WHITE, a)),
                    Offset(cx + sway, cy - hh - up),
                    Size(1f, 1f),
                )
            }
        }

        if (hasFace) {
            val ink = bpkColor(TS_INK)
            drawRect(ink, Offset(cx - 7f * s, cy - 3f * s), Size(3f * s, 3f * s))
            drawRect(ink, Offset(cx + 4f * s, cy - 3f * s), Size(3f * s, 3f * s))
            if (sin(nowMs.toDouble() / 500.0 + x) > 0.0) {
                drawLine(ink, Offset(cx - 5f * s, cy + 5f * s), Offset(cx + 5f * s, cy + 5f * s), 1f)
            } else {
                drawRect(ink, Offset(cx - 2f * s, cy + 3f * s), Size(4f * s, 4f * s))
            }
        }
    }

    /** Side-on cat silhouette batting at a slice, after firmware drawBorisAt(). */
    fun DrawScope.drawBoris(x: Float, y: Float, nowMs: Long, s: Float, swipe: Boolean) {
        val dark = bpkColor(TS_BORIS_DARK)
        val light = bpkColor(TS_BORIS_LIGHT)
        val ink = bpkColor(TS_BORIS_EYE)
        val pink = bpkColor(TS_BORIS_PINK)

        // Tail: a swishing arc behind him, three segments so it curls.
        val sw = sin(nowMs.toDouble() / 300.0).toFloat() * 0.55f
        var tx0 = x + 4f * s
        var ty0 = y + 17f * s
        for (k in 0 until 3) {
            val a = 3.3f + sw * (k + 1) * 0.4f
            val nx = tx0 - cos(a) * 7f * s
            val ny = ty0 - sin(a) * 7f * s
            drawLine(dark, Offset(tx0, ty0), Offset(nx, ny), 1f)
            drawLine(dark, Offset(tx0, ty0 + 1f), Offset(nx, ny + 1f), 1f)
            tx0 = nx
            ty0 = ny
        }

        // Body and haunch.
        drawRoundRect(dark, Offset(x + 4f * s, y + 10f * s), Size(24f * s, 12f * s), CornerRadius(5f * s))
        drawRoundRect(light, Offset(x + 3f * s, y + 12f * s), Size(11f * s, 11f * s), CornerRadius(5f * s))
        // Head, with the ears as two triangles off the top.
        drawRoundRect(light, Offset(x + 23f * s, y + 4f * s), Size(14f * s, 12f * s), CornerRadius(5f * s))
        fillTri(x + 24f * s, y + 6f * s, x + 26f * s, y - 1f * s, x + 30f * s, y + 5f * s, TS_BORIS_LIGHT)
        fillTri(x + 32f * s, y + 5f * s, x + 36f * s, y - 1f * s, x + 37f * s, y + 6f * s, TS_BORIS_LIGHT)
        fillTri(x + 26f * s, y + 5f * s, x + 27f * s, y + 1f * s, x + 29f * s, y + 5f * s, TS_BORIS_PINK)
        fillTri(x + 33f * s, y + 5f * s, x + 35f * s, y + 1f * s, x + 36f * s, y + 6f * s, TS_BORIS_PINK)
        // Eyes and nose.
        drawRect(ink, Offset(x + 27f * s, y + 8f * s), Size(3f * s, 3f * s))
        drawRect(ink, Offset(x + 33f * s, y + 8f * s), Size(3f * s, 3f * s))
        drawRect(pink, Offset(x + 37f * s, y + 12f * s), Size(1f, 1f))

        // Front paw: tucked while drifting, thrown forward on the swipe.
        if (swipe) {
            drawRoundRect(light, Offset(x + 34f * s, y + 13f * s), Size(12f * s, 5f * s), CornerRadius(2f * s))
            drawRect(pink, Offset(x + 45f * s, y + 13f * s), Size(2f, 2f))
        } else {
            drawRoundRect(light, Offset(x + 24f * s, y + 18f * s), Size(9f * s, 5f * s), CornerRadius(2f * s))
        }
        // Back leg.
        drawRoundRect(light, Offset(x + 6f * s, y + 19f * s), Size(9f * s, 5f * s), CornerRadius(2f * s))
    }

    /** Mowin' Man on the bottom edge, after firmware drawMowinManAt(). */
    fun DrawScope.drawMowinMan(x: Float, baseY: Float, nowMs: Long, s: Float) {
        val step = (nowMs / 180L) % 2L != 0L
        val bob = if (step) 0f else s
        val trousers = bpkColor(TS_MOW_TROUSERS)

        // Legs alternate on a walk cycle; the body bobs with it.
        drawRect(trousers, Offset(x + 6f * s, baseY - 9f * s + bob), Size(3f * s, 9f * s))
        drawRect(
            trousers,
            Offset(x + (if (step) 10f else 3f) * s, baseY - 9f * s + bob),
            Size(3f * s, 9f * s),
        )
        drawRoundRect(
            bpkColor(TS_MOW_SHIRT),
            Offset(x + 4f * s, baseY - 19f * s + bob),
            Size(10f * s, 11f * s),
            CornerRadius(3f * s),
        )
        drawRect(
            bpkColor(TS_MOW_SKIN),
            Offset(x + 13f * s, baseY - 17f * s + bob),
            Size(7f * s, 3f * s),
        )
        drawCircle(bpkColor(TS_MOW_SKIN), 4f * s, Offset(x + 9f * s, baseY - 22f * s + bob))
        drawRect(bpkColor(TS_MOW_HAT), Offset(x + 5f * s, baseY - 26f * s + bob), Size(9f * s, 3f * s))

        // Mower: handle up to his hands, deck on the ground, wheels.
        val metal = bpkColor(TS_MOW_METAL)
        drawLine(metal, Offset(x + 19f * s, baseY - 16f * s + bob), Offset(x + 27f * s, baseY - 4f * s), 1f)
        drawLine(metal, Offset(x + 20f * s, baseY - 16f * s + bob), Offset(x + 28f * s, baseY - 4f * s), 1f)
        drawRoundRect(
            bpkColor(TS_MOW_DECK),
            Offset(x + 21f * s, baseY - 7f * s),
            Size(14f * s, 6f * s),
            CornerRadius(2f * s),
        )
        drawCircle(trousers, 2f * s, Offset(x + 23f * s, baseY - s))
        drawCircle(trousers, 2f * s, Offset(x + 33f * s, baseY - s))
    }

    /** The lil guy, one art pixel per frame unit, after firmware drawLilGuy(). */
    fun DrawScope.drawLilGuy(x: Float, baseY: Float, nowMs: Long, s: Float) {
        val f = (nowMs / 80L % TS_LG_FRAMES).toInt()
        val top = baseY - TS_LG_H * s
        for (row in 0 until TS_LG_H) {
            val bits = TS_LILGUY[f * TS_LG_H + row]
            for (col in 0 until TS_LG_W) {
                val c = (bits ushr (col * 2)) and 3
                if (c == 0) continue
                val rgb = when (c) {
                    1 -> TS_LG_HAIR
                    2 -> TS_LG_SKIN
                    else -> TS_LG_BODY
                }
                drawRect(bpkColor(rgb), Offset(x + col * s, top + row * s), Size(s, s))
            }
        }
    }
}

class ToastersScene : BackgroundScene {
    private val rnd = Random(TS_SEED)
    private val painter = ToasterPainter()
    private var bandH = TS_DEFAULT_BAND
    private var layoutBandH = TS_DEFAULT_BAND
    private var started = false

    // Chrome (and rarely gold) toasters.
    private val tx = FloatArray(TS_N)
    private val ty = FloatArray(TS_N)
    private val tScale = FloatArray(TS_N)
    private val tGold = BooleanArray(TS_N)

    // Flying toast.
    private val ox = FloatArray(TS_NT)
    private val oy = FloatArray(TS_NT)
    private val oScale = FloatArray(TS_NT)
    private val oFace = BooleanArray(TS_NT)
    private val oBurnt = BooleanArray(TS_NT)

    // Starfield.
    private val starX = FloatArray(TS_NSTAR)
    private val starY = FloatArray(TS_NSTAR)
    private val starMag = IntArray(TS_NSTAR)
    private val starPh = IntArray(TS_NSTAR)

    // Shooting star. Life and age are in frames.
    private var ssLive = false
    private var ssX = 0f
    private var ssY = 0f
    private var ssVX = 0f
    private var ssVY = 0f
    private var ssAge = 0f
    private var ssLife = 0f
    private var ssNext = 0L

    // Comet, with a position-history trail.
    private var cmLive = false
    private var cmX = 0f
    private var cmY = 0f
    private var cmVX = 0f
    private var cmVY = 0f
    private var cmTurn = 0f
    private val cmHx = FloatArray(TS_CMTRAIL)
    private val cmHy = FloatArray(TS_CMTRAIL)
    private var cmHn = 0
    private var cmNext = 0L

    // Boris, after a slice of toast.
    private var boLive = false
    private var boX = 0f
    private var boY = 0f
    private var boS = 1f
    private var boNext = 0L

    // Mowin' Man and the strip of grass he cuts.
    private val grass = IntArray(TS_GRASSW)
    private var mmLive = false
    private var mmX = 0f
    private var mmNext = 0L

    // The lil guy walks the mower's line.
    private var lgLive = false
    private var lgX = -40f
    private var lgNext = 0L
    private var lgLast = 0L

    init {
        for (i in 0 until TS_N) resetToaster(i, fresh = true)
        for (i in 0 until TS_NT) resetToast(i, fresh = true)
        for (i in 0 until TS_NSTAR) {
            starX[i] = uniform(0f, BPK_VIRTUAL_W)
            starY[i] = uniform(0f, bandH)
            starMag[i] = if (rnd.nextInt(8) == 0) rnd.nextInt(200, 256) else rnd.nextInt(70, 150)
            starPh[i] = rnd.nextInt(256)
        }
    }

    private fun uniform(lo: Float, hi: Float): Float = lo + rnd.nextFloat() * (hi - lo)

    private fun nextMs(from: Int, to: Int): Long = rnd.nextInt(from, to + 1).toLong()

    private fun resetToaster(i: Int, fresh: Boolean) {
        tx[i] = if (fresh) rnd.nextInt(-320, 321).toFloat() else -(rnd.nextInt(0, 41)).toFloat() - 70f
        ty[i] = uniform(12f, bandH - 12f)
        tGold[i] = !fresh && rnd.nextInt(40) == 0
        tScale[i] = 0.55f + rnd.nextFloat() * 0.45f
    }

    /**
     * The band height is only known once drawn, so the first layout (and any
     * resize) proportionally re-places the sprites the firmware seeded against
     * the real band instead of the 240-unit placeholder.
     */
    private fun rescaleToBand() {
        if (bandH == layoutBandH || layoutBandH <= 0f) return
        val f = bandH / layoutBandH
        for (i in 0 until TS_N) ty[i] *= f
        for (i in 0 until TS_NT) oy[i] *= f
        for (i in 0 until TS_NSTAR) starY[i] *= f
        if (boLive) boY *= f
        layoutBandH = bandH
    }

    private fun resetToast(i: Int, fresh: Boolean) {
        ox[i] = if (fresh) rnd.nextInt(-320, 321).toFloat() else -(rnd.nextInt(0, 61)).toFloat() - 16f
        oy[i] = uniform(14f, bandH - 14f)
        oFace[i] = rnd.nextInt(3) == 0
        oBurnt[i] = rnd.nextInt(7) == 0
        oScale[i] = 0.5f + rnd.nextFloat() * 0.4f
    }

    override fun step(dtMs: Float, nowMs: Long) {
        rescaleToBand()
        val k = (dtMs / TS_FRAME_MS).coerceIn(0f, TS_MAX_K)
        if (!started) {
            started = true
            ssNext = nowMs + nextMs(2500, 7000)
            cmNext = nowMs + nextMs(9000, 22000)
            boNext = nowMs + nextMs(40000, 90000)
            mmNext = nowMs + nextMs(55000, 120000)
            lgNext = nowMs + 20000L
        }

        // Toasters climb up and to the right.
        for (i in 0 until TS_N) {
            tx[i] += (0.6f + (i % 3) * 0.25f) * tScale[i] * k
            ty[i] -= (0.15f + (i % 2) * 0.1f) * tScale[i] * k
            if (tx[i] > BPK_VIRTUAL_W + 40f || ty[i] < -44f) resetToaster(i, fresh = false)
        }

        // Toast follows the same climb, a little slower.
        for (i in 0 until TS_NT) {
            ox[i] += (0.7f + (i % 3) * 0.2f) * oScale[i] * k
            oy[i] -= (0.18f + (i % 2) * 0.12f) * oScale[i] * k
            if (ox[i] > BPK_VIRTUAL_W + 40f || oy[i] < -30f) resetToast(i, fresh = false)
        }

        // Starfield drifts down-left, the flock flies up-right.
        for (i in 0 until TS_NSTAR) {
            starX[i] -= 0.06f * k
            starY[i] += 0.03f * k
            if (starX[i] < 0f) starX[i] = BPK_VIRTUAL_W
            if (starY[i] >= bandH) starY[i] = 0f
            if (starY[i] < 0f) starY[i] = bandH - 1f
        }

        stepShooting(k, nowMs)
        stepComet(k, nowMs)
        stepBoris(k, nowMs)
        stepMower(k, nowMs)
        stepLilGuy(nowMs)
    }

    private fun stepShooting(k: Float, nowMs: Long) {
        if (!ssLive && nowMs >= ssNext) {
            ssLive = true
            ssX = uniform(BPK_VIRTUAL_W / 4f, BPK_VIRTUAL_W)
            ssY = uniform(0f, bandH / 3f)
            val sp = 5f + rnd.nextInt(0, 41) / 10f
            ssVX = -sp * 0.86f
            ssVY = sp * 0.5f
            ssAge = 0f
            ssLife = rnd.nextInt(16, 30).toFloat()
        }
        if (ssLive) {
            ssX += ssVX * k
            ssY += ssVY * k
            ssAge += k
            if (ssAge >= ssLife || ssX < -20f || ssY > bandH) {
                ssLive = false
                ssNext = nowMs + nextMs(2500, 7000)
            }
        }
    }

    private fun stepComet(k: Float, nowMs: Long) {
        if (!cmLive && nowMs >= cmNext) {
            cmLive = true
            cmX = BPK_VIRTUAL_W + 30f
            cmY = uniform(4f, bandH - bandH / 3f)
            cmVX = -(1.7f + rnd.nextInt(0, 121) / 100f)
            cmVY = 0.35f + rnd.nextInt(0, 56) / 100f
            cmTurn = rnd.nextInt(-16, 17) / 12000f
            cmHn = 0
        }
        if (cmLive) {
            // Curve the flight by rotating the velocity a little each frame.
            val c = cos(cmTurn * k)
            val s = sin(cmTurn * k)
            val nvx = cmVX * c - cmVY * s
            cmVY = cmVX * s + cmVY * c
            cmVX = nvx
            cmX += cmVX * k
            cmY += cmVY * k

            for (j in TS_CMTRAIL - 1 downTo 1) {
                cmHx[j] = cmHx[j - 1]
                cmHy[j] = cmHy[j - 1]
            }
            cmHx[0] = cmX
            cmHy[0] = cmY
            if (cmHn < TS_CMTRAIL) cmHn++

            if (cmX < -70f || cmY > bandH + 24f || cmY < -24f) {
                cmLive = false
                cmNext = nowMs + nextMs(9000, 22000)
            }
        }
    }

    private fun stepBoris(k: Float, nowMs: Long) {
        if (!boLive && nowMs >= boNext) {
            boLive = true
            boX = -70f
            boY = uniform(10f, bandH - 46f)
            boS = 0.65f + rnd.nextInt(0, 41) / 100f
        }
        if (boLive) {
            boX += 1.1f * k
            if (boX > BPK_VIRTUAL_W + 80f) {
                boLive = false
                boNext = nowMs + nextMs(40000, 90000)
            }
        }
    }

    private fun stepMower(k: Float, nowMs: Long) {
        if (!mmLive && lgLive && nowMs >= mmNext) {
            // The lil guy has the line; come back for it rather than skip.
            mmNext = nowMs + 5000L
        } else if (!mmLive && nowMs >= mmNext) {
            mmLive = true
            mmX = -50f
            for (i in 0 until TS_GRASSW) grass[i] = 0
        }
        if (mmLive) {
            mmX += 0.85f * k
            // A patch that travels with him: grows in ahead, is cut under the
            // deck, holds as stubble behind and dies off once he is past.
            val colW = (BPK_VIRTUAL_W.toInt() + TS_GRASSW - 1) / TS_GRASSW
            for (i in 0 until TS_GRASSW) {
                val gx = i * colW
                if (gx > BPK_VIRTUAL_W) break
                val ahead = gx - mmX
                if (ahead > 26f && ahead < 150f) {
                    if (grass[i] < 7 && (nowMs / 40L + i * 3L) % 5L == 0L) grass[i]++
                } else if (ahead <= 26f && ahead > -6f) {
                    grass[i] = 1
                } else if (ahead <= -6f && ahead > -110f) {
                    if (grass[i] < 3 && (nowMs / 90L + i) % 29L == 0L) grass[i]++
                } else if (grass[i] > 0 && (nowMs / 60L + i) % 7L == 0L) {
                    grass[i]--
                }
            }
            if (mmX > BPK_VIRTUAL_W + 60f) {
                mmLive = false
                mmNext = nowMs + nextMs(55000, 120000)
            }
        }
    }

    private fun stepLilGuy(nowMs: Long) {
        if (!lgLive && mmLive && nowMs >= lgNext) {
            lgNext = nowMs + 5000L
        } else if (!lgLive && nowMs >= lgNext) {
            lgLive = true
            lgX = -(TS_LG_W * 2).toFloat() - 4f
            lgLast = nowMs
        }
        if (lgLive) {
            var ld = nowMs - lgLast
            if (ld > 200L) ld = 200L
            if (ld < 0L) ld = 0L
            lgLast = nowMs
            lgX += 0.025f * ld
            if (lgX > BPK_VIRTUAL_W + 8f) {
                lgLive = false
                lgNext = nowMs + 300000L
            }
        }
    }

    override fun DrawScope.draw(nowMs: Long) {
        drawRect(bpkColor(TS_BG), size = size)
        val k = size.width / BPK_VIRTUAL_W
        if (k <= 0f) return
        bandH = size.height / k
        withTransform({ scale(k, k, Offset.Zero) }) {
            drawBand(nowMs)
        }
    }

    private fun DrawScope.drawBand(nowMs: Long) {
        drawStars(nowMs)
        drawShooting()
        drawComet(nowMs)
        for (i in 0 until TS_N) {
            val body = if (tGold[i]) TS_GOLD else TS_CHROME
            with(painter) { drawToaster(tx[i], ty[i], nowMs, body, tScale[i]) }
            if (tGold[i]) drawSparkle(i)
        }
        for (i in 0 until TS_NT) {
            with(painter) { drawToast(ox[i], oy[i], nowMs, oFace[i], oScale[i], oBurnt[i]) }
        }
        if (boLive) {
            val bob = sin(nowMs.toDouble() / 620.0).toFloat() * 5f
            val swipe = nowMs / 900L % 3L == 0L
            // The slice he is after, always just out of reach ahead of him.
            val t2x = boX + 52f * boS + (if (swipe) 5f else 0f)
            val t2y = boY + bob - 4f + sin(nowMs.toDouble() / 300.0).toFloat() * 3f
            with(painter) {
                drawToast(t2x, t2y, nowMs, hasFace = false, s = boS * 0.55f, burnt = false)
                drawBoris(boX, boY + bob, nowMs, boS, swipe)
            }
        }
        if (mmLive) {
            drawGrass(bandH - 1f)
            with(painter) { drawMowinMan(mmX, bandH - 1f, nowMs, 0.85f) }
        }
        if (lgLive) {
            with(painter) { drawLilGuy(lgX, bandH - 1f, nowMs, 2f) }
        }
    }

    private fun DrawScope.drawGrass(baseY: Float) {
        val colW = (BPK_VIRTUAL_W.toInt() + TS_GRASSW - 1) / TS_GRASSW
        for (i in 0 until TS_GRASSW) {
            val gh = grass[i]
            if (gh <= 0) continue
            val gx = (i * colW).toFloat()
            drawRect(
                bpkColor(if (i and 1 == 1) TS_GRASS_A else TS_GRASS_B),
                Offset(gx, baseY - gh),
                Size(1f, gh.toFloat()),
            )
        }
    }

    private fun DrawScope.drawStars(nowMs: Long) {
        val t = nowMs.toDouble()
        for (i in 0 until TS_NSTAR) {
            val tw = sin(t / 900.0 + starPh[i] * 0.0246).toFloat()
            val b = (starMag[i] + (tw * 38f).toInt()).coerceIn(24, 255)
            val c = if (b > 235) 255 else b + 20
            val sc = bpkRgb(b, b, c)
            val px = floor(starX[i])
            val py = floor(starY[i])
            drawRect(bpkColor(sc), Offset(px, py), Size(1f, 1f))
            // The brightest few get a one-pixel cross so they read as stars.
            if (starMag[i] > 200) {
                val dim = bpkColor(bpkBlend(TS_BG, sc, 150))
                drawRect(dim, Offset(px - 1f, py), Size(1f, 1f))
                drawRect(dim, Offset(px + 1f, py), Size(1f, 1f))
                drawRect(dim, Offset(px, py - 1f), Size(1f, 1f))
                drawRect(dim, Offset(px, py + 1f), Size(1f, 1f))
            }
        }
    }

    private fun DrawScope.drawSparkle(i: Int) {
        for (k in 1..3) {
            val spx = tx[i] - k * 3.5f
            val spy = ty[i] + k * 0.9f + 6f
            drawRect(
                bpkColor(bpkBlend(TS_BG, TS_GOLD, 180 - k * 50)),
                Offset(spx, spy),
                Size(1f, 1f),
            )
        }
    }

    private fun DrawScope.drawShooting() {
        if (!ssLive) return
        // Fade in over the first few frames and out over the last few.
        val remF = ssLife - ssAge
        var amp = 255f
        if (ssAge < 4f) amp = 64f * (ssAge + 1f)
        if (remF < 6f) amp = 42f * remF
        amp = amp.coerceIn(0f, 255f)
        for (k in 0 until 3) {
            val t0 = k * 2.4f
            val t1 = (k + 1) * 2.4f
            val a = (amp * (200 - k * 62) / 255f).toInt()
            drawLine(
                bpkColor(bpkBlend(TS_BG, TS_WHITE, a)),
                Offset(ssX - ssVX * t0, ssY - ssVY * t0),
                Offset(ssX - ssVX * t1, ssY - ssVY * t1),
                1f,
            )
        }
        drawRect(
            bpkColor(bpkBlend(TS_BG, TS_WHITE, amp.toInt())),
            Offset(floor(ssX), floor(ssY)),
            Size(1f, 1f),
        )
    }

    private fun DrawScope.drawComet(nowMs: Long) {
        if (!cmLive) return
        // Walk the history from the far end, so the brighter, wider trail
        // nearer the head paints over the dimmer tail behind it.
        for (k in cmHn - 1 downTo 1) {
            val agef = 1f - k.toFloat() / TS_CMTRAIL
            val a = (20f + 200f * agef * agef).toInt()
            val col = bpkColor(bpkBlend(TS_BG, TS_ICE, a))
            val x0 = cmHx[k]
            val y0 = cmHy[k]
            val x1 = cmHx[k - 1]
            val y1 = cmHy[k - 1]
            drawLine(col, Offset(x0, y0), Offset(x1, y1), 1f)
            if (agef > 0.58f) {
                val dx = x1 - x0
                val dy = y1 - y0
                val off = if (agef > 0.84f) 2 else 1
                for (o in 1..off) {
                    val fo = o.toFloat()
                    if (dx * dx >= dy * dy) {
                        drawLine(col, Offset(x0, y0 - fo), Offset(x1, y1 - fo), 1f)
                        drawLine(col, Offset(x0, y0 + fo), Offset(x1, y1 + fo), 1f)
                    } else {
                        drawLine(col, Offset(x0 - fo, y0), Offset(x1 - fo, y1), 1f)
                        drawLine(col, Offset(x0 + fo, y0), Offset(x1 + fo, y1), 1f)
                    }
                }
            }
        }

        // Head: a bright core with a pulsing glow.
        val pulse = sin(nowMs.toDouble() / 110.0).toFloat()
        val head = Offset(cmX, cmY)
        drawCircle(bpkColor(TS_WHITE), 3f, head)
        drawCircle(
            bpkColor(bpkBlend(TS_BG, TS_ICE, (170f + pulse * 60f).toInt().coerceIn(0, 256))),
            4f,
            head,
            style = TS_HAIR,
        )
        drawCircle(
            bpkColor(bpkBlend(TS_BG, TS_ICE, (60f + pulse * 34f).toInt().coerceIn(0, 256))),
            6f,
            head,
            style = TS_HAIR,
        )
    }
}
