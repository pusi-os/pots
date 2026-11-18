package com.pusios.pots.ui.potsik

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

/** The catalogue's name for the pose the carry draws. */
val PotsikDragEffect.animation: PotsikAction?
    get() = when (this) {
        PotsikDragEffect.CARRY -> PotsikAction.IDLE_HANDS_UP
        PotsikDragEffect.RELEASE -> null
    }

/**
 * How big the character stands.
 *
 * One number decides it: [PotsikLook.sizeScale] times the base size, clamped
 * to the range he is allowed in. The window is sized from exactly this,
 * because the window's rect eats every tap inside it, transparent pixels
 * included: a bigger window would steal taps from the apps underneath.
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
 * Where the window may sit: the whole of it inside the screen.
 *
 * The window carries `FLAG_LAYOUT_NO_LIMITS` (the character may hang over the
 * status bar), which also lets a drag put him past the edges — and a pet
 * dragged off the screen is a pet the user cannot get back. This is the rule
 * the shell applies whenever a gesture ends and whenever the window changes
 * size, and the same rule the in-app character obeys in `clampPlacement`.
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

/** The base size the chat gives him — [PotsikOverlay]'s own default. */
const val DESKTOP_PET_BASE_SIZE_DP = 132f

/** Smaller than this he is impossible to tap. */
const val DESKTOP_PET_MIN_SIZE_DP = 72f

/** Bigger than this he covers too much of somebody else's screen. */
const val DESKTOP_PET_MAX_SIZE_DP = 320f

/** The band above his head his speech bubble is drawn in. */
const val DESKTOP_PET_BUBBLE_ROOM_DP = 96f
