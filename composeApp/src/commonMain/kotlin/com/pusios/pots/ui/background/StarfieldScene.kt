package com.pusios.pots.ui.background

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/*
 * "Starfield" from the PotSquad firmware (theme.cpp drawStarfield): a warp
 * tunnel of concentric heptagon rings, streaking stars, two planets and
 * scenery junk flying out of the vanishing point, plus the ambient channel
 * flicker. Purely visual: nothing here takes input or reacts to anything.
 *
 * Simulation runs in virtual units 320 wide; draw() scales the canvas by
 * size.width / 320 so the geometry matches the firmware at any size.
 *
 * Colour convention: colours are kept in the firmware's RGB565 form and
 * quantised through color565 before they reach Compose, including the
 * procedurally generated star, ring and planet colours.
 */

private const val SF_SEED = 0x5F4A21
private const val SF_NS = 72
private const val SF_RINGS = 15
private const val SF_SIDES = 7
private const val SF_NP = 2
private const val SF_NJ = 2
private const val SF_JUNK_KINDS = 8
private const val SF_STRIPS = 5

// Firmware palette (RGB565) for the vaporwave defaults.
private const val SF_BG = 0x0801
private const val SF_CYAN = 0x07FF
private const val SF_PINK = 0xF96F
private const val SF_VAPOR_PINK = 0xFB99
private const val SF_AMBER = 0xFD20
private const val SF_GREEN = 0x07E0
private const val SF_VAPOR_YELLOW = 0xFFD2
private const val SF_WHITE = 0xFFFF
private const val SF_BLACK = 0x0000

/** Planet palettes as the firmware's 9-byte PAL rows: three latitude bands, each RGB888. */
private val SF_PLANET_PAL = arrayOf(
    intArrayOf(0xC86A3C, 0xE08A4A, 0xA8502C), // rust
    intArrayOf(0x4678BE, 0x60A0DC, 0x305496), // ice
    intArrayOf(0x965AB4, 0xBA7CD6, 0x6E3A8C), // violet
)

class StarfieldScene : BackgroundScene {
    private val rnd = Random(SF_SEED)

    // Simulated clock (ms), advanced by step(); drives warp and junk timing.
    private var simMs = 0L

    // Band height in virtual units, captured by draw() for the integer spawn
    // and retire tests the firmware does against yStart/yEnd.
    private var lastBandH = 0f

    // Warp stars.
    private val sx = FloatArray(SF_NS)
    private val sy = FloatArray(SF_NS)
    private val sz = FloatArray(SF_NS)
    private val szPrev = FloatArray(SF_NS)
    private var warp = 1f
    private var warpNext = 0L

    // Tube phase and spin.
    private var tunPhase = 0f
    private var tunSpin = 0f

    // Planets. pY is a fraction of the band height, so it survives resizes.
    private val pX = FloatArray(SF_NP)
    private val pY = FloatArray(SF_NP)
    private val pVx = FloatArray(SF_NP)
    private val pR = FloatArray(SF_NP)
    private val pPal = IntArray(SF_NP)

    // Scenery junk.
    private val jx = FloatArray(SF_NJ)
    private val jy = FloatArray(SF_NJ)
    private val jz = FloatArray(SF_NJ)
    private val jKind = IntArray(SF_NJ)
    private val jLive = BooleanArray(SF_NJ)
    private var jNextAt = 0L

    // Ambient channel flicker: strips of tinted background.
    private var glitchAt = 0L
    private var glitchOn = false
    private val gY = FloatArray(SF_STRIPS)
    private val gH = FloatArray(SF_STRIPS)
    private val gOff = FloatArray(SF_STRIPS)
    private val gCol = IntArray(SF_STRIPS)

    // Reused path for triangles.
    private val triPath = Path()

    init {
        for (i in 0 until SF_NS) {
            sx[i] = rInt(-200, 201).toFloat()
            sy[i] = rInt(-160, 161).toFloat()
            sz[i] = rInt(20, 420).toFloat()
            szPrev[i] = sz[i]
        }
        warpNext = rInt(4000, 9000).toLong()
        for (i in 0 until SF_NP) {
            pX[i] = rInt(0, 320).toFloat()
            pY[i] = planetY()
            pVx[i] = 0.10f + rInt(0, 12) / 100f
            pR[i] = rInt(8, 20).toFloat()
            pPal[i] = rInt(0, 3)
        }
        jNextAt = rInt(1500, 4000).toLong()
        glitchAt = rInt(6000, 14000).toLong()
    }

    private fun rInt(from: Int, until: Int): Int = rnd.nextInt(from, until)

    // The firmware picks an integer row from bandH/6 .. bandH*5/6 and keeps it
    // as a fraction here so a resize does not strand a planet off-band.
    private fun planetY(): Float {
        val h = lastBandH
        if (h < 8f) return 1f / 6f + rnd.nextFloat() * (4f / 6f)
        val lo = (h / 6f).toInt()
        val hi = (h * 5f / 6f).toInt()
        if (hi <= lo) return lo / h
        return rInt(lo, hi).toFloat() / h
    }

