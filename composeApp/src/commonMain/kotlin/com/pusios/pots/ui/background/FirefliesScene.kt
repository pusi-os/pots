package com.pusios.pots.ui.background

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

// Port of PotSquad drawFireflies (theme.cpp 4651-4937), visual only.
// Virtual units: 320 wide; y is in virtual units from the top of the canvas.

private const val FF_FAR = 20
private const val FF_MID = 14
private const val FF_NEAR = 4
private const val FF_SEED = 0x0F1EF1E5

private const val P_FAR = 1000f
private const val P_MID = 800f
private const val P_NEAR = 650f

private const val VW = BPK_VIRTUAL_W

private const val SKY_TOP = 0x084C
private const val SKY_DUSK = 0x2991
private const val SKY_NIGHT = 0x816C
private const val FIELD = 0x0941
private const val SOIL = 0x00E1
private const val BLADE = 0x0A02
private const val FOG = 0x634F
private const val TREE = 0x0041
private const val TONE0 = 0xDFE9
private const val TONE1 = 0xFEC4
private const val TONE2 = 0x97ED
private const val WHITE = 0xFFFFFF
private const val MOON_FACE = 0xEF7A
private const val MOON_CRATER = 0xCE56
private const val MOON_EDGE = 0xDED6
private const val MOON_HALO = 0xFF99

private val TONES = intArrayOf(bpkRgb565(TONE0), bpkRgb565(TONE1), bpkRgb565(TONE2))
private val SKY_TOP_RGB = bpkRgb565(SKY_TOP)
private val SKY_DUSK_RGB = bpkRgb565(SKY_DUSK)
private val SKY_NIGHT_RGB = bpkRgb565(SKY_NIGHT)
private val FIELD_RGB = bpkRgb565(FIELD)
private val SOIL_RGB = bpkRgb565(SOIL)
private val BLADE_RGB = bpkRgb565(BLADE)
private val FOG_RGB = bpkRgb565(FOG)
private val TREE_RGB = bpkRgb565(TREE)
private val MOON_FACE_RGB = bpkRgb565(MOON_FACE)
private val MOON_CRATER_RGB = bpkRgb565(MOON_CRATER)
private val MOON_EDGE_RGB = bpkRgb565(MOON_EDGE)
private val MOON_HALO_RGB = bpkRgb565(MOON_HALO)

private val SKY_DITH = intArrayOf(-9, 4, 9, -4)
private val GLOW_DITH = intArrayOf(-7, 3, 7, -3)

private class Fly {
    var x = 0f
    var y = 0f
    var hx = 0f
    var hy = 0f
    var vx = 0f
    var dx = 0f
    var dy = 0f
    var ph = 0f
    var pk = 1f
    var tone = 0
    var pat = 0
    var rolled = false
}

class FirefliesScene : BackgroundScene {
    private val rng = Random(FF_SEED)
    private val far = Array(FF_FAR) { Fly() }
    private val mid = Array(FF_MID) { Fly() }
    private val near = Array(FF_NEAR) { Fly() }

    private val lampX = IntArray(FF_NEAR + 1)
    private val lampY = IntArray(FF_NEAR + 1)
    private val lampS = FloatArray(FF_NEAR + 1)
    private val lampTone = IntArray(FF_NEAR + 1)
    private var nl = 0

    private var vh = 240f
    private var inited = false
    private var lastNow = 0L

    private var waveAt = 0L
    private var waveStart = 0L
    private var waveOn = false
    private var gustAt = 0L
    private var gustStart = 0L
    private var gustOn = false
    private var eyesAt = 0L
    private var eyesStart = 0L
    private var eyesOn = false
    private var lantAt = 0L
    private var lantStart = 0L
    private var lantOn = false
    private var eyesX = 0
    private var lantY = 0f

    private var skyHor = SKY_NIGHT_RGB
    private var wind = 0f
    private var lantK = -1f
    private var lantX = 0

    private fun rnd(a: Int, b: Int): Int = if (b <= a) a else a + rng.nextInt(b - a)

    private fun horizonY(): Float = vh * 0.58f
    private fun grassY(): Float = vh * 0.78f
    private fun botY(): Float = vh - 1f

