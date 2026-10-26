package com.pusios.pots.ui.potsik

import androidx.compose.ui.graphics.Color
import kotlin.math.roundToInt

/**
 * The colours of Потсик, ported from the PotSquad firmware's `Theme` namespace.
 *
 * The firmware draws him on an RGB565 screen, so every colour there is a
 * 16-bit value. Those are kept here in the same form — [rgb565] turns one into
 * the 8-bit-per-channel colour Compose draws with — because that is the only
 * way the port keeps the exact same hue: a colour written out by hand as hex
 * would drift, and the character is a flat yellow shape whose whole point is
 * that it reads as one piece.
 */
internal object PotsikPalette {

    /** The firmware's `FUR_MAIN` — his skin. Simpson yellow. */
    val FUR_MAIN: Color = rgb565(0xFF2B)

    /** The firmware's `FUR_LIGHT` — the same yellow, used for the highlights. */
    val FUR_LIGHT: Color = rgb565(0xFF2B)

    /** The firmware's `FUR_DARK` — the shadow tone. */
    val FUR_DARK: Color = rgb565(0x38C0)

    /** The firmware's `FUR_SHADE` — the warmer accent spots. */
    val FUR_SHADE: Color = rgb565(0xFEA3)

    /** The firmware's `SKIN_DARK` — the inner ear. */
    val SKIN_DARK: Color = rgb565(0xCB88)

    /** The firmware's `PINK` — the tongue and the mouth. */
    val PINK: Color = rgb565(0xF81E)

    /** The firmware's `BLACK` and `WHITE`. */
    val BLACK: Color = Color(0xFF000000)
    val WHITE: Color = Color(0xFFFFFFFF)

    /** The firmware's theme accents, used by the costumes and the bubble. */
    val PURPLE: Color = rgb565(0xB400FF)
    val CYAN: Color = rgb565(0x00FFF5)
    val PINK_ACCENT: Color = rgb565(0xFF2D78)
    val VAPOR_PINK: Color = rgb565(0xFB99)
    val VAPOR_PURPLE: Color = rgb565(0xBB5F)
    val VAPOR_BLUE: Color = rgb565(0x067F)
    val VAPOR_YELLOW: Color = rgb565(0xFFD2)
    val GREEN: Color = rgb565(0x00FF88)
    val AMBER: Color = rgb565(0xF800)
    val RED: Color = rgb565(0xF800)

    /** The keyline the firmware draws every outline in: `blend(FUR_DARK, BLACK, 150)`. */
    val KEYLINE: Color = blend(FUR_DARK, BLACK, 150)

    /**
     * The eight-tone palette the base clothes are cut from — the firmware's
     * `CLOTH_COLORS`, in the same order.
     */
    val CLOTH_COLORS: List<Color> = listOf(
        rgb565(0x4810), // indigo
        rgb565(0x19D1), // blue
        rgb565(0xC0B1), // magenta
        rgb565(0x0BEF), // teal
        rgb565(0xC143), // crimson
        rgb565(0x69F3), // purple
        rgb565(0xE301), // orange
        rgb565(0xFFFF), // white
    )
}

/**
 * Turns one of the firmware's RGB565 colours into a colour Compose can draw.
 *
 * The firmware writes five bits of red, six of green and five of blue, so the
 * conversion scales each channel back up to its full range — the same
 * expansion the TFT driver does when it puts the value on the glass.
 */
internal fun rgb565(value: Int): Color {
    val r = (value shr 11) and 0x1F
    val g = (value shr 5) and 0x3F
    val b = value and 0x1F
    return Color(
        red = (r * 255 / 31) / 255f,
        green = (g * 255 / 63) / 255f,
        blue = (b * 255 / 31) / 255f,
    )
}

/**
 * The firmware's `yz565` (potsy.cpp): the same packing as [rgb565], taken from
 * 8-bit channel values. Returns the raw RGB565 code.
 */
internal fun yz565Code(r: Int, g: Int, b: Int): Int = ((r and 0xF8) shl 8) or ((g and 0xFC) shl 3) or (b shr 3)

/** The costume colours, as the firmware's `yz565(...)` constants. */
internal val YZ_ROBE: Color = rgb565(yz565Code(109, 0, 170))
internal val YZ_SLEEVE: Color = rgb565(yz565Code(146, 36, 255))
internal val YZ_DARK: Color = rgb565(yz565Code(73, 0, 85))
internal val YZ_GOLD: Color = rgb565(yz565Code(255, 219, 0))
internal val YZ_LINING: Color = rgb565(yz565Code(182, 0, 85))
internal val YZ_MAGIC: Color = rgb565(yz565Code(0, 255, 255))
internal val YZ_ALARM: Color = rgb565(yz565Code(255, 36, 85))
internal val YZ_ALARMHI: Color = rgb565(yz565Code(255, 219, 170))