    override fun step(dtMs: Float, nowMs: Long) {
        val dt = dtMs.coerceIn(0f, 150f)
        val animK = min(dt / 50f, 3f)
        simMs += dt.toLong()

        // ---- warp: target 7 for 1.9 s, then re-roll after 6-14 s ----
        var targetWarp = 1f
        if (simMs >= warpNext) {
            if (simMs < warpNext + 1900) {
                targetWarp = 7f
            } else {
                warpNext = simMs + rInt(6000, 14000)
            }
        }
        warp += (targetWarp - warp) * 0.07f * animK

        // ---- stars ----
        val stepZ = (1.4f + warp * 1.9f) * animK
        for (i in 0 until SF_NS) {
            szPrev[i] = sz[i]
            sz[i] -= stepZ
            if (sz[i] < 6f) {
                sx[i] = rInt(-200, 201).toFloat()
                sy[i] = rInt(-160, 161).toFloat()
                sz[i] = rInt(330, 430).toFloat()
                szPrev[i] = 0f
            }
        }

        // ---- tube: phase and spin integrated from dt, so warp changes speed smoothly ----
        val tunDt = dt.coerceAtMost(100f)
        val rush = 0.55f + warp * 0.45f
        tunPhase = (tunPhase + tunDt * 0.00055f * rush) % 2f
        tunSpin += tunDt * 0.000555f * rush

        // ---- planets ----
        for (i in 0 until SF_NP) {
            pX[i] += pVx[i] * animK
            if (pX[i] - pR[i] > BPK_VIRTUAL_W) {
                pX[i] = -pR[i] - 2f
                pY[i] = planetY()
                pR[i] = rInt(8, 20).toFloat()
                pVx[i] = 0.10f + rInt(0, 12) / 100f
                pPal[i] = rInt(0, 3)
            }
        }

        // ---- junk: a rare arrival every 6.5-11 s, first at 1.5-4 s ----
        if (simMs >= jNextAt) {
            for (i in 0 until SF_NJ) {
                if (jLive[i]) continue
                val a = rInt(0, 628) / 100f
                val rad = rInt(42, 88).toFloat()
                jz[i] = 300f
                jx[i] = cos(a) * rad
                jy[i] = sin(a) * rad * 0.78f
                jKind[i] = rInt(0, SF_JUNK_KINDS)
                jLive[i] = true
                break
            }
            jNextAt = simMs + rInt(6500, 11000)
        }
        // Scenery retires at z < 6, or early once it is off an edge and closer
        // than z=120. The edge test uses the tube centre draw() will use.
        val bandH = lastBandH
        val cxNow = BPK_VIRTUAL_W / 2f + 14f * sin(nowMs / 2860f)
        val cyNow = bandH / 2f + 9f * cos(nowMs / 2860f * 1.29f)
        for (i in 0 until SF_NJ) {
            if (!jLive[i]) continue
            jz[i] -= (0.030f * jz[i] + 0.35f) * (1f + warp * 0.5f)
            if (jz[i] < 6f) {
                jLive[i] = false
                continue
            }
            if (jz[i] < 120f) {
                val k = 110f / jz[i]
                val x = (cxNow + jx[i] * k).toInt()
                val y = (cyNow + jy[i] * k).toInt()
                var s = (60f * k).toInt()
                if (s < 2) s = 2
                if (s > 52) s = 52
                if (x + s < 0 || x - s >= BPK_VIRTUAL_W ||
                    (bandH > 0f && (y + s < 0 || y - s >= bandH))
                ) {
                    jLive[i] = false
                }
            }
        }

        // ---- ambient channel flicker: 240 ms, first at 6-14 s then 8-18 s ----
        if (simMs >= glitchAt && simMs < glitchAt + 240) {
            glitchOn = true
            val maxY = (lastBandH - 6f).toInt()
            for (k in 0 until SF_STRIPS) {
                gY[k] = if (maxY > 0) rInt(0, maxY).toFloat() else 0f
                gH[k] = rInt(2, 7).toFloat()
                gOff[k] = rInt(-20, 21).toFloat()
                val tint = if (rInt(0, 2) != 0) SF_CYAN else SF_VAPOR_PINK
                gCol[k] = sfBlend565(SF_BG, tint, rInt(90, 190))
            }
        } else {
            glitchOn = false
            if (simMs >= glitchAt + 240) glitchAt = simMs + rInt(8000, 18000)
        }
    }

    override fun DrawScope.draw(nowMs: Long) {
        drawRect(bpkColor(bpkRgb565(SF_BG)), size = size)
        val k = size.width / BPK_VIRTUAL_W
        if (k <= 0f) return
        val bandH = size.height / k
        lastBandH = bandH
        withTransform({ scale(k, k, Offset.Zero) }) {
            drawBand(nowMs, bandH)
        }
    }

    // ---- drawing (virtual units) -----------------------------------------

    private fun DrawScope.drawBand(nowMs: Long, bandH: Float) {
        if (bandH < 8f) return
        val w = BPK_VIRTUAL_W
        val wob = nowMs / 2860f
        val cx = w / 2f + 14f * sin(wob)
        val cy = bandH / 2f + 9f * cos(wob * 1.29f)
        val aspect = if (bandH * 5f > w * 8f) 1f else bandH / w * 1.25f
        val maxR = sqrt(w * w + bandH * bandH) * 0.62f

        drawStars(cx, cy, bandH)
        drawTube(nowMs, cx, cy, aspect, maxR, bandH)
        drawPlanets(bandH)
        drawJunkScenery(cx, cy, bandH)
        if (glitchOn) drawGlitch()
    }

