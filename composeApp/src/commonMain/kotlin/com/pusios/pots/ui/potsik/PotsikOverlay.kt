package com.pusios.pots.ui.potsik

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pusios.pots.LocalAppLanguageCode
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The character's stage, for the parts of the chat that hold a place for him.
 *
 * The stage is a singleton, so this is only a shortcut for the places in the
 * layout that have to report where they ended up — the thread through every
 * composable that holds a slot for the character would be a long one.
 */
val LocalPotsikStage = compositionLocalOf<PotsikStage> {
    error("No PotsikStage in this composition")
}

/**
 * Потсик floating over the chat.
 *
 * One copy of the character, drawn over everything else, at the position the
 * [stage] holds. The chat layout puts anchors where the character belongs —
 * the empty screen, the tile shown while a request is running, the footer
 * under the last answer — and the stage picks the one that applies.
 *
 * He is the PotSquad firmware's character, drawn by the ported renderer and
 * animated by the ported engine: the same moods, the same poses, the same
 * bubble, the same way of reacting to being tapped, held, stroked and
 * dropped.
 *
 * Everything about him — drawing, the touch and the bounds — is worked out
 * from one geometry, [potsikPose], in the overlay's own coordinates. The
 * touch layer covers the whole overlay, so a finger can take hold of him
 * anywhere on the screen and a second finger can pinch him even when it lands
 * outside the character.
 */
@Composable
fun PotsikOverlay(
    stage: PotsikStage,
    look: PotsikLook,
    phase: PotsikPhase,
    modifier: Modifier = Modifier,
    toolName: String? = null,
    baseSize: Dp = 132.dp,
    hasMessages: Boolean = false,
) {
    // The character's own language: the app's, which is the device's.
    val isRussian = LocalAppLanguageCode.current.lowercase().startsWith("ru")

    if (!look.isVisible) return

    val placement by stage.placement.collectAsState()
    val area by stage.area.collectAsState()
    val settings by stage.settings.collectAsState()
    val density = LocalDensity.current
    val basePx = with(density) { baseSize.toPx() }
    val textMeasurer = rememberTextMeasurer()
    val bubbleFill = MaterialTheme.colorScheme.surface
    val bubbleTextColor = MaterialTheme.colorScheme.onSurface
    val bubbleStyle = potsikBubbleTextStyle(12.sp)

    // The clock the character runs on. A frame callback rather than an
    // infinite transition, because the poses need an absolute time to work
    // from (a wave's phase, a blink's slot, the gum bubble's growth).
    //
    // The time is kept in a state, and the canvas below reads it. That is the
    // point of it: the canvas is redrawn whenever the state it read changes,
    // so the character is drawn again every frame. A canvas that is only
    // redrawn when something else on the screen changes would leave him
    // frozen in one pose, with a bubble that never goes away.
    var nowMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        // The last landing the app has taken note of, so the character is
        // only put where he came down once.
        var landedAt = 0L
        while (true) {
            withFrameNanos {
                val now = stage.currentTimeMs()
                // The room he is in: the width, the line his feet stand on
                // and how big he is. The throw needs all three, and they are
                // the numbers the layout gives him.
                val area = stage.area.value
                val here = stage.placement.value
                // One size model: the placement holds the factor, so the
                // drawing, the touch and the bounds all agree on how big he is.
                val characterPx = basePx * here.scale
                val unit = characterPx / CHARACTER_HEIGHT_UNITS
                val engine = stage.engine
                engine.setRoom(
                    width = area.width.toFloat(),
                    floorY = here.y * area.height,
                    ceilingY = 0f,
                    scale = unit,
                )
                nowMs = now
                // The engine advances once per frame: the moods expire, the
                // idle roll runs, and the bubble's clock moves on.
                engine.tick(now)

                // He has come down: put him where he landed, so the layout
                // and the drawing agree about where he is.
                if (engine.isSitting() && engine.landedAt() != landedAt) {
                    landedAt = engine.landedAt()
                    if (area.width > 0 && area.height > 0) {
                        val feet = engine.thrownY() + 53f * unit
                        stage.moveTo(
                            x = engine.thrownX() / area.width,
                            y = feet / area.height,
                        )
                    }
                    engine.clearThrow()
                }
            }
        }
    }

    LaunchedEffect(basePx, look.sizeScale) {
        stage.setBaseCharacterSize(basePx)
        stage.setSizePreference(look.sizeScale)
    }

    // The behaviour settings come from the look, which is the bundle that is
    // persisted — so the engine always has the settings the user last chose.
    val behaviour = look.behaviour()
    LaunchedEffect(behaviour, isRussian) {
        stage.setSettings(behaviour, isRussian)
    }

    // Put him back where the user left him, if the placement mode keeps a
    // move at all.
    LaunchedEffect(behaviour.placementMode, look.pinnedX, look.pinnedY) {
        stage.restorePinned(look.pinnedX, look.pinnedY)
    }

    // The chat tells the engine what it is doing, and the engine decides how
    // he shows it.
    LaunchedEffect(phase, toolName) {
        stage.engine.setPhase(phase, toolName, stage.currentTimeMs())
    }

    CompositionLocalProvider(LocalPotsikStage provides stage) {
        val placement by stage.placement.collectAsState()
        val area by stage.area.collectAsState()
        val density = LocalDensity.current
        Box(
            modifier = modifier
                .fillMaxSize()
                .onGloballyPositioned { coordinates ->
                    stage.setArea(coordinates.size)
                    stage.setAreaOrigin(coordinates.positionInWindow())
                },
        ) {
            // The character is drawn from the stage's own numbers, read as the
            // canvas draws rather than as the composition is built: the places
            // in the layout are only measured during the layout pass, so a
            // position worked out earlier would be a frame out of date.
            Canvas(modifier = Modifier.fillMaxSize()) {
                // The clock is read here, and that is what makes the canvas
                // redraw: the character is drawn again every frame, so he
                // moves instead of standing in one pose.
                val pose = potsikPose(stage, nowMs, basePx) ?: return@Canvas
                val box = pose.box
                drawPotsikCharacter(
                    look = look,
                    frame = pose.frame,
                    cx = box.cx,
                    headTopY = box.headTopY,
                    scale = box.scale,
                )

                // The bubble, above his head, in the same pass so it is
                // always in step with him.
                val now = nowMs
                val engine = stage.engine
                val bubble = engine.bubble(now)
                if (bubble != null) {
                    val bubbleTop = box.headTopY - 46.dp.toPx()
                    // The pop: the bubble grows out of its middle, overshoots
                    // and settles — the firmware's pop, with a floor so the
                    // line is readable from the first frame.
                    val pop = ((now - engine.bubbleStartedAt()) / 220f).coerceIn(0f, 1f)
                    val eased = if (pop < 0.75f) {
                        0.55f + (pop / 0.75f) * 0.57f
                    } else {
                        1.12f - 0.12f * ((pop - 0.75f) / 0.25f)
                    }
                    drawPotsikBubble(
                        textMeasurer = textMeasurer,
                        text = bubble,
                        style = bubbleStyle,
                        centreX = box.cx,
                        topY = bubbleTop,
                        maxWidth = size.width - 32f,
                        fill = bubbleFill,
                        border = PotsikPalette.VAPOR_PINK,
                        textColor = bubbleTextColor,
                        pop = eased,
                    )
                }
            }

            // Touch target: only the character's own box, so the rest of the
            // screen keeps its clicks and scrolls. The box follows the place
            // the layout gave him; drags and pinches are handled here.
            if (area.width > 0 && area.height > 0 && placement.scale > 0f) {
                val characterPx = basePx * placement.scale
                val left = (placement.x * area.width - characterPx / 2f).roundToInt()
                val top = (placement.y * area.height - characterPx).roundToInt()
                val characterDp = with(density) { characterPx.toDp() }
                Box(
                    modifier = Modifier
                        .offset { IntOffset(left, top) }
                        .size(characterDp)
                        .pointerInput(stage, basePx) {
                            trackCharacter(stage = stage, basePx = basePx)
                        },
                )
            }
        }
    }
}

