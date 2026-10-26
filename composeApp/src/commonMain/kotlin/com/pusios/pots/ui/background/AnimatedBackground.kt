package com.pusios.pots.ui.background

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope

/** The animated backgrounds, as the PotSquad firmware names them. */
enum class AnimatedBackground(val nameEn: String, val nameRu: String) {
    NONE("Off", "Выкл"),
    DIGITAL_RAIN("Digital rain", "Цифровой дождь"),
    STARFIELD("Starfield", "Звёздное поле"),
    TOASTERS("Toasters", "Тостеры"),
    FIREFLIES("Fireflies", "Светлячки"),
    SNOWFALL("Snowfall", "Снегопад"),
    ;

    fun name(language: String): String = if (language.startsWith("ru")) nameRu else nameEn

    companion object {
        /** Unknown or missing names fall back to no background. */
        fun fromName(name: String?): AnimatedBackground = entries.firstOrNull { it.name == name } ?: NONE
    }
}

/**
 * One background's simulation.
 *
 * Stepping and drawing are separate on purpose: [step] advances the state by
 * the real elapsed time (never by a frame count), and [draw] only reads it.
 * Drawing must not allocate per frame; keep paths, paints and arrays as
 * fields and reuse them.
 */
interface BackgroundScene {
    /** Advances the simulation by [dtMs] milliseconds, already clamped by the host. */
    fun step(dtMs: Float, nowMs: Long)

    /** Paints the current state over the whole canvas. */
    fun DrawScope.draw(nowMs: Long)
}

/** The scene for [kind], or null when no background is wanted. */
fun createBackgroundScene(kind: AnimatedBackground): BackgroundScene? = when (kind) {
    AnimatedBackground.NONE -> null
    AnimatedBackground.DIGITAL_RAIN -> DigitalRainScene()
    AnimatedBackground.STARFIELD -> StarfieldScene()
    AnimatedBackground.TOASTERS -> ToastersScene()
    AnimatedBackground.FIREFLIES -> FirefliesScene()
    AnimatedBackground.SNOWFALL -> SnowfallScene()
}

/** The longest step a frame may advance the simulation by, so a stall does not teleport things. */
private const val MAX_STEP_MS = 50f

/**
 * Draws an animated background over the whole area it is given.
 *
 * The clock is a frame callback, so the scene only runs while it is composed
 * and on screen; leaving the composition cancels the loop, so nothing keeps
 * running in the background.
 */
@Composable
fun AnimatedBackdrop(
    kind: AnimatedBackground,
    modifier: Modifier = Modifier,
) {
    if (kind == AnimatedBackground.NONE) return
    val scene = remember(kind) { createBackgroundScene(kind) } ?: return
    var nowMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(scene) {
        var lastMs = -1L
        while (true) {
            withFrameNanos { nanos ->
                val ms = nanos / 1_000_000L
                if (lastMs >= 0L) {
                    scene.step(((ms - lastMs).toFloat()).coerceIn(0f, MAX_STEP_MS), ms)
                }
                lastMs = ms
                nowMs = ms
            }
        }
    }
    Canvas(modifier.fillMaxSize()) {
        scene.run { draw(nowMs) }
    }
}

/**
 * True while an animated background is drawn behind the screens. A screen
 * that would otherwise paint its own opaque background checks this and lets the
 * animation show through instead.
 */
val LocalAnimatedBackgroundOn = compositionLocalOf { false }

/** The screen's background colour, or nothing when the animation is behind it. */
@Composable
fun Modifier.screenBackground(): Modifier = if (LocalAnimatedBackgroundOn.current) this else this.background(MaterialTheme.colorScheme.background)