    private fun spawn(f: Fly, y0: Float, y1: Float, spd: Float) {
        f.x = rnd(0, VW.toInt()).toFloat()
        f.hx = f.x
        f.y = rnd(y0.toInt(), y1.toInt().coerceAtLeast(y0.toInt() + 1)).toFloat()
        f.hy = f.y
        f.vx = rnd(-100, 101) / 100f * spd
        f.dx = 0f
        f.dy = 0f
        f.ph = rnd(0, 6283) / 1000f
        f.pk = 0.75f + rnd(0, 66) / 100f
        f.tone = rnd(0, 3)
        f.pat = rnd(0, 6)
        f.rolled = false
    }

    private fun spawnAll() {
        val h = horizonY()
        val g = grassY()
        val b = botY()
        for (f in far) spawn(f, h + 2f, g - 2f, 0.10f)
        for (f in mid) spawn(f, h - 14f, b - 8f, 0.16f)
        for (f in near) spawn(f, h + 4f, b - 6f, 0.22f)
    }

    private fun scheduleAll(now: Long) {
        waveAt = now + rnd(9000, 16000)
        gustAt = now + rnd(18000, 30000)
        eyesAt = now + rnd(14000, 26000)
        lantAt = now + rnd(20000, 34000)
    }

    private fun init(now: Long) {
        inited = true
        lastNow = now
        spawnAll()
        scheduleAll(now)
    }

    private fun stepFly(f: Fly, ds: Float, w: Float, t: Float, yTop: Float, yBot: Float) {
        when (f.pat) {
            0 -> {
                f.hx += f.vx * 0.25f * ds
                f.x = f.hx + sin(t * 0.9f + f.ph) * 8f
                f.y = f.hy + sin(t * 1.8f + f.ph) * 4f
            }

            1 -> {
                f.hx += f.vx * 1.2f * ds
                f.x = f.hx
                f.y = f.hy + sin(t * 0.7f + f.ph) * 5f
            }

            2 -> {
                f.hx += f.vx * 0.3f * ds
                f.x = f.hx
                f.y = f.hy + sin(t * 2.2f + f.ph) * 9f
            }

            3 -> {
                f.hx += f.vx * 0.5f * ds
                f.x = f.hx + cos(t * 1.6f + f.ph) * 10f
                f.y = f.hy + sin(t * 1.6f + f.ph) * 6f
            }

            4 -> {
                var c = (t * 0.7f + f.ph) % 1f
                if (c < 0f) c += 1f
                if (c < 0.22f) {
                    f.hx += f.dx * ds * 2.2f
                    f.hy += f.dy * ds * 2.2f
                } else if (c > 0.97f && !f.rolled) {
                    f.dx = rnd(-50, 51) / 60f
                    f.dy = rnd(-50, 51) / 90f
                    f.rolled = true
                }
                if (c < 0.9f) f.rolled = false
                f.x = f.hx
                f.y = f.hy
            }

            else -> {
                f.hx += f.vx * 0.5f * ds
                val pu = 0.5f + 0.5f * sin(t / (0.7f * f.pk) + f.ph * 3f)
                f.x = f.hx + pu * 3f
                f.y = f.hy - pu * 10f
            }
        }
        f.hx += w * 0.25f * ds
        if (f.hx < -4f) f.hx = VW + 3f
        if (f.hx > VW + 4f) f.hx = -3f
        if (f.hy < yTop + 4f) f.hy = yTop + 4f
        if (f.hy > yBot - 4f) f.hy = yBot - 4f
    }

