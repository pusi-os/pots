package com.pusios.pots.ui.potsik

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.ceil

/**
 * The system overlay window that keeps the character on the desktop while
 * the app is in the background (Android only).
 *
 * The PixelTag rule: the floating window and the in-app surface are mutually
 * exclusive — two surfaces drawing the character at once doubled him and
 * stuttered both. The overlay service reports the window through this flag
 * and the in-app overlay stands down while it is up, so there is only ever
 * one Потсик on screen.
 */
object DesktopPetWindow {

    private val _showing = MutableStateFlow(false)

    /** True while the system window is showing the character on the desktop. */
    val showing: StateFlow<Boolean> = _showing.asStateFlow()

    /** The overlay service reports the window going up and coming down. */
    fun setShowing(showing: Boolean) {
        _showing.value = showing
    }
}

/**
 * A finger is carrying the character right now.
 *
 * The drag of the system window lives in the platform's shell view, which
 * sees the raw screen coordinates and knows nothing of Compose; the character
 * is drawn by the common layer. This flag is the whole handshake between
 * them: the shell decides *when* he is carried, the common layer decides what
 * being carried looks like.
 */
object PotsikCarry {

    private val _carried = MutableStateFlow(false)

    /** True while a finger is carrying him. */
    val carried: StateFlow<Boolean> = _carried.asStateFlow()

    /** Whoever owns the gesture reports it starting and ending. */
    fun setCarried(carried: Boolean) {
        _carried.value = carried
    }
}

/**
 * The size a pinch is at, frame by frame.
 *
 * A pinch on the desktop window resizes the window with `updateViewLayout`
 * and has to resize the character inside it in the same frame — otherwise he
 * would sit at his old size in a window that has already grown. This is that
 * number; the look is written once, when the fingers lift. Null means no
 * pinch is running and the look decides.
 */
object PotsikPinch {

    private val _sizeDp = MutableStateFlow<Float?>(null)

    /** The size the pinch has reached, or null when the look decides. */
    val sizeDp: StateFlow<Float?> = _sizeDp.asStateFlow()

    /** Whoever owns the pinch reports every frame, and null when it ends. */
    fun set(sizeDp: Float?) {
        _sizeDp.value = sizeDp
    }
}

/**
 * Whether the screen the window stands on is lit.
 *
 * The platform shell owns the answer — `PowerManager.isInteractive`, which
 * flips on `ACTION_SCREEN_OFF` and `ACTION_SCREEN_ON` — and reports it here.
 * It is the one condition under which the character's clock may stop asking
 * for frames at all: a dark screen shows nothing of him, so a full park costs
 * nothing. Everything else (a lit screen nobody touches) keeps the clock
 * ticking at the window's own tempo, because the owner's rule is that he
 * breathes smoothly the whole way and does not drop into a money-saving mode
 * while somebody is looking at him.
 *
 * Defaults to `true`: a surface that never reports the screen must never leave
 * the character parked. Whoever owns the receiver resets it on the way out for
 * the same reason — a flag stuck at "dark" with the window gone is a character
 * who comes back frozen.
 */
object PotsikScreen {

    private val _interactive = MutableStateFlow(true)

    /** True while the screen is on. */
    val interactive: StateFlow<Boolean> = _interactive.asStateFlow()

    /** The platform reports every screen-off and screen-on it sees. */
    fun setInteractive(interactive: Boolean) {
        _interactive.value = interactive
    }
}

/**
 * What a finger carrying the character does to him.
 *
 * The carry is the renderer's own pose — `PotsikFrame.dangle`, both arms
 * thrown up and windmilling while the legs kick — which the catalogue calls
 * `IDLE_HANDS_UP`, "Both hands thrown up in surprise". That is the nearest
 * thing the firmware has to being picked up, so a drag reuses it rather than
 * adding a nineteenth animation nobody could check on a device.
 */
enum class PotsikDragEffect {
    /** The finger is on him: he hangs from it, arms up. */
    CARRY,

