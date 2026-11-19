package com.pusios.pots.ui.potsik

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pusios.pots.LocalAppLanguageCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlin.time.TimeSource

/**
 * The character as the desktop window shows him: standing at the bottom of a
 * window that is wider than he is, with the band above his head reserved for
 * the speech bubble.
 *
 * The window's width is not his width: it is [potsikWindowGeometry] — wide
 * enough for the bubble he talks in and for the arms he throws around — so the
 * bubble and the poses are drawn whole instead of being cut by the window's
 * edge. He stands in the middle of it (`cx = size.width / 2`) and the bubble
 * is measured against the same width.
 *
 * The same renderer and the same engine the chat runs, but not the same clock:
 * this surface floats over somebody else's app on a screen that is lit, so at
 * rest it keeps [WINDOW_IDLE_FRAME_MS] (30 frames a second) instead of the
 * in-app breathing roll — the breath is the same 1100 ms, sampled 33 times
 * instead of four, and the staircase the owner saw is gone. The clock parks
 * outright only while the screen is dark, where nothing of him is visible at
 * all ([potsikParkSleepMs], [PotsikScreen]).
 *
 * He is alone here: no anchors, no room to walk — the window is the room.
 * A tap pets him; the window itself is dragged by the platform shell around
 * this composable, which is where the finger's movement belongs (the window
 * moves in raw screen coordinates, and only the shell can see those).
 *
 * The double tap is counted by hand, in the same [androidx.compose.foundation.gestures.detectTapGestures]
 * block the petting uses. Compose's own `onDoubleTap` would hold *every* tap
 * for the whole double-tap window before answering, so petting him — the
 * behaviour that already exists — would start answering with up to 300 ms of
 * lag. Here the first tap pets him at once, and the second one inside the
 * platform's window opens the cloud and takes the line back.
 */
