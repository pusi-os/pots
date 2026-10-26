package com.pusios.pots.ui.background

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.max
import kotlin.random.Random

// Port of PotSquad theme.cpp drawDigitalRain (purely visual).
// Simulated in a 320-wide virtual space; every lit glyph pixel is one drawRect.
// Glyphs are the TFT_eSPI/Adafruit 5x7 GLCD bitmaps the firmware prints.

private const val SPACING = 5
private const val COLS = 64 // 320 / SPACING, capped at the firmware's MAX_COLS
private const val MAX_TRAIL = 24
private const val MINTRAIL = 17
private const val CELL = 8
private const val CELL_W = 6
private const val CELL_H = 8
private const val SPLASH_SLOTS = 6
private const val NPOP = 5
private const val SWEEP_PERIOD_MS = 10_000L
private const val SWEEP_TRAVEL_MS = 1_200L
private const val BOLT_MS = 140L
private const val TEAR_MS = 260L

// Theme defaults from include/theme.h, expanded from RGB565 like the firmware.
private val BG = bpkRgb565(0x0801)
private val WHITE = bpkRgb565(0xFFFF)
private val HUES = intArrayOf(
    bpkRgb565(0xBB5F), // VAPOR_PURPLE, far
    bpkRgb565(0x07FF), // CYAN
    bpkRgb565(0x07E0), // GREEN
    bpkRgb565(0xFB99), // VAPOR_PINK, near
)

// Glyph source order from the firmware: "01" A-Z symbols "POTSPUSI".
private const val GLYPH_CHARS = "01ABCDEFGHIJKLMNOPQRSTUVWXYZ!@#\$%^&*<>{}[]/\\|+=~"
private const val GLYPH_SOURCE = "01ABCDEFGHIJKLMNOPQRSTUVWXYZ!@#\$%^&*<>{}[]/\\|+=~POTSPUSI"

/** GLCD column bitmaps, 5 per glyph, bit 0 = top row. Same order as GLYPH_CHARS. */
private val GLYPH_COLS = intArrayOf(
    0x3E, 0x51, 0x49, 0x45, 0x3E, 0x00, 0x42, 0x7F, 0x40, 0x00,
    0x7C, 0x12, 0x11, 0x12, 0x7C, 0x7F, 0x49, 0x49, 0x49, 0x36,
    0x3E, 0x41, 0x41, 0x41, 0x22, 0x7F, 0x41, 0x41, 0x41, 0x3E,
    0x7F, 0x49, 0x49, 0x49, 0x41, 0x7F, 0x09, 0x09, 0x09, 0x01,
    0x3E, 0x41, 0x41, 0x51, 0x73, 0x7F, 0x08, 0x08, 0x08, 0x7F,
    0x00, 0x41, 0x7F, 0x41, 0x00, 0x20, 0x40, 0x41, 0x3F, 0x01,
    0x7F, 0x08, 0x14, 0x22, 0x41, 0x7F, 0x40, 0x40, 0x40, 0x40,
    0x7F, 0x02, 0x1C, 0x02, 0x7F, 0x7F, 0x04, 0x08, 0x10, 0x7F,
    0x3E, 0x41, 0x41, 0x41, 0x3E, 0x7F, 0x09, 0x09, 0x09, 0x06,
    0x3E, 0x41, 0x51, 0x21, 0x5E, 0x7F, 0x09, 0x19, 0x29, 0x46,
    0x26, 0x49, 0x49, 0x49, 0x32, 0x03, 0x01, 0x7F, 0x01, 0x03,
    0x3F, 0x40, 0x40, 0x40, 0x3F, 0x1F, 0x20, 0x40, 0x20, 0x1F,
    0x3F, 0x40, 0x38, 0x40, 0x3F, 0x63, 0x14, 0x08, 0x14, 0x63,
    0x03, 0x04, 0x78, 0x04, 0x03, 0x61, 0x59, 0x49, 0x4D, 0x43,
    0x00, 0x00, 0x5F, 0x00, 0x00, 0x3E, 0x41, 0x5D, 0x59, 0x4E,
    0x14, 0x7F, 0x14, 0x7F, 0x14, 0x24, 0x2A, 0x7F, 0x2A, 0x12,
    0x23, 0x13, 0x08, 0x64, 0x62, 0x04, 0x02, 0x01, 0x02, 0x04,
    0x36, 0x49, 0x56, 0x20, 0x50, 0x2A, 0x1C, 0x7F, 0x1C, 0x2A,
    0x00, 0x08, 0x14, 0x22, 0x41, 0x00, 0x41, 0x22, 0x14, 0x08,
    0x00, 0x08, 0x36, 0x41, 0x00, 0x00, 0x41, 0x36, 0x08, 0x00,
    0x00, 0x7F, 0x41, 0x41, 0x41, 0x00, 0x41, 0x41, 0x41, 0x7F,
    0x20, 0x10, 0x08, 0x04, 0x02, 0x02, 0x04, 0x08, 0x10, 0x20,
    0x00, 0x00, 0x77, 0x00, 0x00, 0x08, 0x08, 0x3E, 0x08, 0x08,
    0x14, 0x14, 0x14, 0x14, 0x14, 0x02, 0x01, 0x02, 0x04, 0x02,
)

