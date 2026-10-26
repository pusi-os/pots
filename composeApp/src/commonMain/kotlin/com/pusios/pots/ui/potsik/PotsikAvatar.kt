package com.pusios.pots.ui.potsik

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Потсик on his own, in a box.
 *
 * The settings screen shows him here so the costume, the colours and the
 * shades can be seen while they are being changed. It is the same character
 * the chat draws — the same renderer, the same engine — only smaller and
 * without a bubble.
 *
 * The clock is a frame callback rather than an infinite transition, because
 * the poses need an absolute time to work from: a wave's phase, a blink's
 * slot, the gum bubble's growth. Everything the character does is a function
 * of the time, so the same moment always draws the same picture.
 */
@Composable
fun PotsikAvatar(
    look: PotsikLook,
    phase: PotsikPhase,
    modifier: Modifier = Modifier,
    toolName: String? = null,
    size: Dp = 96.dp,
    animationOverride: PotsikAction? = null,
    animationKey: Int = 0,
) {
    if (!look.isVisible) return

    var nowMs by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { frame -> nowMs = (frame - start) / 1_000_000f }
        }
    }

    val density = LocalDensity.current
    val boxPx = with(density) { size.toPx() }

    val isRussian = Locale.current.language.lowercase().startsWith("ru")
    val behaviour = look.behaviour()
    val engine = remember(behaviour, isRussian) { PotsikEngine(behaviour, { isRussian }) }
    LaunchedEffect(phase, toolName, animationOverride, animationKey) {
        engine.setPhase(phase, toolName, nowMs.toLong())
        animationOverride?.let { engine.playAction(it, durationMs = 2_600L, now = nowMs.toLong()) }
    }

    Box(modifier = modifier.size(size)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (boxPx <= 0f) return@Canvas
            val frame = engine.frame(nowMs.toLong())
            // The character is 56 units tall; the box is a square, so the
            // scale is the box's height over the character's height.
            val scale = boxPx / CHARACTER_HEIGHT_UNITS
            val cx = this.size.width / 2f
            val headTopY = 0f
            drawPotsikCharacter(
                look = look,
                frame = frame,
                cx = cx,
                headTopY = headTopY,
                scale = scale,
            )
        }
    }
}

/**
 * How tall the character is, in the firmware's own units.
 *
 * The firmware's `BASE_HEIGHT_NOSHADOW` — the character with the shadow
 * switched off, which is how the shipped build has it.
 */
internal const val CHARACTER_HEIGHT_UNITS = 56f

/**
 * The character in a frozen pose.
 *
 * The settings screen and the golden images both want to see one particular
 * pose, at one particular moment, rather than a character that is moving: so
 * this draws the same character with the clock stopped.
 */
@Composable
fun PotsikPosePreview(
    look: PotsikLook,
    action: PotsikAction,
    timeMs: Float,
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
) {
    if (!look.isVisible) return
    val density = LocalDensity.current
    val boxPx = with(density) { size.toPx() }

    Box(modifier = modifier.size(size)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (boxPx <= 0f) return@Canvas
            val frame = PotsikFrame(
                nowMs = timeMs.toLong(),
                mood = action.mood,
                actPose = action.visitPose,
            )
            drawPotsikCharacter(
                look = look,
                frame = frame,
                cx = this.size.width / 2f,
                headTopY = 0f,
                scale = boxPx / CHARACTER_HEIGHT_UNITS,
            )
        }
    }
}