@Composable
fun PotsikOverlayPet(
    look: PotsikLook,
    modifier: Modifier = Modifier,
    /** Which state the cloud is in, or null while it is closed. */
    cloud: PotsikCloudPhase? = null,
    /** `ViewConfiguration.getDoubleTapTimeout()`, handed in by the platform. */
    doubleTapTimeoutMs: Long = DEFAULT_DOUBLE_TAP_TIMEOUT_MS,
    onDoubleTap: (() -> Unit)? = null,
) {
    val isRussian = LocalAppLanguageCode.current.lowercase().startsWith("ru")
    val behaviour = look.behaviour()
    // The window's own tempo, not the in-app breathing roll: this is the one
    // surface that floats over a screen somebody is looking at, and four
    // frames a second there is a stutter.
    val engine = remember(behaviour, isRussian) {
        PotsikEngine(behaviour, idleFrameMs = potsikIdleFrameMs(overlayWindow = true)) { isRussian }
    }

    // His own clock, running on the wall: the window has no test clock to
    // borrow, and the poses only ever need the time that has passed.
    val timeBase = remember(engine) { TimeSource.Monotonic.markNow() }

    val density = LocalDensity.current
    // A pinch in progress decides the size, so the character grows with the
    // window instead of waiting for the fingers to lift; otherwise the look
    // does.
    val pinchSizeDp by PotsikPinch.sizeDp.collectAsState()
    val characterPx = with(density) { (pinchSizeDp ?: potsikSizeDp(look)).dp.toPx() }
    val textMeasurer = rememberTextMeasurer()
    val bubbleFill = MaterialTheme.colorScheme.surface
    val bubbleTextColor = MaterialTheme.colorScheme.onSurface
    val bubbleStyle = potsikBubbleTextStyle(12.sp)

    var nowMs by remember { mutableLongStateOf(0L) }

    // What the cloud asks of him: the same phases the chat hands the engine.
    val petLook = potsikPetLook(cloud)

    LaunchedEffect(engine) {
        while (true) {
            val seenMotion = engine.motion.value
            val animating = withFrameNanos {
                val now = timeBase.elapsedNow().inWholeMilliseconds
                nowMs = now
                engine.tick(now)
                engine.isAnimating(now)
            }
            // Two reasons to stop asking for frames, and only one of them is
            // the old economy mode: a dark screen shows nothing of him, so the
            // clock sleeps outright; a lit screen only pauses between the
            // deadlines of the tempo this surface keeps (33 ms on the window),
            // because somebody is looking at him.
            //
            // Read from the state, not from a captured value: the loop is not
            // restarted when the screen flips, so the screen coming back wakes
            // the park through the signal below.
            val screenOn = PotsikScreen.interactive.value
            if (!animating || !screenOn) {
                val parkedAt = timeBase.elapsedNow().inWholeMilliseconds
                val deadline = engine.nextEventAt(parkedAt)
                parkUntil(potsikParkSleepMs(screenOn, deadline, parkedAt)) {
                    merge(
                        engine.motion.dropWhile { it == seenMotion },
                        PotsikScreen.interactive.dropWhile { it == screenOn },
                    ).first()
                }
            }
        }
    }

    LaunchedEffect(engine, petLook) {
        val now = timeBase.elapsedNow().inWholeMilliseconds
        engine.setPhase(petLook.phase, petLook.toolName, now)
        // Listening is the idle phase with his head tipped towards the
        // microphone — the idle roll's own lean, not a new pose.
        if (petLook.leaning) engine.leanToListen(now)
        // While the cloud is up he does not talk in bubbles: the pill carries
        // the text, and the bubble would be drawn in the band the capsule sits in.
        if (cloud != null) engine.hush()
    }

    // A finger on the window is carrying him.
    //
    // The drag itself belongs to the shell view — the window moves in raw
    // screen coordinates and only the shell sees those — so it reports the
    // gesture here through the common carry flag, and the character is told
    // what the in-app gesture tells him: he hangs from the finger with both
    // arms thrown up (the catalogue's IDLE_HANDS_UP), and comes down when it
    // lifts. Same pose, same code, whichever surface is drawing him.
    val carried by PotsikCarry.carried.collectAsState()
    LaunchedEffect(engine, carried) {
        when (potsikDragEffect(carried)) {
            PotsikDragEffect.CARRY -> {
                // The engine lets go of a finger that has stopped reporting
                // for a few seconds, so a long drag keeps reporting: a carry
                // that timed out mid-drag would drop him in the air.
                while (true) {
                    engine.grabTo(0f, 0f, timeBase.elapsedNow().inWholeMilliseconds)
                    delay(CARRY_REFRESH_MS)
                }
            }

            PotsikDragEffect.RELEASE -> engine.release(timeBase.elapsedNow().inWholeMilliseconds)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(engine, doubleTapTimeoutMs, onDoubleTap) {
                val counter = PotsikTapCounter(doubleTapTimeoutMs)
                detectTapGestures {
                    val now = timeBase.elapsedNow().inWholeMilliseconds
                    when (counter.onTap(now)) {
                        // A single tap pets him at once — the way it always did.
                        PotsikTap.Single -> engine.onPetted(now)

                        // The second tap of a pair: the cloud opens and the
                        // line the first tap brought up goes away, because the
                        // pill is about to say something more useful.
                        PotsikTap.Double -> {
                            engine.hush()
                            onDoubleTap?.invoke()
                        }
                    }
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (characterPx <= 0f) return@Canvas
            val now = nowMs
            val frame = engine.frame(now)
            val scale = characterPx / CHARACTER_HEIGHT_UNITS
            val cx = size.width / 2f
            // His feet on the window's floor, his head under the bubble band.
            val headTopY = size.height - characterPx
            drawPotsikCharacter(
                look = look,
                frame = frame,
                cx = cx,
                headTopY = headTopY,
                scale = scale,
            )

            val bubble = if (cloud == null) engine.bubble(now) else null
            if (bubble != null) {
                val bubbleTop = (headTopY - 46.dp.toPx()).coerceAtLeast(0f)
                val pop = ((now - engine.bubbleStartedAt()) / 220f).coerceIn(0f, 1f)
                val eased = if (pop < 0.75f) {
                    0.55f + (pop / 0.75f) * 0.57f
                } else {
                    // The peak is the number the bubble's text limit is derived
                    // from: the window must hold the popped bubble.
                    POTSIK_BUBBLE_POP_PEAK - (POTSIK_BUBBLE_POP_PEAK - 1f) * ((pop - 0.75f) / 0.25f)
                }
                drawPotsikBubble(
                    textMeasurer = textMeasurer,
                    text = bubble,
                    style = bubbleStyle,
                    centreX = cx,
                    topY = bubbleTop,
                    maxWidth = size.width,
                    fill = bubbleFill,
                    border = PotsikPalette.VAPOR_PINK,
                    textColor = bubbleTextColor,
                    pop = eased,
                )
            }
        }
    }
}

/** How often a long drag re-reports itself to the engine, well inside its carry timeout. */
private const val CARRY_REFRESH_MS = 1_000L

/**
 * The double-tap window used when the platform does not hand one in: the
 * previews and the desktop build. On Android the real value comes from
 * `ViewConfiguration.getDoubleTapTimeout()`, because the window belongs to the
 * platform's own gesture detector, not to us.
 */
const val DEFAULT_DOUBLE_TAP_TIMEOUT_MS = 300L
