package com.pusios.pots.ui.background

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * Snowfall, ported from the PotSquad firmware's drawSnowfall (theme.cpp).
 *
 * Purely visual: the sky clock, weather, snow bank and tracks, three parallax
 * planes, lodge, igloo, riders with jumps and crashes, the yeti's autonomous
 * chase, wildlife and snowfall. No taps, knocks, speech or unlock hooks.
 */
class SnowfallScene : BackgroundScene {
    private val rnd = Random(0x5A0F)
    private var initDone = false
    private var laidOut = false
    private var dawnAt = 0L

    private val flkX = FloatArray(SNOW_N)
    private val flkY = FloatArray(SNOW_N)
    private val flkLayer = IntArray(SNOW_N)

    private val farX = FloatArray(TREE_FAR)
    private val farH = IntArray(TREE_FAR)
    private val actX = FloatArray(TREE_ACT)
    private val actH = IntArray(TREE_ACT)
    private val actLoad = IntArray(TREE_ACT)
    private val propX = FloatArray(PROP_N)
    private val propKind = IntArray(PROP_N)

    private val bank = FloatArray(SNOW_COLS)
    private val bankTmp = FloatArray(SNOW_COLS)
    private val track = IntArray(SNOW_COLS)

    private var lodgeX = 0f
    private var nearX = 0f
    private var iglooX = 0f

    private var wxInten = 0.18f
    private var wxTarget = 0.35f
    private var wxWind = 0f
    private var wxNextAt = 0L
    private var bankAcc = 0f

    private var inten = 0.18f
    private var wind = 0f
    private var dawn = 0f

    private val ridX = FloatArray(RIDER_N)
    private val ridAir = FloatArray(RIDER_N)
    private val ridVy = FloatArray(RIDER_N)
    private val ridNext = LongArray(RIDER_N)
    private val ridCrashAt = LongArray(RIDER_N)
    private val ridTrick = IntArray(RIDER_N)
    private val ridKind = IntArray(RIDER_N)
    private val ridVar = IntArray(RIDER_N)
    private val ridLive = BooleanArray(RIDER_N)

    private var chaseState = CH_NONE
    private var chaseEnd = END_GIVEUP
    private var chaseTgt = -1
    private var chaseAt = 0L
    private var chaseNext = 0L
    private var eatAt = 0L
    private var yetiX = -60f

    private var dogX = -60f
    private var hareX = -60f
    private var birdAt = 0L
    private var birdX = 0f
    private var birdY = 0f

    private var vh = DEFAULT_VH
    private var yBotI = DEFAULT_VH.toInt()
    private var sc = 1f

    private fun init(nowMs: Long) {
        for (i in 0 until SNOW_N) {
            flkX[i] = rnd.nextFloat() * VW
            flkY[i] = rnd.nextFloat() * vh
            flkLayer[i] = i % 3
        }
        for (i in 0 until SNOW_COLS) {
            bank[i] = rnd.nextInt(4, 9).toFloat()
            track[i] = 0
        }
        for (i in 0 until TREE_FAR) {
            farX[i] = i * (VW + 60f) / TREE_FAR
            farH[i] = rnd.nextInt(18, 28)
        }
        for (i in 0 until TREE_ACT) {
            actX[i] = i * (VW + 90f) / TREE_ACT
            actH[i] = rnd.nextInt(52, 70)
            actLoad[i] = rnd.nextInt(0, 90)
        }
        for (i in 0 until PROP_N) {
            propX[i] = i * (VW + 70f) / PROP_N
            propKind[i] = i % 3
        }
        lodgeX = VW + 140f
        nearX = VW + 120f
        iglooX = VW + 40f
        for (i in 0 until RIDER_N) {
            ridX[i] = -40f
            ridAir[i] = 0f
            ridVy[i] = 0f
            ridTrick[i] = 0
            ridLive[i] = false
            ridCrashAt[i] = 0L
            ridKind[i] = if (i == 1) 1 else 0
            ridVar[i] = i
            ridNext[i] = nowMs + 2200L + i * 5200L
        }
        chaseNext = nowMs + 12000L
        dawnAt = nowMs
        wxNextAt = nowMs
        initDone = true
    }

    override fun step(dtMs: Float, nowMs: Long) {
        if (!initDone) init(nowMs)
        var dt = dtMs
        if (dt > 200f) dt = 200f
        if (dt <= 0f) return
        val ds = dt / 16f

        val phase = posMod(nowMs - dawnAt, DAWN_CYCLE) / DAWN_CYCLE.toFloat()
        dawn = when {
            phase <= 0.62f -> 0f
            phase < 0.80f -> (phase - 0.62f) / 0.18f
            else -> (1f - (phase - 0.80f) / 0.20f).coerceAtLeast(0f)
        }

        if (nowMs >= wxNextAt) {
            val r = rnd.nextInt(100)
            wxTarget = when {
                r < 50 -> 0.12f + rnd.nextInt(18) / 100f
                r < 82 -> 0.36f + rnd.nextInt(24) / 100f
                else -> 0.72f + rnd.nextInt(26) / 100f
            }
            wxNextAt = nowMs + rnd.nextInt(8000, 22000)
        }
        wxInten = (wxInten + (wxTarget - wxInten) * 0.0015f * ds).coerceIn(0.05f, 1f)
        wxWind = sin(nowMs / 5200f) * 0.5f + sin(nowMs / 1700f) * 0.22f * wxInten
        inten = wxInten * (1f - dawn * 0.55f)
        wind = wxWind

        bankAcc += ds
        if (bankAcc > 6f) {
            bankAcc = 0f
            for (i in 0 until SNOW_COLS) {
                val lean = 1f + wind * 0.8f * (i.toFloat() / SNOW_COLS - 0.5f) * 2f
                val melt = (if (inten < 0.25f) 0.16f else 0f) + dawn * 0.45f
                val leanC = if (lean > 0.2f) lean else 0.2f
                val d = (bank[i] + inten * inten * 0.5f * leanC - melt)
                    .coerceIn(2f, SNOW_MAXD.toFloat())
                bank[i] = d.toInt().toFloat()
                if (track[i] > 0) track[i]--
            }
            for (i in 1 until SNOW_COLS - 1) {
                bankTmp[i] = ((bank[i - 1] + bank[i] * 2f + bank[i + 1]) / 4f).toInt().toFloat()
            }
            for (i in 1 until SNOW_COLS - 1) bank[i] = bankTmp[i]
        }

        val fall = 0.55f + inten * 1.5f
        for (i in 0 until SNOW_N) {
            val layer = flkLayer[i]
            val sp = if (layer == 0) {
                1.7f
            } else if (layer == 1) {
                1.05f
            } else {
                0.6f
            }
            flkY[i] += sp * fall * ds
            flkX[i] += wxWind * (1.5f + (2 - layer) * 0.5f) * (0.4f + inten) * ds
            if (flkY[i] > yBotI.toFloat()) {
                flkY[i] = 0f
                flkX[i] = rnd.nextFloat() * VW
            }
            if (flkX[i] < -3f) {
                flkX[i] = VW + 2f
            } else if (flkX[i] > VW + 3f) {
                flkX[i] = -2f
            }
        }

        for (i in 0 until TREE_FAR) {
            farX[i] -= WORLD_SPD * PL_FAR * ds
            if (farX[i] < -20f) {
                farX[i] = VW + 18f
                farH[i] = rnd.nextInt(18, 28)
            }
        }
        for (i in 0 until TREE_ACT) {
            actX[i] -= WORLD_SPD * PL_ACT * ds
            if (actX[i] < -34f) {
                actX[i] = VW + 32f
                actH[i] = rnd.nextInt(52, 70)
                actLoad[i] = rnd.nextInt(0, 60)
            }
            var ld = actLoad[i] + (inten * 1.7f * ds).toInt()
            if (ld > 232 && rnd.nextInt(100) < 3) ld = rnd.nextInt(0, 40)
            if (inten < 0.2f) ld -= (0.6f * ds).toInt()
            actLoad[i] = ld.coerceIn(0, 255)
        }
        for (i in 0 until PROP_N) {
            propX[i] -= WORLD_SPD * PL_ACT * ds
            if (propX[i] < -30f) {
                propX[i] = VW + 28f
                propKind[i] = rnd.nextInt(3)
            }
        }
        lodgeX -= WORLD_SPD * PL_FAR * ds
        if (lodgeX < -60f) lodgeX = VW + 90f + rnd.nextInt(0, 900)
        nearX -= WORLD_SPD * PL_NEAR * ds
        if (nearX < -60f) nearX = VW + 60f + rnd.nextInt(0, 700)
        iglooX -= WORLD_SPD * PL_ACT * ds
        if (iglooX < -40f) iglooX = VW + 60f + rnd.nextInt(0, 240)

        stepRiders(nowMs, ds)
        stepChase(nowMs, ds)

        var alongside = false
        for (k in 0 until RIDER_N) {
            if (ridLive[k] && abs(ridX[k] - dogX) < 46f) alongside = true
        }
        dogX += (if (alongside) 0.80f else 0.30f) * ds
        if (dogX > VW + 34f) dogX = -34f - rnd.nextInt(0, 1500)
        hareX -= (if (chaseState != CH_NONE) 1.7f else 0.55f) * ds
        if (hareX < -30f) hareX = VW + 30f + rnd.nextInt(0, 2200)
        if (birdAt != 0L && nowMs - birdAt >= 2600L) birdAt = 0L
    }