    private fun DrawScope.drawStars(cx: Float, cy: Float, bandH: Float) {
        val w = BPK_VIRTUAL_W.toInt()
        for (i in 0 until SF_NS) {
            // A star respawned this step is skipped for the frame, as the
            // firmware's `continue` does after reseeding it.
            if (szPrev[i] <= 0f) continue
            val z = sz[i]
            val k = 110f / z
            val x = (cx + sx[i] * k).toInt()
            val y = (cy + sy[i] * k).toInt()
            if (x < 0 || x >= w || y < 0f || y >= bandH) continue
            val near = bpkClamp01(1f - z / 430f)
            val bri = (70f + 185f * near).toInt()
            val blue = if (bri > 235) 255 else bri + 20
            val c = bpkColor(bpkRgb565(sfRgb565(bri, bri, blue)))
            if (warp > 1.6f) {
                val kp = 110f / szPrev[i]
                val px = (cx + sx[i] * kp).toInt()
                val py = (cy + sy[i] * kp).toInt()
                drawLine(c, Offset(px.toFloat(), py.toFloat()), Offset(x.toFloat(), y.toFloat()), strokeWidth = 1f)
            } else {
                drawRect(c, Offset(x.toFloat(), y.toFloat()), Size(1f, 1f))
            }
        }
    }

    private fun DrawScope.drawTube(nowMs: Long, cx: Float, cy: Float, aspect: Float, maxR: Float, bandH: Float) {
        val drift = sin(nowMs / 4000f) * 0.03f
        val spin = tunSpin
        val phase = tunPhase
        for (i in SF_RINGS - 1 downTo -2) {
            val fi = i + phase
            val r = maxR * exp(-fi * 0.30f)
            if (r < 3f || r > maxR * 1.9f) continue
            val d = max(0f, fi / SF_RINGS)
            // Odd rings electric blue, even rings purple; the period-2 parity
            // is what keeps the scroll seamless.
            val col = if ((i and 1) != 0) {
                sfHsv565(0.66f + drift, 1f, 1f - d * 0.62f)
            } else {
                sfHsv565(0.80f + drift, 1f, 0.72f - d * 0.42f)
            }
            val lw = (4.2f * (1f - d)).toInt()
            val c = bpkRgb565(col)
            var px = (cx + cos(spin) * r).toInt()
            var py = (cy + sin(spin) * r * aspect).toInt()
            for (k in 1..SF_SIDES) {
                val a = spin + k * (6.2831853f / SF_SIDES)
                val nx = (cx + cos(a) * r).toInt()
                val ny = (cy + sin(a) * r * aspect).toInt()
                if (lw >= 2) {
                    val horiz = (nx - px) * (nx - px) >= (ny - py) * (ny - py)
                    for (o in 0 until lw) {
                        val off = o - lw / 2
                        if (horiz) {
                            sfBandLine(px, py + off, nx, ny + off, bandH, c)
                        } else {
                            sfBandLine(px + off, py, nx + off, ny, bandH, c)
                        }
                    }
                } else {
                    sfBandLine(px, py, nx, ny, bandH, c)
                }
                px = nx
                py = ny
            }
        }
    }

    private fun DrawScope.drawPlanets(bandH: Float) {
        for (i in 0 until SF_NP) {
            val rad = pR[i].toInt()
            val ox = pX[i].toInt()
            val oy = (pY[i] * bandH).toInt()
            if (oy - rad < 0 || oy + rad >= bandH.toInt()) continue
            val pal = SF_PLANET_PAL[pPal[i]]
            for (dy in -rad..rad) {
                val hw = sqrt((rad * rad - dy * dy).toFloat()).toInt()
                if (hw < 1) continue
                // Three latitude bands, picked by row.
                val b = ((dy + rad) * 3) / (2 * rad + 1)
                val lit = sfQuantizeRgb(pal[b])
                val y = (oy + dy).toFloat()
                drawRect(bpkColor(lit), Offset((ox - hw).toFloat(), y), Size((hw * 2 + 1).toFloat(), 1f))
                // Terminator: the trailing third falls into shadow.
                val sw = (hw * 2 + 1) / 3
                if (sw > 0) {
                    val raw = pal[b]
                    val dark = sfQuantizeRgb(
                        bpkRgb(
                            ((raw shr 16) and 0xFF) / 3,
                            ((raw shr 8) and 0xFF) / 3,
                            (raw and 0xFF) / 3,
                        ),
                    )
                    drawRect(
                        bpkColor(dark),
                        Offset((ox + hw - sw).toFloat(), y),
                        Size(sw.toFloat(), 1f),
                    )
                }
            }
        }
    }

    private fun DrawScope.drawJunkScenery(cx: Float, cy: Float, bandH: Float) {
        val w = BPK_VIRTUAL_W.toInt()
        for (i in 0 until SF_NJ) {
            if (!jLive[i]) continue
            val k = 110f / jz[i]
            val x = (cx + jx[i] * k).toInt()
            val y = (cy + jy[i] * k).toInt()
            var s = (60f * k).toInt()
            if (s < 2) s = 2
            if (s > 52) s = 52
            if (x + s < 0 || x - s >= w || y + s < 0 || y - s >= bandH.toInt()) continue
            sfJunk(jKind[i], x.toFloat(), y.toFloat(), s.toFloat())
        }
    }

