package com.pusios.pots.ui.potsik

/** Common-platform equivalent of `Math.floorMod` for positive divisors. */
internal fun floorMod(value: Long, divisor: Long): Long {
    val remainder = value % divisor
    return if (remainder < 0L) remainder + divisor else remainder
}