    override fun step(dtMs: Float, nowMs: Long) {
        if (!inited) init(nowMs)
        val step = nowMs != lastNow
        val dt = if (dtMs > 200f) 200f else dtMs
        val ds = if (step) dt / 16f else 0f
        val t = nowMs / 1000f

        var wnd = sin(nowMs / 3000f) * 0.12f
        if (step) {
            if (!gustOn && nowMs >= gustAt) {
                gustOn = true
                gustStart = nowMs
            }
            if (!waveOn && nowMs >= waveAt) {
                waveOn = true
                waveStart = nowMs
            }
            if (!eyesOn && nowMs >= eyesAt) {
                eyesOn = true
                eyesStart = nowMs
                eyesX = rnd(24, VW.toInt() - 24)
            }
            if (!lantOn && nowMs >= lantAt) {
                lantOn = true
                lantStart = nowMs
                val ly0 = (grassY() - 10f).toInt()
                val ly1 = (botY() - 14f).toInt().coerceAtLeast(ly0 + 1)
                lantY = rnd(ly0, ly1).toFloat()
            }
        }
        if (gustOn) {
            val gk = (nowMs - gustStart) / 3500f
            if (gk >= 1f) {
                gustOn = false
                gustAt = nowMs + rnd(18000, 34000)
            } else {
                wnd += sin(gk * PI.toFloat()) * 1f
            }
        }
        if (waveOn && (nowMs - waveStart) / 2400f > 1.15f) {
            waveOn = false
            waveAt = nowMs + rnd(11000, 20000)
        }
        if (eyesOn && nowMs - eyesStart > 3200L) {
            eyesOn = false
            eyesAt = nowMs + rnd(16000, 30000)
        }
        if (lantOn && (nowMs - lantStart) / 7000f >= 1f) {
            lantOn = false
            lantAt = nowMs + rnd(24000, 40000)
        }
        wind = wnd

        val h = horizonY()
        val g = grassY()
        val b = botY()
        if (step) {
            for (f in far) stepFly(f, ds * 0.5f, wnd * 0.4f, t, h + 2f, g - 2f)
            for (f in mid) stepFly(f, ds, wnd, t, h - 14f, b - 8f)
            for (f in near) stepFly(f, ds * 1.5f, wnd * 1.4f, t, h + 4f, b - 6f)
            lastNow = nowMs
        }
    }

    private fun pulse(f: Fly, nowMs: Long, per: Float): Float = 0.5f + 0.5f * sin(nowMs / (per * f.pk) + f.ph * 3f)

    private fun fogAt(y: Float, nowMs: Long): Float {
        var f = 0f
        for (b in 0..1) {
            val cy = grassY() - 9f - b * 9f + sin(nowMs / (2600f + b * 900f)) * 3f
            val th = 6f + b * 2f
            val d = abs(y - cy) / th
            if (d < 1f) f += (1f - d) * (0.55f - b * 0.2f)
        }
        return if (f > 1f) 1f else f
    }

    private fun skyAt(y: Int, skyH: Int): Int {
        val den = if (skyH > 1) skyH - 1 else 1
        val k = ((y * 255) / den).coerceIn(0, 255)
        return bpkBlend(SKY_TOP_RGB, skyHor, k)
    }