    private fun stepRiders(nowMs: Long, ds: Float) {
        for (k in 0 until RIDER_N) {
            if (!ridLive[k]) {
                if (nowMs >= ridNext[k]) {
                    ridLive[k] = true
                    ridX[k] = -26f
                    ridAir[k] = 0f
                    ridVy[k] = 0f
                    ridTrick[k] = 0
                    ridCrashAt[k] = 0L
                    ridKind[k] = if (rnd.nextInt(100) < 40) 1 else 0
                    ridVar[k] = rnd.nextInt(3)
                }
                continue
            }
            if (ridCrashAt[k] != 0L) {
                if (nowMs - ridCrashAt[k] > CRASH_TUMBLE + CRASH_HOLD) ridCrashAt[k] = 0L
                continue
            }
            val wasX = ridX[k]
            ridX[k] += ((if (ridKind[k] == 1) 0.70f else 0.82f) + k * 0.06f) * ds
            if (ridAir[k] > 0f || ridVy[k] != 0f) {
                ridVy[k] += 0.105f * ds
                ridAir[k] -= ridVy[k] * ds
                if (ridTrick[k] >= 3) ridTrick[k] = 3 + ((ridAir[k].toInt() / 7) and 3)
                if (ridAir[k] <= 0f) {
                    val odds = if (ridTrick[k] >= 3) 34 else 20
                    ridAir[k] = 0f
                    ridVy[k] = 0f
                    if (rnd.nextInt(100) < odds) {
                        ridCrashAt[k] = nowMs
                        birdAt = nowMs
                        birdX = ridX[k]
                        birdY = gAt(ridX[k].toInt()).toFloat() - 26f
                    }
                    ridTrick[k] = 0
                }
            } else {
                for (i in 0 until PROP_N) {
                    if (propKind[i] != 2) continue
                    val px = propX[i]
                    if (wasX <= px && ridX[k] >= px - 1f) {
                        ridVy[k] = -3.4f
                        ridAir[k] = 0.1f
                        ridTrick[k] = rnd.nextInt(1, 4)
                        birdAt = nowMs
                        birdX = ridX[k]
                        birdY = gAt(ridX[k].toInt()).toFloat() - 30f
                        break
                    }
                }
            }
            val c = (ridX[k] * SNOW_COLS / VW).toInt()
            if (c >= 0 && c < SNOW_COLS && ridAir[k] < 2f) track[c] = 26
            if (ridX[k] > VW + 28f) {
                ridLive[k] = false
                ridNext[k] = nowMs + rnd.nextInt(5000, 15000)
                if (chaseTgt == k) chaseTgt = -1
            }
        }
    }

    private fun stepChase(nowMs: Long, ds: Float) {
        val age = nowMs - chaseAt
        when (chaseState) {
            CH_NONE -> {
                var pick = -1
                if (nowMs >= chaseNext && dawn < 0.15f) {
                    for (k in 0 until RIDER_N) {
                        if (ridLive[k] && ridX[k] < VW * 0.45f && (pick < 0 || ridX[k] > ridX[pick])) {
                            pick = k
                        }
                    }
                }
                if (pick >= 0) {
                    chaseTgt = pick
                    val r = rnd.nextInt(100)
                    chaseEnd = when {
                        r < 30 -> END_GIVEUP
                        r < 48 -> END_WINDED
                        r < 66 -> END_TRIP
                        r < 84 -> END_BEATEN
                        else -> END_EAT
                    }
                    chaseState = CH_RUN
                    chaseAt = nowMs
                    yetiX = ridX[pick] - 74f
                }
            }

            CH_RUN -> {
                if (chaseTgt < 0) {
                    chaseState = CH_LEAVE
                    chaseAt = nowMs
                    return
                }
                val fast = chaseEnd == END_EAT || chaseEnd == END_TRIP || chaseEnd == END_BEATEN
                yetiX += (if (fast) 1.05f else 0.66f) * ds
                val tg = ridX[chaseTgt]
                if (chaseEnd == END_WINDED && age > 2600L) {
                    chaseState = CH_RESOLVE
                    chaseAt = nowMs
                } else if (!ridLive[chaseTgt] || yetiX > VW + 34f) {
                    chaseState = CH_LEAVE
                    chaseAt = nowMs
                } else if (fast && yetiX > tg - 13f) {
                    if (chaseEnd == END_EAT) {
                        ridLive[chaseTgt] = false
                        ridNext[chaseTgt] = nowMs + rnd.nextInt(9000, 20000)
                        eatAt = nowMs
                    }
                    chaseState = CH_RESOLVE
                    chaseAt = nowMs
                }
            }

            CH_RESOLVE -> {
                if (chaseEnd == END_BEATEN && age < 900L) yetiX -= 0.9f * ds
                val total = if (chaseEnd == END_EAT) EAT_TOTAL else 1900L
                if (age > total) {
                    chaseState = CH_LEAVE
                    chaseAt = nowMs
                }
            }

            else -> {
                yetiX -= 1.5f * ds
                if (yetiX < -50f) {
                    chaseState = CH_NONE
                    chaseTgt = -1
                    chaseNext = nowMs + rnd.nextInt(16000, 34000)
                }
            }
        }
    }

