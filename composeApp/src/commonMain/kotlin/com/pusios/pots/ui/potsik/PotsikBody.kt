package com.pusios.pots.ui.potsik

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The character's live state for one frame.
 *
 * Everything here is something the firmware's `tick()` computes and then the
 * renderer reads back: the mood he is in, how deep into the squash he is, the
 * emote he is striking, the phase of the walk. The renderer itself keeps no
 * state of its own, which is what lets the same character be drawn in the
 * chat, in the tile and in the settings screen from one set of numbers.
 */
internal data class PotsikFrame(
    /** The clock the whole thing runs on, in milliseconds. */
    val nowMs: Long = 0L,

    /** What he is doing. */
    val mood: PotsikMood = PotsikMood.IDLE,

    /** The emote's pose, when the mood is [PotsikMood.ACT]. */
    val actPose: PotsikVisitPose = PotsikVisitPose.NONE,

    /** The reaction that goes with a startled mood. */
    val reactPose: PotsikReactPose = PotsikReactPose.STARTLED,

    /** How deep his head has sunk into his shoulders, in pixels. */
    val headDrop: Int = 0,

    /** The bob — how far the whole body is up or down, in pixels. */
    val bobOffset: Float = 0f,

    /** The sideways offset — the recoil, the flick, the backing away, in pixels. */
    val bodyOffset: Float = 0f,

    /**
     * How far across the screen he is walking, as a fraction of the room
     * there is: -1 is the left edge of it, 1 the right.
     *
     * The firmware sweeps him across the screen while he walks; the app
     * keeps him where he was put and sweeps him inside that spot's room
     * instead, so a dragged character does not wander off on his own.
     */
    val walkSweep: Float = 0f,

    /** How far the shades have slipped down his nose, in character units. */
    val shadeDrop: Int = 0,

    /** Which way he is reaching: 1 for the right, -1 for the left. */
    val reachDir: Int = 1,

    /** True while he is being carried, so his arms windmill. */
    val dangle: Boolean = false,

    /** The beat of the walk: 0 keeps walking, 1 sniffs, 2 looks up, 3 scratches. */
    val walkBeat: Int = 0,

    /** True while he is saying something, so his mouth flaps. */
    val talking: Boolean = false,

    /** Forces the mouth to flap even when he is not saying anything. */
    val forceTalking: Boolean = false,

    /** When the current stretch started. */
    val stretchStartMs: Long = 0L,

    /** When the current gum bubble started. */
    val gumStartMs: Long = 0L,

    /** True while the binoculars are up. */
    val binoc: Boolean = false,

    /** The types of the last three catches, for the juggling packets. */
    val recentTypes: List<PotsikCatchType> = emptyList(),

    /**
     * True while he is in the air, thrown by a finger that let go moving.
     *
     * While it is true the character is drawn at [thrownX] and [thrownY]
     * instead of at the place the layout put him.
     */
    val thrown: Boolean = false,

    /** Where he is while he is thrown: the centre of his body, in pixels. */
    val thrownX: Float = 0f,

    /** Where he is while he is thrown: the top of his head, in pixels. */
    val thrownY: Float = 0f,

    /** The wobble of a hard landing, in pixels. */
    val landingWobble: Float = 0f,

    /** The squash of the landing itself, in pixels. */
    val landingSquash: Float = 0f,
)

/** The kinds of thing the character has caught — the firmware's detection types. */
internal enum class PotsikCatchType(val color: Color) {
    AIRTAG(PotsikPalette.CYAN),
    FLOCK(PotsikPalette.VAPOR_PURPLE),
    CAMERA(PotsikPalette.PINK_ACCENT),
    TILE(PotsikPalette.VAPOR_YELLOW),
    PHONE(PotsikPalette.GREEN),
    WATCH(PotsikPalette.VAPOR_BLUE),
    UNKNOWN(PotsikPalette.WHITE),
}

/**
 * Where his limbs ended up this frame.
 *
 * The firmware keeps these in file statics, because the costumes hang their
 * props off them — a hat's brim sits on the head anchor, a coat's sleeve
 * follows the arm, a belt sits on the hips. The port keeps them in one small
 * mutable holder for the same reason.
 */
internal class PotsikLimbs {
    var armL0x = 0f
    var armL0y = 0f
    var armL1x = 0f
    var armL1y = 0f
    var armR0x = 0f
    var armR0y = 0f
    var armR1x = 0f
    var armR1y = 0f
    var footLx = 0f
    var footLy = 0f
    var footRx = 0f
    var footRy = 0f
}

/**
 * The character himself.
 *
 * This is the firmware's `drawBody()`, ported shape for shape: the same
 * rounded boxes, the same triangles, the same order they are drawn in, and
 * the same numbers in the same places. The order matters as much as the
 * shapes — the ears go behind the head so their roots do not draw a line
 * across his crown, the costume goes last so it lands on top of him, and the
 * arms are drawn before the face so a hand covering his eyes lands in front
 * of them.
 *
 * The whole character is 56 units tall at scale 1, with the origin at the top
 * of his head and y growing downwards, exactly as the firmware has it.
 */
internal fun DrawScope.drawPotsikCharacter(
    look: PotsikLook,
    frame: PotsikFrame,
    cx: Float,
    headTopY: Float,
    scale: Float,
) {
    val painter = PotsikPainter(
        scope = this,
        look = look,
        frame = frame,
        cx = cx,
        headTopY = headTopY,
        scale = scale,
    )
    painter.draw()
}

/**
 * The drawing pass itself.
 *
 * It is a class rather than a function because the firmware's renderer keeps
 * a dozen values between its steps — the arm positions, the foot positions,
 * the two fur tones — and a class keeps them in one place instead of
 * threading them through every call.
 */