private val GLYPH_SET: IntArray = IntArray(GLYPH_SOURCE.length) { GLYPH_CHARS.indexOf(GLYPH_SOURCE[it]) }

class DigitalRainScene : BackgroundScene {
    private val rng = Random(0x5EED)

    private val yPos = IntArray(COLS)
    private val ySpeed = IntArray(COLS)
    private val yTick = FloatArray(COLS)
    private val hue = IntArray(COLS)
    private val len = IntArray(COLS)
    private val depth = IntArray(COLS)
    private val surge = BooleanArray(COLS)
    private val ring = IntArray(COLS * MAX_TRAIL) // glyph indices, [0] = head

    private val splashX = IntArray(SPLASH_SLOTS)
    private val splashHue = IntArray(SPLASH_SLOTS)
    private val splashAge = IntArray(SPLASH_SLOTS)

    private val popX = IntArray(NPOP)
    private val popY = IntArray(NPOP)
    private val popHue = IntArray(NPOP)
    private val popCh = IntArray(NPOP)
    private val popAge = IntArray(NPOP)

    private var vh = 240
    private var sized = false
    private var started = false

    private var boltAt = 0L
    private var boltNext = 0L
    private var sweepAt = 0L
    private var tearAt = 0L
    private var tearNext = 0L
    private var tearDx = 0

    init {
        for (i in 0 until COLS) {
            respawn(i, anywhere = true)
            for (j in 0 until MAX_TRAIL) ring[i * MAX_TRAIL + j] = rng.nextInt(GLYPH_SET.size)
        }
    }

    override fun step(dtMs: Float, nowMs: Long) {
        if (!started) {
            started = true
            boltNext = nowMs + 14_000L + rng.nextInt(18_000)
        }
        if (nowMs >= boltNext) {
            boltAt = nowMs
            boltNext = nowMs + 14_000L + rng.nextInt(18_000)
        }
        if (nowMs - sweepAt >= SWEEP_PERIOD_MS) sweepAt = nowMs
        if (nowMs >= tearNext) {
            tearAt = nowMs
            tearNext = nowMs + 5_000L + rng.nextInt(9_000)
        }
        tearDx = if (tearAt != 0L && nowMs - tearAt < TEAR_MS) rng.nextInt(-7, 8) else 0

        val tick = dtMs / 50f
        for (i in 0 until COLS) {
            val base = i * MAX_TRAIL
            val l = len[i]
            // Shimmer: two cells in the trail re-roll.
            ring[base + rng.nextInt(1, l)] = rng.nextInt(GLYPH_SET.size)
            if (rng.nextInt(2) == 1) ring[base + rng.nextInt(1, l)] = rng.nextInt(GLYPH_SET.size)

            yTick[i] += tick
            if (yTick[i] >= ySpeed[i]) {
                yTick[i] = 0f
                yPos[i] += CELL
                for (j in MAX_TRAIL - 1 downTo 1) ring[base + j] = ring[base + j - 1]
                ring[base] = rng.nextInt(GLYPH_SET.size)

                if (yPos[i] >= vh - CELL && yPos[i] < vh) spawnSplash(i)
                if (rng.nextInt(90) == 0 && yPos[i] > 20 && yPos[i] < vh - 20) spawnPop(i, ring[base])
                if (yPos[i] > vh + l * CELL) respawn(i, anywhere = false)
            }
        }

        for (k in 0 until SPLASH_SLOTS) {
            if (splashAge[k] > 0) {
                splashAge[k]++
                if (splashAge[k] > 8) splashAge[k] = 0
            }
        }
        for (k in 0 until NPOP) {
            if (popAge[k] > 0) {
                popAge[k]++
                if (popAge[k] > 15) popAge[k] = 0
            }
        }
    }