    override fun DrawScope.draw(nowMs: Long) {
        val s = size.width / VW
        if (s <= 0f) return
        sc = s
        nowMsCache = nowMs
        vh = size.height / s
        yBotI = (vh - 1f).toInt()
        if (!initDone) init(nowMs)
        if (!laidOut) {
            for (i in 0 until SNOW_N) flkY[i] = rnd.nextFloat() * vh
            laidOut = true
        }
        val w = size.width
        val ridgeY = yBotI * 52 / 100
        val bandH = yBotI

        val wl = inten * 70f
        var tr = 8f + wl
        var tg = 10f + wl
        var tb = 96f + inten * 44f
        var br = 54f + wl
        var bg = 190f - wl / 3f
        var bb = 190f + inten * 40f
        if (dawn > 0.01f) {
            tr += dawn * 120f
            tg += dawn * 40f
            tb += dawn * 60f
            br += dawn * 210f
            bg = bg * (1f - dawn * 0.30f) + dawn * 40f
            bb *= 1f - dawn * 0.70f
        }
        val skyTop = rgbClamp(tr, tg, tb)
        val skyLow = rgbClamp(br, bg, bb)
        val haze = skyLow

        val bands = 96
        val bandHf = vh / bands
        for (i in 0 until bands) {
            val c = bpkBlend(skyTop, skyLow, i * 256 / (bands - 1))
            drawRect(
                bpkColor(c),
                Offset(0f, i * bandHf * s),
                Size(w, bandHf * s + 1f),
            )
        }

        val au = (1f - dawn) * (1f - inten * 0.85f) * (0.45f + 0.55f * sin(nowMs / 41000f))
        if (au > 0.05f) aurora(bandH, nowMs, au, skyTop, skyLow)

        stars(ridgeY, nowMs, skyTop, skyLow)
        clouds(nowMs, skyTop, skyLow)
        satellite(nowMs, skyTop, skyLow)
        moon(skyTop, skyLow, bandH)

        val farSnow = bpkBlend(FAR_SNOW, bpkBlend(haze, WHITE, 160), 70)
        if (yBotI > ridgeY) r(0, ridgeY, VW_INT, yBotI - ridgeY, farSnow)

        val lx = lodgeX.toInt()
        if (lx > -40 && lx < VW_INT + 40) lodge(lx, ridgeY + 2)
        for (i in 0 until TREE_FAR) {
            val px = farX[i].toInt()
            pine(px, ridgeY + 2, farH[i], inten > 0.4f, haze, 150, wind * 1.1f, 0)
        }

        val snowLit = bpkBlend(WHITE, VAPOR_BLUE, (16f + inten * 26f).toInt())
        val snowDim = bpkBlend(snowLit, VAPOR_BLUE, 74)
        val trackC = bpkBlend(snowDim, TRACK_C, 150)
        val colW = (VW_INT + SNOW_COLS - 1) / SNOW_COLS
        for (i in 0 until SNOW_COLS) {
            val x0 = i * VW_INT / SNOW_COLS
            val gy = gAt(x0 + colW / 2)
            if (gy >= yBotI) continue
            r(x0, gy, colW, 3, snowLit)
            if (yBotI - gy > 3) r(x0, gy + 3, colW, yBotI - gy - 3, snowDim)
            if (track[i] > 0) r(x0, gy + 4, colW, 2, trackC)
        }

        for (i in 0 until PROP_N) {
            val px = propX[i].toInt()
            if (px < -30 || px > VW_INT + 30) continue
            prop(px, gAt(px) + 2, propKind[i], snowLit, snowDim)
        }
        val ix = iglooX.toInt()
        if (ix > -30 && ix < VW_INT + 30) igloo(ix, gAt(ix) + 2, nowMs, snowLit, snowDim)
        for (i in 0 until TREE_ACT) {
            val px = actX[i].toInt()
            if (px < -34 || px > VW_INT + 34) continue
            pine(px, gAt(px) + 2, actH[i], inten > 0.4f, haze, 30, wind * 3.4f, actLoad[i])
        }

        for (k in 0 until RIDER_N) {
            if (!ridLive[k]) continue
            val sx = ridX[k].toInt()
            if (sx < -28 || sx > VW_INT + 28) continue
            val gy = gAt(sx) - ridAir[k].toInt()
            if (ridCrashAt[k] != 0L) {
                wreck(sx, gAt(sx), nowMs - ridCrashAt[k], ridKind[k], ridVar[k])
                continue
            }
            val cs = sin(ridX[k] * 0.06f + k * 1.7f)
            val carve = if (cs > 0f) 1 else -1
            if (ridAir[k] < 1f) spray(sx, gAt(sx), carve, abs(cs), nowMs)
            if (ridKind[k] == 1) {
                boarder(sx, gy, carve, ridTrick[k], ridVar[k])
            } else {
                skier(sx, gy, carve, ridTrick[k], ridVar[k])
            }
        }

        chaseDraw(nowMs)

        val npx = nearX.toInt()
        if (npx > -60 && npx < VW_INT + 60) {
            pine(npx, yBotI + 6, 96, inten > 0.4f, haze, 0, wind * 5f, 190)
        }

        wildlife(nowMs, skyLow)

        val live = (SNOW_N * (0.18f + 0.82f * inten)).toInt().coerceIn(0, SNOW_N)
        for (i in 0 until live) {
            val layer = flkLayer[i]
            val c = bpkBlend(
                skyLow,
                WHITE,
                if (layer == 0) {
                    235
                } else if (layer == 1) {
                    165
                } else {
                    105
                },
            )
            val fx = flkX[i].toInt()
            val fy = flkY[i].toInt()
            if (layer == 0) r(fx, fy, 2, 2 + (abs(wind) * 3f).toInt(), c) else r(fx, fy, 1, 1, c)
        }
    }

    private fun skyAt(y: Int, top: Int, low: Int, bandH: Int): Int {
        var k = y * 255 / (if (bandH > 1) bandH else 1)
        if (k < 0) k = 0
        if (k > 255) k = 255
        return bpkBlend(top, low, k)
    }

    private fun DrawScope.aurora(bandH: Int, nowMs: Long, strength: Float, top: Int, low: Int) {
        val yStart = 0
        val ty = yStart + 3
        val hgt = bandH * 32 / 100
        if (hgt < 10) return
        for (rb in 0 until 2) {
            val ph = nowMs / (2600f + rb * 900f) + rb * 2.3f
            val th = hgt - rb * (hgt / 4)
            val cy = ty + rb * (hgt / 5)
            var row = 0
            while (row < th) {
                val y = cy + row
                if (y >= yStart + 1 && y < yStart + bandH) {
                    val sway = sin(row * 0.16f + ph) * 44f + sin(row * 0.07f - ph * 0.7f) * 30f
                    val cx = VW_INT / 2 + sway.toInt()
                    val half = 52 + (sin(row * 0.11f + ph * 1.4f) * 26f).toInt()
                    var x0 = cx - half
                    var x1 = cx + half
                    if (x0 < 0) x0 = 0
                    if (x1 > VW_INT) x1 = VW_INT
                    if (x1 - x0 >= 2) {
                        var f = 1f - abs(row - th * 0.5f) / (th * 0.5f)
                        f *= f
                        var a = (f * strength * 190f).toInt() + AURORA_DITH[y and 3]
                        if (a > 3) {
                            if (a > 255) a = 255
                            val lo = row.toFloat() / th
                            val hue = rgbClamp(40f + lo * 190f, 255f - lo * 60f, 120f - lo * 60f)
                            val sky = skyAt(y, top, low, bandH)
                            r(x0, y, x1 - x0, 1, bpkBlend(sky, hue, a))
                        }
                    }
                }
                row++
            }
        }
    }

    private fun DrawScope.stars(ridgeY: Int, nowMs: Long, top: Int, low: Int) {
        val vis = (1f - inten) * (1f - dawn)
        for (i in 0 until 34) {
            var h = (i + 1) * HASH_A
            h = h xor (h ushr 15)
            h *= HASH_B
            h = h xor (h ushr 13)
            val hu = h.toUInt()
            val sx = (hu % VW_U).toInt()
            val sy = 3 + ((hu shr 11) % 52u).toInt()
            if (sy >= ridgeY - 4) continue
            val tw = 0.45f + 0.55f * sin(
                nowMs / (620f + (hu % 900u).toFloat()) + (hu % 628u).toFloat() * 0.01f,
            )
            val c = bpkBlend(skyAt(sy, top, low, yBotI), WHITE, (230f * vis * tw).toInt())
            r(sx, sy, 1, 1, c)
            if ((hu and 7u) == 0u) {
                r(sx + 1, sy, 1, 1, c)
                r(sx, sy + 1, 1, 1, c)
            }
        }
    }

    private fun DrawScope.clouds(nowMs: Long, top: Int, low: Int) {
        for (c in 0 until 2) {
            val sp = 0.0035f + c * 0.0030f
            val cy = 11 + c * 21
            val cw2 = 78 + c * 22
            val cx = fmod(nowMs * sp + c * 240f, VW + 220f).toInt() - 110
            if (cx < -cw2 || cx > VW_INT + cw2) continue
            val sk = skyAt(cy, top, low, yBotI)
            val body = bpkBlend(sk, BLACK, 78)
            val topL = bpkBlend(sk, WHITE, 34)
            for (row in 0 until 5) {
                val rw = cw2 * CLOUD_W[row] / 100
                r(cx - rw / 2 + CLOUD_O[row], cy + row * 3, rw, 4, if (row == 0) topL else body)
            }
            val under = rgbClamp(255f, 206f - dawn * 60f, 150f)
            r(cx - (cw2 * 58) / 200 + 6, cy + 15, cw2 * 58 / 100, 2, bpkBlend(sk, under, 105))
        }
    }

    private fun DrawScope.satellite(nowMs: Long, top: Int, low: Int) {
        val sp = fmod(nowMs / 26000f, 1f)
        if (sp >= 0.80f || dawn >= 0.5f) return
        val sx = (sp * VW * 1.25f).toInt() - 20
        val sy = 8 + (sp * 30f).toInt()
        if (sx < 0 || sx >= VW_INT) return
        val on = ((nowMs / 430L) and 1L) == 0L
        r(sx, sy, 1, 1, bpkBlend(skyAt(sy, top, low, yBotI), WHITE, if (on) 255 else 130))
        if (on) r(sx, sy - 1, 1, 1, bpkBlend(skyAt(sy, top, low, yBotI), WHITE, 90))
    }