    private fun DrawScope.drawGlitch() {
        val w = BPK_VIRTUAL_W
        for (k in 0 until SF_STRIPS) {
            sfRect(this, gOff[k], gY[k], w, gH[k], gCol[k])
        }
    }

    // ---- junk sprites (scenery only; no catch logic) ----------------------

    private fun DrawScope.sfJunk(kind: Int, x: Float, y: Float, s: Float) {
        if (s < 4f) {
            sfCircle(this, x, y, max(1f, s), SF_VAPOR_PINK)
            return
        }
        when (kind) {
            0 -> junkEye(x, y, s)
            1 -> junkFace(x, y, s)
            2 -> junkSaucer(x, y, s)
            3 -> junkTelevision(x, y, s)
            4 -> junkBurger(x, y, s)
            5 -> junkPizza(x, y, s)
            6 -> junkToilet(x, y, s)
            else -> junkDuck(x, y, s)
        }
    }

    private fun DrawScope.junkEye(x: Float, y: Float, s: Float) {
        sfCircle(this, x, y, s + 1f, SF_BLACK)
        sfCircle(this, x, y, s, SF_WHITE)
        sfCircle(this, x - s * 0.06f, y + s * 0.10f, s * 0.94f, 0xEF1D)
        sfCircle(this, x, y - s * 0.06f, s * 0.90f, SF_WHITE)
        sfCircle(this, x + s * 0.16f, y, s * 0.58f, 0x098F)
        sfCircle(this, x + s * 0.16f, y, s * 0.50f, 0x1B9C)
        for (k in 0 until 12) {
            val a = k * 0.5236f
            sfLine(
                this,
                x + s * 0.16f + cos(a) * s * 0.20f,
                y + sin(a) * s * 0.20f,
                x + s * 0.16f + cos(a) * s * 0.48f,
                y + sin(a) * s * 0.48f,
                1f,
                0x0A55,
            )
        }
        sfCircle(this, x + s * 0.16f, y, s * 0.24f, SF_BLACK)
        sfCircle(this, x - s * 0.08f, y - s * 0.36f, s * 0.17f, SF_WHITE)
        sfCircle(this, x + s * 0.40f, y + s * 0.30f, s * 0.07f + 1f, SF_WHITE)
        sfLine(this, x - s * 0.96f, y - s * 0.34f, x - s * 0.34f, y - s * 0.16f, 1f, 0xD104)
        sfLine(this, x - s * 0.90f, y + s * 0.44f, x - s * 0.28f, y + s * 0.24f, 1f, 0xD104)
        sfLine(this, x - s * 0.62f, y - s * 0.62f, x - s * 0.30f, y - s * 0.40f, 1f, 0xD104)
    }

    private fun DrawScope.junkFace(x: Float, y: Float, s: Float) {
        sfCircle(this, x, y, s + 1f, SF_BLACK)
        sfCircle(this, x, y, s, 0xFF4B)
        sfCircle(this, x, y + s * 0.10f, s * 0.94f, 0xF623)
        sfCircle(this, x, y - s * 0.08f, s * 0.86f, 0xFF4B)
        sfCircle(this, x - s * 0.60f, y + s * 0.28f, s * 0.20f, 0xF540)
        sfCircle(this, x + s * 0.60f, y + s * 0.28f, s * 0.20f, 0xF540)
        sfEllipse(this, x - s * 0.40f, y - s * 0.24f, s * 0.22f + 1f, s * 0.30f + 1f, SF_BLACK)
        sfEllipse(this, x + s * 0.40f, y - s * 0.24f, s * 0.22f + 1f, s * 0.30f + 1f, SF_BLACK)
        sfCircle(this, x - s * 0.34f, y - s * 0.34f, s * 0.08f, SF_WHITE)
        sfCircle(this, x + s * 0.46f, y - s * 0.34f, s * 0.08f, SF_WHITE)
        sfLine(this, x - s * 0.66f, y - s * 0.62f, x - s * 0.18f, y - s * 0.48f, s * 0.13f + 1f, 0x7A80)
        sfLine(this, x + s * 0.18f, y - s * 0.48f, x + s * 0.66f, y - s * 0.62f, s * 0.13f + 1f, 0x7A80)
        sfEllipse(this, x, y + s * 0.46f, s * 0.42f, s * 0.34f, SF_BLACK)
        sfEllipse(this, x, y + s * 0.60f, s * 0.24f, s * 0.16f, 0xD189)
        sfRect(this, x - s * 0.26f, y + s * 0.16f, s * 0.16f + 1f, s * 0.13f + 1f, SF_WHITE)
        sfRect(this, x + s * 0.10f, y + s * 0.16f, s * 0.16f + 1f, s * 0.13f + 1f, SF_WHITE)
        sfCircle(this, x + s * 0.92f, y - s * 0.62f, s * 0.13f, 0x7EBF)
    }