    override fun DrawScope.draw(nowMs: Long) {
        val scale = size.width / BPK_VIRTUAL_W
        if (scale <= 0f) return
        vh = (size.height / scale).toInt().coerceAtLeast(1)
        if (!sized) {
            sized = true
            for (i in 0 until COLS) respawn(i, anywhere = true)
        }

        drawRect(bpkColor(BG), Offset.Zero, size)

        val boltAge = nowMs - boltAt
        val bolt = if (boltAt != 0L && boltAge < BOLT_MS) {
            (160 - boltAge * 160 / BOLT_MS).toInt()
        } else {
            0
        }
        val sweepAge = nowMs - sweepAt
        val rollAt = if (sweepAt != 0L && sweepAge < SWEEP_TRAVEL_MS) {
            -40 + ((vh + 80L) * sweepAge / SWEEP_TRAVEL_MS).toInt()
        } else {
            -30_000
        }
        val tearOn = tearAt != 0L && nowMs - tearAt < TEAR_MS
        val tearY = vh - 14

        for (i in 0 until COLS) {
            val h = hue[i]
            val hc = HUES[h]
            val l = len[i]
            val s = surge[i]
            val deep = 170 + depth[i] * 85 / 255
            val xBase = 3 + i * SPACING
            val hot = if (s) 3 else 1
            val base = i * MAX_TRAIL

            for (j in 0 until l) {
                val ry = yPos[i] - j * CELL
                if (ry < 0 || ry >= vh) continue

                var x = xBase
                val rd = ry - rollAt
                if (rd in 0..3) x += 5 - rd
                if (tearOn && ry >= tearY) x += tearDx

                val edge = if (ry < 16) ry * 255 / 16 else 255
                val glyph = GLYPH_SET[ring[base + j]]

                if (j < hot) {
                    var a = deep * edge / 255
                    if (rd in -5..-1) a /= 7
                    val bg = bpkBlend(BG, hc, a / (if (s) 3 else 4))
                    drawGlyph(glyph, x.toFloat(), ry.toFloat(), scale, bpkColor(bpkBlend(BG, WHITE, a)), bg)
                } else {
                    val f = 1f - (j - hot).toFloat() / (l - hot).toFloat()
                    var a = ((if (s) 245f else 225f) * f * f).toInt()
                    a = a * deep / 255
                    a = a * edge / 255
                    if (rd in -5..-1) a = a * 22 / 255
                    var lift = bolt
                    if (rd in 0..17) {
                        val rl = if (rd < 3) 225 else 150 - (rd - 3) * 10
                        if (rl > lift) lift = rl
                    }
                    val body = bpkBlend(BG, hc, a)
                    val fg = if (lift > 0) bpkBlend(body, WHITE, lift) else body
                    val bg = if (j <= hot + 1) bpkBlend(BG, hc, a / 6) else -1
                    drawGlyph(glyph, x.toFloat(), ry.toFloat(), scale, bpkColor(fg), bg)
                }
            }
        }

        // Floating glyphs lifted off the head, growing and fading.
        for (k in 0 until NPOP) {
            val age = popAge[k]
            if (age == 0) continue
            val sz = if (age < 5) {
                1
            } else if (age < 10) {
                2
            } else {
                3
            }
            val color = bpkColor(bpkBlend(BG, HUES[popHue[k]], 235 - age * 15))
            val gx = popX[k] - (sz - 1) * 3
            val gy = popY[k] - age - (sz - 1) * 4
            drawGlyph(GLYPH_SET[popCh[k]], gx.toFloat(), gy.toFloat(), scale * sz, color, -1)
        }

        // Splashes at the floor: two short strokes each.
        val sy = (vh - 2).toFloat()
        for (k in 0 until SPLASH_SLOTS) {
            val age = splashAge[k]
            if (age == 0) continue
            val sp = age * 2
            val col = bpkColor(bpkBlend(BG, HUES[splashHue[k]], 200 - age * 24))
            drawRect(col, Offset((splashX[k] - sp) * scale, sy * scale), Size(sp * scale, scale))
            drawRect(col, Offset((splashX[k] + 1) * scale, sy * scale), Size(sp * scale, scale))
        }
    }

    private fun respawn(i: Int, anywhere: Boolean) {
        surge[i] = rng.nextInt(14) == 0
        depth[i] = if (surge[i]) 255 else rng.nextInt(256)
        ySpeed[i] = if (surge[i]) 2 else max(1, 5 - depth[i] * 3 / 255)
        hue[i] = depth[i] shr 6
        len[i] = if (surge[i]) MAX_TRAIL else MINTRAIL + rng.nextInt(MAX_TRAIL + 1 - MINTRAIL)
        yTick[i] = rng.nextInt(ySpeed[i]).toFloat()
        yPos[i] = if (anywhere) rng.nextInt(0, max(1, vh)) else -rng.nextInt(0, 90)
    }

    private fun spawnSplash(i: Int) {
        var slot = 0
        var oldest = 0
        for (k in 0 until SPLASH_SLOTS) {
            if (splashAge[k] == 0) {
                slot = k
                break
            }
            if (splashAge[k] > oldest) {
                oldest = splashAge[k]
                slot = k
            }
        }
        splashX[slot] = 3 + i * SPACING
        splashHue[slot] = hue[i]
        splashAge[slot] = 1
    }

    private fun spawnPop(i: Int, ch: Int) {
        for (k in 0 until NPOP) {
            if (popAge[k] == 0) {
                popX[k] = 3 + i * SPACING
                popY[k] = yPos[i]
                popCh[k] = ch
                popHue[k] = hue[i]
                popAge[k] = 1
                return
            }
        }
    }

    /** One GLCD text cell: an optional 6x8 background tint, then the 5x7 glyph pixels. */
    private fun DrawScope.drawGlyph(glyph: Int, x: Float, y: Float, px: Float, color: Color, bg: Int) {
        val fx = x * px
        val fy = y * px
        if (bg != -1) drawRect(bpkColor(bg), Offset(fx, fy), Size(CELL_W * px, CELL_H * px))
        val o = glyph * 5
        for (c in 0 until 5) {
            val bits = GLYPH_COLS[o + c]
            if (bits == 0) continue
            for (r in 0 until 7) {
                if ((bits ushr r) and 1 == 1) {
                    drawRect(color, Offset(fx + c * px, fy + r * px), Size(px, px))
                }
            }
        }
    }
}