    private fun DrawScope.moon(top: Int, low: Int, bandH: Int) {
        if (dawn >= 0.92f) return
        val mx = (VW * 0.16f).toInt()
        val my = 30 + (dawn * 46f).toInt()
        val moon = bpkBlend(MOON, top, (dawn * 200f).toInt())
        val skyHere = skyAt(my, top, low, bandH)
        rc(mx, my, 15, moon)
        val mare = bpkBlend(moon, skyHere, 74)
        rc(mx - 5, my - 4, 5, mare)
        rc(mx - 1, my - 7, 3, mare)
        rc(mx + 4, my + 3, 4, mare)
        rc(mx + 1, my + 6, 3, mare)
        rc(mx - 7, my + 3, 2, mare)
        val pit = bpkBlend(moon, skyHere, 120)
        val rim = bpkBlend(moon, WHITE, 90)
        for (i in 0 until 5) {
            val cr = MOON_CRATERS
            rc(mx + cr[i * 3], my + cr[i * 3 + 1], cr[i * 3 + 2], pit)
            rc(mx + cr[i * 3] - 1, my + cr[i * 3 + 1] - 1, cr[i * 3 + 2] - 1, rim)
        }
        val ring = Stroke(width = sc.coerceAtLeast(0.8f))
        drawCircle(bpkColor(bpkBlend(moon, skyHere, 60)), 15f * sc, Offset(mx * sc, my * sc), style = ring)
        drawCircle(bpkColor(bpkBlend(moon, skyHere, 30)), 14f * sc, Offset(mx * sc, my * sc), style = ring)
    }

    private fun DrawScope.lodge(x: Int, ridgeY: Int) {
        val beam = bpkBlend(c565(0x51C5), hazeOf(), 60)
        val beamD = bpkBlend(c565(0x3123), hazeOf(), 60)
        val roof = bpkBlend(c565(0xEF9F), hazeOf(), 40)
        val roofE = bpkBlend(c565(0xB5F9), hazeOf(), 40)
        val frame = bpkBlend(c565(0x28E3), hazeOf(), 50)
        val lit = 1f - dawn * 0.85f
        r(x - 17, ridgeY - 13, 34, 13, beam)
        r(x - 17, ridgeY - 13, 34, 1, beamD)
        r(x - 12, ridgeY - 22, 24, 9, beam)
        for (i in 0 until 3) r(x - 17, ridgeY - 10 + i * 4, 34, 1, beamD)
        r(x - 20, ridgeY - 17, 40, 4, roof)
        r(x - 20, ridgeY - 18, 40, 2, bpkBlend(roof, WHITE, 120))
        r(x - 20, ridgeY - 13, 40, 1, roofE)
        r(x - 15, ridgeY - 26, 30, 4, roof)
        r(x - 15, ridgeY - 27, 30, 2, bpkBlend(roof, WHITE, 120))
        r(x - 15, ridgeY - 22, 30, 1, roofE)

        for (i in 0 until 5) {
            val h = hash32(i)
            val hu = h.toUInt()
            val per = 6200f + (hu % 11000u).toFloat()
            var k = 0.55f + 0.45f * sin(nowOf() / per + (hu % 628u).toFloat() * 0.01f)
            if ((hu and 3u) == 0u) k = 0.74f + 0.26f * sin(nowOf() / 340f)
            val slot = (nowOf() / 21000L).toInt() + i * 7
            var hs = slot * HASH_B
            hs = hs xor (hs ushr 13)
            val out = (hs.toUInt() % 100u) < 22u
            val b = if (out) 0.06f else k
            val glow = rgbClamp(
                255f * lit,
                (150f + 90f * b) * lit * b + 30f,
                60f * b * lit,
            )
            val wx = x + LODGE_WX[i]
            val wy = ridgeY + LODGE_WY[i]
            r(wx - 1, wy - 1, 7, 7, frame)
            r(wx, wy, 5, 5, bpkBlend(beam, glow, (60f + b * 195f).toInt()))
            if (b > 0.55f && !out) r(wx - 1, wy + 5, 7, 2, bpkBlend(beam, glow, (b * 90f).toInt()))
        }
        r(x + 12, ridgeY - 8, 5, 8, frame)
        r(x + 13, ridgeY - 7, 3, 7, bpkBlend(beam, c565(0xFDEB), (170f * lit).toInt()))
        r(x + 11, ridgeY, 8, 2, bpkBlend(roof, c565(0xFE4D), (110f * lit).toInt()))
        r(x - 9, ridgeY - 32, 4, 7, beamD)
        r(x - 10, ridgeY - 33, 6, 2, bpkBlend(roof, WHITE, 90))
        for (i in 0 until 4) {
            val ag = fmod(nowOf() / 700f + i * 0.9f, 4f)
            val sx = x - 7 + (sin(ag * 1.3f) * 5f + ag * 2.4f).toInt()
            val sy = ridgeY - 35 - (ag * 5f).toInt()
            if (sy < 2) continue
            r(sx, sy, 2 + ag.toInt(), 2, bpkBlend(hazeOf(), WHITE, (90f - ag * 18f).toInt()))
        }
    }

    private fun DrawScope.igloo(x: Int, gy: Int, nowMs: Long, lit: Int, dim: Int) {
        val radius = 12
        val hh = 13
        val edge = bpkBlend(dim, c565(0x4A69), 110)
        for (i in 0 until hh) {
            val d = hh - i
            var hw = 0
            while ((hw + 1) * (hw + 1) * hh * hh <= radius * radius * (hh * hh - d * d + d)) hw++
            r(x - hw - 1, gy - hh + i, 2 * hw + 2, 1, edge)
            if (hw > 0) r(x - hw, gy - hh + i, 2 * hw, 1, lit)
        }
        r(x - 3, gy - hh - 1, 6, 1, edge)
        var row = 0
        var by = gy - 4
        while (by > gy - hh + 2) {
            val d = gy - by
            var hw = 0
            while ((hw + 1) * (hw + 1) * hh * hh <= radius * radius * (hh * hh - d * d)) hw++
            if (hw > 2) r(x - hw + 1, by, 2 * hw - 2, 1, dim)
            var bx = x - hw + 3 + (row and 1) * 3
            while (bx < x + hw - 1) {
                r(bx, by - 3, 1, 3, dim)
                bx += 6
            }
            by -= 4
            row++
        }
        val dx = x - 3
        r(dx - 4, gy - 6, 9, 6, c565(0x18A2))
        r(dx - 3, gy - 7, 7, 1, c565(0x18A2))
        r(dx - 2, gy - 8, 5, 1, c565(0x18A2))
        val blink = (nowMs % 3700L) < 160L
        for (e in 0 until 2) {
            val ex = dx - 3 + e * 4
            val ey = gy - 5
            if (blink) {
                r(ex, ey + 1, 3, 1, WHITE)
                continue
            }
            r(ex, ey, 3, 2, WHITE)
            r(ex + 1, ey + 1, 1, 1, BLACK)
        }
    }

    private fun DrawScope.prop(px: Int, gy: Int, kind: Int, snowLit: Int, snowDim: Int) {
        when (kind) {
            0 -> {
                r(px - 7, gy - 8, 15, 8, c565(0x5B4E))
                r(px - 5, gy - 10, 9, 3, c565(0x7C52))
            }

            1 -> {
                r(px - 4, gy - 8, 9, 8, c565(0x61E3))
                r(px - 4, gy - 9, 9, 2, c565(0x82C5))
            }

            else -> {
                for (k in 0 until 14) r(px - 7 + k, gy - 1 - k, 1, k + 2, snowLit)
                r(px + 5, gy - 15, 3, 3, snowDim)
            }
        }
    }

