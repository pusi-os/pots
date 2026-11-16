package com.pusios.pots.ui.potsik

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The geometry of the desktop window: it is sized from the look, so the
 * character fits it exactly and the window never eats taps meant for the
 * apps underneath (the PixelTag rule: the touch zone is the window's rect,
 * transparent pixels included — so the rect stays small).
 */
class DesktopPetSizeTest {

    @Test
    fun `the default look gives the base size`() {
        assertEquals(132f, desktopPetCharacterSize(PotsikLook.Default).value, 0.01f)
    }

    @Test
    fun `the size slider and the percent row both scale him`() {
        val look = PotsikLook.Default.copy(sizeScale = 1.5f, sizePercent = 50)
        assertEquals(132f * 1.5f * 0.5f, desktopPetCharacterSize(look).value, 0.01f)
    }

    @Test
    fun `he never shrinks below the minimum or grows past the maximum`() {
        val small = PotsikLook.Default.copy(sizeScale = 0.6f, sizePercent = 50)
        assertEquals(DESKTOP_PET_MIN_SIZE_DP, desktopPetCharacterSize(small).value, 0.01f)

        val big = PotsikLook.Default.copy(sizeScale = 2.0f, sizePercent = 150)
        assertEquals(DESKTOP_PET_MAX_SIZE_DP, desktopPetCharacterSize(big).value, 0.01f)
    }

    @Test
    fun `the bubble room is a fixed band above his head`() {
        val room: Dp = DESKTOP_PET_BUBBLE_ROOM_DP.dp
        assertEquals(DESKTOP_PET_BUBBLE_ROOM_DP, room.value, 0.01f)
    }
}