internal class PotsikPainter(
    scope: DrawScope,
    private val look: PotsikLook,
    private val frame: PotsikFrame,
    private val cx: Float,
    private val headTopY: Float,
    scale: Float,
) : PotsikCanvas(scope, scale) {

    private val limbs = PotsikLimbs()

    /** The arm paint queued this frame, by shoulder: 0 = left, 1 = right. */
    private val armOps: Array<MutableList<() -> Unit>> = arrayOf(mutableListOf(), mutableListOf())

    /** The body's centre line, which the reach pose can shift sideways. */
    private var cx2: Float = cx + frame.bodyOffset

    /** The hip anchor: the top of the torso, which the bob moves. */
    private var hy: Float = headTopY

    /** The head anchor: the head hangs off this, and it moves with the squash. */
    private var hh: Float = headTopY

    /** The keyline colour — the outline round every thick shape. */
    private val keyCol: Color = PotsikPalette.KEYLINE

    /** The rim thickness of the keyline. */
    private val kb: Float = S(1).coerceAtLeast(1f)

    private var furMain: Color = PotsikPalette.FUR_MAIN
    private var furLight: Color = PotsikPalette.FUR_LIGHT

    private val now: Long = frame.nowMs

    /** The whole thing. The order of the steps is the firmware's order. */
    fun draw() {
        val outfit = look.outfit

        // The emote's whole-body pose: a crouch, a bow, a hanging head.
        var crouch = 0f
        var actHead = 0f
        when (frame.actPose) {
            PotsikVisitPose.CROUCH -> {
                crouch = S(9)
                actHead = S(2)
            }

            PotsikVisitPose.BOW -> {
                crouch = S(3)
                actHead = S(7)
            }

            PotsikVisitPose.SAD -> actHead = S(3)

            PotsikVisitPose.HOWL -> actHead = -S(3)

            PotsikVisitPose.PULL -> cx2 -= frame.reachDir * S(4)

            else -> Unit
        }
        hy = headTopY + frame.bobOffset + crouch

        // The costume's own colours. The fur tones are kept before they are
        // changed, because the costumes that dress the body need the head and
        // the hands back in his own yellow afterwards.
        val furMain0 = furMain
        val furLight0 = furLight
        outfit.bodyColor?.let { furMain = it }
        outfit.bellyColor?.let { furLight = it }
        if (outfit == PotsikOutfit.OVER9000) {
            furLight = furLight0
        }

        // The costume layers that belong behind him.
        drawWings()
        drawTanookiTail()

        // The masks: what the costume hides is not drawn at all.
        val robeEnd = if (outfit == PotsikOutfit.YZZERD) {
            val hem = hy + S(48)
            if (frame.mood != PotsikMood.WALK && hem > hy + S(49) - crouch) hy + S(49) - crouch else hem
        } else {
            -30000f
        }
        val feetHidden = outfit == PotsikOutfit.YZZERD || outfit == PotsikOutfit.OVER9000
        val torsoHidden = outfit == PotsikOutfit.OVER9000 ||
            outfit == PotsikOutfit.SPACE ||
            outfit == PotsikOutfit.PLUMBER ||
            outfit == PotsikOutfit.TALLBRO ||
            outfit == PotsikOutfit.CAPTAIN

        // The keyline round the head and the torso.
        if (outfit != PotsikOutfit.VOIDEYE) {
            keyRR(cx2 - S(13), hy + frame.headDrop + actHead - S(1), S(26), S(26), S(5))
        }
        if (robeEnd < hy + S(42)) {
            keyRR(cx2 - S(torsoHalf() + 1), hy + S(22), S(2 * torsoHalf() + 2), S(20), S(6))
        }

        // The legs and the feet.
        val legCol = when (outfit) {
            PotsikOutfit.PLUMBER, PotsikOutfit.TALLBRO -> rgb565(0x2255)
            PotsikOutfit.SPACE -> rgb565(0xDEDF)
            else -> furMain
        }
        drawLegsAndFeet(crouch, robeEnd, legCol, feetHidden)

        // Behind the torso: the rune rings and the far half of the wizard's orbit.
        if (outfit == PotsikOutfit.YZZERD) drawYzBack()

        // The torso.
        if (!torsoHidden) {
            fillRoundRect(cx2 - S(torsoHalf()), hy + S(23), S(2 * torsoHalf()), S(18), S(5), furMain)
        }

        // The base clothes: the tee, the shorts, the sneakers.
        if (outfit.wearsBaseClothes) {
            drawBaseClothes(legCol)
        }

        // The costume's own torso garments.
        drawTorsoGarment(crouch)

        // The arms. They come before the face on purpose: a hand that covers
        // his eyes has to land in front of them. The arm chain only queues
        // its strokes; flushArms() paints each arm with its own tee sleeve.
        drawArms(crouch)
        flushArms(outfit.wearsBaseClothes)

        // The parka's hood shell, around the head.
        if (outfit == PotsikOutfit.PARKA) drawParkaHood(actHead)

        // The head group. Everything from here hangs off hh rather than hy,
        // because the squash sinks the head into the shoulders while the
        // torso keeps its own anchor. That difference is the whole effect.
        hh = hy + frame.headDrop + actHead

        // The costume's head layers, and then his own fur back for the head.
        when (outfit) {
            PotsikOutfit.PARKA -> {
                furMain = PotsikPalette.FUR_MAIN
                furLight = PotsikPalette.FUR_LIGHT
            }

            PotsikOutfit.YZZERD -> {
                drawYzBehindHead()
                furMain = furMain0
                furLight = furLight0
            }

            PotsikOutfit.OVER9000 -> {
                drawO9BehindHead()
                furMain = furMain0
                furLight = furLight0
            }

            PotsikOutfit.TH3_0N3 -> {
                drawT3BehindHead()
                furMain = furMain0
                furLight = furLight0
            }

            else -> Unit
        }

        // The stripes and the cuff are painted over the fur, so they follow
        // whatever pose he is in.
        if (outfit == PotsikOutfit.STRIPES) {
            drawPrisonStripes()
        }

        // The costume's head furniture, behind the head.
        if (outfit == PotsikOutfit.BLUEBLUR) drawBlueBlurQuills()
        if (outfit == PotsikOutfit.TANOOKI) drawTanookiEars()

        // The face.
        val hideFace = outfit == PotsikOutfit.VOIDEYE
        val noMouth = outfit == PotsikOutfit.PARKA
        if (!hideFace) {
            drawHead()
            drawCowlick()
            drawEars()
            drawCheeks()
            drawNose()
            drawFace()
            drawMouth(noMouth)
        }

        // The costume, on top of everything.
        scope.drawPotsikOutfit(look = look, frame = frame, hh = hh, limbs = limbs, cx2 = cx2, scale = scale)

        // The props.
        drawProps()
    }

    // ------------------------------------------------------------------
    // The body
    // ------------------------------------------------------------------

    private fun drawLegsAndFeet(crouch: Float, robeEnd: Float, legCol: Color, feetHidden: Boolean) {
        val mood = frame.mood
        if (frame.dangle) {
            // Carried: both legs straight down, kicking out of phase.
            val kp = (now % 260) / 260f * TAU
            val kL = (sin(kp) * S(4)).toInt()
            val kR = (sin(kp + PI) * S(4)).toInt()
            keyR(cx2 - S(10) + kL, hy + S(40), S(8), S(12))
            keyR(cx2 + S(2) + kR, hy + S(40), S(8), S(12))
            keyRR(cx2 - S(13) + kL, hy + S(51), S(12), S(6), 2f)
            keyRR(cx2 + S(1) + kR, hy + S(51), S(12), S(6), 2f)
            legFill(cx2 - S(10) + kL, hy + S(40), S(8), S(12), robeEnd, legCol)
            legFill(cx2 + S(2) + kR, hy + S(40), S(8), S(12), robeEnd, legCol)
            limbs.footLx = cx2 - S(13) + kL
            limbs.footLy = hy + S(51)
            limbs.footRx = cx2 + S(1) + kR
            limbs.footRy = hy + S(51)
            if (!feetHidden) {
                fillRoundRect(limbs.footLx, limbs.footLy, S(12), S(6), 2f, furLight)
                fillRoundRect(limbs.footRx, limbs.footRy, S(12), S(6), 2f, furLight)
            }
            return
        }
        if (mood == PotsikMood.WALK) {
            val legPhase = if (frame.walkBeat != 0) 0f else (now % 400) / 400f * TAU
            val legL = (sin(legPhase) * S(3)).toInt()
            val legR = (sin(legPhase + PI) * S(3)).toInt()
            legKey(cx2 - S(10), hy + S(40), S(8), S(10) + legL, robeEnd)
            legKey(cx2 + S(2), hy + S(40), S(8), S(10) + legR, robeEnd)
            keyRR(cx2 - S(13), hy + S(49) + legL, S(12), S(6), 2f)
            keyRR(cx2 + S(1), hy + S(49) + legR, S(12), S(6), 2f)
            legFill(cx2 - S(10), hy + S(40), S(8), S(10) + legL, robeEnd, legCol)
            legFill(cx2 + S(2), hy + S(40), S(8), S(10) + legR, robeEnd, legCol)
            limbs.footLx = cx2 - S(13)
            limbs.footLy = hy + S(49) + legL
            limbs.footRx = cx2 + S(1)
            limbs.footRy = hy + S(49) + legR
        } else {
            val legH = S(10) - crouch
            val footY = hy + S(49) - crouch
            legKey(cx2 - S(10), hy + S(40), S(8), legH, robeEnd)
            legKey(cx2 + S(2), hy + S(40), S(8), legH, robeEnd)
            keyRR(cx2 - S(13), footY, S(12), S(6), 2f)
            keyRR(cx2 + S(1), footY, S(12), S(6), 2f)
            legFill(cx2 - S(10), hy + S(40), S(8), legH, robeEnd, legCol)
            legFill(cx2 + S(2), hy + S(40), S(8), legH, robeEnd, legCol)
            limbs.footLx = cx2 - S(13)
            limbs.footLy = footY
            limbs.footRx = cx2 + S(1)
            limbs.footRy = footY
        }
        if (!feetHidden) {
            fillRoundRect(limbs.footLx, limbs.footLy, S(12), S(6), 2f, furLight)
            fillRoundRect(limbs.footRx, limbs.footRy, S(12), S(6), 2f, furLight)
        }
    }

    private fun drawBaseClothes(legCol: Color) {
        val th = torsoHalf()
        val teeC = PotsikPalette.CLOTH_COLORS[look.shirtColor.index]
        val teeDk = blend(teeC, PotsikPalette.BLACK, 90)
        val shrtC = PotsikPalette.CLOTH_COLORS[look.shortsColor.index]
        val shrtDk = blend(shrtC, PotsikPalette.BLACK, 90)

        // The tee: the shoulders down to the waist. Its top corners are
        // rounded, but the hem is a straight cut, so no fur shows under it.
        val teeTop = hy + S(23)
        val teeBot = teeTop + S(12)
        fillRoundRect(cx2 - S(th), teeTop, S(2 * th), S(12), S(3), teeC)
        fillRect(cx2 - S(th), teeBot - S(3), S(2 * th), S(3), teeC)
        fillRect(cx2 - S(th), hy + S(23), S(2 * th), S(1) + 1, teeDk)
        fillRect(cx2 - S(th), hy + S(34), S(2 * th), S(1) + 1, teeDk)

        // The shorts: the hips and the top of each leg, cut short so his own
        // fur shows between them and the feet. The top edge is square, so the
        // rounded corners do not show the torso under the tee's hem.
        fillRoundRect(cx2 - S(th), hy + S(35), S(2 * th), S(12), S(3), shrtC)
        fillRect(cx2 - S(th), hy + S(35), S(2 * th), S(3), shrtC)
        fillRect(cx2 - S(th), hy + S(35), S(2 * th), S(1) + 1, shrtDk)
        // A centre seam, so the shorts read as two legs rather than a skirt.
        fillRect(cx2 - 1, hy + S(38), 2f, S(9), shrtDk)

        // The sneakers, over the feet, wherever the current pose put them.
        val shoeC = PotsikPalette.CLOTH_COLORS[look.shoesColor.index]
        val shoeDk = blend(shoeC, PotsikPalette.BLACK, 70)
        for (k in 0..1) {
            val fx = if (k == 0) limbs.footLx else limbs.footRx
            val fy = if (k == 0) limbs.footLy else limbs.footRy
            fillRoundRect(fx - kb, fy - kb, S(12) + 2 * kb, S(6) + 2 * kb, 3f, keyCol)
            fillRoundRect(fx, fy, S(12), S(6), 2f, shoeC)
            fillRect(fx, fy + S(6) - S(2), S(12), S(2), shoeDk)
            drawFastHLine(fx + S(1), fy + S(1), S(4), shoeDk)
        }
    }

    /**
     * The tee's short sleeve on one arm (k = 0 left, 1 right), from the
     * shoulder down 30% of the arm, with a cuff line across its end.
     */
    private fun drawSleeve(k: Int) {
        val teeC = PotsikPalette.CLOTH_COLORS[look.shirtColor.index]
        val teeDk = blend(teeC, PotsikPalette.BLACK, 90)
        val sw = S(9)
        val x0 = if (k == 0) limbs.armL0x else limbs.armR0x
        val y0 = if (k == 0) limbs.armL0y else limbs.armR0y
        val x1 = if (k == 0) limbs.armL1x else limbs.armR1x
        val y1 = if (k == 0) limbs.armL1y else limbs.armR1y
        // Integer steps, as the firmware's (x1 - x0) * 3 / 10.
        val mx = x0 + ((x1 - x0) * 3f / 10f).toInt()
        val my = y0 + ((y1 - y0) * 3f / 10f).toInt()
        wideLine(x0, y0, mx, my, sw, teeC)
        val dx = (x1 - x0).toFloat()
        val dy = (y1 - y0).toFloat()
        val ln = sqrt(dx * dx + dy * dy)
        if (ln > 0.1f) {
            val px = -dy / ln
            val py = dx / ln
            wideLine(mx - px * sw * 0.5f, my - py * sw * 0.5f, mx + px * sw * 0.5f, my + py * sw * 0.5f, S(1), teeDk)
        }
    }

    private fun drawTorsoGarment(crouch: Float) {
        val th = torsoHalf()
        when (look.outfit) {
            PotsikOutfit.SPACE -> {
                fillRoundRect(cx2 - S(th), hy + S(23), S(2 * th), S(18), S(5), rgb565(0xE71D))
                fillRect(cx2 - S(th), hy + S(30), S(2 * th), S(2), rgb565(0x6BB0))
                fillRoundRect(cx2 - S(5), hy + S(33), S(10), S(6), 2f, rgb565(0x2967))
                val tick = ((now / 500) and 1L) != 0L
                fillRect(cx2 - S(3), hy + S(35), S(2), S(2), if (tick) rgb565(0x07F1) else rgb565(0x0240))
                fillRect(cx2 + S(1), hy + S(35), S(2), S(2), if (tick) rgb565(0x6800) else rgb565(0xF9E7))
            }

            PotsikOutfit.PLUMBER, PotsikOutfit.TALLBRO -> {
                val tall = look.outfit == PotsikOutfit.TALLBRO
                val shirt = if (tall) rgb565(0x05A0) else rgb565(0xD800)
                val den = rgb565(0x2255)
                val denDk = rgb565(0x012A)
                val denHi = rgb565(0x4C9F)
                val gold = rgb565(0xFEC0)
                fillRoundRect(cx2 - S(th), hy + S(23), S(2 * th), S(18), S(5), shirt)
                fillRoundRect(cx2 - S(th), hy + S(33), S(2 * th), S(8), S(4), den)
                fillRoundRect(cx2 - S(8), hy + S(27), S(16), S(10), S(2), den)
                fillRect(cx2 - S(9), hy + S(23), S(3), S(5), den)
                fillRect(cx2 + S(6), hy + S(23), S(3), S(5), den)
                fillRect(cx2 - S(8), hy + S(27), S(1).coerceAtLeast(1f), S(8), denHi)
                fillRect(cx2 + S(7), hy + S(28), S(1).coerceAtLeast(1f), S(12), denDk)
                fillRoundRect(cx2 - S(3), hy + S(29), S(6), S(4), 1f, denDk)
                for (sg in listOf(-1, 1)) {
                    val bx = cx2 + sg * S(6) - (if (sg > 0) S(1) else 0f)
                    fillCircle(bx, hy + S(28), S(1) + 1, PotsikPalette.BLACK)
                    fillCircle(bx, hy + S(28), S(1), gold)
                }
            }

            PotsikOutfit.CAPTAIN -> {
                val coat = rgb565(0xB120)
                val coatDk = rgb565(0x6800)
                val gold = rgb565(0xFEC0)
                fillRoundRect(cx2 - S(th), hy + S(23), S(2 * th), S(18), S(5), coat)
                fillTriangle(cx2 - S(5), hy + S(23), cx2 + S(5), hy + S(23), cx2, hy + S(32), PotsikPalette.WHITE)
                fillTriangle(cx2 - S(5), hy + S(23), cx2 - S(9), hy + S(23), cx2 - S(1), hy + S(33), coatDk)
                fillTriangle(cx2 + S(5), hy + S(23), cx2 + S(9), hy + S(23), cx2 + S(1), hy + S(33), coatDk)
                fillRect(cx2 - S(th), hy + S(35), S(2 * th), S(3), PotsikPalette.BLACK)
                fillRect(cx2 - S(2), hy + S(35), S(4), S(3), gold)
                fillRect(cx2 - S(1), hy + S(36), (S(2) - 1).coerceAtLeast(1f), 1f, PotsikPalette.BLACK)
                for (sg in listOf(-1, 1)) {
                    for (k in 0..1) {
                        fillCircle(cx2 + sg * S(6), hy + S(27) + k * S(4), S(1), gold)
                    }
                }
            }

            PotsikOutfit.TANOOKI -> {
                fillEllipse(cx2, hy + S(34), S(11), S(8), rgb565(0xEEF7))
            }

            PotsikOutfit.YZZERD -> drawYzRobe()

            PotsikOutfit.OVER9000 -> drawO9Gi()

            PotsikOutfit.SHAMBLER -> drawShamblerClothes()

            PotsikOutfit.TH3_0N3 -> drawT3Coat()

            PotsikOutfit.PARKA -> {
                val ink = rgb565(0x1040)
                val seam = rgb565(0x8A21)
                fillRoundRect(cx2 - S(torsoHalf() + 2), hy + S(21), S(2 * torsoHalf() + 4), S(24), S(7), ink)
                fillRoundRect(cx2 - S(torsoHalf() + 1), hy + S(22), S(2 * torsoHalf() + 2), S(22), S(6), rgb565(0xFC43))
                fillRect(cx2 - S(torsoHalf() - 1), hy + S(40), S(2 * torsoHalf() - 2), 1f, seam)
                fillRect(cx2 - 1, hy + S(24), 2f, S(16), seam)
                val bootC = rgb565(0x20C2)
                fillRoundRect(limbs.footLx - kb, limbs.footLy - kb, S(12) + 2 * kb, S(6) + 2 * kb, 2f, ink)
                fillRoundRect(limbs.footRx - kb, limbs.footRy - kb, S(12) + 2 * kb, S(6) + 2 * kb, 2f, ink)
                fillRoundRect(limbs.footLx, limbs.footLy, S(12), S(6), 2f, bootC)
                fillRoundRect(limbs.footRx, limbs.footRy, S(12), S(6), 2f, bootC)
            }

            else -> Unit
        }
    }

    // ------------------------------------------------------------------
    // The arms
    // ------------------------------------------------------------------

    /**
     * The YZZERD robe, over the torso and legs and under the arms: the
     * firmware's yzRobe. One copy a pixel bigger is the outline, the gold hem
     * and buttons go on top, and the slippers come last, toes turned out.
     */
    private fun drawYzRobe() {
        val th1 = S(torsoHalf() + 1)
        val top = hy + S(22)
        var hem = hy + S(48)
        // A crouch sinks the body onto feet that stay where they are, so the
        // hem stops at them rather than going through the floor.
        val lowFoot = maxOf(limbs.footLy, limbs.footRy)
        if (hem > lowFoot) hem = lowFoot
        val hw = S(17)
        fun body(g: Float, c: Color) {
            val tw = th1 + g
            val bw = hw + g
            fillTriangle(cx2 - tw, top - g, cx2 + tw, top - g, cx2 + bw, hem + g, c)
            fillTriangle(cx2 - tw, top - g, cx2 + bw, hem + g, cx2 - bw, hem + g, c)
        }
        body(1f, PotsikPalette.BLACK)
        body(0f, YZ_ROBE)
        if (hem > top) {
            val y0 = hem - S(3)
            val w0 = th1 + ((hw - th1) * (y0 - top)) / (hem - top)
            fillTriangle(cx2 - w0, y0, cx2 + w0, y0, cx2 + hw, hem, YZ_GOLD)
            fillTriangle(cx2 - w0, y0, cx2 + hw, hem, cx2 - hw, hem, YZ_GOLD)
            fillRect(cx2 - S(2), top + 1, S(4), hem - top - S(3), YZ_GOLD)
            for (k in 0 until 3) {
                fillRect(cx2 - S(1), hy + S(34) + k * S(4), S(2), S(2), YZ_DARK)
            }
            for (k in 1..3) {
                for (sg in listOf(-1, 1)) {
                    fillRect(cx2 + sg * k * S(4), hem - S(2), 1f, 1f, YZ_DARK)
                }
            }
        }
        for (k in 0..1) {
            val sg = if (k == 0) -1 else 1
            val fx = if (k == 0) limbs.footLx else limbs.footRx
            val fy = if (k == 0) limbs.footLy else limbs.footRy
            val out = if (k == 0) fx + S(1) else fx + S(12) - S(1)
            fillRoundRect(fx - 1, fy - 1, S(12) + 2, S(6) + 2, 3f, PotsikPalette.BLACK)
            fillTriangle(out, fy + S(1) - 1, out, fy + S(6), out + sg * (S(5) + 1), fy + S(4), PotsikPalette.BLACK)
            // The one-pixel black toe tick.
            fillRect(out + sg * S(5), fy + S(4) - 1, 1f, 3f, PotsikPalette.BLACK)
            fillRoundRect(fx, fy, S(12), S(6), 2f, YZ_DARK)
            fillTriangle(out, fy + S(1), out, fy + S(6) - 1, out + sg * S(5), fy + S(4), YZ_DARK)
        }
    }

    /**
     * YZZERD's back pass, drawn before the torso: the two counter-rotating
     * rune rings on the floor, the lit beads, the motes that lift off them,
     * the alarm ring, and the far half of his orbit.
     */
    private fun drawYzBack() {
        val mood = frame.mood
        val asleep = mood == PotsikMood.SLEEPY
        val alarm = mood == PotsikMood.SHOCKED
        val (mc, mh) = yzMagicFor(mood)
        val ground = maxOf(limbs.footLy, limbs.footRy) + S(6)
        // Asleep they stop; startled they turn three times as fast.
        val per = if (alarm) 2000L else 6000L
        val rot = if (asleep) 0f else (now % per).toFloat() / per * TAU
        val gy = ground - S(1)
        for (ring in 0..1) {
            val rx = if (ring == 0) S(27) else S(20)
            val ry = if (ring == 0) S(5) else S(3)
            val n = if (ring == 0) 12 else 8
            val col = if (ring == 0 || asleep || alarm) mc else YZ_GOLD
            for (i in 0 until n) {
                val a = (if (ring == 0) rot else -rot * 1.5f) + i * TAU / n
                val b = a + TAU / n * 0.55f
                wideLine(
                    cx2 + (cos(a) * rx).toInt(),
                    gy + (sin(a) * ry).toInt(),
                    cx2 + (cos(b) * rx).toInt(),
                    gy + (sin(b) * ry).toInt(),
                    2,
                    col,
                )
                // Every third gap on the outer ring holds a lit bead.
                if (ring == 0 && !asleep && i % 3 == 0) {
                    val c = a - TAU / n * 0.22f
                    val nx = cx2 + (cos(c) * rx).toInt()
                    val ny = gy + (sin(c) * ry).toInt()
                    fillCircle(nx, ny, S(1) + 1, mc)
                    fillRect(nx - 1, ny - 1, 2f, 2f, mh)
                }
            }
        }
        if (!asleep) {
            // Motes lifting off the ring and fading as they climb.
            for (i in 0 until 6) {
                val tt = now + i * 430L
                val ph = tt % 1700L
                val h = yzHash(((tt / 1700L) * 11L + i).toInt())
                val side = if (i and 1 == 1) 1 else -1
                val mx = cx2 + side * (S(18) + unsignedMod(h, S(9).toInt() + 1))
                val my = gy - unsignedMod(h ushr 8, S(3).toInt() + 1) - (ph * S(22).toInt() / 1700L).toInt()
                if (ph < 800L) {
                    drawFastHLine(mx - 2, my, 5, mc)
                    drawFastVLine(mx, my - 2, 5, mc)
                    fillRect(mx - 1, my - 1, 3f, 3f, mh)
                } else if (ph < 1400L) {
                    fillRect(mx - 1, my - 1, 3f, 3f, mc)
                } else {
                    fillRect(mx, my, 2f, 2f, mc)
                }
            }
        }
        if (alarm) {
            // A ring of light bursting outward along the floor.
            val ph = now % 500L
            val rx = (S(27) + (ph * S(12).toInt() / 500L).toInt()).toInt()
            drawEllipseOutline(cx2, gy, rx.toFloat(), (rx * 5 / 27).toFloat(), if (ph < 300L) mh else mc)
        }
        yzOrbitPass(cx2, hy, ground, now, mood, front = false)
    }

    /** The firmware's yzMagic: the colour of his magic and its highlight, by mood. */
    private fun yzMagicFor(m: PotsikMood): Pair<Color, Color> = when (m) {
        PotsikMood.SHOCKED -> YZ_ALARM to YZ_ALARMHI
        PotsikMood.SLEEPY -> YZ_DARK to YZ_SLEEVE
        else -> YZ_MAGIC to PotsikPalette.WHITE
    }

    /**
     * The firmware's yzOrbit. Called twice per frame: with front=false from
     * drawYzBack (the far half of the orbit, behind the torso) and with
     * front=true from the front pass (the near half, over him). Split by the
     * sign of sin, so the orb passes behind him and in front of him.
     */
    private fun yzOrbitPass(cx2: Float, hy: Float, ground: Float, now: Long, mood: PotsikMood, front: Boolean) {
        val (mc, mh) = yzMagicFor(mood)
        if (mood == PotsikMood.SLEEPY) {
            // Asleep, the orb comes down and rests by his foot.
            if (front) yzOrb((cx2 + S(22)).toInt(), (ground - S(4)).toInt(), S(4).toInt(), mc, mh)
            return
        }
        val per = if (mood == PotsikMood.SHOCKED) 1200L else 3600L
        val a0 = (now % per).toFloat() / per.toFloat() * TAU
        for (k in 4 downTo 0) {
            val a = a0 - k * 0.30f
            if ((sin(a) >= 0f) != front) continue
            val x = (cx2 + (cos(a) * S(26)).toInt()).toInt()
            val y = (hy + S(20) + (sin(a) * S(7)).toInt()).toInt()
            if (k == 0) {
                yzOrb(x, y, S(5).toInt(), mc, mh)
                // A glint going round inside it, and a halo that breathes.
                val g = (now % 900L).toFloat() / 900f * TAU
                fillRect(x + (cos(g) * S(2)).toInt(), y + (sin(g) * S(2)).toInt(), 2, 2, mh)
                if ((now / 300L) and 1L == 1L) drawCircleOutline(x, y, S(5) + 3, mc)
            } else {
                val r = if (k == 1) {
                    S(2)
                } else if (k == 2) {
                    S(1) + 1
                } else {
                    S(1)
                }
                fillCircle(x, y, r, if (k and 1 == 1) mc else mh)
            }
        }
        // The gold star going the other way round.
        val b = -((now % 2300L).toFloat() / 2300f) * TAU
        if ((sin(b) >= 0f) == front) {
            val x = (cx2 + (cos(b) * S(22)).toInt()).toInt()
            val y = (hy + S(41) + (sin(b) * S(4)).toInt()).toInt()
            yzStar(x, y, S(3).toInt() + 1, PotsikPalette.BLACK)
            yzStar(x, y, S(3).toInt(), YZ_GOLD)
        }
    }

    /**
     * OVER 9000's gi over the torso and legs, under the arms: the firmware's
     * o9Gi. The gi hangs from the hip anchor, the belt sits on it, and the
     * boots go over his feet with a red band round the top.
     */
    private fun drawO9Gi() {
        val top = hy + S(22)
        val by = hy + S(37)
        val th = torsoHalf()
        fillRoundRect(cx2 - S(th + 2) - 1, top - S(1) - 1, S(2 * th + 4) + 2, by - top + S(3) + 2, S(4), PotsikPalette.BLACK)
        fillRoundRect(cx2 - S(th + 2), top - S(1), S(2 * th + 4), by - top + S(3), S(4), O9_GI)
        // The undershirt in its V, and the lapels either side of it.
        fillTriangle(cx2 - S(7), top, cx2 + S(7), top, cx2, top + S(11), O9_BLUE)
        wideLine(cx2 - S(7), top, cx2 + S(1), top + S(12), S(1) + 1, O9_GIDK)
        wideLine(cx2 + S(7), top, cx2 - S(1), top + S(12), S(1) + 1, O9_GIDK)
        // The belt, with its black rules just outside the blue.
        fillRect(cx2 - S(th + 1), by, S(2 * th + 2), S(4), O9_BLUE)
        drawFastHLine(cx2 - S(th + 1), by - 1, S(2 * th + 2), PotsikPalette.BLACK)
        drawFastHLine(cx2 - S(th + 1), by + S(4), S(2 * th + 2), PotsikPalette.BLACK)
        inked(PotsikPalette.BLACK, O9_BLUE) { ox, oy, c ->
            fillRect(cx2 - S(4) + ox, by - S(1) + oy, S(5), S(6), c)
            fillTriangle(cx2 - S(4) + ox, by + S(4) + oy, cx2 - S(1) + ox, by + S(4) + oy, cx2 - S(6) + ox, by + S(12) + oy, c)
            fillTriangle(cx2 - S(2) + ox, by + S(4) + oy, cx2 + S(1) + ox, by + S(4) + oy, cx2 + S(1) + ox, by + S(11) + oy, c)
        }
        // The chest disc, its black ring and his own mark on it.
        val ex = cx2 + S(6)
        val ey = top + S(5)
        fillCircle(ex, ey, S(3) + 1, PotsikPalette.BLACK)
        fillCircle(ex, ey, S(3), PotsikPalette.WHITE)
        fillRect(ex - S(1), ey - S(2), S(1) + 1, S(4), PotsikPalette.BLACK)
        fillRect(ex - S(2), ey - S(1), S(4), S(1), PotsikPalette.BLACK)
        // Boots over his feet, with a red band round the top.
        for (k in 0..1) {
            val fx = if (k == 0) limbs.footLx else limbs.footRx
            val fy = if (k == 0) limbs.footLy else limbs.footRy
            fillRoundRect(fx - 1, fy - S(4) - 1, S(12) + 2, S(10) + 2, 3f, PotsikPalette.BLACK)
            fillRoundRect(fx, fy - S(4), S(12), S(10), 2f, O9_BLUE)
            fillRect(fx + 1, fy - S(4), S(12) - 2, S(2), O9_RED)
        }
    }

    /**
     * The short gi sleeve on his right arm, its hem torn in two places. The
     * firmware's o9Sleeve: a black outline, the gi, a darker torn band and
     * the bare fur at the ends.
     */
    private fun drawO9Sleeve() {
        val x0 = limbs.armR0x.toInt()
        val y0 = limbs.armR0y.toInt()
        val x1 = limbs.armR1x.toInt()
        val y1 = limbs.armR1y.toInt()
        yzAcross(x0, y0, x1, y1, -6, 44, S(5).toInt() + 1, S(5).toInt() + 1, PotsikPalette.BLACK)
        yzAcross(x0, y0, x1, y1, -4, 42, S(5).toInt(), S(5).toInt(), O9_GI)
        yzAcross(x0, y0, x1, y1, 34, 42, S(5).toInt(), S(5).toInt(), O9_GIDK)
        yzAcross(x0, y0, x1, y1, 32, 43, S(2).toInt(), 0, PotsikPalette.FUR_MAIN)
        yzAcross(x0, y0, x1, y1, 36, 43, 0, S(2).toInt(), PotsikPalette.FUR_MAIN)
    }

    /**
     * SHAMBLER's jeans and shirt, over the torso and legs and under the arms:
     * the firmware's shJeans and shShirt. The shirt is the one that is torn.
     */
    private fun drawShamblerClothes() {
        val th = torsoHalf()
        // The jeans, torn off ragged above his knees, a gap between his legs.
        val top = hy + S(36)
        val bot = hy + S(46)
        val x0 = cx2 - S(th)
        val w = S(2 * th)
        fillRect(x0 - 1, top, w + 2, bot - top + 1, PotsikPalette.BLACK)
        fillRect(x0, top, w, bot - top, SH_JEANS)
        fillTriangle(cx2 - S(2), bot, cx2 + S(2), bot, cx2, bot - S(4), SH_FUR)
        drawLine(cx2 - S(2), bot - 1, cx2, bot - S(4) - 1, PotsikPalette.BLACK)
        drawLine(cx2 + S(2), bot - 1, cx2, bot - S(4) - 1, PotsikPalette.BLACK)
        // The outer two quarters hang down in ragged points; the gap took the middle.
        for (i in listOf(0, 3)) {
            val ax = x0 + i * w / 4f
            val bx = x0 + (i + 1) * w / 4f
            val mx = (ax + bx) / 2f
            fillTriangle(ax, bot, bx, bot, mx, bot + S(3), SH_JEANS)
            drawLine(ax, bot, mx, bot + S(3), PotsikPalette.BLACK)
            drawLine(bx, bot, mx, bot + S(3), PotsikPalette.BLACK)
        }
        fillRect(x0 + S(2), top + S(2), S(1), bot - top - S(4), SH_JEANSDK)
        // A torn knee, and the mud he walked through.
        val kx = cx2 + S(5)
        val ky = hy + S(42)
        fillEllipse(kx, ky, S(3) + 1, S(2) + 1, PotsikPalette.BLACK)
        fillEllipse(kx, ky, S(3), S(2), SH_FUR)
        drawFastHLine(kx - S(3), ky - S(2), S(6), PotsikPalette.WHITE)
        fillCircle(cx2 - S(6), hy + S(41), S(2), SH_MUD)
        fillCircle(cx2 - S(4), hy + S(43), S(1), SH_MUD)

        // The shirt, its hem torn in four notches that show the jeans, and a
        // ragged hole low on his left side that shows the fur.
        val sTop = hy + S(21)
        val hem = hy + S(39)
        val sx0 = cx2 - S(th + 2)
        val sw = S(2 * th + 4)
        fillRoundRect(sx0 - 1, sTop - 1, sw + 2, hem - sTop + 2, S(4), PotsikPalette.BLACK)
        fillRoundRect(sx0, sTop, sw, hem - sTop, S(4), SH_SHIRT)
        val notches = arrayOf(intArrayOf(-10, -6, 5), intArrayOf(-3, 1, 3), intArrayOf(3, 8, 6), intArrayOf(9, 13, 3))
        for (n in notches) {
            val ax = cx2 + S(n[0])
            val bx = cx2 + S(n[1])
            val mx = (ax + bx) / 2f
            val my = hem - S(n[2])
            fillTriangle(ax, hem, bx, hem, mx, my, SH_JEANS)
            drawLine(ax, hem - 1, mx, my - 1, PotsikPalette.BLACK)
            drawLine(bx, hem - 1, mx, my - 1, PotsikPalette.BLACK)
        }
        val hx = cx2 - S(6)
        val hy2 = sTop + S(10)
        val rag = arrayOf(intArrayOf(-3, -2), intArrayOf(0, -4), intArrayOf(3, -1), intArrayOf(4, 2), intArrayOf(0, 4), intArrayOf(-4, 2))
        fun out1(v: Int): Float = (
            if (v > 0) {
                1
            } else if (v < 0) {
                -1
            } else {
                0
            }
            ).toFloat()
        for (i in 0 until 6) {
            val j = (i + 1) % 6
            fillTriangle(
                hx,
                hy2,
                hx + S(rag[i][0]) + out1(rag[i][0]),
                hy2 + S(rag[i][1]) + out1(rag[i][1]),
                hx + S(rag[j][0]) + out1(rag[j][0]),
                hy2 + S(rag[j][1]) + out1(rag[j][1]),
                PotsikPalette.BLACK,
            )
        }
        for (i in 0 until 6) {
            val j = (i + 1) % 6
            fillTriangle(hx, hy2, hx + S(rag[i][0]), hy2 + S(rag[i][1]), hx + S(rag[j][0]), hy2 + S(rag[j][1]), SH_FUR)
        }
        fillRect(sx0 + sw - S(3), sTop + S(3), S(2), hem - sTop - S(8), SH_SHIRTDK)
    }

    /**
     * TH3 0N3's coat, over the torso and legs and under the arms, with its
     * boots: the firmware's t3Coat. Flared, the hem stirs; the violet edge
     * holds the coat on black, the lapels fold back to show their lining,
     * and the back split shows the same lining.
     */
    private fun drawT3Coat() {
        val th = torsoHalf()
        val top = hy + S(22)
        var hem = hy + S(53)
        val lowFoot = maxOf(limbs.footLy, limbs.footRy)
        if (hem > lowFoot + S(2)) hem = lowFoot + S(2)
        val tw = S(th + 2)
        val hw = S(20) + (sin((now % 1800L) / 1800f * TAU) * S(1)).toInt().toFloat()
        fun body(g: Float, c: Color) {
            val tw2 = tw + g
            val bw = hw + g
            fillTriangle(cx2 - tw2, top - g, cx2 + tw2, top - g, cx2 + bw, hem + g, c)
            fillTriangle(cx2 - tw2, top - g, cx2 + bw, hem + g, cx2 - bw, hem + g, c)
        }
        body(1f, PotsikPalette.BLACK)
        body(0f, T3_COAT)
        drawLine(cx2 - tw, top + S(2), cx2 - hw, hem - S(1), T3_EDGE)
        drawLine(cx2 + tw, top + S(2), cx2 + hw, hem - S(1), T3_EDGE)

        // Open down the front: the black shirt between the lapels.
        fillTriangle(cx2 - S(5), top, cx2 + S(5), top, cx2, hy + S(38), PotsikPalette.BLACK)
        val lining = PotsikPalette.VAPOR_PURPLE
        for (sg in listOf(-1, 1)) {
            val ax = cx2 + sg * S(5)
            val ay = top
            val bx = cx2 + sg * S(1)
            val by = hy + S(37)
            val ox = cx2 + sg * S(10)
            val oy = top + S(9)
            fillTriangle(ax, ay - 1, ox + sg, oy, bx, by + 1, PotsikPalette.BLACK)
            fillTriangle(ax, ay, ox, oy, bx, by, lining)
            drawLine(ax, ay, bx, by, blend(lining, PotsikPalette.WHITE, 120))
        }
        // The split up the back from the hem shows the same lining.
        fillTriangle(cx2, hem - S(9), cx2 - S(3), hem, cx2 + S(3), hem, lining)
        drawLine(cx2, hem - S(9), cx2 - S(3), hem, PotsikPalette.BLACK)
        drawLine(cx2, hem - S(9), cx2 + S(3), hem, PotsikPalette.BLACK)
        drawLine(cx2, hy + S(38), cx2, hem - S(9), PotsikPalette.BLACK)
        // The boots.
        for (k in 0..1) {
            val fx = if (k == 0) limbs.footLx else limbs.footRx
            val fy = if (k == 0) limbs.footLy else limbs.footRy
            fillRoundRect(fx - 1, fy - S(2) - 1, S(12) + 2, S(8) + 2, S(2), PotsikPalette.BLACK)
            fillRoundRect(fx, fy - S(2), S(12), S(8), S(2), T3_COAT)
        }
    }

    /**
     * The firmware's yzAcross, through the canvas members, for the sleeves
     * and the bands. Every arm end is whole pixels by now, so this only
     * narrows the types.
     */
    private fun armAcross(x0: Float, y0: Float, x1: Float, y1: Float, u0: Int, u1: Int, h0: Int, h1: Int, c: Color) {
        yzAcross(x0.toInt(), y0.toInt(), x1.toInt(), y1.toInt(), u0, u1, h0, h1, c)
    }

    /**
     * The firmware's yzBehindHead sleeves, for the arms as they stand now:
     * the bell sleeve and its gold cuff on each arm.
     */
    private fun drawYzSleeves() {
        for (k in 0..1) {
            val x0 = if (k == 0) limbs.armL0x else limbs.armR0x
            val y0 = if (k == 0) limbs.armL0y else limbs.armR0y
            val x1 = if (k == 0) limbs.armL1x else limbs.armR1x
            val y1 = if (k == 0) limbs.armL1y else limbs.armR1y
            armAcross(x0, y0, x1, y1, 48, 88, S(4).toInt() + 1, S(6).toInt() + 1, PotsikPalette.BLACK)
            armAcross(x0, y0, x1, y1, 50, 78, S(4).toInt(), S(6).toInt() - 1, YZ_SLEEVE)
            armAcross(x0, y0, x1, y1, 78, 86, S(6).toInt() - 1, S(6).toInt(), YZ_GOLD)
        }
    }

    /**
     * The arm chain.
     *
     * An arm is one line from the shoulder to the hand — the firmware has no
     * elbow, and that is why the poses read as simple shapes rather than as
     * limbs with joints. Which line depends on what he is doing, and the
     * order of the branches matters: being carried beats the mood, the
     * binoculars beat the mood, and the mood beats everything else.
     */
    private fun drawArms(crouch: Float) {
        val mood = frame.mood
        val act = frame.actPose
        restArms(0, 0)

        if (frame.dangle) {
            // Both arms up and windmilling: he is being held by something
            // above him, so the arms go up whatever mood he was in.
            val fw = (sin((now % 220) / 220f * TAU) * S(6)).toInt()
            limbTo(cx2 - S(11), hy + S(26), cx2 - S(20), hy - S(2) + fw)
            limbTo(cx2 + S(11), hy + S(26), cx2 + S(20), hy - S(2) - fw)
            return
        }
        if (mood == PotsikMood.STRETCH) {
            // Both arms overhead, lengthening through the yawn and coming
            // back down as it ends.
            val se = if (now > frame.stretchStartMs) now - frame.stretchStartMs else 0
            val sk = sin(minOf(se.toFloat() / STRETCH_MS, 1f) * PI)
            limbTo(cx2 - S(11), hy + S(26), cx2 - S(13) - (S(6) * sk).toInt(), hy + S(26) - (S(33) * sk).toInt())
            limbTo(cx2 + S(11), hy + S(26), cx2 + S(13) + (S(6) * sk).toInt(), hy + S(26) - (S(33) * sk).toInt())
            return
        }
        if (mood == PotsikMood.JUGGLE) {
            // The hands alternate on the same cycle the packets use, so a
            // hand is always at the top of its travel as a packet leaves.
            val jw = (sin((now % 1200) / 1200f * TAU) * S(5)).toInt()
            limbTo(cx2 - S(11), hy + S(26), cx2 - S(19), hy + S(20) + jw)
            limbTo(cx2 + S(11), hy + S(26), cx2 + S(19), hy + S(20) - jw)
            return
        }
        if (mood == PotsikMood.ACT) {
            drawActArms()
            return
        }
        if (mood == PotsikMood.WAVE) {
            val wa = -1f + sin((now % 400) / 400f * TAU) * 0.5f
            val ex = cx2 + S(13) + cos(wa) * (18f * scale)
            val ey = hy + S(28) + sin(wa) * (18f * scale)
            limbTo(cx2 + S(11), hy + S(28), ex, ey)
            hangingArm(-1)
            return
        }
        if (mood == PotsikMood.SHOCKED) {
            val shakeT = (now % 140) / 140f * TAU
            val shake = (sin(shakeT) * S(2)).toInt()
            when (frame.reactPose) {
                PotsikReactPose.HANDS_UP -> {
                    limbTo(cx2 - S(11), hy + S(26), cx2 - S(14) + shake, hy - S(10))
                    limbTo(cx2 + S(11), hy + S(26), cx2 + S(14) + shake, hy - S(10))
                }

                PotsikReactPose.COVER_FACE -> {
                    // The crossing arms are drawn after the head, so they land
                    // in front of the face.
                }

                PotsikReactPose.POINT_SHADES, PotsikReactPose.DISGUST -> {
                    hangingArm(-1)
                }

                else -> {
                    limbTo(cx2 - S(11), hy + S(26), cx2 - S(23) + shake, hy + S(10))
                    limbTo(cx2 + S(11), hy + S(26), cx2 + S(23) + shake, hy + S(10))
                }
            }
            return
        }
        if (mood == PotsikMood.DANCE) {
            val daT = (now % 500) / 500f * TAU
            val armX = (sin(daT) * S(11)).toInt()
            limbTo(cx2 - S(14), hy + S(20), cx2 - S(14) + armX, hy + S(44), S(8))
            limbTo(cx2 + S(14), hy + S(20), cx2 + S(14) + armX, hy + S(44), S(8))
            return
        }
        if (mood == PotsikMood.WALK && frame.walkBeat == 1) {
            // Nose down, having a good sniff at whatever is on the floor.
            limbTo(cx2 - S(11), hy + S(26), cx2 - S(15), hy + S(42))
            limbTo(cx2 + S(11), hy + S(26), cx2 + S(15), hy + S(42))
            return
        }
        if (mood == PotsikMood.WALK && frame.walkBeat == 2) {
            restArms(0, 0)
            hangingArm(-1)
            limbTo(cx2 + S(11), hy + S(26), cx2 + S(5), hy + S(1))
            return
        }
        if (mood == PotsikMood.WALK && frame.walkBeat == 3) {
            // A scratch behind the ear, the hand buzzing.
            val bz = (sin(now / 45f) * S(2)).toInt()
            restArms(0, 0)
            hangingArm(-1)
            limbTo(cx2 + S(11), hy + S(26), cx2 + S(16) + bz, hy + S(11))
            return
        }
        if (mood == PotsikMood.WALK) {
            // Opposite arm, opposite leg, on the same phase the legs use.
            val legPhase = (now % 400) / 400f * TAU
            val armL = (sin(legPhase + PI) * S(4)).toInt()
            val armR = (sin(legPhase) * S(4)).toInt()
            restArms(armL, armR)
            hangingArm(-1, armL)
            hangingArm(1, armR)
            return
        }
        if (mood == PotsikMood.SLEEPY) {
            // Static on purpose: motion here would fight the tired read.
            hangingArm(-1)
            hangingArm(1)
            return
        }
        // The idle: a gentle continuous sway, so standing there never reads
        // as a frozen frame.
        val armPhase = (now % 1800) / 1800f * TAU
        val armSwingL = (sin(armPhase) * S(3)).toInt()
        val armSwingR = (sin(armPhase + PI) * S(3)).toInt()
        restArms(armSwingL, armSwingR)
        hangingArm(-1, armSwingL)
        hangingArm(1, armSwingR)
    }

    private fun drawActArms() {
        val sd = frame.reachDir
        val act = frame.actPose
        val beat = sin((now % 500) / 500f * TAU)
        when (act) {
            PotsikVisitPose.SALUTE -> {
                hangingArm(-sd)
                limbTo(cx2 + sd * S(11), hy + S(26), cx2 + sd * S(9), hy + S(3))
            }

            PotsikVisitPose.BOW -> {
                limbTo(cx2 - S(11), hy + S(26), cx2 + S(2), hy + S(36))
                limbTo(cx2 + S(11), hy + S(26), cx2 - S(2), hy + S(34))
            }

            PotsikVisitPose.HUG -> {
                limbTo(cx2 + sd * S(11), hy + S(26), cx2 + sd * S(27), hy + S(24))
                limbTo(cx2 - sd * S(11), hy + S(26), cx2 + sd * S(21), hy + S(31))
            }

            PotsikVisitPose.SAD -> {
                limbTo(cx2 - S(11), hy + S(26), cx2 - S(9), hy + S(44))
                limbTo(cx2 + S(11), hy + S(26), cx2 + S(9), hy + S(44))
            }

            PotsikVisitPose.GRR -> {
                val sh = (sin((now % 120) / 120f * TAU) * S(1)).toInt()
                limbTo(cx2 - S(11), hy + S(26), cx2 - S(21) + sh, hy + S(41))
                limbTo(cx2 + S(11), hy + S(26), cx2 + S(21) + sh, hy + S(41))
                val gl = cx2 - S(21) + sh
                val gr = cx2 + S(21) + sh
                queueArm(gl) { fillCircle(gl, hy + S(41), S(4), furLight) }
                queueArm(gr) { fillCircle(gr, hy + S(41), S(4), furLight) }
            }

            PotsikVisitPose.CROUCH -> {
                limbTo(cx2 - S(11), hy + S(26), cx2 - S(13), hy + S(38))
                limbTo(cx2 + S(11), hy + S(26), cx2 + S(13), hy + S(38))
            }

            PotsikVisitPose.PULL -> {
                val tug = (beat * S(2)).toInt()
                limbTo(cx2 + sd * S(11), hy + S(26), cx2 + sd * S(30) + tug, hy + S(28))
                limbTo(cx2 - sd * S(11), hy + S(26), cx2 + sd * S(24) + tug, hy + S(31))
            }

            PotsikVisitPose.WIGGLE -> {
                val wg = (sin((now % 180) / 180f * TAU) * S(3)).toInt()
                limbTo(cx2 + sd * S(11), hy + S(26), cx2 + sd * S(28), hy + S(24) + wg)
                limbTo(cx2 - sd * S(11), hy + S(26), cx2 + sd * S(20), hy + S(32) - wg)
            }

            PotsikVisitPose.CHEER -> {
                val pk = (abs(beat) * S(5)).toInt()
                limbTo(cx2 - S(11), hy + S(26), cx2 - S(19), hy - S(6) + pk)
                limbTo(cx2 + S(11), hy + S(26), cx2 + S(19), hy - S(6) + pk)
            }

            PotsikVisitPose.SELFIE -> {
                hangingArm(-sd)
                val px = cx2 + sd * S(22)
                val py = hy - S(4)
                limbTo(cx2 + sd * S(11), hy + S(26), px, py)
                queueArm(px) {
                    fillRoundRect(px - S(3), py - S(6), S(6), S(9), 1f, PotsikPalette.BLACK)
                    fillRect(px - S(2), py - S(5), S(4), S(6), blend(PotsikPalette.CYAN, PotsikPalette.BLACK, 90))
                }
            }

            PotsikVisitPose.HOWL -> {
                limbTo(cx2 - S(11), hy + S(26), cx2 - S(15), hy + S(12))
                limbTo(cx2 + S(11), hy + S(26), cx2 + S(15), hy + S(12))
            }

            PotsikVisitPose.POINT -> {
                hangingArm(-sd)
                limbTo(cx2 + sd * S(11), hy + S(26), cx2 + sd * S(27), hy - S(4))
            }

            PotsikVisitPose.STRAIN -> {
                hangingArm(-sd)
                val sh = (sin((now % 120) / 120f * TAU) * S(1)).toInt()
                limbTo(cx2 + sd * S(11), hy + S(26), cx2 + sd * S(30), hy + S(22) + sh)
            }

            else -> {
                hangingArm(-1)
                hangingArm(1)
            }
        }
    }

    /** A hanging arm: the rounded rectangle the firmware draws for a rest. */
    private fun hangingArm(side: Int, offset: Int = 0) {
        val ax = if (side < 0) cx2 - S(18) else cx2 + S(10)
        val ay = hy + S(22) + offset
        queueArm(ax) {
            keyRR(ax, ay, S(8), S(22), S(3))
            fillRoundRect(ax, ay, S(8), S(22), S(3), furLight)
        }
    }

    /**
     * Queues paint for the arm whose shoulder is at [x]. The arm chain only
     * queues: [flushArms] paints the queue once both arms are known, so the
     * arm that crosses the body can be drawn in front of the other one.
     */
    private fun queueArm(x: Float, paint: () -> Unit) {
        armOps[if (x < cx2) 0 else 1].add(paint)
    }

    /**
     * Paints the queued arms and each arm's tee sleeve. The far arm goes
     * first: an arm whose hand crosses the centre line is the near one and is
     * painted last, so its strokes and sleeve land over the other arm rather
     * than under it. With no crossing, the order is the firmware's: left, then
     * right.
     */
    private fun flushArms(withSleeves: Boolean) {
        val leftCrosses = limbs.armL1x > cx2
        val rightCrosses = limbs.armR1x < cx2
        val order = if (leftCrosses && !rightCrosses) intArrayOf(1, 0) else intArrayOf(0, 1)
        for (k in order) {
            for (paint in armOps[k]) paint()
            if (withSleeves) drawSleeve(k)
        }
        armOps[0].clear()
        armOps[1].clear()
    }

    /** Records the resting arms without drawing them. */
    private fun restArms(dL: Int, dR: Int) {
        limbs.armL0x = cx2 - S(14)
        limbs.armL0y = hy + S(24) + dL
        limbs.armL1x = cx2 - S(14)
        limbs.armL1y = hy + S(42) + dL
        limbs.armR0x = cx2 + S(14)
        limbs.armR0y = hy + S(24) + dR
        limbs.armR1x = cx2 + S(14)
        limbs.armR1y = hy + S(42) + dR
    }

    /**
     * Draws an arm from the shoulder to the hand and records where it went.
     *
     * The side is decided by the shoulder, not by the hand: a dance throws
     * both arms to the same side of the centre, and keying off the hand would
     * file both of them as the same arm.
     */
    private fun limbTo(x0: Float, y0: Float, x1: Float, y1: Float, w: Float = 0f) {
        if (x0 < cx2) {
            limbs.armL0x = x0
            limbs.armL0y = y0
            limbs.armL1x = x1
            limbs.armL1y = y1
        } else {
            limbs.armR0x = x0
            limbs.armR0y = y0
            limbs.armR1x = x1
            limbs.armR1y = y1
        }
        val ww = if (w != 0f) w else S(7)
        val sleeve = if (look.outfit == PotsikOutfit.YZZERD) YZ_SLEEVE else furLight
        queueArm(x0) {
            keyW(x0, y0, x1, y1, ww)
            wideLine(x0, y0, x1, y1, ww, sleeve)
        }
    }

    // ------------------------------------------------------------------
    // The head
    // ------------------------------------------------------------------

    private fun drawHead() {
        // A rounded box, flat yellow all over: he is built from rounded
        // rectangles, and a flat skull reads clean at this size where stacked
        // lighter patches looked muddy.
        fillRoundRect(cx2 - S(12), hh, S(24), S(24), S(4), furMain)
        val skin = if (look.outfit == PotsikOutfit.SHAMBLER) SH_SKIN else furMain
        fillRoundRect(cx2 - S(7), hh + S(13), S(14), S(10), S(3), skin)
    }

    private fun drawCowlick() {
        val outfit = look.outfit
        if (outfit == PotsikOutfit.PARKA || outfit == PotsikOutfit.TANOOKI ||
            outfit == PotsikOutfit.YZZERD || outfit == PotsikOutfit.OVER9000
        ) {
            return
        }
        if (outfit != PotsikOutfit.BLUEBLUR) {
            // The short one, upright.
            keyT(cx2 - S(7), hh + S(2), cx2 - S(4), hh - S(5), cx2 - S(1), hh + S(2))
            fillTriangle(cx2 - S(7), hh + S(2), cx2 - S(4), hh - S(5), cx2 - S(1), hh + S(2), furMain)
            // The tall one, its apex pushed out over its own right base corner
            // so the whole tuft leans instead of standing to attention.
            val tallApex = hh - (S(9) * 80) / 100
            keyT(cx2 - S(2), hh + S(2), cx2 + S(6), tallApex, cx2 + S(6), hh + S(2))
            fillTriangle(cx2 - S(2), hh + S(2), cx2 + S(6), tallApex, cx2 + S(6), hh + S(2), furMain)
        }
        // The side fringe, tucked inside the head edge so the whole crown fits
        // the silhouette.
        keyT(cx2 - S(10), hh + S(3), cx2 - S(7), hh - S(4), cx2 - S(4), hh + S(3))
        fillTriangle(cx2 - S(10), hh + S(3), cx2 - S(7), hh - S(4), cx2 - S(4), hh + S(3), furMain)
        keyT(cx2 + S(4), hh + S(3), cx2 + S(7), hh - S(4), cx2 + S(10), hh + S(3))
        fillTriangle(cx2 + S(4), hh + S(3), cx2 + S(7), hh - S(4), cx2 + S(10), hh + S(3), furMain)
    }

    private fun drawEars() {
        // Small rounded squares hugging the narrow face, to sit with the boxy
        // skull rather than as ovals.
        val ew = S(4)
        val eh = S(4)
        for (sg in listOf(-1, 1)) {
            val ex = cx2 + sg * S(11)
            fillRoundRect(ex - ew / 2, hh + S(13) - eh / 2, ew, eh, S(1), furMain)
            fillRoundRect(ex - ew / 2 + 1, hh + S(13) - eh / 2 + 1, ew - 2, eh - 2, S(1), blend(furMain, PotsikPalette.SKIN_DARK, 90))
        }
    }

    private fun drawCheeks() {
        if (look.outfit == PotsikOutfit.SHAMBLER) return
        val cheek = blend(furMain, PotsikPalette.SKIN_DARK, 60)
        val cw = S(4)
        val ch = S(4)
        fillRoundRect(cx2 - S(8) - cw / 2, hh + S(15), cw, ch, S(1), cheek)
        fillRoundRect(cx2 + S(8) - cw / 2, hh + S(15), cw, ch, S(1), cheek)
    }

    private fun drawNose() {
        fillRoundRect(cx2 - S(2) - 1, hh + S(13), S(4) + 2, S(3) + 2, S(1), PotsikPalette.BLACK)
        fillRoundRect(cx2 - S(2), hh + S(14), S(4), S(3), S(1), furMain)
    }

    /** The eyes, per mood: the startled ones, the sad ones, the closed ones, the usual ones. */
    private fun drawFace() {
        val mood = frame.mood
        val act = frame.actPose
        if (mood == PotsikMood.SHOCKED) {
            var pdx = 0f
            var pdy = 0f
            when (frame.reactPose) {
                PotsikReactPose.LOOK_UP -> pdy = -S(2)
                PotsikReactPose.LOOK_AROUND -> pdx = sin((now % 600) / 600f * TAU) * S(2)
                else -> Unit
            }
            fillEllipse(cx2 - S(5), hh + S(9), S(3), S(4), PotsikPalette.WHITE)
            fillEllipse(cx2 + S(5), hh + S(9), S(3), S(4), PotsikPalette.WHITE)
            fillCircle(cx2 - S(5) + pdx, hh + S(9) + pdy, S(1), PotsikPalette.BLACK)
            fillCircle(cx2 + S(5) + pdx, hh + S(9) + pdy, S(1), PotsikPalette.BLACK)
        } else if (act == PotsikVisitPose.SAD) {
            wideLine(cx2 - S(10), hh + S(6), cx2 - S(3), hh + S(4), S(1) + 1, PotsikPalette.BLACK)
            wideLine(cx2 + S(10), hh + S(6), cx2 + S(3), hh + S(4), S(1) + 1, PotsikPalette.BLACK)
            fillCircle(cx2 - S(6), hh + S(9), S(1) + 1, PotsikPalette.BLACK)
            fillCircle(cx2 + S(6), hh + S(9), S(1) + 1, PotsikPalette.BLACK)
            // A tear, falling.
            val tk = (now % 1100) / 1100f
            fillCircle(cx2 - S(7), hh + S(11) + (tk * S(9)).toInt(), S(1) + 1, PotsikPalette.CYAN)
        } else if (mood == PotsikMood.STRETCH) {
            drawClosedEyes()
        } else if (mood == PotsikMood.SLEEPY) {
            drawClosedEyes()
            drawSleepZ()
        } else {
            drawUsualEyes()
        }

        // The angry brows.
        if (act == PotsikVisitPose.GRR || act == PotsikVisitPose.STRAIN) {
            wideLine(cx2 - S(12), hh + S(3), cx2 - S(3), hh + S(6), S(2), PotsikPalette.BLACK)
            wideLine(cx2 + S(12), hh + S(3), cx2 + S(3), hh + S(6), S(2), PotsikPalette.BLACK)
        }

        // The wink's sparkle.
        if (mood == PotsikMood.WINK) {
            val sx = cx2 - S(16)
            val sy = hh + S(3)
            drawLine(sx - S(2), sy, sx + S(2), sy, PotsikPalette.WHITE)
            drawLine(sx, sy - S(2), sx, sy + S(2), PotsikPalette.WHITE)
        }
    }

    /**
     * The shut eyes.
     *
     * The firmware draws the sleeping lids in its own light fur, which on the
     * default yellow is the very same colour as the face — so the eyes vanish
     * and he is left with a blank face. Here the lid is a dark line instead,
     * in the same keyline every other outline of his is drawn in, so a shut
     * eye still reads as a shut eye.
     *
     * The shape is the firmware's: two shallow arcs that dip towards the
     * middle, the content, peaceful eye.
     */
    private fun drawClosedEyes() {
        val w = minOf(S(1), 3f).coerceAtLeast(1.5f)
        wideLine(cx2 - S(9), hh + S(9), cx2 - S(2), hh + S(11), w, PotsikPalette.KEYLINE)
        wideLine(cx2 + S(2), hh + S(11), cx2 + S(9), hh + S(9), w, PotsikPalette.KEYLINE)
    }

    /**
     * The "Z" that drifts up while he is asleep — the firmware's own, on the
     * same 1.6 s cycle, fading out as it goes.
     */
    private fun drawSleepZ() {
        val phase = (now % 1600) / 1600f
        val zx = cx2 + S(15) + S(6) * phase
        val zy = hh + S(1) - S(14) * phase
        val size = S(4).coerceAtLeast(3f)
        val colour = PotsikPalette.CYAN.copy(alpha = (1f - phase).coerceIn(0f, 1f))
        val w = minOf(S(1), 2f).coerceAtLeast(1f)
        wideLine(zx, zy, zx + size, zy, w, colour)
        wideLine(zx + size, zy, zx, zy + size, w, colour)
        wideLine(zx, zy + size, zx + size, zy + size, w, colour)
    }

    private fun drawUsualEyes() {
        // TH3 0N3 wears his own round glasses instead (drawn with the costume),
        // and the firmware skips the plain shades for him.
        if (look.outfit == PotsikOutfit.TH3_0N3) return
        val shadeTint = look.shadeTint.color
        val blink = isBlinking(now)
        val winking = frame.mood == PotsikMood.WINK
        val openLens = blend(PotsikPalette.BLACK, shadeTint, 60)
        val lensL = if (blink || winking) PotsikPalette.BLACK else openLens
        val lensR = if (blink) PotsikPalette.BLACK else openLens
        val sd = frame.shadeDrop

        if (!look.glassesOn) {
            // The bare eyes: the big Simpsons-style eyes, with the lashes on
            // the outside corner when they are switched on.
            val eex = S(6)
            val erx = S(7) / 2
            val ery = S(7) / 2
            if (blink) {
                drawLine(cx2 - eex - erx, hh + S(10), cx2 - eex + erx, hh + S(10), PotsikPalette.BLACK)
                drawLine(cx2 + eex - erx, hh + S(10), cx2 + eex + erx, hh + S(10), PotsikPalette.BLACK)
            } else {
                for (sg in listOf(-1, 1)) {
                    val ex = cx2 + sg * eex
                    fillRoundRect(ex - erx - 1, hh + S(10) - ery - 1, 2 * erx + 2, 2 * ery + 2, S(1) + 1, PotsikPalette.BLACK)
                    fillRoundRect(ex - erx, hh + S(10) - ery, 2 * erx, 2 * ery, S(1), PotsikPalette.WHITE)
                    if (look.lashesOn) {
                        val ly = hh + S(10) - ery
                        val lx = ex + sg * erx
                        drawLine(lx, ly, lx + sg, ly - S(1) - 1, PotsikPalette.BLACK)
                        drawLine(lx - sg, ly, lx, ly - S(1) - 1, PotsikPalette.BLACK)
                    }
                }
                val py = hh + S(10) + S(1) / 2
                if (winking) {
                    drawLine(cx2 - eex - erx, hh + S(10), cx2 - eex + erx, hh + S(10), PotsikPalette.BLACK)
                    fillCircle(cx2 + eex, py, S(1) + 1, PotsikPalette.BLACK)
                } else {
                    fillCircle(cx2 - eex, py, S(1) + 1, PotsikPalette.BLACK)
                    fillCircle(cx2 + eex, py, S(1) + 1, PotsikPalette.BLACK)
                    fillCircle(cx2 - eex + 1, py - 1, 1f, PotsikPalette.WHITE)
                    fillCircle(cx2 + eex + 1, py - 1, 1, PotsikPalette.WHITE)
                }
            }
            return
        }

        // The shades: two frames, a bridge, the lenses and the glint that
        // sweeps across them.
        fillRoundRect(cx2 - S(12), hh + S(6) + sd, S(10), S(7), 2f, PotsikPalette.BLACK)
        fillRoundRect(cx2 + S(2), hh + S(6) + sd, S(10), S(7), 2, PotsikPalette.BLACK)
        val bx0 = cx2 - S(12) + S(10) - 1
        val bx1 = cx2 + S(2)
        fillRect(bx0, hh + S(8) + sd, bx1 - bx0 + 1, S(2), PotsikPalette.BLACK)
        fillRoundRect(cx2 - S(11), hh + S(7) + sd, S(8), S(5), 1f, lensL)
        fillRoundRect(cx2 + S(3), hh + S(7) + sd, S(8), S(5), 1f, lensR)
        if (sd > 0) {
            fillCircle(cx2 - S(7), hh + S(6), S(2), PotsikPalette.BLACK)
            fillCircle(cx2 + S(7), hh + S(6), S(2), PotsikPalette.BLACK)
        }
        val sweep = (now % 2400) / 2400f
        val gx = (sweep * 6f).toInt()
        if (lensL != PotsikPalette.BLACK) drawFastVLine(cx2 - S(11) + S(1 + gx), hh + S(7) + sd, S(4), PotsikPalette.WHITE)
        if (lensR != PotsikPalette.BLACK) drawFastVLine(cx2 + S(3) + S(1 + gx), hh + S(7) + sd, S(4), PotsikPalette.WHITE)
    }

    private fun drawMouth(noMouth: Boolean) {
        val act = frame.actPose
        val talking = frame.forceTalking || frame.talking
        when {
            noMouth -> Unit

            act == PotsikVisitPose.HOWL -> {
                // Wide open and round — the howl itself.
                fillEllipse(cx2, hh + S(19), S(4), S(6), PotsikPalette.BLACK)
                fillEllipse(cx2, hh + S(21), S(2), S(2), PotsikPalette.PINK)
            }

            act == PotsikVisitPose.GRR || act == PotsikVisitPose.STRAIN -> {
                // Gritted teeth.
                fillRoundRect(cx2 - S(7), hh + S(16), S(14), S(6), S(2), PotsikPalette.BLACK)
                fillRect(cx2 - S(6), hh + S(17), S(12), S(4), PotsikPalette.WHITE)
                for (k in -1..1) drawFastVLine(cx2 + k * S(3), hh + S(17), S(4), PotsikPalette.BLACK)
            }

            frame.mood == PotsikMood.GUM -> {
                // Pursed, because there is a bubble coming out of it.
                fillCircle(cx2, hh + S(18), S(2), PotsikPalette.BLACK)
            }

            talking && ((now / 160) % 2 == 0L) -> {
                fillRoundRect(cx2 - S(8), hh + S(15), S(16), S(9), S(3), PotsikPalette.BLACK)
                fillRect(cx2 - S(6), hh + S(16), S(12), S(2), PotsikPalette.WHITE)
                fillEllipse(cx2, hh + S(21), S(5), S(3), PotsikPalette.PINK)
            }

            else -> {
                // The resting grin: a small rounded mouth, a row of teeth and
                // a short lip line, so it reads as friendly rather than a shout.
                fillRoundRect(cx2 - S(6), hh + S(16), S(12), S(6), S(2), PotsikPalette.BLACK)
                fillRect(cx2 - S(5), hh + S(17), S(10), S(2), PotsikPalette.WHITE)
                fillRect(cx2 - S(5), hh + S(19), S(10), 1f, PotsikPalette.BLACK)
                fillRect(cx2 - S(5), hh + S(20), S(10), S(1) + 1, PotsikPalette.PINK)
            }
        }
    }

    // ------------------------------------------------------------------
    // The costume layers that go behind him
    // ------------------------------------------------------------------

    /**
     * The CHROME WING's pair of wings, behind the torso and the head: the
     * firmware's dispatch in drawBody(). They rest, then break into a short
     * burst of three beats every few seconds (see [chromeWingFlapAt]).
     */
    private fun drawWings() {
        if (look.outfit != PotsikOutfit.CHROMEWING) return
        val cBr = rgb565(0xDEDF)
        val cMd = rgb565(0x9495)
        val cDk = rgb565(0x4A4A)
        val flapAt = chromeWingFlapAt(now)
        val fAge = now - flapAt
        var beat = 0f
        if (fAge < 900L) {
            val fp = fAge / 900f
            beat = sin(fp * 3f * TAU) * (1f - fp)
        }
        // Anchored to his shoulders, not to the head: wings grow from a back.
        val sy = hy + S(20)
        val len = S(25)
        val shine = if (fAge < 900L) 0.25f + 0.75f * fAge / 900f else 1f
        drawChromeWing(cx2 - S(13), sy, len, 218f, -beat, 0.50f, 3, cMd, PotsikPalette.BLACK, -0.55f, 26f, cDk)
        drawChromeWing(cx2 + S(13), sy, len, -38f, beat, 0.50f, 3, cMd, PotsikPalette.BLACK, 0.55f, 26f, cDk)
        drawChromeWing(cx2 - S(13), sy, len * shine, 218f, -beat, 0.22f, 0, PotsikPalette.WHITE, PotsikPalette.WHITE, -0.55f, 26f, cBr)
        drawChromeWing(cx2 + S(13), sy, len * shine, -38f, beat, 0.22f, 0, PotsikPalette.WHITE, PotsikPalette.WHITE, 0.55f, 26f, cBr)
    }

    private fun drawTanookiTail() {
        if (look.outfit != PotsikOutfit.TANOOKI) return
        val tcream = rgb565(0xDB60)
        val tdark = rgb565(0x6920)
        val pl = floatArrayOf(10f, 42f, 24.4f, 45.2f, 38.8f, 26f, 32.4f, 8.4f)
        val sPhase = (now % 3400) / 3400f * TAU
        // The occasional decaying double flick over the top of the wave,
        // timed off a hashed slot so it never lands on a beat you can
        // predict — the same idea as his blink schedule.
        var fl = 0f
        val slot = 5200L
        val hsh = potsikHash((slot * 2654435761L).toInt())
        val f0 = slot * 5200 + floorMod(hsh.toLong(), 4400L)
        if (now >= f0 && now < f0 + 620) {
            val k = (now - f0) / 620f
            fl = sin(k * 12.566371f) * (1f - k)
        }
        val tn = 18
        val px = FloatArray(tn)
        val py = FloatArray(tn)
        for (i in 0 until tn) {
            val u = i.toFloat() / (tn - 1)
            val v = 1f - u
            val bx = v * v * v * pl[0] + 3 * v * v * u * pl[2] + 3 * v * u * u * pl[4] + u * u * u * pl[6]
            val by = v * v * v * pl[1] + 3 * v * v * u * pl[3] + 3 * v * u * u * pl[5] + u * u * u * pl[7]
            val uu = if (u + 0.02f > 1f) 1f else u + 0.02f
            val w2 = 1f - uu
            val qx = w2 * w2 * w2 * pl[0] + 3 * w2 * w2 * uu * pl[2] + 3 * w2 * uu * uu * pl[4] + uu * uu * uu * pl[6]
            val qy = w2 * w2 * w2 * pl[1] + 3 * w2 * w2 * uu * pl[3] + 3 * w2 * uu * uu * pl[5] + uu * uu * uu * pl[7]
            var dx = qx - bx
            var dy = qy - by
            var len = sqrt(dx * dx + dy * dy)
            if (len < 0.0001f) len = 1f
            val amp = u * (0.4f + 0.6f * u)
            val d = sin(u * TAU - sPhase) * 4f * amp + fl * 5f * u * u
            px[i] = cx2 + ((bx + (-dy / len) * d) * scale)
            py[i] = hy + ((by + (dx / len) * d) * scale)
        }
        for (j in 0 until tn - 1) {
            val u = j.toFloat() / (tn - 1)
            var rr = (7f * (1f - u * u * (0.35f + 0.65f * u)) + 2.2f) * 0.8f
            val hj = potsikHash(j * 2654435761.toInt() xor 0x9E3779B9.toInt())
            rr *= 0.90f + (hj and 255) / 255f * 0.20f
            var w = (rr * 2f * scale).toInt()
            if (w < 2) w = 2
            wideLine(px[j], py[j], px[j + 1], py[j + 1], w, if ((j / 3) and 1 == 1) tdark else tcream)
        }
    }

    private fun drawBlueBlurQuills() {
        val q = intArrayOf(
            -6, 3, 6, 3, -2, -8,
            -6, 4, -13, 11, -24, -4,
            -11, 10, -13, 19, -26, 8,
            6, 4, 13, 11, 25, -2,
            11, 10, 13, 19, 27, 10,
        )
        val offsets = intArrayOf(-1, 0, 1, 0, 0, -1, 0, 1, 0, 0)
        for (pass in 0 until 5) {
            val c = if (pass < 4) PotsikPalette.BLACK else furMain
            for (i in 0 until 5) {
                fillTriangle(
                    cx2 + S(q[i * 6]) + offsets[pass * 2],
                    hh + S(q[i * 6 + 1]) + offsets[pass * 2 + 1],
                    cx2 + S(q[i * 6 + 2]) + offsets[pass * 2],
                    hh + S(q[i * 6 + 3]) + offsets[pass * 2 + 1],
                    cx2 + S(q[i * 6 + 4]) + offsets[pass * 2],
                    hh + S(q[i * 6 + 5]) + offsets[pass * 2 + 1],
                    c,
                )
            }
        }
        for (i in 0 until 5) {
            drawLine(cx2 + S(q[i * 6]), hh + S(q[i * 6 + 1]), cx2 + S(q[i * 6 + 4]), hh + S(q[i * 6 + 5]), furLight)
        }
    }

    private fun drawTanookiEars() {
        val earC = rgb565(0xDB60)
        val earIn = rgb565(0x6920)
        for (sg in listOf(-1, 1)) {
            val bo = cx2 + sg * S(14)
            val bi = cx2 + sg * S(4)
            val tx = cx2 + sg * S(11)
            val ty = hh - S(9)
            scope.inked(PotsikPalette.BLACK, earC) { ox, oy, c ->
                scope.fillTriangle(bo + ox, hh + S(4) + oy, bi + ox, hh + S(2) + oy, tx + ox, ty + oy, c)
            }
            fillTriangle(cx2 + sg * S(12), hh + S(2), cx2 + sg * S(7), hh + S(1), cx2 + sg * S(11), ty + S(4), earIn)
        }
    }

    private fun drawParkaHood(actHead: Float) {
        val hcy = hy + frame.headDrop + actHead + S(8)
        fillCircle(cx2, hcy, S(23) + 2, rgb565(0x1040))
        fillCircle(cx2, hcy, S(23), rgb565(0xFC43))
        val pow = (S(17) * 102) / 100
        val poh = (S(16) * 102) / 100
        fillEllipse(cx2, hcy, pow, poh, rgb565(0x6A05))
        fillEllipse(cx2, hcy, pow - S(4), poh - S(4), rgb565(0x4943))
    }

    private fun drawYzBehindHead() {
        val dark = YZ_DARK
        val lining = YZ_LINING
        val gold = YZ_GOLD

        // The bell sleeves, with their gold cuffs.
        drawYzSleeves()

        // A tall collar standing up either side of his head, lined in red.
        for (sg in listOf(-1, 1)) {
            scope.inked(PotsikPalette.BLACK, dark) { ox, oy, c ->
                scope.fillTriangle(
                    cx2 + sg * (S(6) - 2) + ox,
                    hh + S(26) + 1 + oy,
                    cx2 + sg * (S(19) + 2) + ox,
                    hh + S(27) + 1 + oy,
                    cx2 + sg * (S(25) + 1) + ox,
                    hh + S(3) - 3 + oy,
                    c,
                )
            }
            fillTriangle(cx2 + sg * S(6), hh + S(26), cx2 + sg * S(19), hh + S(27), cx2 + sg * S(25), hh + S(3), dark)
            fillTriangle(cx2 + sg * S(9), hh + S(26), cx2 + sg * S(17), hh + S(26), cx2 + sg * S(23), hh + S(8), lining)
            wideLine(cx2 + sg * S(19), hh + S(27), cx2 + sg * S(25), hh + S(3), S(1) + 1, gold)
        }

        // The gold epaulettes, with their fringe.
        for (sg in listOf(-1, 1)) {
            val ex = cx2 + sg * S(14)
            fillEllipse(ex, hy + S(23), S(6) + 1, S(2) + 1, PotsikPalette.BLACK)
            fillEllipse(ex, hy + S(23), S(6), S(2), gold)
            for (k in -1..1) {
                fillRect(ex + k * S(3) - 1, hy + S(24), 2, S(3), gold)
            }
        }
    }

    /**
     * The eight spikes of the Over 9000 hair, behind his head.
     *
     * Each tip sways a little, out of step with its neighbours, and a glint
     * runs up one spike at a time. Every spike is gold down its left side and
     * a darker gold down its right, so the whole lot reads as a sheaf.
     */
    private fun drawO9BehindHead() {
        drawO9Sleeve()
        val gold = O9_GOLD
        val goldDk = O9_GOLDDK
        val goldHi = O9_GOLDHI

        val hair = intArrayOf(
            -14, 12, -15, 3, -27, -2, -15, 6, -10, 1, -26, -17,
            -11, 2, -4, 0, -15, -27, -5, 0, 2, 0, -4, -31,
            1, 0, 8, 1, 9, -30, 6, 1, 13, 3, 20, -24,
            12, 3, 15, 9, 27, -12, 14, 9, 15, 15, 26, 6,
        )
        val tx = FloatArray(8)
        val ty = FloatArray(8)
        for (i in 0 until 8) {
            val ph = (now % 2600) / 2600f * TAU + i * 1.3f
            tx[i] = cx2 + S(hair[i * 6 + 4]) + (sin(ph) * 1.5f).toInt()
            ty[i] = hh + S(hair[i * 6 + 5]) + (cos(ph) * 1.0f).toInt()
        }
        // The outline: one copy of each spike pushed a pixel out from its
        // own middle.
        for (i in 0 until 8) {
            val vx = floatArrayOf(cx2 + S(hair[i * 6]), cx2 + S(hair[i * 6 + 2]), tx[i])
            val vy = floatArrayOf(hh + S(hair[i * 6 + 1]), hh + S(hair[i * 6 + 3]), ty[i])
            val mx = (vx[0] + vx[1] + vx[2]) / 3f
            val my = (vy[0] + vy[1] + vy[2]) / 3f
            for (k in 0 until 3) {
                vx[k] += if (vx[k] > mx) {
                    1f
                } else if (vx[k] < mx) {
                    -1f
                } else {
                    0f
                }
                vy[k] += if (vy[k] > my) {
                    1f
                } else if (vy[k] < my) {
                    -1f
                } else {
                    0f
                }
            }
            fillTriangle(vx[0], vy[0], vx[1], vy[1], vx[2], vy[2] - 1, PotsikPalette.BLACK)
        }
        // The left halves of every spike go on first, so a spike's darker
        // side is never under its neighbour's gold.
        for (i in 0 until 8) {
            val ax = cx2 + S(hair[i * 6])
            val ay = hh + S(hair[i * 6 + 1])
            val bx = cx2 + S(hair[i * 6 + 2])
            val by = hh + S(hair[i * 6 + 3])
            val mx = cx2 + S((hair[i * 6] + hair[i * 6 + 2]) / 2)
            val my = hh + S((hair[i * 6 + 1] + hair[i * 6 + 3]) / 2)
            fillTriangle(ax, ay, mx, my, tx[i], ty[i], gold)
            fillTriangle(ax, ay, bx, by, mx, my, gold)
        }
        // The right halves and the light line up the middle.
        for (i in 0 until 8) {
            val mx = cx2 + S((hair[i * 6] + hair[i * 6 + 2]) / 2)
            val my = hh + S((hair[i * 6 + 1] + hair[i * 6 + 3]) / 2)
            fillTriangle(mx, my, cx2 + S(hair[i * 6 + 2]), hh + S(hair[i * 6 + 3]), tx[i], ty[i], goldDk)
            drawLine(mx, my, tx[i], ty[i], goldHi)
        }
        // The glint, running up one spike at a time.
        val g = ((now / 600) % 8).toInt()
        val gp = (now % 600).toInt()
        val mx = cx2 + S((hair[g * 6] + hair[g * 6 + 2]) / 2)
        val my = hh + S((hair[g * 6 + 1] + hair[g * 6 + 3]) / 2)
        fillRect(
            mx + (tx[g] - mx) * gp / 600 - 1,
            my + (ty[g] - my) * gp / 600 - 1,
            3,
            3,
            PotsikPalette.WHITE,
        )
    }

    private fun drawT3BehindHead() {
        // The collar, turned up either side of his jaw: a black keyline, the
        // coat, and the violet edge line that holds it on black.
        for (sg in listOf(-1, 1)) {
            fillTriangle(
                cx2 + sg * S(6),
                hh + S(28) + 1,
                cx2 + sg * (S(19) + 1),
                hh + S(28) + 1,
                cx2 + sg * (S(21) + 1),
                hh + S(12) - 1,
                PotsikPalette.BLACK,
            )
            fillTriangle(cx2 + sg * S(7), hh + S(27), cx2 + sg * S(19), hh + S(27), cx2 + sg * S(21), hh + S(13), T3_COAT)
            drawLine(cx2 + sg * S(19), hh + S(27), cx2 + sg * S(21), hh + S(13), T3_EDGE)
        }
    }

    /**
     * The STRIPES prison stripes, painted over the fur after the body and the
     * arms and before the head: the firmware's prisonStripes.
     *
     * The firmware reads each pixel back from its frame and classifies it:
     * fur-main pixels (torso and legs) take the body stripes, fur-light pixels
     * (the arms) take the arm stripes, except inside the hand radius, which
     * stays fur. A Compose canvas cannot be read back, so the same classes
     * come from the geometry the body was drawn with. Each run of suit gets a
     * lit pixel on its left end and a shadow on its right.
     */
    private fun drawPrisonStripes() {
        val torsoTop = (hy + S(23)).toInt()
        val handTop = minOf(limbs.armL1y, limbs.armR1y) - S(5)
        val y0 = minOf(hy + S(22), handTop).toInt()
        val y1 = minOf(limbs.footLy, limbs.footRy).toInt()
        val x0 = (cx2 - S(30)).toInt()
        val x1 = (cx2 + S(30)).toInt()
        if (y1 <= y0 || x1 <= x0) return

        val band = lround(1.48f * scale).coerceAtLeast(2)
        val bodyW = rgb565(0xE73B)
        val bodyD = rgb565(0x18E3)
        val armW = PotsikPalette.WHITE
        val armD = rgb565(0x31A7)
        val lit = rgb565(0xCE58)
        val shade = rgb565(0x0862)
        val armR = S(7) / 2f
        val handR = S(4) + 1f
        val torsoHalfPx = S(torsoHalf())
        val legL = cx2 - S(10)
        val legGap = cx2 - S(2)
        val legR = cx2 + S(2)
        val legEnd = cx2 + S(10)

        val n = x1 - x0
        val cls = IntArray(n)
        for (y in y0 until y1) {
            val dark = ((((y - torsoTop) + band * 64) / band) and 1) == 1
            val yf = y.toFloat()
            for (i in 0 until n) {
                val x = (x0 + i).toFloat()
                val onArm = distToSegment(x, yf, limbs.armL0x, limbs.armL0y, limbs.armL1x, limbs.armL1y) <= armR ||
                    distToSegment(x, yf, limbs.armR0x, limbs.armR0y, limbs.armR1x, limbs.armR1y) <= armR
                val inHand = sqDist(x, yf, limbs.armL1x, limbs.armL1y) <= handR * handR ||
                    sqDist(x, yf, limbs.armR1x, limbs.armR1y) <= handR * handR
                val inTorso = x >= cx2 - torsoHalfPx && x < cx2 + torsoHalfPx && y < torsoTop + S(18)
                val inLegs = y >= hy + S(40) && ((x >= legL && x < legGap) || (x >= legR && x < legEnd))
                cls[i] = when {
                    onArm && inHand -> 0
                    onArm -> 2
                    inTorso || inLegs -> 1
                    else -> 0
                }
            }
            var i = 0
            while (i < n) {
                if (cls[i] == 0) {
                    i++
                    continue
                }
                var e = i
                while (e + 1 < n && cls[e + 1] != 0) e++
                // Inside the run, one span per kind; the two ends shaded.
                var a = i
                while (a <= e) {
                    var b = a
                    while (b + 1 <= e && cls[b + 1] == cls[a]) b++
                    val col = if (cls[a] == 1) {
                        if (dark) bodyD else bodyW
                    } else {
                        if (dark) armD else armW
                    }
                    drawFastHLine(x0 + a, y, b - a + 1, col)
                    a = b + 1
                }
                if (e > i) {
                    fillRect(x0 + i, y, 1f, 1f, lit)
                    fillRect(x0 + e, y, 1f, 1f, shade)
                }
                i = e + 1
            }
        }

        // The number patch on his chest: 1337, in the little 3x5 digits where
        // he is big enough to read them.
        val w = maxOf(S(8).toInt(), 8)
        val h = if (S(3) > 5) S(3).toInt() + 1 else 5
        val px0 = (cx2 - w / 2 + S(2)).toInt()
        val py0 = torsoTop + S(3).toInt()
        fillRect(px0 - 1, py0 - 1, w + 2, h + 2, PotsikPalette.BLACK)
        fillRect(px0, py0, w, h, PotsikPalette.WHITE)
        if (scale >= 1.9f) {
            val d1 = intArrayOf(2, 6, 2, 2, 7)
            val d3 = intArrayOf(7, 1, 7, 1, 7)
            val d7 = intArrayOf(7, 1, 2, 2, 2)
            val digits = arrayOf(d1, d3, d3, d7)
            var tx = px0 + (w - 15) / 2
            val ty = py0 + (h - 5) / 2
            for (digit in digits) {
                for (r in 0 until 5) {
                    for (k in 0 until 3) {
                        if (digit[r] and (4 shr k) != 0) fillRect(tx + k, ty + r, 1f, 1f, PotsikPalette.BLACK)
                    }
                }
                tx += 4
            }
        }
        ankleCuff()
    }

    /** The iron cuff on his right ankle, with its black ring: the firmware's ankleCuff. */
    private fun ankleCuff() {
        val lx = (limbs.footRx + S(1)).toInt()
        val lw = S(8).toInt()
        val ch = if (S(1) > 2) S(1).toInt() + 1 else 2
        val cy = limbs.footRy.toInt() - ch - 1
        val iron = rgb565(0x7C11)
        val ironHi = rgb565(0xC65A)
        fillRect(lx - 1, cy - 1, lw + 2, ch + 2, PotsikPalette.BLACK)
        fillRect(lx, cy, lw, ch, iron)
        drawFastHLine(lx, cy, lw, ironHi)
        val rw = if (S(1) > 1) S(1).toInt() else 2
        fillRect(lx + lw, cy - 1, rw + 1, ch + 2, PotsikPalette.BLACK)
        fillRect(lx + lw, cy, rw, ch, ironHi)
    }

    private fun sqDist(px: Float, py: Float, x: Float, y: Float): Float {
        val dx = px - x
        val dy = py - y
        return dx * dx + dy * dy
    }

    private fun distToSegment(px: Float, py: Float, x0: Float, y0: Float, x1: Float, y1: Float): Float {
        val dx = x1 - x0
        val dy = y1 - y0
        val len2 = dx * dx + dy * dy
        if (len2 < 0.0001f) return sqrt(sqDist(px, py, x0, y0))
        val t = (((px - x0) * dx + (py - y0) * dy) / len2).coerceIn(0f, 1f)
        return sqrt(sqDist(px, py, x0 + t * dx, y0 + t * dy))
    }

    // ------------------------------------------------------------------
    // The props
    // ------------------------------------------------------------------

    private fun drawProps() {
        if (frame.binoc) {
            val sw = (sin((now % 3200) / 3200f * TAU) * (4f * scale)).toInt()
            for (sgn in listOf(-1, 1)) {
                val lx = cx2 + sgn * S(7) + sw
                fillCircle(lx, hh + S(9), S(5) + kb, keyCol)
                fillCircle(lx, hh + S(9), S(5), blend(PotsikPalette.BLACK, PotsikPalette.WHITE, 45))
                fillCircle(lx, hh + S(9), S(3), blend(PotsikPalette.CYAN, PotsikPalette.BLACK, 150))
            }
        }
        if (frame.mood == PotsikMood.GUM && frame.gumStartMs != 0L) {
            val ge = now - frame.gumStartMs
            if (ge < GUM_GROW_MS + GUM_HOLD_MS) {
                val gk = if (ge < GUM_GROW_MS) ge.toFloat() / GUM_GROW_MS else 1f
                val r = (scale * (2f + 8f * sqrt(gk) + sin(now / 120f) * gk * 0.8f)).toInt()
                val by = hh + S(21) + (r * 0.75f).toInt()
                fillCircle(cx2, by, r + kb, keyCol)
                fillCircle(cx2, by, r.toFloat(), blend(PotsikPalette.VAPOR_PINK, PotsikPalette.WHITE, 55))
                fillCircle(cx2 - r / 3, by - r / 3, r / 5 + 1, PotsikPalette.WHITE)
            } else if (ge < GUM_GROW_MS + GUM_HOLD_MS + GUM_POP_MS) {
                val pk = (ge - GUM_GROW_MS - GUM_HOLD_MS).toFloat() / GUM_POP_MS
                val d = (pk * S(16)).toInt()
                for (i in 0 until 6) {
                    val ang = i / 6f * TAU
                    fillCircle(
                        cx2 + (cos(ang) * d).toInt(),
                        hh + S(25) + (sin(ang) * d).toInt(),
                        (S(2) * (1f - pk)).toInt() + 1f,
                        blend(PotsikPalette.VAPOR_PINK, PotsikPalette.WHITE, 55),
                    )
                }
            }
        }
        if (frame.mood == PotsikMood.JUGGLE) {
            for (i in 0 until 3) {
                val ph = now + i * 400L
                val u = (ph % 1200L) / 1200f
                val side = if ((ph / 1200L) and 1L == 1L) 1 else -1
                val px = cx2 - side * (S(19) * cos(u * PI)).toInt()
                val py = hy + S(26) - (sin(u * PI) * S(40)).toInt()
                val type = frame.recentTypes.getOrElse(i) { PotsikCatchType.UNKNOWN }
                fillRect(px - S(3) - kb, py - S(3) - kb, S(6) + 2 * kb, S(6) + 2 * kb, keyCol)
                fillRect(px - S(3), py - S(3), S(6), S(6), type.color)
            }
        }
    }

    // ------------------------------------------------------------------
    // The helpers
    // ------------------------------------------------------------------

    private fun torsoHalf(): Int = 11

    private fun keyR(x: Float, y: Float, w: Float, h: Float) {
        fillRect(x - kb, y - kb, w + 2 * kb, h + 2 * kb, keyCol)
    }

    private fun keyRR(x: Float, y: Float, w: Float, h: Float, r: Float) {
        fillRoundRect(x - kb, y - kb, w + 2 * kb, h + 2 * kb, r, keyCol)
    }

    private fun keyW(x0: Float, y0: Float, x1: Float, y1: Float, w: Float) {
        wideLine(x0, y0, x1, y1, w + 2 * kb, keyCol)
    }

    private fun keyT(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
        val gx = (x1 + x2 + x3) / 3
        val gy = (y1 + y2 + y3) / 3
        fun ox(v: Float) = v + if (v > gx) {
            kb
        } else if (v < gx) {
            -kb
        } else {
            0f
        }
        fun oy(v: Float) = if (v < gy) v - kb else v
        fillTriangle(ox(x1), oy(y1), ox(x2), oy(y2), ox(x3), oy(y3), keyCol)
    }

    private fun legKey(x: Float, y: Float, w: Float, h: Float, robeEnd: Float) {
        var y0 = y - kb
        val y1 = y + h + kb
        if (y0 < robeEnd) y0 = robeEnd
        if (y1 > y0) fillRect(x - kb, y0, w + 2 * kb, y1 - y0, keyCol)
    }

    private fun legFill(x: Float, y: Float, w: Float, h: Float, robeEnd: Float, col: Color) {
        var y0 = y
        val y1 = y + h
        if (y0 < robeEnd) y0 = robeEnd
        if (y1 > y0) fillRect(x, y0, w, y1 - y0, col)
    }

    /**
     * The flap schedule: the firmware's `flapNext = now + 3200 + random(0, 4200)`,
     * as a pure function of the clock so a frame can be reproduced.
     *
     * Time is cut into slots of 7400 ms and each slot holds one flap, at a
     * seeded offset in [3200, 7400). Consecutive flaps are therefore at least
     * 3200 ms apart, as the firmware's are. The last flap started at or before
     * [nowMs] is returned.
     */
    private fun chromeWingFlapAt(nowMs: Long): Long {
        val slot = 7400L
        fun flapIn(s: Long): Long = s * slot + 3200L + unsignedMod(yzHash(s.toInt()), 4200).toLong()
        val k = nowMs / slot
        val t = flapIn(k)
        return if (t <= nowMs) t else flapIn(k - 1)
    }

    /**
     * One chrome wing: the firmware's theme.cpp drawWing. A curved lobe of
     * eight stations, each turned a little further than the last (the curl),
     * with a body fill, the keyline round it, a shaded lower edge along the
     * centreline, and feather divisions drawn back onto it.
     */
    private fun drawChromeWing(
        sx: Float,
        sy: Float,
        len: Float,
        angDeg: Float,
        flap: Float,
        width: Float,
        ndiv: Int,
        body: Color,
        edge: Color,
        curl: Float,
        lift: Float,
        shade: Color,
    ) {
        val n = 8
        val step = len / n
        val da = curl * 60f * DEG / n
        var a = (angDeg + flap * lift) * DEG
        var px = sx
        var py = sy
        val tx = FloatArray(n + 1)
        val ty = FloatArray(n + 1)
        val bx = FloatArray(n + 1)
        val by = FloatArray(n + 1)
        val cx = FloatArray(n + 1)
        val cy = FloatArray(n + 1)
        for (i in 0..n) {
            val wgt = CHROME_WING_PROFILE[i] * width * len
            val nx = -sin(a)
            val ny = cos(a)
            cx[i] = px.toInt().toFloat()
            cy[i] = py.toInt().toFloat()
            tx[i] = (px + nx * wgt * 0.58f).toInt().toFloat()
            ty[i] = (py + ny * wgt * 0.58f).toInt().toFloat()
            bx[i] = (px - nx * wgt * 0.44f).toInt().toFloat()
            by[i] = (py - ny * wgt * 0.44f).toInt().toFloat()
            px += cos(a) * step
            py += sin(a) * step
            a += da
        }

        // The body: the top edge out, the bottom edge back.
        val hx = FloatArray(2 * (n + 1))
        val hy = FloatArray(2 * (n + 1))
        var m = 0
        for (i in 0..n) {
            hx[m] = tx[i]
            hy[m] = ty[i]
            m++
        }
        for (i in n downTo 0) {
            hx[m] = bx[i]
            hy[m] = by[i]
            m++
        }
        fillPoly(hx, hy, m, body)
        for (i in 0 until m) {
            val j = (i + 1) % m
            drawLine(hx[i], hy[i], hx[j], hy[j], edge)
        }

        // The shade runs along whichever edge is lower on screen, so a mirrored
        // pair both get their shadow underneath.
        val shadeTop = ty[n / 2] > by[n / 2]
        val sxA = if (shadeTop) tx else bx
        val syA = if (shadeTop) ty else by
        val shx = FloatArray(2 * (n + 1))
        val shy = FloatArray(2 * (n + 1))
        var sn = 0
        for (i in 2..n) {
            shx[sn] = cx[i]
            shy[sn] = cy[i]
            sn++
        }
        for (i in n downTo 2) {
            shx[sn] = sxA[i]
            shy[sn] = syA[i]
            sn++
        }
        fillPoly(shx, shy, sn, shade)

        // The feather divisions.
        for (d in 1..ndiv) {
            val i0 = (n * (0.26f + 0.16f * d)).toInt()
            var i1 = i0 + 2
            if (i1 > n) i1 = n
            if (i1 <= i0) break
            drawLine(cx[i0], cy[i0], sxA[i1], syA[i1], edge)
        }
    }

    companion object {
        internal const val TAU = 6.2831853f
        internal const val PI = 3.14159265f

        /** One degree, in radians: the firmware's 0.017453293f. */
        internal const val DEG = 0.017453293f

        /**
         * The chrome wing's width profile, station by station from the shoulder
         * to the tip: the firmware's WING_PROFILE. It holds its width through the
         * middle and drops only at the very end, so the tip reads as rounded.
         */
        internal val CHROME_WING_PROFILE = floatArrayOf(0.400f, 0.650f, 0.900f, 0.997f, 0.986f, 0.956f, 0.885f, 0.724f, 0f)

        /** How long a stretch lasts, in milliseconds. */
        const val STRETCH_MS = 1400f

        /** How long the gum bubble grows, holds and pops. */
        const val GUM_GROW_MS = 900L
        const val GUM_HOLD_MS = 700L
        const val GUM_POP_MS = 320L
    }
}

/**
 * Whether his eyes are shut this frame.
 *
 * The firmware's blink is a pure function of the clock: a 2600-millisecond
 * slot, a hash of the slot's number picks the moment inside it and whether it
 * is a slow blink, a double blink or an ordinary one. It is ported as it is
 * so he blinks on the same schedule as the firmware — and so a screenshot
 * taken at a given moment looks the same every time.
 */
internal fun isBlinking(nowMs: Long): Boolean {
    val slot = nowMs / 2600L
    var h = (slot * 2654435761L).toInt()
    h = h xor (h ushr 15)
    h *= 2246822519L.toInt()
    h = h xor (h ushr 13)
    val t0 = slot * 2600L + 300 + (h.toLong() and 0x7FFFFFFF) % 1700
    val kind = (h ushr 20) and 7
    return when (kind) {
        0 -> nowMs in t0 until t0 + 440
        1 -> nowMs in t0 until t0 + 140 || nowMs in (t0 + 230) until (t0 + 370)
        else -> nowMs in t0 until t0 + 140
    }
}
