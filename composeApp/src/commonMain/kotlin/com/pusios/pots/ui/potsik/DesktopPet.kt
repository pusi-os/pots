package com.pusios.pots.ui.potsik

import androidx.compose.ui.unit.Dp
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
 * How big the character stands in the desktop window.
 *
 * The in-app base size times the user's own two dials — the same number the
 * chat draws him at, so moving between the app and the desktop does not
 * change how big he is. The window is sized from exactly this, because the
 * window's rect eats every tap inside it, transparent pixels included: a
 * bigger window would steal taps from the apps underneath.
 */
fun desktopPetCharacterSize(look: PotsikLook): Dp {
    val scaled = DESKTOP_PET_BASE_SIZE_DP * look.sizeScale * look.sizePercent / 100f
    return scaled.coerceIn(DESKTOP_PET_MIN_SIZE_DP, DESKTOP_PET_MAX_SIZE_DP).dp
}

/** The base size the chat gives him — [PotsikOverlay]'s own default. */
const val DESKTOP_PET_BASE_SIZE_DP = 132f

/** Smaller than this he is impossible to tap. */
const val DESKTOP_PET_MIN_SIZE_DP = 72f

/** Bigger than this he covers too much of somebody else's screen. */
const val DESKTOP_PET_MAX_SIZE_DP = 320f

/** The band above his head his speech bubble is drawn in. */
const val DESKTOP_PET_BUBBLE_ROOM_DP = 96f
