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
 *
 * One size, one number: `132 dp × sizeScale`, clamped. The old second dial is
 * folded into that number on load, and that fold is what keeps an existing
 * user's character the size they chose.
 */
class DesktopPetSizeTest {

    @Test
    fun `the default look gives the base size`() {
        assertEquals(132f, desktopPetCharacterSize(PotsikLook.Default).value, 0.01f)
    }

    @Test
    fun `the single size dial scales him`() {
        val look = PotsikLook.Default.copy(sizeScale = 1.5f)
        assertEquals(132f * 1.5f, desktopPetCharacterSize(look).value, 0.01f)
    }

    @Test
    fun `he never shrinks below the minimum or grows past the maximum`() {
        assertEquals(
            DESKTOP_PET_MIN_SIZE_DP,
            desktopPetCharacterSize(PotsikLook.Default.copy(sizeScale = 0.1f)).value,
            0.01f,
        )
        assertEquals(
            DESKTOP_PET_MAX_SIZE_DP,
            desktopPetCharacterSize(PotsikLook.Default.copy(sizeScale = 9f)).value,
            0.01f,
        )
    }

    @Test
    fun `the old percent row is folded into the one size`() {
        val old = PotsikLook.Default.copy(sizeScale = 1.2f, sizePercent = 150)
        val migrated = old.withSizeMigrated()

        assertEquals(1.8f, migrated.sizeScale, 0.0001f)
        assertEquals(100, migrated.sizePercent, "the legacy field is parked, never read again")
        // What he was drawn at before the change is what he is drawn at after.
        assertEquals(132f * 1.2f * 1.5f, desktopPetCharacterSize(migrated).value, 0.01f)
    }

    @Test
    fun `a look that never used the percent row is left exactly as it is`() {
        val look = PotsikLook.Default.copy(sizeScale = 0.9f)
        assertEquals(look, look.withSizeMigrated())
    }

    @Test
    fun `folding twice is the same as folding once`() {
        val once = PotsikLook.Default.copy(sizeScale = 0.8f, sizePercent = 75).withSizeMigrated()
        assertEquals(once, once.withSizeMigrated())
    }

    @Test
    fun `the bubble room is a fixed band above his head`() {
        val room: Dp = DESKTOP_PET_BUBBLE_ROOM_DP.dp
        assertEquals(DESKTOP_PET_BUBBLE_ROOM_DP, room.value, 0.01f)
    }
}