    private fun DrawScope.pine(x: Int, y: Int, h: Int, snowy: Boolean, haze: Int, mix: Int, bend: Float, load: Int) {
        if (h < 8) return
        val deep = bpkBlend(c565(0x09E1), haze, mix)
        val dk = bpkBlend(c565(0x0AE1), haze, mix)
        val md = bpkBlend(c565(0x1BE2), haze, mix)
        val lt = bpkBlend(c565(0x3503), haze, mix)
        val tip = bpkBlend(c565(0x6645), haze, mix)
        val bark = bpkBlend(c565(0x61E3), haze, mix)
        val snow = bpkBlend(c565(0xEFBF), haze, mix)
        val w = h * 58 / 100
        val tw = if (h > 40) 6 else 4
        val barkH = h * 22 / 100
        r(x - tw / 2, y - barkH, tw, barkH, bark)
        r(x - tw / 2, y - barkH, tw / 2, barkH, bpkBlend(bark, BLACK, 70))

        if (h < 34) {
            for (ti in 0 until 3) {
                val bw = w * CHEAP_WID[ti] / 100
                val by = y - h * CHEAP_BASE[ti] / 100
                val th = h * 36 / 100
                for (tierRow in 0 until 4) {
                    val rw = bw - bw * tierRow * 88 / 400
                    val ry = by - th * tierRow / 4
                    if (rw < 2) continue
                    r(x - rw / 2, ry - th / 4 - 1, rw, th / 4 + 2, if (tierRow < 2) dk else md)
                }
            }
            return
        }

        for (ti in 0 until 3) {
            val bw = w * TIER_WID[ti] / 100
            val by = y - h * TIER_BASE[ti] / 100
            val th = h * 38 / 100
            val bx = x + (bend * (ti + 1) * 0.8f).toInt()
            val core = if (ti == 0) {
                deep
            } else if (ti == 1) {
                dk
            } else {
                md
            }
            val face = if (ti == 0) {
                dk
            } else if (ti == 1) {
                md
            } else {
                lt
            }
            val edge = if (ti == 2) tip else lt
            for (tierRow in 0 until 6) {
                val rw = bw * ROW_W[tierRow] / 100 - bw * tierRow * 62 / 600
                if (rw < 3) continue
                val ry = by - th * tierRow / 6
                val rh = th / 6 + 2
                val rx = bx - rw / 2
                r(rx, ry - rh, rw, rh, core)
                r(rx, ry - rh, rw * 46 / 100, rh, face)
                if (rw > 7) r(rx, ry - rh, 2, rh, edge)
            }
            if (bw > 9) r(bx - bw / 2 + 2, by - 1, bw - 4, 1, deep)
            if (snowy && bw > 9) {
                val l = 60 + load * 195 / 255
                r(bx - bw / 2 + 1, by - 3, bw * 30 * l / 25500, 2, snow)
                r(bx - bw * 8 / 100, by - 4, bw * 26 * l / 25500, 2, snow)
                r(bx + bw * 22 / 100, by - 3, bw * 22 * l / 25500, 2, snow)
                if (load > 150) {
                    r(bx - bw / 2 + 3, by - 5, bw * 34 / 100, 1, snow)
                    r(bx + bw * 4 / 100, by - 6, bw * 24 / 100, 1, snow)
                }
            }
        }
        val lx = x + (bend * 3.2f).toInt()
        r(lx - 1, y - h - 2, 3, 5, lt)
        r(lx - 1, y - h - 2, 1, 5, tip)
        if (load > 110) r(lx - 1, y - h - 3, 3, 1, snow)
    }

    private fun DrawScope.skier(x: Int, y: Int, carve: Int, trick: Int, varI: Int) {
        val l = carve * 2
        val ski = c565(0xFE83)
        val boot = c565(0x2146)
        val skin = c565(0xF611)
        val pant: Int
        val coat: Int
        val coatL: Int
        val arm: Int
        val hat: Int
        when (varI) {
            1 -> {
                coat = c565(0xE163)
                coatL = c565(0xFC6D)
                pant = c565(0x1905)
                hat = c565(0xFE83)
                arm = c565(0xFE2C)
            }

            2 -> {
                coat = c565(0xEF7E)
                coatL = WHITE
                pant = c565(0x91FA)
                hat = c565(0x2D83)
                arm = c565(0x3A09)
            }

            else -> {
                coat = c565(0x2A7A)
                coatL = c565(0x7D1F)
                pant = c565(0x2D83)
                hat = c565(0xE101)
                arm = c565(0xE2B3)
            }
        }
        val pole = c565(0x3A09)
        if (trick >= 3) {
            val f = trick - 3
            val cy = y - 16
            r(x - 7, cy - 7, 15, 14, pant)
            r(x - 5, cy - 5, 11, 8, coat)
            val sx1 = x + SKI_FLIP[f * 4] - 1
            val sy1 = cy + SKI_FLIP[f * 4 + 1] - 1
            val sx2 = x + SKI_FLIP[f * 4 + 2] - 1
            val sy2 = cy + SKI_FLIP[f * 4 + 3] - 1
            r(sx1, sy1, if ((f and 1) != 0) 3 else 11, if ((f and 1) != 0) 11 else 3, ski)
            r(sx2, sy2, if ((f and 1) != 0) 3 else 11, if ((f and 1) != 0) 11 else 3, ski)
            r(x - 3, cy - 9, 7, 4, hat)
            return
        }
        if (trick == 1) {
            r(x - 10, y - 6, 13, 2, ski)
            r(x - 3, y - 9, 13, 2, ski)
            r(x - 5, y - 14, 11, 7, pant)
        } else if (trick == 2) {
            r(x - 13, y - 4, 12, 2, ski)
            r(x + 2, y - 4, 12, 2, ski)
            r(x - 6, y - 14, 12, 7, pant)
        } else {
            r(x - 9 + l * 2, y - 2, 12, 2, ski)
            r(x - 2 + l * 2, y, 12, 2, ski)
            r(x - 4 + l, y - 8, 4, 6, boot)
            r(x + 1 + l, y - 8, 4, 6, boot)
            r(x - 5 + l, y - 15, 10, 8, pant)
        }
        r(x - 5, y - 24, 11, 10, coat)
        r(x - 5, y - 24, 11, 3, coatL)
        if (trick == 2) {
            r(x - 12, y - 25, 7, 4, arm)
            r(x + 6, y - 25, 7, 4, arm)
        } else {
            r(x - 9, y - 23, 4, 7, arm)
            r(x + 6, y - 23, 4, 7, arm)
        }
        r(x - 3, y - 30, 7, 6, skin)
        r(x - 2, y - 28, 2, 2, c565(0x3943))
        r(x + 1, y - 28, 2, 2, c565(0x3943))
        r(x - 4, y - 34, 9, 4, hat)
        r(x - 4, y - 31, 9, 2, c565(0xF7BF))
        r(x - 1, y - 37, 3, 3, WHITE)
        if (trick == 0) {
            ln(x - 8, y - 21, x - 13, y - 1, 1, pole)
            ln(x + 9, y - 21, x + 13, y - 1, 1, pole)
        }
    }

    private fun DrawScope.boarder(x: Int, y: Int, carve: Int, trick: Int, varI: Int) {
        val l = carve * 2
        val skin = c565(0xF611)
        val boot = c565(0x18C4)
        val eye = c565(0x3943)
        val deck: Int
        val deckD: Int
        val coat: Int
        val coatL: Int
        val pant: Int
        val beanie: Int
        when (varI) {
            1 -> {
                deck = c565(0x2657)
                deckD = c565(0x140F)
                coat = c565(0x1905)
                coatL = c565(0x634F)
                pant = c565(0xCE5A)
                beanie = c565(0xFB02)
            }

            2 -> {
                deck = c565(0xFF45)
                deckD = c565(0xB4A1)
                coat = c565(0xE163)
                coatL = c565(0xFC6D)
                pant = c565(0x2146)
                beanie = c565(0xF79E)
            }

            else -> {
                deck = c565(0xFB02)
                deckD = c565(0xB1C1)
                coat = c565(0x91FA)
                coatL = c565(0xC47F)
                pant = c565(0x2146)
                beanie = c565(0xFE83)
            }
        }
        if (trick >= 3) {
            val f = trick - 3
            val cy = y - 17
            r(x - 7, cy - 7, 14, 14, coat)
            r(x - 7, cy - 7, 14, 3, coatL)
            r(x - 5, cy + 1, 10, 6, pant)
            r(
                x + BOARD_FLIP[f * 4],
                cy + BOARD_FLIP[f * 4 + 1],
                BOARD_FLIP[f * 4 + 2],
                BOARD_FLIP[f * 4 + 3],
                if ((f and 1) != 0) deckD else deck,
            )
            r(x - 3, cy - 11, 7, 4, beanie)
            return
        }
        r(x - 13 + l, y - 2, 27, 3, deck)
        r(x - 13 + l, y + 1, 27, 1, deckD)
        r(x - 15 + l, y - 4, 3, 4, deck)
        r(x + 13 + l, y - 4, 3, 4, deck)
        r(x - 9 + l, y - 7, 6, 5, boot)
        r(x + 4 + l, y - 7, 6, 5, boot)
        if (trick == 1) {
            r(x - 6, y - 15, 13, 8, pant)
            r(x - 6, y - 25, 13, 10, coat)
            r(x - 6, y - 25, 13, 3, coatL)
            r(x - 12, y - 16, 6, 4, coat)
            r(x + 7, y - 27, 5, 8, coat)
        } else if (trick == 2) {
            r(x - 6, y - 16, 13, 9, pant)
            r(x - 7, y - 26, 14, 11, coat)
            r(x - 7, y - 26, 14, 3, coatL)
            r(x - 13, y - 28, 7, 4, coat)
            r(x + 6, y - 20, 6, 4, coat)
        } else {
            r(x - 6 + l / 2, y - 16, 13, 10, pant)
            r(x - 7, y - 26, 14, 11, coat)
            r(x - 7, y - 26, 14, 3, coatL)
            r(x - 13, y - 25, 6, 4, coat)
            r(x + 7, y - 23, 6, 4, coat)
        }
        r(x - 4, y - 32, 9, 6, skin)
        r(x - 2, y - 30, 2, 2, eye)
        r(x + 2, y - 30, 2, 2, eye)
        r(x - 5, y - 36, 11, 5, beanie)
        r(x - 5, y - 37, 11, 2, bpkBlend(beanie, WHITE, 90))
    }