    private fun DrawScope.junkSaucer(x: Float, y: Float, s: Float) {
        val beam = sfBlend565(SF_BG, 0x7F96, 80)
        sfTri(this, triPath, x - s * 0.28f, y + s * 0.24f, x + s * 0.62f, y + s * 1.05f, x - s * 0.62f, y + s * 1.05f, beam)
        sfTri(this, triPath, x - s * 0.28f, y + s * 0.24f, x + s * 0.28f, y + s * 0.24f, x + s * 0.62f, y + s * 1.05f, beam)
        sfEllipse(this, x, y + s * 0.06f, s + 1f, s * 0.34f + 2f, SF_BLACK)
        sfEllipse(this, x, y + s * 0.34f, s * 0.80f, s * 0.26f, 0x394C)
        sfEllipse(this, x, y + s * 0.06f, s, s * 0.34f, 0x7C55)
        sfEllipse(this, x, y - s * 0.02f, s * 0.96f, s * 0.26f, 0xADBA)
        sfEllipse(this, x, y - s * 0.08f, s * 0.90f, s * 0.16f, 0xD71E)
        sfCircle(this, x, y - s * 0.34f, s * 0.44f + 1f, SF_BLACK)
        sfCircle(this, x, y - s * 0.34f, s * 0.44f, 0x2E9F)
        sfCircle(this, x, y - s * 0.32f, s * 0.36f, 0x9F9F)
        sfCircle(this, x, y - s * 0.30f, s * 0.17f, 0x1B4A)
        sfCircle(this, x - s * 0.07f, y - s * 0.36f, s * 0.05f + 1f, SF_BLACK)
        sfCircle(this, x + s * 0.07f, y - s * 0.36f, s * 0.05f + 1f, SF_BLACK)
        sfCircle(this, x - s * 0.16f, y - s * 0.48f, s * 0.10f, SF_WHITE)
        for (k in -2..2) {
            sfCircle(
                this,
                x + k * s * 0.36f,
                y + s * 0.16f,
                s * 0.10f + 1f,
                if ((k and 1) != 0) SF_AMBER else SF_PINK,
            )
        }
        sfLine(this, x - s * 0.30f, y + s * 0.30f, x - s * 0.42f, y + s * 0.62f, s * 0.09f + 1f, 0x5B30)
    }

    private fun DrawScope.junkTelevision(x: Float, y: Float, s: Float) {
        val chassis = 0x8C54
        val hi = 0xC63B
        val lo = 0x5B0E
        sfLine(this, x - s * 0.26f, y - s * 0.62f, x - s * 0.86f, y - s * 1.30f, 2f, hi)
        sfLine(this, x + s * 0.26f, y - s * 0.62f, x + s * 0.86f, y - s * 1.30f, 2f, hi)
        sfCircle(this, x - s * 0.86f, y - s * 1.30f, s * 0.09f + 1f, SF_WHITE)
        sfCircle(this, x + s * 0.86f, y - s * 1.30f, s * 0.09f + 1f, SF_WHITE)
        sfRect(this, x - s * 0.62f, y + s * 0.72f, s * 0.20f + 1f, s * 0.26f + 1f, lo)
        sfRect(this, x + s * 0.42f, y + s * 0.72f, s * 0.20f + 1f, s * 0.26f + 1f, lo)
        sfRect(this, x - s - 1f, y - s * 0.66f - 1f, 2f * s + 3f, s * 1.40f + 3f, SF_BLACK)
        sfRect(this, x - s, y - s * 0.66f, 2f * s, s * 1.40f, chassis)
        sfRect(this, x - s, y - s * 0.66f, 2f * s, s * 0.14f + 1f, hi)
        sfRect(this, x - s, y + s * 0.62f, 2f * s, s * 0.12f + 1f, lo)
        sfRect(this, x - s * 0.86f, y - s * 0.52f, s * 1.42f, s * 1.08f, 0x1084)
        val bars = intArrayOf(SF_PINK, SF_AMBER, SF_VAPOR_YELLOW, SF_CYAN, SF_GREEN)
        for (k in 0 until 5) {
            sfRect(this, x - s * 0.82f + k * s * 0.27f, y - s * 0.48f, s * 0.25f + 1f, s * 1.00f, bars[k])
        }
        sfRect(this, x - s * 0.82f, y - s * 0.20f, s * 1.35f, s * 0.10f + 1f, SF_WHITE)
        sfRect(this, x + s * 0.56f, y - s * 0.52f, s * 0.30f, s * 1.08f, chassis)
        sfCircle(this, x + s * 0.76f, y - s * 0.24f, s * 0.13f + 1f, 0x39C9)
        sfCircle(this, x + s * 0.76f, y - s * 0.24f, s * 0.07f, hi)
        sfCircle(this, x + s * 0.76f, y + s * 0.10f, s * 0.13f + 1f, 0x39C9)
        sfCircle(this, x + s * 0.76f, y + s * 0.10f, s * 0.07f, hi)
        for (k in 0 until 3) {
            sfRect(this, x + s * 0.66f, y + s * 0.34f + k * (s * 0.09f + 1f), s * 0.22f, s * 0.05f + 1f, lo)
        }
    }