    /** The finger lifted: he comes down again. */
    RELEASE,
}

/** The effect a drag in progress has, and the one letting go has. */
fun potsikDragEffect(dragging: Boolean): PotsikDragEffect = if (dragging) PotsikDragEffect.CARRY else PotsikDragEffect.RELEASE

/**
 * How big the character stands.
 *
 * One number decides it: [PotsikLook.sizeScale] times the base size, clamped
 * to the range he is allowed in. The window is sized from this number and the
 * bubble's room ([potsikWindowGeometry]) — not from the character alone,
 * because the bubble is drawn inside the same window and its rect eats every
 * tap inside it, transparent pixels included.
 *
 * The pinch writes the same number, so a size set with two fingers on the
 * desktop is the size he comes back at.
 */
fun desktopPetCharacterSize(look: PotsikLook): Dp = potsikSizeDp(look).dp

/** The size he may have: smaller is impossible to tap, bigger covers somebody's screen. */
fun clampPotsikSizeDp(sizeDp: Float): Float = sizeDp.coerceIn(DESKTOP_PET_MIN_SIZE_DP, DESKTOP_PET_MAX_SIZE_DP)

/** The look's size in dp — the one number the drawing, the window and the pinch share. */
fun potsikSizeDp(look: PotsikLook): Float = clampPotsikSizeDp(DESKTOP_PET_BASE_SIZE_DP * look.sizeScale)

/**
 * The look's size for an absolute size in dp.
 *
 * A pinch ends on a size, and this is how that size is written down. The
 * result is clamped, so a look can never hold a size the window could not
 * have.
 */
fun potsikSizeScale(sizeDp: Float): Float = clampPotsikSizeDp(sizeDp) / DESKTOP_PET_BASE_SIZE_DP

/**
 * The size a pinch leaves him at.
 *
 * The fingers' span scales the size he had when they landed: the ratio is a
 * ratio of distances, so moving the window under the fingers does not change
 * it. A pinch that starts on a zero span (both fingers on one pixel) and a
 * pinch that ends on one keep the size they had — dividing by zero would
 * throw the character to a random size.
 */
fun pinchedPotsikSize(startSizeDp: Float, startSpanPx: Float, spanPx: Float): Float {
    if (startSpanPx <= 0f || spanPx <= 0f) return clampPotsikSizeDp(startSizeDp)
    return clampPotsikSizeDp(startSizeDp * spanPx / startSpanPx)
}

/**
 * Where a window may sit: the whole of it inside the screen.
 *
 * The window carries `FLAG_LAYOUT_NO_LIMITS` (the character may hang over the
 * status bar), which also lets a drag put him past the edges — and a pet
 * dragged off the screen is a pet the user cannot get back. This is the rule
 * the in-app character obeys in `clampPlacement`, and the one the capsule's
 * window obeys on the desktop.
 *
 * The capsule's window obeys this one because it *is* its content: keeping the
 * window on the screen is the same as keeping the capsule on the screen. The
 * character's window is bigger than he is and obeys [clampPotsikWindowOffset]
 * instead.
 */
fun clampWindowOffset(
    x: Int,
    y: Int,
    windowWidth: Int,
    windowHeight: Int,
    screenWidth: Int,
    screenHeight: Int,
): IntOffset {
    val maxX = (screenWidth - windowWidth).coerceAtLeast(0)
    val maxY = (screenHeight - windowHeight).coerceAtLeast(0)
    return IntOffset(x.coerceIn(0, maxX), y.coerceIn(0, maxY))
}