    private fun DrawScope.spray(x: Int, gy: Int, carve: Int, power: Float, nowMs: Long) {
        if (power < 0.45f) return
        val pw = bpkBlend(WHITE, c565(0xCF1E), 70)
        val n = 2 + (power * 2.6f).toInt()
        for (i in 0 until n) {
            val ph = fmod(nowMs / 78f + i * 2.1f, 5f)
            val dx = -carve * (3 + (ph * 2.4f).toInt())
            val dy = -1 - (ph * 1.05f).toInt() - (if ((i and 1) != 0) 1 else 0)
            val sz = if (ph < 2.2f) 2 else 1
            r(x + dx, gy + dy, sz, sz, if (i % 3 != 0) pw else WHITE)
        }
    }

    private fun DrawScope.wreck(x: Int, y: Int, age: Long, kind: Int, varI: Int) {
        val skin = c565(0xF611)
        val coat: Int
        val pant: Int
        val hat: Int
        var gear = c565(0xFE83)
        when (varI) {
            1 -> {
                coat = c565(0xE163)
                pant = c565(0x1905)
                hat = c565(0xFE83)
            }

            2 -> {
                coat = c565(0xEF7E)
                pant = c565(0x91FA)
                hat = c565(0x2D83)
            }

            else -> {
                coat = c565(0x2A7A)
                pant = c565(0x2D83)
                hat = c565(0xE101)
            }
        }
        if (kind == 1) gear = c565(0xFB02)
        val coatL = bpkBlend(coat, WHITE, 90)
        val pantD = bpkBlend(pant, BLACK, 80)
        val fly = if (age < 420L) age.toFloat() / 420f else 1f
        val d = (fly * 13f).toInt()
        val up = if (age < 420L) ((1f - fly) * 14f).toInt() else 0
        if (kind == 1) {
            r(x + 6 + d, y - 3 - up, 18, 3, gear)
            r(x + 6 + d, y - up, 18, 1, bpkBlend(gear, BLACK, 80))
        } else {
            r(x - 14 - d, y - 2, 14, 2, gear)
            r(x + 7 + d, y - 9 - up, 3, 13, gear)
        }
        r(x - 17 - d, y - 5, 8, 1, c565(0x3A09))
        r(x + 12 + d, y - 7 - up / 2, 1, 8, c565(0x3A09))
        if (age < 420L) {
            r(x - 9, y - 7, 18, 6, coat)
            r(x - 9, y - 7, 18, 2, coatL)
            r(x - 13, y - 4, 5, 3, pant)
            r(x + 8, y - 9, 6, 4, pantD)
            r(x - 4, y - 11, 7, 5, skin)
            r(x - 6 + (fly * 8f).toInt(), y - 20 - up, 8, 4, hat)
        } else {
            r(x - 8, y - 9, 15, 8, coat)
            r(x - 8, y - 9, 15, 2, coatL)
            r(x - 12, y - 4, 6, 3, pant)
            r(x + 6, y - 5, 6, 4, pant)
            r(x - 3, y - 16, 8, 7, skin)
            r(x - 2, y - 13, 2, 1, c565(0x3943))
            r(x + 2, y - 13, 2, 1, c565(0x3943))
            r(x - 4, y - 20, 9, 4, hat)
            if (age > 900L) r(x + 4, y - 22, 3, 7, coat)
        }
        for (i in 0 until 5) {
            val px = x - 12 + i * 6 + (fly * (if ((i and 1) != 0) 4f else -4f)).toInt()
            val py = y - 14 - ((1f - fly) * 10f).toInt() + (i % 3) * 4
            r(px, py, 2, 2, bpkBlend(WHITE, c565(0xC6BD), (fly * 160f).toInt()))
        }
    }

    private fun DrawScope.yeti(
        x: Int,
        y: Int,
        nowMs: Long,
        pose: Int,
        bulge: Int,
        holding: Boolean,
        holdDX: Int,
        holdDY: Int,
    ) {
        val fur = c565(0x94D5)
        val furHi = c565(0xCE9B)
        val furS = c565(0x6350)
        val mane = c565(0x7BF1)
        val head = c565(0x31A7)
        val limb = c565(0x2987)
        val eye = c565(0xF922)
        val tooth = c565(0xFE83)
        val claw = c565(0xEF7E)

        if (pose == YPOSE_DOWN) {
            val kick = (sin(nowMs / 90f) * 4f).toInt()
            r(x - 18, y - 12, 34, 10, fur)
            r(x - 18, y - 12, 34, 3, furHi)
            r(x + 14, y - 14, 14, 9, head)
            r(x + 12, y - 16, 6, 4, mane)
            r(x - 24, y - 10, 8, 4, limb)
            r(x - 22, y - 20 + kick, 4, 10, limb)
            r(x - 14, y - 22 - kick, 4, 11, limb)
            r(x + 18, y - 6, 6, 3, tooth)
            return
        }

        val bobT = if (pose == YPOSE_WINDED) 260f else 90f
        val bobA = if (pose == YPOSE_WINDED) 2.5f else 1.5f
        val bob = (sin(nowMs / bobT) * bobA).toInt()
        val b = y - bob
        val lean = when (pose) {
            YPOSE_WINDED -> 4
            YPOSE_RECOIL -> -6
            else -> 0
        }
        val hip = lean / 2
        val gait = if (pose == YPOSE_RUN) (sin(nowMs / 90f) * 4f).toInt() else 0
        r(x - 8 + hip, b - 17, 4, 15, limb)
        r(x + 4 + hip, b - 17, 4, 15, limb)
        r(x - 12 + hip + gait, b - 3 - (if (gait > 2) 1 else 0), 8, 3, limb)
        r(x + 4 + hip - gait, b - 3 - (if (gait < -2) 1 else 0), 8, 3, limb)

        val jy = b - 34
        fun body() {
            var yy = b - 38
            for (i in 0 until 6) {
                val extra = if (bulge > 0 && i >= 1 && i <= 4) bulge else 0
                r(x - YETI_GW[i] - extra + lean, yy, (YETI_GW[i] + extra) * 2, YETI_GH[i], fur)
                yy += YETI_GH[i]
            }
            r(x - 11 + lean, b - 33, 3, 14, furS)
            r(x + 6 + lean, b - 35, 4, 12, furHi)
        }
        fun arm(sd: Int) {
            val ex: Int
            val ey: Int
            val hx: Int
            val hy: Int
            if (holding || pose == YPOSE_EAT || pose == YPOSE_RECOIL) {
                ex = sd * 13
                ey = -46 + holdDY / 2
                hx = sd * 11 + 4 + holdDX
                hy = -56 + holdDY
            } else if (pose == YPOSE_WINDED) {
                ex = sd * 13
                ey = -30
                hx = sd * 11
                hy = -22 + (sin(nowMs / 260f) * 1.5f).toInt()
            } else {
                val ph = nowMs / 294f + sd * 2f
                val a = sin(ph)
                val c = sin(ph - 1.5f)
                ex = (sd * (15f + a * 6f)).toInt()
                ey = (-42f + a * 7f).toInt()
                hx = (sd * (17f + c * 10f)).toInt()
                hy = (-55f + c * 9f).toInt()
            }
            val sx = x + sd * 7 + lean
            val aex = x + ex + lean
            val aey = b + ey
            val ahx = x + hx + lean
            val ahy = b + hy
            ln(sx, jy, aex, aey, 7, fur)
            ln(sx, jy, aex, aey, 4, furS)
            ln(aex, aey, ahx, ahy, 5, limb)
            r(aex - 3, aey - 3, 6, 6, fur)
            r(ahx - 3, ahy - 2, 7, 5, limb)
            for (c in 0 until 3) {
                val e = if (c == 1) 1 else 0
                r(ahx - 4 + c * 3, ahy + 3 - e, 2, 4 + e, claw)
            }
        }
        body()
        arm(-1)
        arm(1)

        r(x - 11 + lean, b - 52, 22, 4, mane)
        r(x - 13 + lean, b - 50, 3, 7, mane)
        r(x + 10 + lean, b - 50, 3, 7, mane)
        r(x - 10 + lean, b - 49, 20, 13, head)
        r(x - 7 + lean, b - 45, 6, 4, eye)
        r(x + 1 + lean, b - 45, 6, 4, eye)
        r(x - 7 + lean, b - 46, 3, 2, eye)
        r(x + 4 + lean, b - 46, 3, 2, eye)
        val th = if (pose == YPOSE_EAT) {
            7
        } else if (pose == YPOSE_WINDED) {
            6
        } else {
            4
        }
        r(x - 7 + lean, b - 40, 14, th, tooth)
        for (i in 0 until 4) r(x - 4 + i * 3 + lean, b - 40, 1, th, head)
        if (pose == YPOSE_EAT) r(x - 7 + lean, b - 40 + th / 2, 14, 1, head)
    }