    private fun DrawScope.junkBurger(x: Float, y: Float, s: Float) {
        val bunTop = 0xF5AB
        val bunLo = 0xDC87
        sfEllipse(this, x, y - s * 0.20f, s + 1f, s * 0.68f + 1f, SF_BLACK)
        sfEllipse(this, x, y - s * 0.20f, s, s * 0.66f, bunTop)
        sfRect(this, x - s, y - s * 0.20f, 2f * s, s * 0.24f, bunLo)
        sfRect(this, x - s, y - s * 0.36f, 2f * s, s * 0.22f, bunTop)
        for (k in 0 until 5) {
            sfEllipse(this, x + s * SF_SEEDS_X[k], y + s * SF_SEEDS_Y[k], s * 0.11f + 1f, s * 0.07f + 1f, 0xFF99)
        }
        val lettuce = 0x3DEB
        sfRect(this, x - s * 1.02f, y - s * 0.16f, s * 2.04f, s * 0.16f + 1f, lettuce)
        for (k in -3..3) sfCircle(this, x + k * s * 0.30f, y - s * 0.04f, s * 0.15f, lettuce)
        sfEllipse(this, x, y + s * 0.06f, s * 0.94f, s * 0.14f + 1f, 0xD9C5)
        sfEllipse(this, x, y + s * 0.04f, s * 0.72f, s * 0.08f + 1f, 0xF30A)
        sfRect(this, x - s * 0.90f, y + s * 0.14f, s * 1.80f, s * 0.16f + 1f, 0xFE04)
        sfRect(this, x - s * 0.58f, y + s * 0.28f, s * 0.20f, s * 0.18f, 0xFE04)
        sfRect(this, x + s * 0.34f, y + s * 0.28f, s * 0.20f, s * 0.16f, 0xFE04)
        sfRect(this, x - s * 0.94f, y + s * 0.28f, s * 1.88f, s * 0.32f, 0x79E2)
        sfRect(this, x - s * 0.94f, y + s * 0.28f, s * 1.88f, s * 0.08f + 1f, 0x9A83)
        sfEllipse(this, x + s * 0.74f, y + s * 0.22f, s * 0.22f, s * 0.09f + 1f, 0x5589)
        sfEllipse(this, x, y + s * 0.56f, s * 0.94f + 1f, s * 0.32f + 1f, SF_BLACK)
        sfEllipse(this, x, y + s * 0.54f, s * 0.94f, s * 0.30f, 0xE509)
        sfRect(this, x - s * 0.94f, y + s * 0.36f, s * 1.88f, s * 0.18f, 0xE509)
    }

    private fun DrawScope.junkPizza(x: Float, y: Float, s: Float) {
        sfTri(this, triPath, x, y - s - 1f, x - s * 0.92f, y + s * 0.84f, x + s * 0.92f, y + s * 0.84f, SF_BLACK)
        sfTri(this, triPath, x, y - s * 1.02f, x - s * 0.90f, y + s * 0.82f, x + s * 0.90f, y + s * 0.82f, 0xED88)
        sfTri(this, triPath, x, y - s * 0.82f, x - s * 0.72f, y + s * 0.64f, x + s * 0.72f, y + s * 0.64f, 0xC245)
        sfTri(this, triPath, x, y - s * 0.66f, x - s * 0.60f, y + s * 0.52f, x + s * 0.60f, y + s * 0.52f, 0xFE8C)
        sfTri(this, triPath, x, y - s * 0.60f, x - s * 0.34f, y + s * 0.10f, x + s * 0.34f, y + s * 0.10f, 0xFF32)
        sfEllipse(this, x, y + s * 0.80f, s * 0.94f, s * 0.26f, 0xDCC8)
        sfRect(this, x - s * 0.92f, y + s * 0.66f, s * 1.84f, s * 0.16f + 1f, 0xDCC8)
        for (k in 0 until 3) sfCircle(this, x + s * SF_CRUST_X[k], y + s * 0.80f, s * 0.09f + 1f, 0xAB44)
        for (k in 0 until 3) {
            val r = s * SF_PEP_R[k] + 1f
            val cx = x + s * SF_PEP_X[k]
            val cy = y + s * SF_PEP_Y[k]
            sfCircle(this, cx, cy, r, 0x88E3)
            sfCircle(this, cx, cy, r * 0.72f, 0xD1C5)
            sfCircle(this, cx - r * 0.28f, cy - r * 0.28f, r * 0.24f, 0xF34A)
        }
        for (k in 0 until 3) {
            sfEllipse(this, x + s * SF_HAM_X[k], y + s * SF_HAM_Y[k], s * 0.09f + 1f, s * 0.05f + 1f, 0x2C67)
        }
    }