/**
 * The size of the character's window, in pixels.
 *
 * Two numbers set the width, and the wider one wins:
 *
 * - **his arms** — `1.15 · C`. The widest pose he draws without a costume
 *   (`WAVE`, the hand reaching 28.8 units out of 56 plus half a hand) needs
 *   1.15 times his height, and a window narrower than that cut his hands off
 *   in the poses he spends the day in;
 * - **his speech bubble** — 191 dp. The bubble is drawn inside the same
 *   window, and the widest of the 313 lines in `PotsikBanter` needs 150 dp of
 *   text: that is 90 % of the lines in two rows and every one of them in
 *   three, so the three-row cap costs nothing. 191 dp is that number with the
 *   padding and the pop's 1.12 taken out.
 *
 * The height stays `C + 96 dp` — the body and the band the bubble is drawn in.
 * [screenWidthPx] caps the width, so the window is never wider than the screen
 * it stands on; the floor of `C` keeps it from being narrower than the
 * character even when the screen is.
 *
 * The character stands in the middle of the window (`cx = size.width / 2` in
 * the drawing code), so a window that changes width has to move its left edge
 * by half the difference: [potsikCharacterCxOnScreen] and
 * [potsikWindowXForCentre] are that arithmetic.
 */
fun potsikWindowGeometry(
    characterPx: Int,
    density: Float,
    bubbleRoomDp: Float,
    screenWidthPx: Int,
): PotsikWindowGeometry {
    val body = characterPx.coerceAtLeast(1)
    val forArms = ceil(POTSIK_WINDOW_ARMS_FACTOR * body).toInt()
    val forBubble = ceil(POTSIK_MIN_WINDOW_WIDTH_DP * density).toInt()
    val width = maxOf(forArms, forBubble).coerceAtMost(maxOf(screenWidthPx, body))
    val height = body + ceil(bubbleRoomDp * density).toInt()
    return PotsikWindowGeometry(width, height)
}

/** The window the character stands in, in pixels. */
data class PotsikWindowGeometry(val widthPx: Int, val heightPx: Int)

/** The screen x of the character's middle in a window whose left edge is [windowX]. */
fun potsikCharacterCxOnScreen(windowX: Int, windowWidthPx: Int): Int = windowX + windowWidthPx / 2

/** The window's left edge that puts the character's middle at [characterCxOnScreen]. */
fun potsikWindowXForCentre(characterCxOnScreen: Int, windowWidthPx: Int): Int = characterCxOnScreen - windowWidthPx / 2

/**
 * Where the character's window may sit: his *bubble* stays on the screen, the
 * window's empty corners may hang over the edge.
 *
 * The window is wider and taller than he is (the bubble's room), so clamping
 * the *window* into the screen — [clampWindowOffset], the rule the capsule
 * obeys — would leave a dead strip of `(W − C) / 2` on each side that he can
 * never reach: 59 dp of screen at the smallest size.
 *
 * Clamping only his body is not enough either. The bubble is drawn centred on
 * him and its tail points at his head, so it cannot be moved off his middle to
 * dodge an edge; what has to fit is the bubble's width around his middle. The
 * body therefore stops half a bubble short of the edge — 49.5 / 19.5 / 4.5 dp
 * at 72 / 132 / 320 dp — and the line he says is whole at every position. A
 * body-only clamp would put him on the last pixel and let the display cut half
 * of the bubble, which is the owner's complaint moved from the window's edge
 * to the screen's.
 *
 * The bubble's width is [potsikBubbleWidthFor] — the widest bubble the text
 * limit allows in this window, so the rule holds for the longest line and is
 * merely conservative for the short ones.
 *
 * `FLAG_LAYOUT_NO_LIMITS` is what makes the window hanging over an edge legal.
 */
fun clampPotsikWindowOffset(
    x: Int,
    y: Int,
    characterPx: Int,
    windowWidth: Int,
    windowHeight: Int,
    screenWidth: Int,
    screenHeight: Int,
): IntOffset {
    // His middle on the screen must sit at least half a bubble in from either
    // edge. On a screen narrower than the bubble the two bounds meet in the
    // middle instead of running into the negatives. The half is rounded up:
    // rounding down would leave the bubble half a pixel over the edge, which
    // is the clip this whole rule is about.
    val halfBubble = ceil(potsikBubbleWidthFor(windowWidth.toFloat()) / 2f).toInt()
    val reach = halfBubble.coerceAtMost(screenWidth / 2)
    val minX = reach - windowWidth / 2
    val maxX = screenWidth - reach - windowWidth / 2
    // His feet stand on the window's floor and the band above his head may
    // hang over the top edge: he reaches the top and the bottom.
    val minY = characterPx - windowHeight
    val maxY = screenHeight - windowHeight
    return IntOffset(clampToRange(x, minX, maxX), clampToRange(y, minY, maxY))
}