    private fun DrawScope.victim(x: Int, y: Int, age: Long, nowMs: Long, kind: Int, varI: Int) {
        if (age >= EAT_CHOMP) return
        val skin = c565(0xF611)
        val boot = c565(0x2146)
        var coat = c565(0x2A7A)
        var coatL = c565(0x7D1F)
        var pant = c565(0x2D83)
        var hat = c565(0xE101)
        var gear = c565(0xFE83)
        if (kind == 1) {
            coat = c565(0x91FA)
            coatL = c565(0xC47F)
            pant = c565(0x2146)
            hat = c565(0xFE83)
            gear = c565(0xFB02)
        }
        if (varI == 1) {
            coat = c565(0xE163)
            coatL = c565(0xFC6D)
            pant = c565(0x1905)
            hat = c565(0xFE83)
        } else if (varI == 2) {
            coat = c565(0xEF7E)
            coatL = WHITE
            pant = c565(0x91FA)
            hat = c565(0x2D83)
        }
        val b = y - (sin(nowMs / 90f) * 1.5f).toInt()
        var fight = 1f
        if (age >= EAT_RAISE && age < EAT_TURN) {
            fight = 0.18f
        } else if (age >= EAT_TURN) {
            fight = 0.55f
        }
        val kick = (sin(nowMs / 55f) * 3f * fight).toInt()
        val wav = (sin(nowMs / 70f) * 3f * fight).toInt()
        if (age < EAT_TURN) {
            val vx: Int
            val vy: Int
            if (age < EAT_GRAB) {
                vx = x + 15
                vy = b - 26
            } else if (age < EAT_RAISE) {
                val k = (age - EAT_GRAB).toFloat() / (EAT_RAISE - EAT_GRAB).toFloat()
                vx = x + 15 + (k * 6f).toInt()
                vy = b - 26 - (k * 22f).toInt()
            } else {
                vx = x + 21
                vy = b - 48
            }
            r(vx - 5, vy + 12, 4, 8 + kick, pant)
            r(vx + 1, vy + 12, 4, 8 - kick, pant)
            r(vx - 6, vy + 19 + kick, 5, 3, boot)
            r(vx + 1, vy + 19 - kick, 5, 3, boot)
            r(vx - 6, vy, 12, 13, coat)
            r(vx - 6, vy, 12, 3, coatL)
            r(vx - 10, vy + 2 - wav, 5, 6, coat)
            r(vx + 6, vy + 2 + wav, 5, 6, coat)
            r(vx - 3, vy - 7, 8, 7, skin)
            if (age < EAT_RAISE) {
                r(vx - 2, vy - 5, 2, 3, c565(0x3943))
                r(vx + 2, vy - 5, 2, 3, c565(0x3943))
            } else {
                r(vx - 2, vy - 4, 2, 1, c565(0x3943))
                r(vx + 2, vy - 4, 2, 1, c565(0x3943))
            }
            r(vx - 4, vy - 11, 10, 4, hat)
            return
        }
        val k = (age - EAT_TURN).toFloat() / (EAT_CHOMP - EAT_TURN).toFloat()
        if (k < 0.34f) {
            val vx = x + 19
            val vy = b - 46
            r(vx - 8, vy + 4, 13, 9, coat)
            r(vx - 8, vy + 4, 13, 3, coatL)
            r(vx + 4, vy + 9, 9, 7, pant)
            r(vx - 14, vy - 1, 8, 7, skin)
            r(vx - 17, vy - 3, 5, 6, hat)
            r(vx + 11, vy + 14 + kick, 5, 3, boot)
            r(vx - 5, vy + 12 + wav, 5, 5, coat)
            if (kind == 1) {
                r(vx + 13, vy + 12, 14, 3, gear)
            } else {
                r(vx + 13, vy + 11, 11, 2, gear)
                r(vx + 13, vy + 16, 11, 2, gear)
            }
            return
        }
        val adv = ((k - 0.34f) / 0.66f * 11f).toInt()
        val hx = x + 4 - adv
        val hy = b - 42
        r(hx + 2, hy, 6, 5, hat)
        r(hx + 7, hy + 2, 5, 5, skin)
        r(hx + 10, hy + 5, 11, 8, coat)
        r(hx + 10, hy + 5, 11, 2, coatL)
        r(hx + 12, hy + 3, 5, 4, coat)
        r(hx + 19, hy + 10, 9, 7, pant)
        r(hx + 25, hy + 15 + kick, 5, 4, boot)
        r(hx + 25, hy + 20 - kick, 5, 4, boot)
        if (kind == 1) {
            r(hx + 27, hy + 16, 15, 3, gear)
        } else {
            r(hx + 27, hy + 13 + kick, 11, 2, gear)
            r(hx + 27, hy + 21 - kick, 11, 2, gear)
        }
    }

    private fun DrawScope.chaseDraw(nowMs: Long) {
        if (chaseState == CH_NONE) return
        val yx = yetiX.toInt()
        if (yx <= -34 || yx >= VW_INT + 34) return
        var pose = YPOSE_RUN
        if (chaseState == CH_RESOLVE || (chaseState == CH_LEAVE && nowMs - chaseAt < 400L)) {
            pose = when (chaseEnd) {
                END_EAT -> YPOSE_EAT
                END_TRIP -> YPOSE_DOWN
                END_BEATEN -> YPOSE_RECOIL
                END_WINDED -> YPOSE_WINDED
                else -> YPOSE_RUN
            }
        }
        val gy = gAt(yx)
        if (pose == YPOSE_EAT) {
            val age = nowMs - eatAt
            val bulge = if (age < EAT_GULP) {
                0
            } else if (age < EAT_PAT) {
                3 + ((age - EAT_GULP) / 140L).toInt()
            } else {
                5
            }
            var hdx = 4
            var hdy = 24
            if (age < EAT_GRAB) {
                hdx = 2
                hdy = 24
            } else if (age < EAT_RAISE) {
                val d = age - EAT_GRAB
                val span = EAT_RAISE - EAT_GRAB
                hdy = 24 - (d * 16L / span).toInt()
                hdx = 2 + (d * 8L / span).toInt()
            } else if (age < EAT_TURN) {
                hdx = 10
                hdy = 8
            } else if (age < EAT_CHOMP) {
                val d = age - EAT_TURN
                val span = EAT_CHOMP - EAT_TURN
                hdx = 10 - (d * 9L / span).toInt()
                hdy = 8 - (d * 6L / span).toInt()
            } else {
                hdx = 1
                hdy = 2
            }
            val face = when {
                age < EAT_TURN -> YPOSE_RUN
                age < EAT_GULP -> YPOSE_EAT
                else -> YPOSE_RUN
            }
            yeti(yx, gy, nowMs, face, bulge, age < EAT_CHOMP, hdx, hdy)
            val vk = if (chaseTgt >= 0) ridKind[chaseTgt] else 0
            val vv = if (chaseTgt >= 0) ridVar[chaseTgt] else 0
            victim(yx, gy, age, nowMs, vk, vv)
            if (age >= EAT_GULP) {
                val py = gy - 26 + (sin(nowMs / 110f) * 2f).toInt()
                r(yx - 3, py, 8, 4, c565(0xEF7E))
            }
        } else {
            yeti(yx, gy, nowMs, pose, 0, false, 0, 0)
        }
        if (pose == YPOSE_RECOIL && chaseTgt >= 0 && ridLive[chaseTgt]) {
            val sx = ridX[chaseTgt].toInt()
            ln(sx + 4, gAt(sx) - 22, yx - 8, gy - 30, 1, c565(0x3A09))
        }
    }