internal val O9_GI: Color = rgb565(yz565Code(255, 109, 0))
internal val O9_GIDK: Color = rgb565(yz565Code(182, 73, 0))
internal val O9_BLUE: Color = rgb565(yz565Code(0, 36, 170))
internal val O9_RED: Color = rgb565(yz565Code(219, 0, 0))
internal val O9_GOLD: Color = rgb565(yz565Code(255, 219, 0))
internal val O9_GOLDDK: Color = rgb565(yz565Code(219, 146, 0))
internal val O9_GOLDHI: Color = rgb565(yz565Code(255, 255, 170))
internal val O9_BOLT: Color = rgb565(yz565Code(0, 219, 255))
internal val O9_SCOUTER: Color = rgb565(yz565Code(0, 255, 0))
internal val O9_SCOUTDK: Color = rgb565(yz565Code(0, 146, 0))

internal val SH_FUR: Color = rgb565(yz565Code(109, 146, 85))
internal val SH_FURLT: Color = rgb565(yz565Code(146, 182, 85))
internal val SH_SKIN: Color = rgb565(yz565Code(182, 219, 170))
internal val SH_SHIRT: Color = rgb565(yz565Code(146, 73, 0))
internal val SH_SHIRTDK: Color = rgb565(yz565Code(109, 36, 0))
internal val SH_JEANS: Color = rgb565(yz565Code(36, 73, 170))
internal val SH_JEANSDK: Color = rgb565(yz565Code(0, 36, 85))
internal val SH_GREY: Color = rgb565(yz565Code(182, 182, 170))
internal val SH_MUD: Color = rgb565(yz565Code(109, 73, 0))

internal val T3_COAT: Color = rgb565(yz565Code(36, 0, 85))
internal val T3_EDGE: Color = rgb565(yz565Code(109, 36, 170))
internal val T3_G1: Color = rgb565(yz565Code(182, 255, 170))
internal val T3_G2: Color = rgb565(yz565Code(0, 255, 0))
internal val T3_G3: Color = rgb565(yz565Code(0, 182, 0))
internal val T3_RED: Color = rgb565(yz565Code(255, 0, 0))

/**
 * The firmware's `CwTheme::blend`: a linear mix between two colours.
 *
 * [t] runs 0 to 256, where 0 leaves [a] alone and 256 replaces it with [b] —
 * the firmware's fixed-point convention, kept as it is so the numbers in the
 * ported code are the numbers in the original.
 */
internal fun blend(a: Color, b: Color, t: Int): Color {
    val clamped = t.coerceIn(0, 256)
    fun channel(from: Float, to: Float): Float {
        val v = from + (to - from) * clamped / 256f
        return v.coerceIn(0f, 1f)
    }
    return Color(
        red = channel(a.red, b.red),
        green = channel(a.green, b.green),
        blue = channel(a.blue, b.blue),
    )
}

/**
 * The firmware's `random(0, n)` — an integer in `0 until n`.
 *
 * The firmware's own generator is a linear congruential one seeded from the
 * clock; this one is seeded the same way, so the same millisecond gives the
 * same number and a frame can be reproduced.
 */
internal class PotsikRandom(seed: Long) {
    private var state: Long = if (seed == 0L) 0x2545F4914F6CDD1DL else seed

    /** The next raw 32-bit value. */
    private fun next(): Long {
        state = (state * 6364136223846793005L + 1442695040888963407L)
        return (state ushr 16) and 0xFFFFFFFFL
    }

    /** An integer in `0 until n`, or 0 when [n] is not positive. */
    fun nextInt(n: Int): Int {
        if (n <= 0) return 0
        return (next() % n).toInt()
    }

    /** A float in `0f..1f`. */
    fun nextFloat(): Float = (next() and 0xFFFFFF).toFloat() / 0x1000000f
}

/**
 * The firmware's hash — the same mix used for the blink slots, the tail's
 * wobble and the confetti, so the port blinks on the same schedule.
 */
internal fun potsikHash(value: Int): Int {
    var h = value
    h = h xor (h ushr 15)
    h *= 2246822519L.toInt()
    h = h xor (h ushr 13)
    return h and 0xFFFFFFFF.toInt()
}

/**
 * The firmware's `yzHash` (potsy.cpp), exactly: the same two multiplies and
 * xorshifts in 32-bit unsigned arithmetic. Int multiplication wraps the same
 * way, so the bits match. Read the result as unsigned (see [unsignedMod]).
 */
internal fun yzHash(value: Int): Int {
    var v = value * 2654435761L.toInt()
    v = v xor (v ushr 15)
    v *= 2246822519L.toInt()
    v = v xor (v ushr 13)
    return v
}

/** `h % n` for an unsigned 32-bit value held in an Int. */
internal fun unsignedMod(h: Int, n: Int): Int = ((h.toLong() and 0xFFFFFFFFL) % n).toInt()

/** Rounds the way the firmware's `lroundf` does. */
internal fun lround(value: Float): Int = value.roundToInt()