/**
 * Where he is drawn at [nowMs], in the overlay's own coordinates.
 *
 * The drawing and the touch both read this one function, so what the finger
 * grabs is exactly what is on the screen — including the sweep of a walk and
 * the arc of a throw, which move him away from his place in the layout.
 *
 * Null when the overlay has not been measured yet.
 */
internal fun potsikPose(stage: PotsikStage, nowMs: Long, basePx: Float): PotsikPose? {
    val area = stage.area.value
    if (area.width <= 0 || area.height <= 0) return null
    val here = stage.placement.value
    if (here.scale <= 0f) return null
    val characterPx = basePx * here.scale
    val frame = stage.engine.frame(nowMs)
    // While he walks he crosses the room he is standing in — the firmware's
    // sweep, kept inside the spot he was put at.
    val room = (area.width - characterPx) / 2f
    val walkPx = frame.walkSweep * room
    // The character is 56 units tall; the scale is the drawn height over the
    // unit count.
    val unit = characterPx / CHARACTER_HEIGHT_UNITS
    val box = if (frame.thrown) {
        PotsikBox(cx = frame.thrownX, headTopY = frame.thrownY, scale = unit)
    } else {
        PotsikBox(
            cx = here.x * area.width + walkPx,
            headTopY = here.y * area.height - characterPx,
            scale = unit,
        )
    }
    return PotsikPose(frame = frame, box = box, characterPx = characterPx)
}

/** The character as he is drawn at one moment: his frame and his touch box. */
internal class PotsikPose(
    val frame: PotsikFrame,
    val box: PotsikBox,
    val characterPx: Float,
)

/**
 * The finger on the character.
 *
 * The firmware's own gesture model: a tap pets him, a stroke strokes him, a
 * drag carries him — he hangs from the finger and kicks his legs — and letting
 * go ends the carry. A finger that was still moving when it let go throws him;
 * one that had already stopped drops him.
 *
 * Two fingers are a pinch: he is made bigger or smaller, proportionally, and
 * the gesture then waits for every finger to lift before it does anything
 * else, so the second finger never turns into a carry.
 *
 * A touch that does not start on him is left alone — it is not consumed, so
 * the chat underneath keeps its scrolling and its taps.
 */
