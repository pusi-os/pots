package com.pusios.pots.ui.potsik

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The costumes — the firmware's `drawOutfit()`, ported.
 *
 * The costume layer is drawn last, over everything else, and it hangs off the
 * head anchor rather than the hip anchor: a hat sits on the head, and a head
 * that moves with the squash takes its hat with it.
 *
 * Each costume is the firmware's own drawing — the same shapes, in the same
 * order, with the same colours. A costume that recolours the body does that
 * in [PotsikPainter] before the body is drawn, because there is no cheap way
 * to repaint a silhouette that has already been drawn in another colour.
 */
internal fun DrawScope.drawPotsikOutfit(
    look: PotsikLook,
    frame: PotsikFrame,
    hh: Float,
    limbs: PotsikLimbs,
    cx2: Float,
    scale: Float,
) {
    val painter = PotsikCostumePainter(this, look, frame, hh, limbs, cx2, scale)
    painter.draw()
}

/** The costume drawing pass itself. */
internal class PotsikCostumePainter(
    scope: DrawScope,
    private val look: PotsikLook,
    private val frame: PotsikFrame,
    private val hh: Float,
    private val limbs: PotsikLimbs,
    private val cx2: Float,
    scale: Float,
) : PotsikCanvas(scope, scale) {

    private val now: Long = frame.nowMs
    private val kb: Float = S(1).coerceAtLeast(1f)
    private val keyCol: Color = PotsikPalette.KEYLINE
    private val yzGoldC: Color = rgb565(0xFEC0)

    /**
     * The hip anchor: the head anchor less the squash and the emote's own
     * drop.
     *
     * The costumes that hang from his shoulders rather than from his head —
     * the wizard's robe and his magic — need it, and the body painter works
     * it out the same way.
     */
    private val actHead: Float
        get() = when (frame.actPose) {
            PotsikVisitPose.CROUCH -> S(2)
            PotsikVisitPose.BOW -> S(7)
            PotsikVisitPose.SAD -> S(3)
            PotsikVisitPose.HOWL -> -S(3)
            else -> 0f
        }

    /** The hip anchor — see [actHead]. */
    private val hy: Float
        get() = hh - frame.headDrop - actHead

    fun draw() {
        when (look.outfit) {
            PotsikOutfit.NONE -> Unit
            PotsikOutfit.TANOOKI -> Unit
            PotsikOutfit.UNICORN -> drawUnicornHorn()
            PotsikOutfit.TINFOIL -> drawFoilHat()
            PotsikOutfit.SHADOW -> drawNinja()
            PotsikOutfit.PLUMBER -> drawBroCap(tall = false)
            PotsikOutfit.TALLBRO -> drawBroCap(tall = true)
            PotsikOutfit.SPACE -> drawAstronaut()
            PotsikOutfit.BLUEBLUR -> drawBlueBlur()
            PotsikOutfit.CAPTAIN -> drawCaptain()
            PotsikOutfit.CHROMEWING -> Unit
            PotsikOutfit.VOIDEYE -> drawVoidEye()
            PotsikOutfit.PARKA -> drawParka()
            PotsikOutfit.YZZERD -> drawYzzerd()
            PotsikOutfit.OVER9000 -> drawOver9000()
            PotsikOutfit.SHAMBLER -> drawShambler()
            PotsikOutfit.TH3_0N3 -> drawTh3()
        }
    }

    // ------------------------------------------------------------------
    // The costumes
    // ------------------------------------------------------------------

    /** The unicorn's horn: a white cone with six coloured spiral bands. */
    private fun drawUnicornHorn() {
        val baseY = hh
        val tipY = baseY - S(24)
        val baseW = S(9)
        fillTriangle(cx2 - baseW / 2f, baseY, cx2 + baseW / 2f, baseY, cx2, tipY, PotsikPalette.WHITE)
        val bands = listOf(
            PotsikPalette.RED,
            PotsikPalette.AMBER,
            PotsikPalette.VAPOR_YELLOW,
            PotsikPalette.GREEN,
            PotsikPalette.VAPOR_BLUE,
            PotsikPalette.VAPOR_PURPLE,
        )
        for (i in 0 until 6) {
            val frac = i / 6f
            val y0 = baseY + ((tipY - baseY) * frac).toInt()
            val w0 = (baseW * (1f - frac)).toInt()
            wideLine(cx2 - w0 / 2f - S(1), y0, cx2 + w0 / 2f + S(1), y0 - S(3), S(2), bands[i])
        }
    }

    /** The foil hat: a crumpled four-facet cone, a rolled brim and a propeller. */
    private fun drawFoilHat() {
        val ink = PotsikPalette.BLACK
        val fDk = rgb565(0x4A4A)
        val fMd = rgb565(0x9495)
        val fLt = rgb565(0xDEDF)
        val ax = cx2 + S(2)
        val ay = hh - S(12)
        val by = hh + S(1)
        val bx = floatArrayOf(cx2 - S(14), cx2 - S(6), cx2 + S(1), cx2 + S(8), cx2 + S(14))
        val tx = ax + S(1)
        val ty = ay - S(4)

        scope.inked(ink, fMd) { ox, oy, c ->
            scope.fillTriangle(bx[0] + ox, by + oy, bx[4] + ox, by + oy, ax + ox, ay + oy, c)
            scope.fillRoundRect(cx2 - S(16) + ox, hh - S(1) + oy, S(32), S(5), S(2), c)
            scope.drawLine(ax + ox, ay + oy, tx + ox, ty + oy, c)
        }
        val fc = listOf(fLt, fMd, fLt, fDk)
        for (i in 0 until 4) {
            fillTriangle(bx[i], by, bx[i + 1], by, ax, ay, fc[i])
        }
        // The crumples.
        drawLine(cx2 - S(9), hh - S(2), cx2 - S(4), hh - S(5), fDk)
        drawLine(cx2 + S(4), hh - S(1), cx2 + S(6), hh - S(6), fMd)
        drawLine(bx[0] + S(3), by - S(2), ax - S(1), ay + S(2), PotsikPalette.WHITE)
        // The rolled brim.
        fillRoundRect(cx2 - S(16), hh - S(1), S(32), S(5), S(2), fMd)
        fillRect(cx2 - S(15), hh - S(1), S(30), S(1).coerceAtLeast(1f), fLt)
        fillRect(cx2 - S(15), hh + S(3), S(30), 1, fDk)
        var k = -12
        while (k <= 12) {
            drawFastVLine(cx2 + S(k), hh + S(1), S(2), if (k and 4 != 0) fDk else fLt)
            k += 4
        }
        // The stalk and the propeller.
        drawLine(ax, ay, tx, ty, fDk)
        val a = (now % 700) / 700f * PotsikPainter.TAU
        val c = cos(a)
        val len = S(8).toFloat()
        val th = S(1) + 1f
        val bladeA = rgb565(0xD800)
        val bladeB = rgb565(0x02BF)
        val aFront = sin(a) > 0f
        blade(-1f, if (aFront) bladeB else bladeA, c, len, th, tx, ty, ink)
        blade(1f, if (aFront) bladeA else bladeB, c, len, th, tx, ty, ink)
        fillCircle(tx, ty, S(1), rgb565(0xFEC0))

        // The glint that runs round the hat: three places, and a short flare
        // in each of them.
        val gp = now % 4200
        if (gp < 360) {
            val g = intArrayOf(-8, -2, -2, -7, 5, -2)
            val gi = ((now / 4200) % 3).toInt()
            val gx = cx2 + S(g[gi * 2])
            val gy = hh + S(g[gi * 2 + 1])
            val ga = S(2).coerceAtLeast(2f)
            drawFastHLine(gx - ga, gy, ga * 2 + 1, PotsikPalette.WHITE)
            drawFastVLine(gx, gy - ga, ga * 2 + 1, PotsikPalette.WHITE)
        }
    }

    private fun blade(dir: Float, col: Color, c: Float, len: Float, th: Float, tx: Float, ty: Float, ink: Color) {
        val half = maxOf(1, (kotlin.math.abs(c) * len * 0.5f).toInt())
        val bxc = tx + (dir * (if (c >= 0f) 1f else -1f) * half)
        scope.inked(ink, col) { ox, oy, cc ->
            scope.fillEllipse(bxc + ox, ty + oy, half, th, cc)
        }
    }

    /** The ninja: the lower-face mask, the red headband with its trailing tails, the sash. */
    private fun drawNinja() {
        val ink = PotsikPalette.BLACK
        val navy = rgb565(0x212A)
        val navyL = rgb565(0x4A55)
        val red = rgb565(0xD800)
        val redHi = rgb565(0xFB6A)
        val steel = rgb565(0xB5B5)

        scope.inked(ink, navy) { ox, oy, c ->
            scope.fillRoundRect(cx2 - S(12) + ox, hh + S(14) + oy, S(24), S(10), S(4), c)
        }
        drawFastHLine(cx2 - S(9), hh + S(17), S(18), navyL)

        // The headband's tails, travelling in a wave.
        val ph = (now % 900) / 900f * PotsikPainter.TAU
        val kx = cx2 + S(14)
        val ky = hh + S(3)
        for (pass in 0..1) {
            for (k in 0..1) {
                val w = S(2).coerceAtLeast(2f)
                var px = kx
                var py = ky
                for (i in 1..3) {
                    val nx = kx + S(4) * i
                    val ny = ky + (S(2 + k * 3) * i / 2) + (sin(ph - i * 1.3f + k) * S(1) * i / 2).toInt()
                    wideLine(px, py, nx, ny, if (pass == 0) w + 2 else w, if (pass == 0) ink else red)
                    px = nx
                    py = ny
                }
            }
        }

        scope.inked(ink, red) { ox, oy, c ->
            scope.fillRoundRect(cx2 - S(15) + ox, hh + S(1) + oy, S(30), S(4), S(1), c)
            scope.fillCircle(kx + ox, ky + oy, S(2) + 1, c)
        }
        drawFastHLine(cx2 - S(13), hh + S(1), S(24), redHi)

        // The sash and the hanging end.
        val th = 11
        scope.inked(ink, red) { ox, oy, c ->
            scope.fillRect(cx2 - S(th) + ox, hh + S(32) + oy, S(2 * th), S(4), c)
            scope.fillTriangle(cx2 + S(5) + ox, hh + S(35) + oy, cx2 + S(9) + ox, hh + S(35) + oy, cx2 + S(8) + ox, hh + S(41) + oy, c)
        }
        drawFastHLine(cx2 - S(th) + 1, hh + S(32), S(2 * th) - 2, redHi)

        // The throwing star at the hip.
        val sx = cx2 - S(6)
        val sy = hh + S(34)
        val sr = S(2) + 1
        fillTriangle(sx - sr, sy, sx + sr, sy, sx, sy - sr - 1, steel)
        fillTriangle(sx - sr, sy, sx + sr, sy, sx, sy + sr + 1, steel)
        fillCircle(sx, sy, 1, ink)
    }

    /** The plumber's cap — and his taller brother's, in green. */
    private fun drawBroCap(tall: Boolean) {
        val ink = PotsikPalette.BLACK
        val cap = if (tall) rgb565(0x05A0) else rgb565(0xD800)
        val capDk = if (tall) rgb565(0x0360) else rgb565(0x9000)
        val capHi = if (tall) rgb565(0x6FEA) else rgb565(0xFB6A)
        val top = if (tall) S(9) else S(7)
        val domeH = top + S(4)
        val domeR = if (S(7) < domeH / 2) S(7) else domeH / 2

        scope.inked(ink, cap) { ox, oy, c ->
            scope.fillRoundRect(cx2 - S(15) + ox, hh - top + oy, S(30), domeH, domeR, c)
            scope.fillEllipse(cx2 + S(3) + ox, hh + S(3) + oy, S(13), S(2) + 1, c)
        }
        fillRect(cx2 - S(14), hh + S(1), S(28), S(2), capDk)
        fillEllipse(cx2 - S(8), hh - top + S(3), S(3), S(1) + 1, capHi)
        fillEllipse(cx2 + S(3), hh + S(3), S(13), S(2) + 1, capDk)
        drawFastHLine(cx2 - S(9), hh + S(2), S(22), capHi)

        // The emblem: a white disc with the letter on it.
        val er = S(3) + 1
        val ex = cx2 - S(1)
        val ey = hh - top + er + S(1) + 1
        fillCircle(ex, ey, er + 1, ink)
        fillCircle(ex, ey, er, PotsikPalette.WHITE)
        val p = (S(2) * 3) / 5
        if (p >= 1) capGlyph(ex - (3 * p) / 2, ey - (5 * p) / 2, p, cap)

        // The moustache.
        val tash = rgb565(0x4920)
        scope.inked(ink, tash) { ox, oy, c ->
            scope.fillEllipse(cx2 - S(4) + ox, hh + S(15) + oy, S(5), S(2), c)
            scope.fillEllipse(cx2 + S(4) + ox, hh + S(15) + oy, S(5), S(2), c)
        }

        // The white gloves.
        val crease = rgb565(0xB5B5)
        val gr = S(4)
        fillCircle(limbs.armL1x, limbs.armL1y, gr + 1, ink)
        fillCircle(limbs.armR1x, limbs.armR1y, gr + 1, ink)
        fillCircle(limbs.armL1x, limbs.armL1y, gr, PotsikPalette.WHITE)
        fillCircle(limbs.armR1x, limbs.armR1y, gr, PotsikPalette.WHITE)
        drawFastHLine(limbs.armL1x - gr / 2, limbs.armL1y + gr / 3, gr, crease)
        drawFastHLine(limbs.armR1x - gr / 2, limbs.armR1y + gr / 3, gr, crease)
    }

    /** The three-by-five letter on the plumber's cap. */
    private fun capGlyph(x: Float, y: Float, p: Float, col: Color) {
        val rows = intArrayOf(0b111, 0b100, 0b111, 0b001, 0b111)
        for (r in 0 until 5) {
            for (c in 0 until 3) {
                if ((rows[r] shr (2 - c)) and 1 == 1) {
                    fillRect((x + c * p).toFloat(), (y + r * p).toFloat(), p.toFloat(), p.toFloat(), col)
                }
            }
        }
    }

    /** The astronaut: the bubble helmet, the chest pack and the drifting glint. */
    private fun drawAstronaut() {
        val steel = rgb565(0xE73E)
        val cy = hh + S(10)
        val r = S(19)
        val ar = r - S(4)

        // The highlight arc, drifting around the rim.
        val d = sin((now % 16000) / 16000f * PotsikPainter.TAU) * 0.20f
        val a0 = PotsikPainter.PI * (1.06f + d)
        val a1 = PotsikPainter.PI * (1.48f + d)
        var px = cx2 + (cos(a0) * ar).toInt()
        var py = cy + (sin(a0) * ar).toInt()
        for (i in 1..8) {
            val a = a0 + (a1 - a0) * i / 8f
            val nx = cx2 + (cos(a) * ar).toInt()
            val ny = cy + (sin(a) * ar).toInt()
            wideLine(px, py, nx, ny, S(2).coerceAtLeast(2f), PotsikPalette.WHITE)
            px = nx
            py = ny
        }

        for (k in 0..2) drawCircleOutline(cx2, cy, (r - k).toFloat(), steel)
        drawCircleOutline(cx2, cy, (r + 1).toFloat(), PotsikPalette.BLACK)
        fillCircle(cx2 + (r * 55) / 100, cy + (r * 45) / 100, S(1), PotsikPalette.WHITE)

        // The chest pack.
        fillRoundRect(cx2 - S(13), hh + S(21), S(26), S(5), S(2), rgb565(0xBDF9))
        fillRect(cx2 - S(13), hh + S(21), S(26), 1, rgb565(0xF7BF))

        // The glint that rides the drifting highlight.
        val mx = cx2 + (cos(a0) * ar).toInt()
        val my = cy + (sin(a0) * ar).toInt()
        val arm = S(3).coerceAtLeast(2f)
        val th = S(2).coerceAtLeast(2f)
        fillRect(mx - arm, my - th / 2, arm * 2, th, PotsikPalette.WHITE)
        fillRect(mx - th / 2, my - arm, th, arm * 2, PotsikPalette.WHITE)

        // The arms in their spacesuit: the suit's own colour over most of the
        // arm, the seam at the elbow and the glove at the end of it.
        val suit = rgb565(0xDEDF)
        val suitDk = rgb565(0x9495)
        val glove = rgb565(0xFC80)
        val aw = S(8)
        for (k in 0..1) {
            val x0 = if (k == 0) limbs.armL0x else limbs.armR0x
            val y0 = if (k == 0) limbs.armL0y else limbs.armR0y
            val x1 = if (k == 0) limbs.armL1x else limbs.armR1x
            val y1 = if (k == 0) limbs.armL1y else limbs.armR1y
            // The suit starts a fifth of the way down the arm, so the
            // shoulder is still his own fur.
            val sx = x0 + (x1 - x0) / 5f
            val sy = y0 + (y1 - y0) / 5f
            wideLine(sx, sy, x1, y1, aw + 2, PotsikPalette.BLACK)
            wideLine(sx, sy, x1, y1, aw, suit)
            val mx2 = x0 + (x1 - x0) * 0.55f
            val my2 = y0 + (y1 - y0) * 0.55f
            wideLine(mx2 - aw / 2, my2, mx2 + aw / 2, my2, S(1), suitDk)
            fillCircle(x1, y1, S(4) + 1, PotsikPalette.BLACK)
            fillCircle(x1, y1, S(4), glove)
        }

        // The boots.
        val boot = rgb565(0x9495)
        for (k in 0..1) {
            val fx = if (k == 0) limbs.footLx else limbs.footRx
            val fy = if (k == 0) limbs.footLy else limbs.footRy
            scope.inked(PotsikPalette.BLACK, boot) { ox, oy, c ->
                scope.fillRoundRect(fx + ox, fy - S(2) + oy, S(12), S(8), S(2), c)
            }
            fillRect(fx + 1, fy - S(2), S(10), S(2), suit)
            drawFastHLine(fx + 1, fy + S(5), S(10), PotsikPalette.BLACK)
        }
    }

    /** The blue blur: the white gloves and the red shoes. */
    private fun drawBlueBlur() {
        val shoe = rgb565(0xD800)
        val shoeHi = rgb565(0xFB6A)
        for (k in 0..1) {
            val fx = if (k == 0) limbs.footLx else limbs.footRx
            val fy = if (k == 0) limbs.footLy else limbs.footRy
            scope.inked(PotsikPalette.BLACK, shoe) { ox, oy, c ->
                scope.fillRoundRect(fx - S(1) + ox, fy - S(1) + oy, S(14), S(7), S(3), c)
            }
            drawFastHLine(fx + S(1), fy - S(1), S(8), shoeHi)
            fillRect(fx - S(1) + 1, fy + S(4), S(14) - 2, S(2), PotsikPalette.WHITE)
            fillRect(fx + S(5), fy - S(1), S(3), S(5), PotsikPalette.WHITE)
            fillRect(fx + S(6), fy + S(1), S(1).coerceAtLeast(1f), S(2), rgb565(0xFEC0))
        }
        fillCircle(limbs.armL1x, limbs.armL1y, S(4) + 1, PotsikPalette.BLACK)
        fillCircle(limbs.armR1x, limbs.armR1y, S(4) + 1, PotsikPalette.BLACK)
        fillCircle(limbs.armL1x, limbs.armL1y, S(4), PotsikPalette.WHITE)
        fillCircle(limbs.armR1x, limbs.armR1y, S(4), PotsikPalette.WHITE)

        // The dust he kicks up while he walks: a puff behind each foot in
        // turn, growing and fading as the step goes by.
        if (frame.mood == PotsikMood.WALK) {
            val wp = now % 400
            val k = if (wp < 200) 0 else 1
            val age = (wp % 200).toInt()
            val pr = S(1) + age * S(2) / 200 + 1
            val fx = if (k == 0) limbs.footLx else limbs.footRx
            val fy = if (k == 0) limbs.footLy else limbs.footRy
            val dx = (if (k == 0) -1 else 1) * age * S(3) / 200
            fillCircle(fx + dx, fy - pr / 2, pr, rgb565(0xB5B5))
        }
    }

    /** The captain: the tricorn with the gold braid and the skull and crossbones. */
    private fun drawCaptain() {
        val ink = PotsikPalette.BLACK
        val felt = rgb565(0x212A)
        val feltHi = rgb565(0x4A55)
        val gold = rgb565(0xFEC0)
        val b = intArrayOf(-17, 2, -19, -11, -9, -3, -5, -7, 0, -8, 5, -7, 9, -3, 19, -11, 17, 2)

        fun brim(ox: Int, oy: Int, c: Color) {
            val fx = cx2 + ox
            val fy = hh + S(1) + oy
            for (i in 0 until 9) {
                val j = (i + 1) % 9
                fillTriangle(
                    fx,
                    fy,
                    cx2 + S(b[i * 2]) + ox,
                    hh + S(b[i * 2 + 1]) + oy,
                    cx2 + S(b[j * 2]) + ox,
                    hh + S(b[j * 2 + 1]) + oy,
                    c,
                )
            }
        }

        scope.inked(ink, felt) { ox, oy, c ->
            scope.fillEllipse(cx2 + ox, hh - S(5) + oy, S(12), S(9), c)
            brim(ox, oy, c)
        }
        fillEllipse(cx2 - S(4), hh - S(11), S(4), S(1) + 1, feltHi)
        brim(0, 0, felt)

        // The braid along the brim's top edge.
        for (i in 1..6) {
            wideLine(
                cx2 + S(b[i * 2]),
                hh + S(b[i * 2 + 1]),
                cx2 + S(b[(i + 1) * 2]),
                hh + S(b[(i + 1) * 2 + 1]),
                S(1) + 1,
                gold,
            )
        }

        // The skull and crossbones.
        val kx = cx2
        val ky = hh - S(3)
        val bw = S(1) + 1
        wideLine(kx - S(5), ky - S(3), kx + S(5), ky + S(3), bw, PotsikPalette.WHITE)
        wideLine(kx + S(5), ky - S(3), kx - S(5), ky + S(3), bw, PotsikPalette.WHITE)
        fillCircle(kx, ky - S(1), S(3), PotsikPalette.WHITE)
        fillRect(kx - S(2), ky + S(1), S(4), S(2), PotsikPalette.WHITE)
        val ep = S(1).coerceAtLeast(1f)
        fillRect(kx - S(2), ky - S(2), ep, ep, ink)
        fillRect(kx + S(1), ky - S(2), ep, ep, ink)

        // The eye-patch strap.
        wideLine(cx2 - S(11), hh + S(5), cx2 + S(14), hh + S(3), S(2), PotsikPalette.BLACK)
    }

    /** The void eye: his head becomes a sphere of stars with an iris that moves. */
    private fun drawVoidEye() {
        val er = S(16)
        val ey = hh + S(11)
        val glow = ((now / 1400) % 3L) != 0L
        fillEllipse(cx2, ey + S(5), er + S(7), (er * 92) / 100 + S(1), if (glow) rgb565(0x4935) else rgb565(0x212A))
        fillEllipse(cx2, ey + S(5), er + S(6), (er * 92) / 100, rgb565(0x41F0))
        fillEllipse(cx2, ey + S(6), er + S(3), (er * 82) / 100, rgb565(0x1066))
        fillCircle(cx2, ey, er + 1, rgb565(0x8337))
        fillCircle(cx2, ey, er, rgb565(0x28EB))

        // The star field, drifting.
        val phase = ((now / 44) and 63L).toInt()
        // The radius ramp: a square-root ramp, so the field stays evenly
        // dense instead of bunching in the middle.
        val rstep = floatArrayOf(0f, 7f, 10f, 12f, 14f, 16f, 17f, 19f, 20f, 21f, 22f, 23f, 24f, 25f, 26f, 27f, 28f, 29f, 30f, 31f, 32f, 32f, 33f, 34f, 35f, 35f, 36f, 37f, 37f, 38f, 39f, 39f, 40f, 41f, 41f, 42f, 42f, 43f, 43f, 44f, 45f, 45f, 46f, 46f, 47f, 47f, 48f, 48f, 49f, 49f, 50f, 50f, 51f, 51f, 52f, 52f, 53f, 53f, 54f, 54f, 55f, 55f, 56f, 56f)
        // The phase of each star, so they do not all recycle at once.
        val starPhase = intArrayOf(0, 23, 46, 5, 28, 51, 10, 33, 56, 15, 38, 61, 20, 43, 2, 25, 48, 7, 30, 53, 12, 35, 58, 17, 40, 63, 22, 45, 4, 27, 50, 9, 32, 55, 14, 37, 60, 19, 42, 1, 24, 47, 6, 29, 52, 11)
        val dx = floatArrayOf(64f, -47f, 6f, 39f, -63f, 54f, -17f, -29f, 60f, -59f, 27f, 19f, -55f, 63f, -37f, -8f, 49f, -64f, 45f, -3f, -41f, 63f, -53f, 14f, 32f, -61f, 58f, -25f, -22f, 57f, -62f, 35f, 11f, -51f, 64f, -43f, 0f, 43f, -64f, 51f, -11f, -34f, 62f, -57f, 22f, 24f)
        val dy = floatArrayOf(0f, 43f, -64f, 51f, -11f, -34f, 62f, -57f, 22f, 24f, -58f, 61f, -32f, -14f, 52f, -63f, 41f, 3f, -45f, 64f, -49f, 9f, 37f, -62f, 56f, -19f, -27f, 59f, -60f, 30f, 16f, -54f, 63f, -39f, -5f, 47f, -64f, 47f, -6f, -39f, 63f, -54f, 17f, 29f, -60f, 59f)
        for (i in 0 until 46) {
            val sk = (phase + starPhase[i]) and 63
            val rad = rstep[sk]
            val sx = cx2 + (dx[i] * rad * er) / 4096
            val sy = ey + (dy[i] * rad * er) / 4096
            val b = (90 + sk * 2).coerceAtMost(255)
            val sc = Color(b / 255f, b / 255f, 1f)
            if (sk > 52) fillRect(sx, sy, 2, 2, sc) else fillRect(sx, sy, 1, 1, sc)
        }

        // The iris, with the gaze wandering inside it.
        val gaze = voidGaze(now, er.toInt())
        val gx = gaze.first
        val gy = gaze.second
        val ix = cx2 + (er * 16) / 100 + gx
        val iy = ey + gy
        fillCircle(ix, iy, (er * 58) / 100, rgb565(0x384C))
        fillCircle(ix, iy, (er * 50) / 100, rgb565(0x91FB))
        val spokeCol = rgb565(0x6135)
        val spokes = floatArrayOf(13f, 0f, 31f, 0f, 9f, 9f, 22f, 22f, 0f, 13f, 0f, 31f, -9f, 9f, -22f, 22f, -13f, 0f, -31f, 0f, -9f, -9f, -22f, -22f, 0f, -13f, 0f, -31f, 9f, -9f, 22f, -22f)
        for (sp in 0 until 8) {
            drawLine(
                ix + spokes[sp * 4] * er / 64,
                iy + spokes[sp * 4 + 1] * er / 64,
                ix + spokes[sp * 4 + 2] * er / 64,
                iy + spokes[sp * 4 + 3] * er / 64,
                spokeCol,
            )
        }
        fillCircle(ix, iy, (er * 24) / 100, PotsikPalette.BLACK)
        fillCircle(cx2 - (er * 8) / 100, ey - (er * 36) / 100, (er * 17) / 100, rgb565(0x947A))

        // The lid, closing over the sphere for a blink.
        val k = voidBlink(now)
        val erI = er.toInt()
        if (k > 0 && erI > 0) {
            val lh = (erI * k) / 255 + 1
            for (i in 0..minOf(lh, erI)) {
                val yT = -erI + i
                val yB = erI - i
                val wT = sqrt((erI * erI - yT * yT).toDouble()).toInt()
                val wB = sqrt((erI * erI - yB * yB).toDouble()).toInt()
                // The leading row of each lid is in the edge colour, so a
                // closing lid is not a flat slab of colour.
                val c = if (i == lh - 1) PotsikPalette.BLACK else rgb565(0x41F0)
                if (wT > 0) drawFastHLine(cx2 - wT, ey + yT, 2 * wT, c)
                if (wB > 0) drawFastHLine(cx2 - wB, ey + yB, 2 * wB, c)
            }
        }
    }

    /**
     * Where the void eye is looking.
     *
     * It snaps to a new place and holds it, the way an eye does, rather
     * than drifting across the sphere — a drift reads as a fish, not as
     * something looking at you. The target is a hash of the slot, so it is
     * never the same twice in a row and never lands on a beat.
     */
    private fun voidGaze(now: Long, r: Int): Pair<Int, Int> {
        val slot = 1600L
        val move = 260L
        val s = now / slot
        val (px, py) = voidGazeTarget(if (s > 0) s - 1 else 0, r)
        val (tx, ty) = voidGazeTarget(s, r)
        val into = now - s * slot
        if (into >= move) return tx to ty
        return (px + (tx - px) * into / move).toInt() to (py + (ty - py) * into / move).toInt()
    }

    /** The spot the void eye looks at in a given slot. */
    private fun voidGazeTarget(slot: Long, r: Int): Pair<Int, Int> {
        val h = potsikHash((slot * 2654435761L).toInt())
        // One slot in eight is a straight stare.
        if ((h and 7) == 0) return 0 to 0
        val gx = (floorMod(h.toLong(), 9L) - 4L).toInt() * r / 16
        val gy = (floorMod((h shr 8).toLong(), 7L) - 3L).toInt() * r / 16
        return gx to gy
    }

    /**
     * How far the void eye's lid is closed: zero is open, 255 is shut.
     *
     * The same hashed slots the face's own blink uses, so the eye blinks on
     * the same beat as the rest of him.
     */
    private fun voidBlink(now: Long): Int {
        val slot = 2600L
        val dur = 300L
        val h = potsikHash((slot * 2654435761L).toInt())
        val t0 = slot * slot + 300 + floorMod(h.toLong(), (slot - 900).toLong())
        if (now < t0) return 0
        val d = now - t0
        if (d >= dur) return 0
        return if (d < dur / 2) (d * 510 / dur).toInt() else ((dur - d) * 510 / dur).toInt()
    }

    /** The parka: the mittens and the drawstrings that swing. */
    private fun drawParka() {
        val ink = rgb565(0x1040)
        val org = rgb565(0xFC43)
        val seam = rgb565(0x8A21)
        val fur = rgb565(0x6A05)
        val oy = hh + S(8)
        val ow = (S(17) * 102) / 100
        val oh = (S(16) * 102) / 100
        val r = S(23)
        val rInt = r.toInt()
        val mr = S(5)
        val hdr = r + mr

        // The hood's rim: the orange ring round his head. The face shows only
        // through a band of rows that is S(14) wide each side; every other row
        // is orange right across, so the hood covers the mouth and the chin,
        // like a hood drawn down over the lower face.
        //
        // The band sits over the eyes — units 5..14 — and it is measured in
        // whole rows from the hood's own centre, not in raw pixels off hh: the
        // bounds stay glued to the face at every scale, and the purely integer
        // test keeps the rim from flickering as the head bobs under it.
        val faceHalf = S(14).toInt()
        val bandFrom = (S(5) - S(8)).toInt()
        val bandTo = (S(14) - S(8)).toInt()
        for (dy2 in -(rInt + 1)..(rInt + 1)) {
            val yy = lround(oy + dy2).toFloat()
            val a2 = (rInt + 1) * (rInt + 1) - dy2 * dy2
            if (a2 < 0) continue
            val xo = sqrt(a2.toFloat()).toInt()
            val b2 = rInt * rInt - dy2 * dy2
            val xr = if (b2 > 0) sqrt(b2.toFloat()).toInt() else -1
            val inBand = dy2 in bandFrom..bandTo
            val xj = if (inBand) faceHalf else 0
            val xk = if (inBand) faceHalf + 2 else 0
            if (xo > xj) {
                drawFastHLine(cx2 - xo, yy, xo - xj + 1, ink)
                drawFastHLine(cx2 + xj, yy, xo - xj + 1, ink)
            }
            if (xr > xk) {
                drawFastHLine(cx2 - xr, yy, xr - xk + 1, org)
                drawFastHLine(cx2 + xk, yy, xr - xk + 1, org)
            }
        }
        drawCircleOutline(cx2, oy, (r + 1).toFloat(), ink)

        // The ink edges across the top and the bottom of the face opening, on
        // the same whole rows the band is measured with.
        drawFastHLine(cx2 - faceHalf, oy + bandFrom, 2 * faceHalf + 1, ink)
        drawFastHLine(cx2 - faceHalf, oy + bandTo, 2 * faceHalf + 1, ink)

        // The hood's own front rim: Kenny's face sits deep inside the hood,
        // so a fur ring runs round the face opening, in front of everything
        // but the eyes. An ellipse one module wider than the opening, stroked
        // in fur with an ink outline on both sides, reads as the hood's
        // thickness instead of a flat orange disc.
        val rimRx = faceHalf + S(3)
        val rimRy = ((bandTo - bandFrom) / 2f + S(4))
        val rimCy = oy + (bandFrom + bandTo) / 2f
        drawEllipseOutline(cx2, rimCy, rimRx + 1f, rimRy + 1f, ink)
        drawEllipseOutline(cx2, rimCy, rimRx, rimRy, fur)
        drawEllipseOutline(cx2, rimCy, rimRx - S(2), rimRy - S(2), ink)

        // The mittens.
        for (sg in listOf(-1, 1)) {
            val ax = if (sg < 0) limbs.armL1x else limbs.armR1x
            val ay = if (sg < 0) limbs.armL1y else limbs.armR1y
            val sx = if (sg < 0) limbs.armL0x else limbs.armR0x
            val sy = if (sg < 0) limbs.armL0y else limbs.armR0y
            fillCircle(ax, ay, mr + 1, ink)
            fillCircle(ax, ay, mr, fur)
            val tr = (mr * 52) / 100
            val tx = ax - sg * ((mr * 62) / 100)
            fillCircle(tx, ay - S(2), tr, fur)
            drawCircleOutline(tx, ay - S(2), tr, ink)
        }

        // The drawstrings, swinging.
        val d = (sin((now % 9000) / 9000f * PotsikPainter.TAU) * S(1)).toInt()
        val vy = oy + oh - S(1)
        wideLine(cx2 + d, vy, cx2 - S(5) + d, vy + S(8), 2, seam)
        wideLine(cx2 + d, vy, cx2 + S(5) + d, vy + S(8), 2, seam)

        // An arm that crosses the hood is drawn again on top of it, so it
        // wears the coat's colour instead of sticking out of the fur.
        for (k in 0..1) {
            val x0 = if (k == 0) limbs.armL0x else limbs.armR0x
            val y0 = if (k == 0) limbs.armL0y else limbs.armR0y
            val x1 = if (k == 0) limbs.armL1x else limbs.armR1x
            val y1 = if (k == 0) limbs.armL1y else limbs.armR1y
            if (segmentDistance(cx2, oy, x0, y0, x1, y1) < hdr) {
                wideLine(x0, y0, x1, y1, S(7) + 2, seam)
                wideLine(x0, y0, x1, y1, S(7), org)
            }
        }
    }

    /** How far a point is from a line, measured to the segment and not to its line. */
    private fun segmentDistance(
        px: Float,
        py: Float,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
    ): Float {
        val dx = x1 - x0
        val dy = y1 - y0
        val len2 = dx * dx + dy * dy
        if (len2 < 0.0001f) {
            return sqrt((px - x0) * (px - x0) + (py - y0) * (py - y0))
        }
        val t = (((px - x0) * dx + (py - y0) * dy) / len2).coerceIn(0f, 1f)
        val nx = x0 + t * dx
        val ny = y0 + t * dy
        return sqrt((px - nx) * (px - nx) + (py - ny) * (py - ny))
    }

    /** YZZERD: the robe, the wide hat, the forked beard and the star on top. */
    private fun drawYzzerd() {
        val robe = rgb565(0x6815)
        val sleeve = rgb565(0x913F)
        val dark = rgb565(0x480A)
        val gold = rgb565(0xFEC0)
        val beard = rgb565(0xFFFF)
        val beardSh = rgb565(0xB5B5)
        val magic = rgb565(0x07FF)

        // The slippers on his feet, with their toes turned out.
        for (k in 0..1) {
            val fx = if (k == 0) limbs.footLx else limbs.footRx
            val fy = if (k == 0) limbs.footLy else limbs.footRy
            val sg = if (k == 0) -1 else 1
            val out = if (k == 0) fx + S(1) else fx + S(12) - S(1)
            scope.inked(PotsikPalette.BLACK, dark) { ox, oy, c ->
                scope.fillRoundRect(fx - 1 + ox, fy - 1 + oy, S(12) + 2, S(6) + 2, 3, c)
                scope.fillTriangle(out + ox, fy + S(1) - 1 + oy, out + ox, fy + S(6) + oy, out + sg * (S(5) + 1) + ox, fy + S(4) + oy, c)
            }
            fillRoundRect(fx, fy, S(12), S(6), 2, dark)
            fillTriangle(out, fy + S(1), out, fy + S(6) - 1, out + sg * S(5), fy + S(4), dark)
        }

        // The hands.
        for (sg in listOf(-1, 1)) {
            val x = if (sg < 0) limbs.armL1x else limbs.armR1x
            val y = if (sg < 0) limbs.armL1y else limbs.armR1y
            fillCircle(x, y, S(4) + 1, PotsikPalette.BLACK)
            fillCircle(x, y, S(4), PotsikPalette.FUR_MAIN)
        }

        // The forked beard.
        fun fork(g: Int, c: Color) {
            fillRoundRect(cx2 - S(14) - g, hh + S(16) - g, S(5) + 2 * g, S(10) + 2 * g, S(2), c)
            fillRoundRect(cx2 + S(9) - g, hh + S(16) - g, S(5) + 2 * g, S(10) + 2 * g, S(2), c)
            fillRect(cx2 - S(12) - g, hh + S(24), S(24) + 2 * g, S(3), c)
            fillTriangle(cx2 - S(12) - g, hh + S(26), cx2 + S(1) + g, hh + S(26), cx2 - S(6), hh + S(41) + 2 * g, c)
            fillTriangle(cx2 - S(1) - g, hh + S(26), cx2 + S(12) + g, hh + S(26), cx2 + S(6), hh + S(41) + 2 * g, c)
        }
        fork(1, PotsikPalette.BLACK)
        fork(0, beard)
        drawLine(cx2 - S(7), hh + S(27), cx2 - S(6), hh + S(35), beardSh)
        drawLine(cx2 + S(7), hh + S(27), cx2 + S(6), hh + S(35), beardSh)

        // The moustache.
        wideLine(cx2 - S(1), hh + S(15), cx2 - S(9), hh + S(17), S(3) + 2, PotsikPalette.BLACK)
        wideLine(cx2 + S(1), hh + S(15), cx2 + S(9), hh + S(17), S(3) + 2, PotsikPalette.BLACK)
        wideLine(cx2 - S(1), hh + S(15), cx2 - S(9), hh + S(17), S(3), beard)
        wideLine(cx2 + S(1), hh + S(15), cx2 + S(9), hh + S(17), S(3), beard)

        val (mc, mh) = when (frame.mood) {
            PotsikMood.SHOCKED -> rgb565(0xF92A) to rgb565(0xFED5)
            PotsikMood.SLEEPY -> rgb565(0x480A) to rgb565(0x913F)
            else -> magic to PotsikPalette.WHITE
        }

        // The hat, as the firmware's yzFront: the brim, the cone standing on
        // it, a shade down the right, two gold bands and the gold front edge
        // of the brim.
        val brimY = hh + S(1)
        val brimRx = S(18)
        val brimRy = S(3)
        fillEllipse(cx2, brimY, brimRx + 1, brimRy + 1, PotsikPalette.BLACK)
        yzCone(cx2, hh, PotsikPalette.BLACK, 0, 1)
        fillEllipse(cx2, brimY, brimRx, brimRy, robe)
        yzCone(cx2, hh, robe)
        yzCone(cx2, hh, dark, 62)
        yzConeBand(cx2, hh, 0.5f, 3.5f)
        yzConeBand(cx2, hh, 15f, 16.5f)
        val rxI = brimRx.toInt()
        val ryI = brimRy.toInt()
        for (dx in -rxI + 2..rxI - 2) {
            val fr = 1f - (dx * dx).toFloat() / (rxI * rxI).toFloat()
            val dy = (ryI * sqrt(fr)).toInt()
            drawFastVLine(cx2 + dx, brimY + dy - 2, 2f, gold)
        }
        // The gem in the band, and the emblem read from across a room.
        yzOrb(cx2, hh - S(2), S(2), mc, mh)
        yzStar(cx2, hh - S(9), S(4) + 1, PotsikPalette.BLACK)
        yzStar(cx2, hh - S(9), S(4), gold)
        fillRect(cx2 - 1, hh - S(9) - 1, 3f, 3f, mc)

        // The magic: the orb that circles him with its trail of motes, and
        // the gold star going the other way. The colour follows his mood —
        // cyan at rest, red and three times as fast when he is startled.
        if (frame.mood == PotsikMood.SLEEPY) {
            // Asleep, the orb comes down and rests by his foot.
            val ground = maxOf(limbs.footLy, limbs.footRy)
            yzOrb(cx2 + S(22), ground - S(4), S(4), mc, mh)
        } else {
            val per = if (frame.mood == PotsikMood.SHOCKED) 1200L else 3600L
            val a0 = (now % per) / per.toFloat() * PotsikPainter.TAU
            for (k in 4 downTo 0) {
                val a = a0 - k * 0.30f
                val x = cx2 + (cos(a) * S(26)).toInt()
                val y = hy + S(20) + (sin(a) * S(7)).toInt()
                if (k == 0) {
                    yzOrb(x, y, S(5), mc, mh)
                    // A glint going round inside it, and a halo that breathes.
                    val g = (now % 900) / 900f * PotsikPainter.TAU
                    fillRect(x + (cos(g) * S(2)).toInt(), y + (sin(g) * S(2)).toInt(), 2, 2, mh)
                    if ((now / 300) and 1L != 0L) drawCircleOutline(x, y, S(5) + 3, mc)
                } else {
                    val r = when (k) {
                        1 -> S(2)
                        2 -> S(1) + 1
                        else -> S(1)
                    }
                    fillCircle(x, y, r, if (k and 1 == 1) mc else mh)
                }
            }
            // The gold star, on its own orbit going the other way.
            val b = -(now % 2300) / 2300f * PotsikPainter.TAU
            val sx = cx2 + (cos(b) * S(22)).toInt()
            val sy = hy + S(41) + (sin(b) * S(4)).toInt()
            yzStar(sx, sy, S(3) + 1, PotsikPalette.BLACK)
            yzStar(sx, sy, S(3), gold)
        }

        // The star on the tip, pulsing.
        if (frame.mood != PotsikMood.SLEEPY) {
            val alarm = frame.mood == PotsikMood.SHOCKED
            val pp = if (alarm) 300 else 1400
            val up = sin((now % pp) / pp.toFloat() * PotsikPainter.TAU) > 0.3f
            val sr = S(3) + (if (up) 1f else 0f) + (if (alarm) S(1) else 0f)
            yzStar(cx2, hh - S(25), sr + 1, PotsikPalette.BLACK)
            yzStar(cx2, hh - S(25), sr, if (alarm) (if (up) mh else mc) else (if (up) PotsikPalette.WHITE else gold))
        }
    }

    /** The wizard's orb: a ball of magic with a light in the middle of it. */
    private fun yzOrb(x: Float, y: Float, r: Float, c: Color, hi: Color) {
        fillCircle(x, y, r + 1, PotsikPalette.BLACK)
        fillCircle(x, y, r, c)
        fillCircle(x - r / 3, y - r / 3, (r / 3).coerceAtLeast(1f), hi)
    }

    /** The four-pointed star: two horizontal and two vertical triangles, as the firmware's yzStar. */
    private fun yzStar(cx: Float, cy: Float, r: Float, color: Color) {
        val n = (r / 3f).toInt().coerceAtLeast(1).toFloat()
        fillTriangle(cx - r, cy, cx + r, cy, cx, cy - n, color)
        fillTriangle(cx - r, cy, cx + r, cy, cx, cy + n, color)
        fillTriangle(cx, cy - r, cx, cy + r, cx - n, cy, color)
        fillTriangle(cx, cy - r, cx, cy + r, cx + n, cy, color)
    }

    /**
     * The hat's cone, as the firmware's yzCone: base at hh, half-widths 11/5/0
     * at 0/-13/-24 units. cut > 0 keeps only the right-hand part (the shade);
     * g > 0 is the same cone g pixels bigger, for its outline.
     */
    private fun yzCone(cx: Float, hh: Float, c: Color, cut: Int = 0, g: Int = 0) {
        val sp = arrayOf(intArrayOf(0, 11), intArrayOf(-13, 5), intArrayOf(-24, 0))
        if (g > 0) drawFastVLine(cx, hh + (sp[2][0] * scale).toInt() - g, g.toFloat(), c)
        for (i in 0..1) {
            val ay = hh + (sp[i][0] * scale).toInt()
            val by = hh + (sp[i + 1][0] * scale).toInt()
            val aw = (sp[i][1] * scale).toInt() + g
            val bw = (sp[i + 1][1] * scale).toInt() + g
            val al = cx - aw + (2f * aw * cut) / 100f
            val bl = cx - bw + (2f * bw * cut) / 100f
            fillTriangle(al, ay, cx + aw, ay, cx + bw, by, c)
            fillTriangle(al, ay, cx + bw, by, bl, by, c)
        }
    }

    /** Half-width of the hat's cone yu units above its base. */
    private fun yzConeWidth(yu: Float): Int {
        val w = if (yu <= 13f) 11f - 6f * yu / 13f else 5f - 5f * (yu - 13f) / 11f
        return (w * scale).toInt()
    }

    /** A gold band round the hat's cone, between two heights in units. */
    private fun yzConeBand(cx: Float, hh: Float, y0: Float, y1: Float) {
        val wa = yzConeWidth(y0)
        val wb = yzConeWidth(y1)
        val ya = hh - (y0 * scale).toInt()
        val yb = hh - (y1 * scale).toInt()
        fillTriangle(cx - wa, ya, cx + wa, ya, cx + wb, yb, yzGoldC)
        fillTriangle(cx - wa, ya, cx + wb, yb, cx - wb, yb, yzGoldC)
    }

    /**
     * OVER 9000's front layer, drawn last: the firmware's o9Front. The gi
     * itself, the belt and the boots are in the body pass (drawO9Gi). Here:
     * the wristbands and his own fur hands, the fringe, the scouter, the
     * static and the bolt.
     */
    private fun drawOver9000() {
        val fur = PotsikPalette.FUR_MAIN
        val black = PotsikPalette.BLACK
        val white = PotsikPalette.WHITE
        val bolt = rgb565(0x06DF)

        // The wristbands and the hands, one pair per arm.
        for (sg in -1..1 step 2) {
            val x0 = (if (sg < 0) limbs.armL0x else limbs.armR0x).toInt()
            val y0 = (if (sg < 0) limbs.armL0y else limbs.armR0y).toInt()
            val x1 = (if (sg < 0) limbs.armL1x else limbs.armR1x).toInt()
            val y1 = (if (sg < 0) limbs.armL1y else limbs.armR1y).toInt()
            yzAcross(x0, y0, x1, y1, 66, 88, S(4).toInt() + 1, S(4).toInt() + 1, black)
            yzAcross(x0, y0, x1, y1, 68, 86, S(4).toInt(), S(4).toInt(), O9_BLUE)
            fillCircle(x1, y1, S(4) + 1, black)
            fillCircle(x1, y1, S(4), fur)
        }

        // The fringe: each lock outlined in black, then gold, then the light
        // line down it from its middle to its tip.
        val b = intArrayOf(-15, -1, -5, -2, -12, 7, -7, -2, 2, -2, -5, 6, 0, -2, 9, -2, 5, 7, 7, -2, 15, -1, 13, 4)
        for (i in 0 until 4) {
            val ax = cx2 + S(b[i * 6])
            val ay = hh + S(b[i * 6 + 1])
            val bx = cx2 + S(b[i * 6 + 2])
            val by = hh + S(b[i * 6 + 3])
            val tx = cx2 + S(b[i * 6 + 4])
            val ty = hh + S(b[i * 6 + 5])
            fillTriangle(ax - 1, ay, bx - 1, by, tx - 1, ty, black)
            fillTriangle(ax + 1, ay, bx + 1, by, tx + 1, ty, black)
            fillTriangle(ax, ay, bx, by, tx, ty + 1, black)
        }
        for (i in 0 until 4) {
            val ax = cx2 + S(b[i * 6])
            val ay = hh + S(b[i * 6 + 1])
            val bx = cx2 + S(b[i * 6 + 2])
            val by = hh + S(b[i * 6 + 3])
            val tx = cx2 + S(b[i * 6 + 4])
            val ty = hh + S(b[i * 6 + 5])
            fillTriangle(ax, ay, bx, by, tx, ty, O9_GOLD)
            drawLine(cx2 + S((b[i * 6] + b[i * 6 + 2]) / 2), ay + 1, tx, ty - 1, O9_GOLDHI)
        }
        // The lock that has fallen in front of the face.
        inked(black, O9_GOLD) { ox, oy, c ->
            fillTriangle(cx2 - S(3) + ox, hh + S(1) + oy, cx2 + S(1) + ox, hh + S(1) + oy, cx2 - S(4) + ox, hh + S(12) + oy, c)
        }

        // The scouter: a timer, not input, shows it for 3100 ms in every 12 s.
        if (now % 12000L < 3100L) {
            val lx = cx2 + S(2)
            val ly = hh + S(5)
            val lw = S(11)
            val lh = S(9)
            fillRect(cx2 + S(14), hh + S(9), S(4), S(7), black)
            fillRect(cx2 + S(14) + 1, hh + S(9) + 1, S(4) - 2, S(7) - 2, rgb565(yz565Code(146, 146, 170)))
            fillRect(lx + lw - 1, hh + S(6), cx2 + S(15) - (lx + lw) + 1, S(2), black)
            fillRect(lx - 1, ly - 1, lw + 2, lh + 2, black)
            fillRect(lx, ly, lw, lh, rgb565(0x0480))
            val frameCol = rgb565(0x07E0)
            fillRect(lx + 1, ly + 1, lw - 2, 1, frameCol)
            fillRect(lx + 1, ly + lh - 2, lw - 2, 1, frameCol)
            fillRect(lx + 1, ly + 1, 1, lh - 2, frameCol)
            fillRect(lx + lw - 2, ly + 1, 1, lh - 2, frameCol)
            // The target mark, hunting across the lens.
            val mx = (lx + S(3) + (sin(now / 120f) + 1f) * S(2)).toInt()
            val my = (ly + S(3) + (cos(now / 170f) + 1f) * S(1)).toInt()
            fillRect(mx - 1, my, 3, 1, O9_SCOUTER)
            fillRect(mx, my - 1, 1, 3, O9_SCOUTER)
        }

        // The statics, crackling round him: seven slots at most, 90 ms each.
        if (frame.mood != PotsikMood.SLEEPY) {
            val slot = now / 90L
            for (i in 0 until 7) {
                val h = yzHash((slot * 5L + i).toInt()).toLong() and 0xFFFFFFFFL
                if ((h and 3L) == 0L) continue
                val a = (h % 628L) / 100f
                val ca = cos(a)
                val sa = sin(a)
                var x = cx2.toInt() + (ca * S(24)).toInt()
                var y = hy.toInt() + S(20).toInt() + (sa * S(30)).toInt()
                for (k in 0 until 4) {
                    val nx = x + ((h shr (8 + k * 3)) % 9L).toInt() - 4 + (ca * S(4)).toInt()
                    val ny = y + ((h shr (16 + k * 3)) % 9L).toInt() - 4 + (sa * S(4)).toInt()
                    drawLine(x, y, nx, ny, white)
                    drawLine(x + 1, y, nx + 1, ny, bolt)
                    x = nx
                    y = ny
                }
            }
        }

        // Now and then a bolt goes down his side to the floor. Startled, often.
        val per = if (frame.mood == PotsikMood.SHOCKED) 900L else 2800L
        if (now % per < 180L) {
            val h = yzHash((now / per).toInt()).toLong() and 0xFFFFFFFFL
            val sg = if ((h and 1L) != 0L) 1 else -1
            val ground = maxOf(limbs.footLy, limbs.footRy) + S(6)
            val top = hy + S(24)
            var x = cx2 + sg * S(18)
            var y = top
            for (k in 1..5) {
                val nx = cx2 + sg * (S(18) + ((h shr (k * 3)) % (S(6).toInt() + 1)).toInt())
                val ny = top + (ground - top) * k / 5f
                wideLine(x, y, nx, ny, 3, bolt)
                drawLine(x, y, nx, ny, white)
                x = nx
                y = ny
            }
            drawEllipseOutline(x, ground, S(6), S(1) + 1, white)
        }
    }

    /**
     * The shambler's costume layer, drawn last: the stitched scar, the
     * bandage on his left forearm, the stump of his right sleeve, the one
     * sneaker and four bare toes, the cracked lens, and the flies.
     *
     * The jeans and the shirt are in the body pass (drawShamblerClothes), so
     * the arms go over them and the head does not.
     */
    private fun drawShambler() {
        val white = PotsikPalette.WHITE
        val grey = rgb565(0xB5B5)
        val shirt = rgb565(0x9240)
        val furLt = rgb565(0x95AA)
        val red = rgb565(0xD800)

        // The stitched scar.
        val x0 = cx2 - S(10)
        val y0 = hh + S(4)
        val x1 = cx2 + S(6)
        val y1 = hh + S(2)
        drawLine(x0, y0, x1, y1, PotsikPalette.BLACK)
        for (i in 1..4) {
            val x = x0 + (x1 - x0) * i / 5
            val y = y0 + (y1 - y0) * i / 5
            drawFastVLine(x, y - S(1), 2 * S(1) + 1, PotsikPalette.BLACK)
        }

        // A bandage round his left forearm, its loose end swinging from the
        // arm's own two ends: a black keyline, the white roll, and two grey
        // creases.
        val ax0 = limbs.armL0x
        val ay0 = limbs.armL0y
        val ax1 = limbs.armL1x
        val ay1 = limbs.armL1y
        yzAcross(ax0.toInt(), ay0.toInt(), ax1.toInt(), ay1.toInt(), 48, 76, S(4).toInt() + 1, S(4).toInt() + 1, PotsikPalette.BLACK)
        yzAcross(ax0.toInt(), ay0.toInt(), ax1.toInt(), ay1.toInt(), 50, 74, S(4).toInt(), S(4).toInt(), white)
        yzAcross(ax0.toInt(), ay0.toInt(), ax1.toInt(), ay1.toInt(), 58, 60, S(4).toInt(), S(4).toInt(), grey)
        yzAcross(ax0.toInt(), ay0.toInt(), ax1.toInt(), ay1.toInt(), 66, 68, S(4).toInt(), S(4).toInt(), grey)
        val ex = ax0 + (ax1 - ax0) * 74 / 100f
        val ey = ay0 + (ay1 - ay0) * 74 / 100f
        val swing = sin((now % 1400) / 1400f * PotsikPainter.TAU) * S(2)
        wideLine(ex, ey, ex - S(4) + swing, ey + S(7), S(1) + 1, white)

        // What is left of his right sleeve: a short cuff at the shoulder, with
        // two tatters hanging off it.
        val rx0 = limbs.armR0x
        val ry0 = limbs.armR0y
        val rx1 = limbs.armR1x
        val ry1 = limbs.armR1y
        yzAcross(rx0.toInt(), ry0.toInt(), rx1.toInt(), ry1.toInt(), 4, 22, S(4).toInt() + 1, S(4).toInt() + 1, PotsikPalette.BLACK)
        yzAcross(rx0.toInt(), ry0.toInt(), rx1.toInt(), ry1.toInt(), 5, 21, S(4).toInt(), S(4).toInt(), shirt)
        val dx = rx1 - rx0
        val dy = ry1 - ry0
        val len = sqrt(dx * dx + dy * dy) + 0.01f
        for (sd in listOf(-1, 1)) {
            val bx = rx0.toInt() + (dx * 0.21f + (-dy / len) * sd * S(2)).toInt()
            val by = ry0.toInt() + (dy * 0.21f + (dx / len) * sd * S(2)).toInt()
            val tx = bx + (dx / len * S(5)).toInt()
            val ty = by + (dy / len * S(5)).toInt()
            fillTriangle(bx - S(1) - 1, by, bx + S(1) + 1, by, tx.toFloat(), ty + 1f, PotsikPalette.BLACK)
            fillTriangle(bx - S(1), by, bx + S(1), by, tx.toFloat(), ty.toFloat(), shirt)
        }

        // One tatty sneaker on his left foot, its laces crossed, and the four
        // bare toes of the right foot, which has lost its shoe.
        val lx = limbs.footLx
        val ly = limbs.footLy
        fillRoundRect(lx - 1, ly - S(3) - 1, S(12) + 2, S(9) + 2, S(3), PotsikPalette.BLACK)
        fillRoundRect(lx, ly - S(3), S(12), S(9), S(3), white)
        fillRect(lx, ly + S(4), S(12), S(2), red)
        drawLine(lx + S(3), ly - S(1), lx + S(7), ly + S(1), PotsikPalette.BLACK)
        drawLine(lx + S(3), ly + S(1), lx + S(7), ly - S(1), PotsikPalette.BLACK)
        for (k in 0 until 4) {
            val tx = limbs.footRx + S(2) + k * S(3)
            val ty = limbs.footRy + S(4)
            fillCircle(tx, ty, S(1) + 1, PotsikPalette.BLACK)
            fillCircle(tx, ty, S(1), furLt)
        }

        // The crack across his right lens: only while the lens is open, and
        // it slips with the shades.
        if (look.glassesOn && !isBlinking(now)) {
            val cy = hh + frame.shadeDrop
            drawLine(cx2 + S(4), cy + S(8), cx2 + S(7), cy + S(10), white)
            drawLine(cx2 + S(7), cy + S(10), cx2 + S(6), cy + S(11), white)
            drawLine(cx2 + S(7), cy + S(10), cx2 + S(10), cy + S(9), white)
        }

        if (frame.mood == PotsikMood.SLEEPY) return
        // The three flies going round his head, their wings flapping. Each
        // wing takes its colour from the beat, offset per fly.
        for (i in 0 until 3) {
            val a = (now % 1800) / 1800f * PotsikPainter.TAU * (if (i == 1) -1f else 1f) + i * 2.1f
            val fxp = cx2 + (cos(a) * S(22)).toInt()
            val fyp = hh - S(4) + (sin(a) * S(6)).toInt() + (sin(3 * a) * S(2)).toInt()
            val wing = if (((now / 50) + i) and 1L == 1L) white else grey
            fillRect(fxp - 3, fyp - 3, 3, 3, wing)
            fillRect(fxp + 2, fyp - 3, 3, 3, wing)
            fillRect(fxp - 1, fyp - 1, 4, 4, PotsikPalette.BLACK)
        }
        // One of them, now and then, settles on his head for a rest.
        if ((now % 5000) < 1400) {
            val fx = cx2 - S(2)
            val fy = hh - S(1)
            fillRect(fx - 3, fy - 3, 3, 2, grey)
            fillRect(fx + 2, fy - 3, 3, 2, grey)
            fillRect(fx - 1, fy - 1, 4, 4, PotsikPalette.BLACK)
        }
        // A buzz mark beside them, on every other 400 ms.
        if (((now / 400) and 1L) == 1L) {
            val zx = cx2 + S(18)
            val zy = hh - S(8)
            drawLine(zx, zy, zx + S(3), zy, PotsikPalette.BLACK)
            drawLine(zx + S(3), zy, zx, zy + S(3), PotsikPalette.BLACK)
            drawLine(zx, zy + S(3), zx + S(3), zy + S(3), PotsikPalette.BLACK)
        }
    }

    /**
     * TH3 0N3's costume layer: the round glasses and the hands.
     *
     * The coat, shirt and lapels are in the body pass (drawT3Coat), so the
     * arms and the head go over them. The hands are his own fur inside a
     * black disc, drawn last as the firmware's t3Front does.
     */
    private fun drawTh3() {
        for (sg in listOf(-1, 1)) {
            val x = if (sg < 0) limbs.armL1x else limbs.armR1x
            val y = if (sg < 0) limbs.armL1y else limbs.armR1y
            fillCircle(x, y, S(4) + 1, PotsikPalette.BLACK)
            fillCircle(x, y, S(4), PotsikPalette.FUR_MAIN)
        }

        // The round glasses, in place of his own shades: the same chunky
        // frames and thick bridge, but round, with the code falling inside
        // them on dark green glass.
        t3Shades()
    }

    /**
     * The glasses the chosen one wears.
     *
     * The code falls a little faster every few seconds and the lenses tear
     * sideways for a moment, the way the headline does.
     */
    private fun t3Shades() {
        // The shades ride the same slip as the plain ones do.
        val ey = hh + frame.shadeDrop + S(10)
        val bigR = S(5)
        val r = S(4)
        val glitch = (now % 5200) < 180
        // Shut eyes: the frames stay on, the lenses go.
        val blink = isBlinking(now)
        val glassGlitch = rgb565(0x480A)
        val glass = rgb565(0x0120)
        val white = PotsikPalette.WHITE

        for (sg in listOf(-1, 1)) {
            fillCircle(cx2 + sg * S(6), ey, bigR, PotsikPalette.BLACK)
        }
        fillRect(cx2 - S(2), ey - S(2), S(4), S(2), PotsikPalette.BLACK)

        for (sg in listOf(-1, 1)) {
            if (blink) continue
            // The right lens tears sideways when the code glitches.
            val lx = cx2 + sg * S(6) + (if (glitch && sg > 0) S(1) else 0f)
            fillCircle(lx, ey, r, if (glitch) glassGlitch else glass)
            for (k in -1..1) {
                val x = lx + k * S(2)
                val span = 2 * r
                val y = ey - r + ((now / 70 + (k + 1) * 5 + (if (sg > 0) 3 else 0)) % span).toInt()
                val half = (sqrt((r * r - (x - lx) * (x - lx)).toFloat()) - 1).toInt()
                for (d in 0 until S(3).toInt()) {
                    val yy = y - d
                    if (yy > ey - half && yy < ey + half) {
                        val c = when {
                            glitch -> if (d == 0) white else PotsikPalette.VAPOR_PINK
                            d == 0 -> rgb565(0xB7F5)
                            d < S(2) -> rgb565(0x07E0)
                            else -> rgb565(0x05A0)
                        }
                        fillRect(x, yy, 2, 1, c)
                    }
                }
            }
            // The glint across the top of the lens.
            drawLine(lx - S(3), ey - S(1), lx - S(1), ey - S(3), white)
        }
    }

    // ------------------------------------------------------------------
    // The helpers
    // ------------------------------------------------------------------
}