    private fun DrawScope.junkToilet(x: Float, y: Float, s: Float) {
        val porc = 0xE75E
        val shad = 0xBE1A
        sfRect(this, x - s * 0.78f, y - s * 1.08f, s * 1.56f, s * 0.22f + 2f, SF_BLACK)
        sfRect(this, x - s * 0.76f, y - s * 1.06f, s * 1.52f, s * 0.18f + 1f, porc)
        sfRect(this, x - s * 0.76f, y - s * 1.06f, s * 1.52f, s * 0.07f + 1f, SF_WHITE)
        sfRect(this, x - s * 0.76f, y - s * 0.90f, s * 1.52f, s * 0.05f + 1f, shad)
        sfRect(this, x - s * 0.68f, y - s * 0.90f, s * 1.36f, s * 0.72f, SF_BLACK)
        sfRect(this, x - s * 0.66f, y - s * 0.88f, s * 1.32f, s * 0.68f, porc)
        sfRect(this, x - s * 0.66f, y - s * 0.26f, s * 1.32f, s * 0.10f + 1f, shad)
        sfRect(this, x + s * 0.50f, y - s * 0.66f, s * 0.26f, s * 0.14f + 1f, 0xCD04)
        sfCircle(this, x - s * 0.86f, y - s * 0.60f, s * 0.18f + 1f, SF_WHITE)
        sfCircle(this, x - s * 0.86f, y - s * 0.60f, s * 0.07f, shad)
        sfEllipse(this, x, y + s * 0.14f, s * 0.88f + 1f, s * 0.48f + 1f, SF_BLACK)
        sfEllipse(this, x, y + s * 0.14f, s * 0.88f, s * 0.48f, porc)
        sfEllipse(this, x, y + s * 0.06f, s * 0.80f, s * 0.40f, SF_WHITE)
        sfEllipse(this, x, y + s * 0.10f, s * 0.62f, s * 0.30f, 0x9518)
        sfEllipse(this, x, y + s * 0.12f, s * 0.50f, s * 0.23f, 0x2CFB)
        sfEllipse(this, x - s * 0.14f, y + s * 0.06f, s * 0.22f, s * 0.09f + 1f, 0x8F1F)
        sfRect(this, x - s * 0.32f, y + s * 0.50f, s * 0.64f, s * 0.42f, SF_BLACK)
        sfRect(this, x - s * 0.30f, y + s * 0.52f, s * 0.60f, s * 0.40f, 0xDF3D)
        sfRect(this, x - s * 0.30f, y + s * 0.52f, s * 0.12f + 1f, s * 0.40f, SF_WHITE)
        sfEllipse(this, x, y + s * 0.92f, s * 0.56f, s * 0.16f + 1f, porc)
    }

    private fun DrawScope.junkDuck(x: Float, y: Float, s: Float) {
        val body = 0xFE44
        val lit = 0xFF0C
        val shad = 0xF540
        sfEllipse(this, x - s * 0.05f, y + s * 0.66f, s * 1.05f, s * 0.22f + 1f, sfBlend565(SF_BG, 0x7E5F, 90))
        sfTri(this, triPath, x - s * 0.78f, y + s * 0.10f, x - s * 1.24f, y - s * 0.28f, x - s * 0.66f, y - s * 0.16f, 0xFDA0)
        sfEllipse(this, x - s * 0.08f, y + s * 0.30f, s * 0.94f + 1f, s * 0.54f + 1f, SF_BLACK)
        sfEllipse(this, x - s * 0.08f, y + s * 0.30f, s * 0.94f, s * 0.54f, body)
        sfEllipse(this, x - s * 0.08f, y + s * 0.16f, s * 0.86f, s * 0.36f, lit)
        sfEllipse(this, x - s * 0.18f, y + s * 0.30f, s * 0.50f, s * 0.28f, shad)
        for (k in -2..2) sfCircle(this, x - s * 0.18f + k * s * 0.17f, y + s * 0.50f, s * 0.10f + 1f, shad)
        sfEllipse(this, x - s * 0.22f, y + s * 0.22f, s * 0.34f, s * 0.16f + 1f, 0xFE87)
        sfCircle(this, x + s * 0.50f, y - s * 0.42f, s * 0.46f + 1f, SF_BLACK)
        sfCircle(this, x + s * 0.50f, y - s * 0.42f, s * 0.46f, body)
        sfCircle(this, x + s * 0.44f, y - s * 0.52f, s * 0.34f, lit)
        sfRect(this, x + s * 0.84f, y - s * 0.38f, s * 0.46f, s * 0.22f + 1f, 0xFC62)
        sfRect(this, x + s * 0.84f, y - s * 0.24f, s * 0.36f, s * 0.11f + 1f, 0xDB00)
        sfCircle(this, x + s * 1.02f, y - s * 0.34f, s * 0.04f + 1f, 0xA220)
        sfCircle(this, x + s * 0.56f, y - s * 0.56f, s * 0.13f + 1f, SF_BLACK)
        sfCircle(this, x + s * 0.60f, y - s * 0.60f, s * 0.05f + 1f, SF_WHITE)
        sfLine(this, x + s * 0.44f, y - s * 0.74f, x + s * 0.68f, y - s * 0.72f, s * 0.07f + 1f, 0xCC40)
        sfCircle(this, x + s * 0.26f, y - s * 0.26f, s * 0.11f + 1f, 0xFCF6)
    }

    // ---- colour helpers ---------------------------------------------------

    /** Firmware hsv2rgb, packed to RGB565 with the firmware's truncation. */
    private fun sfHsv565(h0: Float, s: Float, v: Float): Int {
        val h = h0 - floor(h0)
        val hi = floor(h * 6f)
        val f = h * 6f - hi
        val p = v * (1f - s)
        val q = v * (1f - f * s)
        val u = v * (1f - (1f - f) * s)
        val r: Float
        val g: Float
        val b: Float
        when (hi.toInt() % 6) {
            0 -> {
                r = v
                g = u
                b = p
            }

            1 -> {
                r = q
                g = v
                b = p
            }

            2 -> {
                r = p
                g = v
                b = u
            }

            3 -> {
                r = p
                g = q
                b = v
            }

            4 -> {
                r = u
                g = p
                b = v
            }

            else -> {
                r = v
                g = p
                b = q
            }
        }
        return sfRgb565(
            (r * 255f).toInt().coerceIn(0, 255),
            (g * 255f).toInt().coerceIn(0, 255),
            (b * 255f).toInt().coerceIn(0, 255),
        )
    }