private suspend fun PointerInputScope.trackCharacter(
    stage: PotsikStage,
    basePx: Float,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val startMs = stage.currentTimeMs()
        val characterPx = basePx * stage.placement.value.scale
        val box = PotsikBox(
            cx = characterPx / 2f,
            headTopY = 0f,
            scale = if (characterPx > 0f) characterPx / CHARACTER_HEIGHT_UNITS else 1f,
        )
        // His touch: the chat underneath must not also take this tap.
        down.consume()

        val start = down.position
        val trackedId = down.id
        var moved = false
        var carried = false
        var held = false
        var pinched = false
        var lastDx = 0f
        var strokeMs = 0L

        while (true) {
            val event = awaitPointerEvent()
            val now = stage.currentTimeMs()
            val pressed = event.changes.count { it.pressed }

            if (pressed >= 2) {
                // A pinch. The zoom is read from both fingers wherever they
                // are; a carry in progress is let go of first.
                pinched = true
                val zoom = event.calculateZoom()
                if (zoom != 1f) stage.scaleBy(zoom)
                if (carried) {
                    stage.engine.release(now)
                    carried = false
                }
                event.changes.forEach { it.consume() }
                continue
            }

            if (pinched) {
                // After a pinch the gesture only waits for the fingers to lift.
                if (pressed == 0) break
                continue
            }

            val tracked = event.changes.firstOrNull { it.id == trackedId }
            if (tracked == null || !tracked.pressed) {
                // The finger that took hold of him let go.
                if (carried) {
                    // The carry ends here: a moving finger throws him, a
                    // still one drops him.
                    stage.engine.release(now)
                } else if (!moved) {
                    // A tap on him: he bounces and says something.
                    stage.engine.noteTapAt(down.position.x, down.position.y, box)
                    stage.engine.onPetted(now)
                } else if (strokeMs < FLICK_MS && abs(lastDx) > FLICK_PX) {
                    // A stroke that covered fifty pixels in under a third of
                    // a second is a flick, not a pet.
                    stage.engine.flick(if (lastDx > 0) 1 else -1, now)
                }
                break
            }

            val dx = tracked.position.x - start.x
            val dy = tracked.position.y - start.y
            if (!moved && dx * dx + dy * dy > MOVE_PX * MOVE_PX) {
                moved = true
                strokeMs = now - startMs
            }
            lastDx = dx

            // A finger that stays put is a hold: he says so and bounces.
            if (!held && !moved && now - startMs >= HOLD_MS) {
                held = true
                stage.engine.onHeld(now)
            }

            if (moved) {
                // The drag is ours now: the chat must not scroll under it.
                tracked.consume()
                carried = true
                val size = stage.area.value
                // The placement mode and the draggable switch decide whether
                // the finger may move him at all; a tap always works.
                val canMove = stage.canMove && stage.settings.value.draggable
                if (canMove && size.width > 0 && size.height > 0) {
                    val pan = tracked.positionChangeIgnoreConsumed()
                    stage.moveBy(pan.x / size.width, pan.y / size.height)
                    // The engine is told as well, so he hangs from the finger
                    // while he is carried, the way the firmware's does.
                    stage.engine.grabTo(
                        x = stage.placement.value.x * size.width,
                        y = stage.placement.value.y * size.height,
                        now = now,
                    )
                }
                // The chatter: he says something now and then while he is
                // being carried about.
                stage.engine.onPetting(now)
            }
        }
    }
}

/** How far a finger has to move before the drag is a drag. */
private const val MOVE_PX = 12f

/** How long a finger has to stay still before it is a hold. */
private const val HOLD_MS = 600L

/** How fast a stroke has to be for it to be a flick. */
private const val FLICK_MS = 320L

/** How far a stroke has to go for it to be a flick. */
private const val FLICK_PX = 48f

/**
 * A place in the chat layout where the character belongs.
 *
 * The anchor measures itself and tells the stage where it ended up. It draws
 * nothing — the character is drawn by [PotsikOverlay], so there is only ever
 * one of him on screen.
 *
 * The size of the anchor is the size the character should be there: the empty
 * screen's big, the working tile's larger, the footer's small.
 */
@Composable
internal fun PotsikAnchor(
    kind: PotsikAnchorKind,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
) {
    val stage = LocalPotsikStage.current
    val density = LocalDensity.current
    val sizePx = with(density) { size.toPx() }

    // The anchor is only a place, not a thing: when the layout drops it, the
    // place goes with it, and the character moves on to the next one.
    DisposableEffect(kind) {
        onDispose { stage.setAnchor(kind, null) }
    }

    Box(
        modifier = modifier
            .size(size)
            .onGloballyPositioned { coordinates ->
                val rect = coordinates.boundsInWindow()
                stage.setAnchor(
                    kind = kind,
                    spec = PotsikAnchorSpec(
                        centerX = rect.center.x,
                        feetY = rect.bottom,
                        sizePx = sizePx,
                    ),
                )
            },
    )
}
