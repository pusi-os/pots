package com.pusios.pots.ui.background

import androidx.compose.ui.graphics.Color

/*
 * Colour helpers shared by the scenes ported from the PotSquad firmware.
 *
 * Colours travel as packed 0xRRGGBB Ints; Compose Color is a value class, so
 * wrapping them at the call site does not allocate.
 *
 * Coordinates: the firmware draws on a 320-pixel-wide band. The scenes keep
 * their simulation in those "virtual" units, 320 wide and as tall as the canvas
 * is at that scale, and draw with one uniform scale factor (canvas width / 320),
 * so circles stay round and proportions match the firmware.
 */

/** Virtual width of the firmware band. Every scene lays out against this. */
internal const val BPK_VIRTUAL_W = 320f

/** RGB565 (as the firmware stores colours) to packed 0xRRGGBB, with the firmware's 5/6-bit scaling. */
internal fun bpkRgb565(c: Int): Int {
    val r = ((c shr 11) and 0x1F) * 255 / 31
    val g = ((c shr 5) and 0x3F) * 255 / 63
    val b = (c and 0x1F) * 255 / 31
    return (r shl 16) or (g shl 8) or b
}

/** Packed 0xRRGGBB from channels. */
internal fun bpkRgb(r: Int, g: Int, b: Int): Int = (r shl 16) or (g shl 8) or b

/**
 * The firmware's blend(a, b, t): t in 0..256, 0 gives [a], 256 gives [b],
 * computed per channel with 8-bit fixed point.
 */
internal fun bpkBlend(a: Int, b: Int, t: Int): Int {
    val tt = if (t < 0) {
        0
    } else if (t > 256) {
        256
    } else {
        t
    }
    val it = 256 - tt
    val r = (((a shr 16) and 0xFF) * it + ((b shr 16) and 0xFF) * tt) shr 8
    val g = (((a shr 8) and 0xFF) * it + ((b shr 8) and 0xFF) * tt) shr 8
    val bl = ((a and 0xFF) * it + (b and 0xFF) * tt) shr 8
    return (r shl 16) or (g shl 8) or bl
}

/** Opaque Compose colour from packed 0xRRGGBB. */
internal fun bpkColor(rgb: Int): Color = Color((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)

/** Compose colour from packed 0xRRGGBB with an 8-bit alpha (0..255). */
internal fun bpkColorA(rgb: Int, alpha: Int): Color = Color((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF, alpha.coerceIn(0, 255))

/** Clamps a float into 0..1. */
internal fun bpkClamp01(v: Float): Float = if (v < 0f) {
    0f
} else if (v > 1f) {
    1f
} else {
    v
}