    override fun DrawScope.draw(nowMs: Long) {
        val s = size.width / VW
        if (s <= 0f) return
        val newVh = size.height / s
        if (abs(newVh - vh) > 0.5f) {
            vh = newVh
            spawnAll()
        }
        val wInt = VW.toInt()
        val horizon = horizonY()
        val horizonI = horizon.toInt()
        val grassTop = grassY()
        val grassI = grassTop.toInt()
        val yBot = botY()
        val yBotI = yBot.toInt()
        val fullW = size.width

        val dusk = 0.5f + 0.5f * cos(nowMs / 26000f)
        skyHor = bpkBlend(SKY_DUSK_RGB, SKY_NIGHT_RGB, (dusk * 255f).toInt())

        // Sky, one virtual row at a time with the firmware's row dither.
        val skyH = horizonI
        val den = if (skyH > 1) skyH - 1 else 1
        for (y in 0 until horizonI) {
            var k = (y * 255) / den + SKY_DITH[y and 3]
            if (k < 0) k = 0
            if (k > 255) k = 255
            drawRect(bpkColor(bpkBlend(SKY_TOP_RGB, skyHor, k)), Offset(0f, y * s), Size(fullW, s))
        }

        // Stars fade in as dusk leaves.
        val starVis = (1f - dusk) * (1f - dusk)
        if (starVis > 0.04f) {
            val skyRange = if (skyH > 24) skyH - 20 else 4
            for (i in 0 until 26) {
                var hsh = (i + 5) * -1640531535
                hsh = hsh xor (hsh ushr 15)
                hsh *= -2048144777
                hsh = hsh xor (hsh ushr 13)
                val hu = hsh.toLong() and 0xFFFFFFFFL
                val sx = (hu % wInt).toInt()
                val sy = 2 + ((hu ushr 11) % skyRange.toLong()).toInt()
                val tw = 0.5f + 0.5f * sin(nowMs / (700f + (hu % 800L).toFloat()) + i.toFloat())
                val c = bpkBlend(skyAt(sy, skyH), WHITE, (starVis * (70f + tw * 150f)).toInt())
                drawRect(bpkColor(c), Offset(sx * s, sy * s), Size(s, s))
            }
        }

        // Moon: warm per-row halo sampled against the sky, then face, crater, edge.
        val mx = (wInt * 79) / 100
        val my = (skyH * 22) / 100
        val hr = 13
        for (dy in -hr..hr) {
            val yy = my + dy
            if (yy < 0 || yy >= horizonI) continue
            val half = sqrt((hr * hr - dy * dy).toFloat()).toInt()
            if (half < 1) continue
            val fr = 1f - abs(dy.toFloat()) / (hr + 1f)
            var a = (fr * fr * 70f).toInt() + SKY_DITH[yy and 3]
            if (a <= 2) continue
            if (a > 255) a = 255
            drawRect(
                bpkColor(bpkBlend(skyAt(yy, skyH), MOON_HALO_RGB, a)),
                Offset((mx - half) * s, yy * s),
                Size((half * 2 + 1) * s, s),
            )
        }
        drawCircle(bpkColor(MOON_EDGE_RGB), radius = 5f * s, center = Offset(mx * s, my * s))
        drawCircle(bpkColor(MOON_FACE_RGB), radius = 4f * s, center = Offset(mx * s, my * s))
        drawCircle(bpkColor(MOON_CRATER_RGB), radius = 2f * s, center = Offset((mx - 2) * s, (my - 1) * s))

        // Treeline silhouette.
        var tx = 0
        while (tx < wInt) {
            var hsh = (tx / 8 + 40) * -1640531535
            hsh = hsh xor (hsh ushr 13)
            val hu = hsh.toLong() and 0xFFFFFFFFL
            val hh = 6 + (hu % 14L).toInt()
            drawRect(
                bpkColor(TREE_RGB),
                Offset(tx * s, (horizonI - hh) * s),
                Size(8f * s, (hh + 2) * s),
            )
            if ((hu and 2L) != 0L) {
                drawRect(
                    bpkColor(TREE_RGB),
                    Offset((tx + 2 + (hu % 3L).toInt()) * s, (horizonI - hh - 5) * s),
                    Size(3f * s, 6f * s),
                )
            }
            tx += 8
        }

        // Something in the treeline: two eyes, one blink.
        if (eyesOn) {
            val ea = nowMs - eyesStart
            if (!(ea > 1700L && ea < 1850L)) {
                val eye = bpkColor(TONES[2])
                drawRect(eye, Offset((eyesX - 4) * s, (horizonI - 9) * s), Size(2f * s, 2f * s))
                drawRect(eye, Offset((eyesX + 2) * s, (horizonI - 9) * s), Size(2f * s, 2f * s))
            }
        }

        // Far field.
        drawRect(bpkColor(FIELD_RGB), Offset(0f, horizon * s), Size(fullW, (grassTop - horizon) * s))

        // Two drifting fog bands; also the bloom trigger for far fireflies.
        for (y in (horizonI + 2) until grassI) {
            val f = fogAt(y.toFloat(), nowMs)
            if (f < 0.04f) continue
            var a = (f * 120f).toInt() + SKY_DITH[y and 3] / 2
            if (a < 2) continue
            if (a > 255) a = 255
            drawRect(
                bpkColor(bpkBlend(FIELD_RGB, FOG_RGB, a)),
                Offset(0f, y * s),
                Size(fullW, s),
            )
        }

        // Far fireflies: single points, blooming to small halos inside fog.
        for (i in 0 until FF_FAR) {
            val f = far[i]
            val pu = pulse(f, nowMs, P_FAR)
            if (pu < 0.35f) continue
            val x = f.x.toInt()
            val y = f.y.toInt()
            val fg = fogAt(y.toFloat(), nowMs)
            if (fg > 0.3f) {
                ffGlow(
                    x,
                    y,
                    3,
                    TONES[f.tone],
                    bpkBlend(FIELD_RGB, FOG_RGB, (fg * 120f).toInt()),
                    (pu * 150f).toInt(),
                    horizonI,
                    grassI,
                    s,
                )
            } else {
                drawRect(
                    bpkColor(bpkBlend(FIELD_RGB, TONES[f.tone], (pu * 230f).toInt())),
                    Offset(x * s, y * s),
                    Size(s, s),
                )
            }
        }

        // Near grass, lit by the near fireflies and by the passing lantern.
        drawRect(bpkColor(SOIL_RGB), Offset(0f, grassTop * s), Size(fullW, (vh - grassTop) * s))

        nl = 0
        for (i in 0 until FF_NEAR) {
            val pu = pulse(near[i], nowMs, P_NEAR)
            if (pu > 0.4f) {
                lampX[nl] = near[i].x.toInt()
                lampY[nl] = near[i].y.toInt()
                lampS[nl] = pu
                lampTone[nl] = near[i].tone
                nl++
            }
        }
        lantK = -1f
        lantX = 0
        if (lantOn) {
            val k = (nowMs - lantStart) / 7000f
            if (k < 1f) {
                lantK = k
                lantX = (-20f + k * (VW + 40f)).toInt()
                lampX[nl] = lantX
                lampY[nl] = lantY.toInt()
                lampS[nl] = 1.3f
                lampTone[nl] = 1
                nl++
            }
        }

        tx = 0
        while (tx < wInt) {
            var hsh = (tx / 4 + 9) * -1640531535
            hsh = hsh xor (hsh ushr 13)
            val hu = hsh.toLong() and 0xFFFFFFFFL
            val bh = 3 + (hu % 7L).toInt()
            var c = BLADE_RGB
            var best = 0f
            var bt = 0
            for (i in 0 until nl) {
                val dx = (tx - lampX[i]).toFloat()
                val dy = (yBot - bh - lampY[i])
                val d = sqrt(dx * dx + dy * dy) / 30f
                if (d < 1f) {
                    val v = (1f - d) * lampS[i]
                    if (v > best) {
                        best = v
                        bt = lampTone[i]
                    }
                }
            }
            if (best > 0.02f) c = bpkBlend(BLADE_RGB, TONES[bt], (best * 190f).toInt())
            val lean = (wind * (3f + (hu % 4L).toFloat())).toInt()
            if (lean != 0) {
                drawLine(
                    bpkColor(c),
                    Offset(tx * s, yBot * s),
                    Offset((tx + lean) * s, (yBot - bh) * s),
                    strokeWidth = s,
                )
            } else {
                drawRect(bpkColor(c), Offset(tx * s, (yBot - bh) * s), Size(s, bh * s))
            }
            tx += 4
        }

        // Mid fireflies: small cross, plus a wind streak when it blows.
        for (i in 0 until FF_MID) {
            val f = mid[i]
            val pu = pulse(f, nowMs, P_MID)
            if (pu < 0.3f) continue
            val x = f.x.toInt()
            val y = f.y.toInt()
            val bg = if (y < horizonI) {
                skyAt(y, skyH)
            } else if (y < grassI) {
                FIELD_RGB
            } else {
                SOIL_RGB
            }
            val c = bpkBlend(bg, TONES[f.tone], (pu * 255f).toInt())
            if (abs(wind) > 0.4f) {
                val len = abs(wind * 10f).toInt()
                if (len > 0) {
                    drawRect(
                        bpkColor(bpkBlend(bg, c, 110)),
                        Offset((x - (wind * 10f).toInt()) * s, y * s),
                        Size(len * s, s),
                    )
                }
            }
            drawRect(bpkColor(c), Offset((x - 1) * s, y * s), Size(3f * s, s))
            drawRect(bpkColor(c), Offset(x * s, (y - 1) * s), Size(s, 3f * s))
        }

        // Near fireflies: soft blooms with a hot cross.
        for (i in 0 until FF_NEAR) {
            val f = near[i]
            val pu = pulse(f, nowMs, P_NEAR)
            if (pu < 0.2f) continue
            val x = f.x.toInt()
            val y = f.y.toInt()
            val bg = if (y < grassI) FIELD_RGB else SOIL_RGB
            ffGlow(x, y, 5, TONES[f.tone], bg, (pu * 255f).toInt(), horizonI, yBotI, s)
            val hot = bpkBlend(WHITE, TONES[f.tone], 90)
            drawRect(bpkColor(hot), Offset((x - 1) * s, y * s), Size(3f * s, s))
            drawRect(bpkColor(hot), Offset(x * s, (y - 1) * s), Size(s, 3f * s))
        }

        // Lantern near the lens: flare, bloom and a hot core, eased in and out.
        if (lantK >= 0f) {
            val ed = if (lantK < 0.15f) {
                lantK / 0.15f
            } else if (lantK > 0.85f) {
                (1f - lantK) / 0.15f
            } else {
                1f
            }
            val ly = lantY.toInt()
            val lc = TONES[1]
            val bg = if (ly < grassI) FIELD_RGB else SOIL_RGB
            drawRect(
                bpkColor(bpkBlend(bg, lc, (ed * 60f).toInt())),
                Offset((lantX - 44) * s, ly * s),
                Size(88f * s, s),
            )
            ffGlow(lantX, ly, 9, lc, bg, (ed * 255f).toInt(), horizonI, yBotI, s)
            drawRect(
                bpkColor(bpkBlend(WHITE, lc, 60)),
                Offset((lantX - 1) * s, (ly - 1) * s),
                Size(3f * s, 3f * s),
            )
        }

        // Sync ripple: a white band travels left to right through the field.
        if (waveOn) {
            val waveT = (nowMs - waveStart) / 2400f
            if (waveT >= 0f) {
                for (i in 0 until FF_FAR) waveHit(far[i], waveT, false, s)
                for (i in 0 until FF_MID) waveHit(mid[i], waveT, true, s)
                for (i in 0 until FF_NEAR) waveHit(near[i], waveT, true, s)
            }
        }
    }