    private fun DrawScope.wildlife(nowMs: Long, skyLow: Int) {
        val dx = dogX.toInt()
        if (dogX > -24f && dogX < VW + 24f) {
            val dy = gAt(dx)
            val dog = c565(0xB385)
            val dogD = c565(0x8263)
            val gait = (sin(nowMs / 95f) * 2f).toInt()
            r(dx - 8, dy - 10, 15, 7, dog)
            r(dx + 5, dy - 15, 8, 7, dog)
            r(dx + 11, dy - 16, 3, 4, dogD)
            r(dx + 12, dy - 11, 3, 2, c565(0x28E3))
            r(dx - 11, dy - 13, 4, 5, dogD)
            r(dx - 6, dy - 4 + gait, 3, 4, dogD)
            r(dx + 2, dy - 4 - gait, 3, 4, dogD)
        }
        val hx = hareX.toInt()
        if (hareX > -22f && hareX < VW + 22f) {
            val hop = (abs(sin(nowMs / 120f)) * (if (chaseState != CH_NONE) 9f else 4f)).toInt()
            val hy2 = gAt(hx) - hop
            r(hx - 5, hy2 - 6, 10, 5, WHITE)
            r(hx + 4, hy2 - 9, 5, 5, WHITE)
            r(hx + 6, hy2 - 14, 2, 6, WHITE)
            r(hx + 3, hy2 - 14, 2, 6, WHITE)
            r(hx - 6, hy2 - 3, 3, 3, WHITE)
            r(hx + 7, hy2 - 8, 2, 2, c565(0xB1E7))
        }
        if (birdAt != 0L && nowMs - birdAt < 2600L) {
            val ba = (nowMs - birdAt).toFloat() / 2600f
            for (i in 0 until 5) {
                val bx = (birdX + ba * (28f + i * 13f)).toInt()
                val by = (birdY - ba * (30f + i * 6f) + sin(nowMs / 90f + i) * 3f).toInt()
                if (bx < -6 || bx > VW_INT + 6 || by < 0) continue
                val bc = bpkBlend(skyLow, BLACK, (190f * (1f - ba)).toInt())
                val flap = if (((nowMs / 110L).toInt() + i) and 1 != 0) 1 else -1
                r(bx - 3, by, 3, 1, bc)
                r(bx + 1, by, 3, 1, bc)
                r(bx - 3, by - flap, 2, 1, bc)
                r(bx + 2, by - flap, 2, 1, bc)
            }
        }
    }

    private fun gAt(x: Int): Int {
        var c = x * SNOW_COLS / VW_INT
        if (c < 0) {
            c = 0
        } else if (c >= SNOW_COLS) {
            c = SNOW_COLS - 1
        }
        return yBotI - bank[c].toInt() - ((VW_INT - x) * SNOW_RISE) / VW_INT
    }

    private fun hazeOf(): Int = rgbClamp(
        54f + inten * 70f + dawn * 210f,
        (190f - inten * 70f / 3f) * (1f - dawn * 0.30f) + dawn * 40f,
        (190f + inten * 40f) * (1f - dawn * 0.70f),
    )

    private fun nowOf(): Long = nowMsCache

    private var nowMsCache = 0L

    private fun DrawScope.r(x: Int, y: Int, w: Int, h: Int, c: Int) {
        if (w <= 0 || h <= 0) return
        drawRect(bpkColor(c), Offset(x * sc, y * sc), Size(w * sc + 0.5f, h * sc + 0.5f))
    }

    private fun DrawScope.rc(cx: Int, cy: Int, radius: Int, c: Int) {
        if (radius <= 0) return
        drawCircle(bpkColor(c), radius * sc, Offset(cx * sc, cy * sc))
    }

    private fun DrawScope.ln(x1: Int, y1: Int, x2: Int, y2: Int, width: Int, c: Int) {
        drawLine(
            bpkColor(c),
            Offset(x1 * sc, y1 * sc),
            Offset(x2 * sc, y2 * sc),
            strokeWidth = width * sc,
        )
    }

    private fun rgbClamp(r: Float, g: Float, b: Float): Int = bpkRgb(
        r.toInt().coerceIn(0, 255),
        g.toInt().coerceIn(0, 255),
        b.toInt().coerceIn(0, 255),
    )

    private companion object {
        const val VW = BPK_VIRTUAL_W
        const val VW_INT = 320
        val VW_U: UInt = 320u
        const val DEFAULT_VH = 240f
        const val WHITE = 0xFFFFFF
        const val BLACK = 0x000000

        val VAPOR_BLUE = bpkRgb565(0x067F)
        val FAR_SNOW = bpkRgb565(0xC6BD)
        val TRACK_C = bpkRgb565(0x7D19)
        val MOON = bpkRgb565(0xEF9F)

        const val SNOW_COLS = 40
        const val SNOW_N = 56
        const val TREE_FAR = 6
        const val TREE_ACT = 5
        const val PROP_N = 4
        const val SNOW_MAXD = 24
        const val SNOW_RISE = 26
        const val DAWN_CYCLE = 210000L
        const val WORLD_SPD = 0.34f
        const val PL_FAR = 0.22f
        const val PL_ACT = 1.0f
        const val PL_NEAR = 2.40f

        const val RIDER_N = 3
        const val CRASH_TUMBLE = 420L
        const val CRASH_HOLD = 1500L

        const val CH_NONE = 0
        const val CH_RUN = 1
        const val CH_RESOLVE = 2
        const val CH_LEAVE = 3

        const val END_GIVEUP = 0
        const val END_WINDED = 1
        const val END_TRIP = 2
        const val END_BEATEN = 3
        const val END_EAT = 4

        const val YPOSE_RUN = 0
        const val YPOSE_EAT = 1
        const val YPOSE_WINDED = 2
        const val YPOSE_DOWN = 3
        const val YPOSE_RECOIL = 4

        const val EAT_GRAB = 300L
        const val EAT_RAISE = 760L
        const val EAT_TURN = 1720L
        const val EAT_CHOMP = 2140L
        const val EAT_GULP = 2440L
        const val EAT_PAT = 2980L
        const val EAT_TOTAL = EAT_PAT

        const val HASH_A = -1640531535
        const val HASH_B = -2048144777

        val ROW_W = intArrayOf(100, 86, 94, 72, 78, 52)
        val TIER_BASE = intArrayOf(20, 46, 70)
        val TIER_WID = intArrayOf(100, 78, 52)
        val CHEAP_BASE = intArrayOf(22, 48, 72)
        val CHEAP_WID = intArrayOf(100, 74, 46)

        val LODGE_WX = intArrayOf(-13, -3, 7, -8, 3)
        val LODGE_WY = intArrayOf(-9, -9, -9, -20, -20)

        val CLOUD_W = intArrayOf(46, 74, 100, 84, 58)
        val CLOUD_O = intArrayOf(-6, 4, 0, -8, 6)

        val MOON_CRATERS = intArrayOf(6, -6, 3, -8, -1, 2, 2, -2, 2, 8, 6, 2, -3, 8, 2)

        val SKI_FLIP = intArrayOf(
            -11, -2, 11, 2,
            -2, -11, 2, 11,
            11, 2, -11, -2,
            2, 11, -2, -11,
        )
        val BOARD_FLIP = intArrayOf(
            -14, 4, 28, 4,
            -10, -12, 20, 22,
            -14, -8, 28, 4,
            -9, -12, 18, 22,
        )

        val YETI_GW = intArrayOf(8, 10, 11, 11, 10, 8)
        val YETI_GH = intArrayOf(3, 3, 4, 5, 4, 3)

        val AURORA_DITH = intArrayOf(-7, 3, 7, -3)
    }
}

private fun c565(v: Int): Int = bpkRgb565(v)

private fun fmod(a: Float, b: Float): Float {
    val r = a % b
    return if (r < 0f) r + b else r
}

private fun posMod(v: Long, m: Long): Long {
    val r = v % m
    return if (r < 0) r + m else r
}

private fun hash32(i: Int): Int {
    var h = (i + 3) * -1640531535
    h = h xor (h ushr 15)
    h *= -2048144777
    h = h xor (h ushr 13)
    return h
}