/** `coerceIn` that survives an empty range: a character bigger than the screen starts at the edge. */
private fun clampToRange(value: Int, lo: Int, hi: Int): Int = if (lo >= hi) lo else value.coerceIn(lo, hi)

/**
 * How often a parked clock wakes him while he is at rest, on the surface that
 * draws him: the in-app breathing roll, or the window's own tempo.
 *
 * `null` leaves the engine its breathing tick — `BREATHE_MIN_MS` to
 * `BREATHE_MAX_MS`, four to eight frames a second. That is right inside the
 * app, where the character shares a screen with the chat and the clock parks
 * between the ticks; on a window floating over somebody else's app it is the
 * stutter the owner reported: a 1100 ms breath sampled four times is a
 * staircase, not a breath.
 *
 * A window gets [WINDOW_IDLE_FRAME_MS] — 30 frames a second, half the cost of
 * an honest 60, and 33 samples per breath, so there are no steps left to see.
 */
internal fun potsikIdleFrameMs(overlayWindow: Boolean): Long? = if (overlayWindow) WINDOW_IDLE_FRAME_MS else null

/** 33 ms ≈ 30 fps: a third of a 100 ms tick, and 33 samples per 1100 ms breath. */
internal const val WINDOW_IDLE_FRAME_MS = 33L

/**
 * How long the parked clock sleeps, in milliseconds, or `null` to wait for a
 * signal alone.
 *
 * Two conditions, and they are different questions:
 *
 * - a **lit** screen keeps the engine's own next deadline — the breathing tick
 *   in the app, [WINDOW_IDLE_FRAME_MS] on the window. He is being looked at,
 *   so the clock keeps turning at the tempo of the surface that draws him;
 * - a **dark** screen is the one case where nothing of him is visible: the
 *   clock takes the sleep that costs nothing and wakes on the screen coming
 *   back or on something touching him. That is the whole of the old v3.13.1
 *   economy mode, kept where it belongs.
 */
internal fun potsikParkSleepMs(screenInteractive: Boolean, nextEventAt: Long?, now: Long): Long? = if (!screenInteractive) null else nextEventAt?.minus(now)?.coerceAtLeast(1L)

/** The base size the chat gives him — [PotsikOverlay]'s own default. */
const val DESKTOP_PET_BASE_SIZE_DP = 132f

/** Smaller than this he is impossible to tap. */
const val DESKTOP_PET_MIN_SIZE_DP = 72f

/** Bigger than this he covers too much of somebody else's screen. */
const val DESKTOP_PET_MAX_SIZE_DP = 320f

/** The band above his head his speech bubble is drawn in. */
const val DESKTOP_PET_BUBBLE_ROOM_DP = 96f

/**
 * How much wider than his body the window must be for the arms he throws
 * around: `WAVE` reaches 28.8 units out of 56, plus half a hand.
 */
internal const val POTSIK_WINDOW_ARMS_FACTOR = 1.15f

/**
 * The narrowest the window may be, in dp.
 *
 * 191 dp of window is 150 dp of text at the lowest density there is
 * (`(150 + 20) · 1.12 = 190.4`): the padding is a fixed 20 px and the pop
 * scales the bubble to 1.12 of its width, so the window has to be 190.4 dp
 * before the text box reaches the 150 dp the 313 lines were measured against —
 * 90 % of them in two rows, all of them in three. The width is the price of
 * the bubble, and it is paid in taps: the strip beside the character inside
 * the window belongs to him.
 */
internal const val POTSIK_MIN_WINDOW_WIDTH_DP = 191f