    private fun DrawScope.waveHit(f: Fly, waveT: Float, cross: Boolean, s: Float) {
        if (abs(f.x / VW - waveT) >= 0.07f) return
        val x = f.x.toInt()
        val y = f.y.toInt()
        if (cross) {
            drawRect(bpkColor(WHITE), Offset((x - 2) * s, y * s), Size(5f * s, s))
            drawRect(bpkColor(WHITE), Offset(x * s, (y - 2) * s), Size(s, 5f * s))
        } else {
            drawRect(bpkColor(WHITE), Offset(x * s, y * s), Size(s, s))
        }
    }

    private fun DrawScope.ffGlow(
        cx: Int,
        cy: Int,
        r: Int,
        rgb: Int,
        bg: Int,
        str: Int,
        yMin: Int,
        yMax: Int,
        s: Float,
    ) {
        for (dy in -r..r) {
            val yy = cy + dy
            if (yy < yMin || yy >= yMax) continue
            val half = sqrt((r * r - dy * dy).toFloat()).toInt()
            if (half < 1) continue
            val fr = 1f - abs(dy.toFloat()) / (r + 1f)
            var ao = (fr * fr * str * 0.42f).toInt() + GLOW_DITH[yy and 3]
            var ai = (fr * fr * str).toInt() + GLOW_DITH[yy and 3]
            if (ao > 255) ao = 255
            if (ai > 255) ai = 255
            if (ao > 3) {
                drawRect(
                    bpkColor(bpkBlend(bg, rgb, ao)),
                    Offset((cx - half) * s, yy * s),
                    Size((half * 2 + 1) * s, s),
                )
            }
            val ih = half / 2
            if (ai > 3) {
                drawRect(
                    bpkColor(bpkBlend(bg, rgb, ai)),
                    Offset((cx - ih) * s, yy * s),
                    Size((ih * 2 + 1) * s, s),
                )
            }
        }
    }
}