    private companion object {
        // Junk geometry tables, hoisted so drawing does not allocate.
        val SF_SEEDS_X = floatArrayOf(-0.62f, -0.20f, 0.24f, 0.62f, 0.02f)
        val SF_SEEDS_Y = floatArrayOf(-0.62f, -0.72f, -0.68f, -0.56f, -0.50f)
        val SF_CRUST_X = floatArrayOf(-0.55f, 0f, 0.55f)
        val SF_PEP_X = floatArrayOf(0f, -0.28f, 0.30f)
        val SF_PEP_Y = floatArrayOf(-0.20f, 0.26f, 0.22f)
        val SF_PEP_R = floatArrayOf(0.19f, 0.16f, 0.16f)
        val SF_HAM_X = floatArrayOf(-0.14f, 0.20f, -0.34f)
        val SF_HAM_Y = floatArrayOf(0.50f, -0.44f, -0.10f)
    }
}

// ---- primitive helpers (virtual units; the canvas is already scaled) -------
// Colours here are RGB565, as the firmware constants are.

/** The firmware's TFT_eSPI color565(r, g, b). */
private fun sfRgb565(r: Int, g: Int, b: Int): Int = ((r and 0xF8) shl 8) or ((g and 0xFC) shl 3) or ((b and 0xFF) shr 3)

/** color565(r, g, b) expanded back to packed 0xRRGGBB, as the panel shows it. */
private fun sfQuantizeRgb(rgb: Int): Int = bpkRgb565(
    sfRgb565((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF),
)

/** A line clipped to the band, with the firmware's integer endpoints. */
private fun DrawScope.sfBandLine(x0: Int, y0: Int, x1: Int, y1: Int, bandH: Float, rgb: Int) {
    val last = bandH.toInt() - 1
    if (last < 0 || (y0 < 0 && y1 < 0) || (y0 > last && y1 > last)) return
    var ax = x0
    var ay = y0
    var bx = x1
    var by = y1
    if (ay != by) {
        val dx = x1 - x0
        val dy = y1 - y0
        if (ay < 0) {
            ax = x0 + dx * (0 - y0) / dy
            ay = 0
        } else if (ay > last) {
            ax = x0 + dx * (last - y0) / dy
            ay = last
        }
        if (by < 0) {
            bx = x0 + dx * (0 - y0) / dy
            by = 0
        } else if (by > last) {
            bx = x0 + dx * (last - y0) / dy
            by = last
        }
    }
    drawLine(bpkColor(rgb), Offset(ax.toFloat(), ay.toFloat()), Offset(bx.toFloat(), by.toFloat()), strokeWidth = 1f)
}

/** Firmware blend() on RGB565 channels; t in 0..256, 0 gives [a], 256 gives [b]. */
private fun sfBlend565(a: Int, b: Int, t: Int): Int {
    val tt = t.coerceIn(0, 256)
    val it = 256 - tt
    val r = (((a shr 11) and 0x1F) * it + ((b shr 11) and 0x1F) * tt) shr 8
    val g = (((a shr 5) and 0x3F) * it + ((b shr 5) and 0x3F) * tt) shr 8
    val bl = ((a and 0x1F) * it + (b and 0x1F) * tt) shr 8
    return (r shl 11) or (g shl 5) or bl
}

private fun sfCircle(scope: DrawScope, x: Float, y: Float, r: Float, rgb565: Int) {
    if (r <= 0f) return
    scope.drawCircle(bpkColor(bpkRgb565(rgb565)), r, Offset(x, y))
}

private fun sfEllipse(scope: DrawScope, x: Float, y: Float, rx: Float, ry: Float, rgb565: Int) {
    if (rx <= 0f || ry <= 0f) return
    scope.drawOval(
        bpkColor(bpkRgb565(rgb565)),
        topLeft = Offset(x - rx, y - ry),
        size = Size(rx * 2f, ry * 2f),
    )
}

private fun sfRect(scope: DrawScope, x: Float, y: Float, w: Float, h: Float, rgb565: Int) {
    if (w <= 0f || h <= 0f) return
    scope.drawRect(bpkColor(bpkRgb565(rgb565)), Offset(x, y), Size(w, h))
}

private fun sfLine(scope: DrawScope, x0: Float, y0: Float, x1: Float, y1: Float, width: Float, rgb565: Int) {
    scope.drawLine(bpkColor(bpkRgb565(rgb565)), Offset(x0, y0), Offset(x1, y1), strokeWidth = width)
}

private fun sfTri(
    scope: DrawScope,
    path: Path,
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    x3: Float,
    y3: Float,
    rgb565: Int,
) {
    path.reset()
    path.moveTo(x1, y1)
    path.lineTo(x2, y2)
    path.lineTo(x3, y3)
    path.close()
    scope.drawPath(path, bpkColor(bpkRgb565(rgb565)))
}
