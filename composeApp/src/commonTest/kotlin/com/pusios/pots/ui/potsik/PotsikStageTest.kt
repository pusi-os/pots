package com.pusios.pots.ui.potsik

import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rule that keeps Потсик on the screen, and the stage that applies it.
 *
 * The agent's tool and the user's own dragging both go through the stage, so
 * these are the tests that stand between "the character can be moved" and "the
 * character can be moved off the screen".
 */
class PotsikStageTest {

    private val screenWidth = 1000f
    private val screenHeight = 2000f
    private val characterSize = 200f

    private fun clamp(requested: PotsikPlacement) = clampPlacement(
        requested = requested,
        areaWidth = screenWidth,
        areaHeight = screenHeight,
        baseCharacterSize = characterSize,
    )

    @Test
    fun `a character in the middle stays there`() {
        val placement = clamp(PotsikPlacement(x = 0.5f, y = 0.5f))
        assertEquals(0.5f, placement.x, 0.001f)
        assertEquals(0.5f, placement.y, 0.001f)
    }

    @Test
    fun `a character pushed off the left edge is pulled back into view`() {
        // The character is 200 wide on a 1000 wide screen, so his centre can
        // come no closer to the edge than a tenth of the width.
        val placement = clamp(PotsikPlacement(x = 0f, y = 0.5f))
        assertEquals(0.1f, placement.x, 0.001f)
    }

    @Test
    fun `a character pushed off the right edge is pulled back into view`() {
        val placement = clamp(PotsikPlacement(x = 1f, y = 0.5f))
        assertEquals(0.9f, placement.x, 0.001f)
    }

    @Test
    fun `a character pushed off the top edge is pulled back into view`() {
        // His feet are the anchor, so the top edge is a tenth of the screen
        // above them at this size.
        val placement = clamp(PotsikPlacement(x = 0.5f, y = 0f))
        assertEquals(0.1f, placement.y, 0.001f)
    }

    @Test
    fun `a character pushed off the bottom edge is pulled back into view`() {
        val placement = clamp(PotsikPlacement(x = 0.5f, y = 1f))
        assertEquals(1f, placement.y, 0.001f)
    }

    @Test
    fun `a character scaled up past the screen is scaled back to fit`() {
        // Five times his size would be 1000 wide — exactly the screen's short
        // side — but the scale itself is capped at four times, so that is
        // where it stops.
        val placement = clamp(PotsikPlacement(x = 0.5f, y = 0.5f, scale = 20f))
        assertEquals(PotsikStage.MAX_SCALE, placement.scale, 0.001f)
    }

    @Test
    fun `a character scaled down keeps its place`() {
        val placement = clamp(PotsikPlacement(x = 0.5f, y = 0.5f, scale = 0.5f))
        assertEquals(0.5f, placement.scale, 0.001f)
        assertEquals(0.5f, placement.x, 0.001f)
    }

    @Test
    fun `nothing is clamped before the screen reports its size`() {
        val placement = clampPlacement(
            requested = PotsikPlacement(x = 0.5f, y = 0.5f),
            areaWidth = 0f,
            areaHeight = 0f,
            baseCharacterSize = 0f,
        )
        assertEquals(0.5f, placement.x, 0.001f)
        assertEquals(0.5f, placement.y, 0.001f)
    }

    @Test
    fun `the stage clamps a move the agent asks for`() {
        val stage = stage()
        stage.moveTo(0f, 0f)
        val placement = stage.placement.value
        // The anchor's character is half the base size, so he is 100 wide on
        // a 1000 wide screen and 100 tall on a 2000 tall one: his centre can
        // come no closer to the edge than a twentieth of the width.
        assertEquals(0.05f, placement.x, 0.001f)
        assertEquals(0.05f, placement.y, 0.001f)
    }

    @Test
    fun `a move pins the character`() {
        val stage = stage()
        assertFalse(stage.isPinned.value)
        stage.moveTo(0.3f, 0.7f)
        assertTrue(stage.isPinned.value)
    }

    @Test
    fun `resetting puts the character back where the layout wants him`() {
        val stage = stage()
        stage.moveTo(0.3f, 0.7f)
        stage.resetPlacement()
        assertFalse(stage.isPinned.value)
        val placement = stage.placement.value
        assertEquals(0.5f, placement.x, 0.001f)
        assertEquals(0.8f, placement.y, 0.001f)
    }

    @Test
    fun `the working tile wins over the footer`() {
        val stage = stage()
        stage.setAnchor(
            PotsikAnchorKind.FOOTER,
            PotsikAnchorSpec(centerX = 900f, feetY = 1900f, sizePx = 100f),
        )
        stage.setAnchor(
            PotsikAnchorKind.WORKING,
            PotsikAnchorSpec(centerX = 500f, feetY = 900f, sizePx = 300f),
        )
        // The working tile's anchor is the one in force, so the character
        // stands in the middle of the screen rather than in the footer.
        val placement = stage.placement.value
        assertEquals(0.5f, placement.x, 0.001f)
        assertEquals(0.45f, placement.y, 0.001f)
    }

    @Test
    fun `an anchor that goes away stops holding the character`() {
        val stage = stage()
        stage.setAnchor(PotsikAnchorKind.FOOTER, null)
        val placement = stage.placement.value
        // With nowhere in the layout to stand, the character falls back to
        // the corner rather than to where the footer was: the sections
        // outside the chat have no slot of their own.
        assertEquals(PotsikPlacement.FALLBACK_X, placement.x, 0.001f)
        assertEquals(PotsikPlacement.FALLBACK_Y, placement.y, 0.001f)
    }

    @Test
    fun `the scale the agent asks for is the scale the character gets`() {
        val stage = stage()
        stage.setScale(2f)
        assertEquals(2f, stage.placement.value.scale, 0.001f)
    }

    @Test
    fun `the clock goes up`() {
        val stage = PotsikStage()
        val first = stage.currentTimeMs()
        // A clock that stood still would freeze the character in the pose it
        // was drawn in, and leave every bubble up for ever. The spin is the
        // portable way of waiting for the clock to move on.
        var later = first
        var spins = 0
        while (later == first && spins < 500_000) {
            later = stage.currentTimeMs()
            spins++
        }
        assertTrue(later > first, "the clock should go up: $first then $later")
    }

    @Test
    fun `an animation the agent asked for runs and then stops`() {
        val stage = stage()
        stage.playAnimation(PotsikAction.IDLE_WAVE, durationMs = 50)
        val now = stage.currentTimeMs()
        assertEquals(PotsikAction.IDLE_WAVE, stage.activeAnimation(now))
        assertNull(stage.activeAnimation(now + 100))
    }

    @Test
    fun `the system mode forgets a move, the other two keep it`() {
        val stage = PotsikStage()

        // The system mode: the chat puts him where it wants, so a move is
        // not kept at all.
        stage.setSettings(PotsikSettings(placementMode = PotsikPlacementMode.SYSTEM), isRussian = false)
        stage.moveTo(0.2f, 0.3f)
        assertFalse(stage.isPinned.value)
        assertNull(stage.pinnedPosition.value)

        // The persistent mode: a move is kept, and it is reported so the
        // app can write it down.
        var reported: Pair<Float, Float>? = null
        stage.onMoved = { x, y -> reported = x to y }
        stage.setSettings(PotsikSettings(placementMode = PotsikPlacementMode.PERSISTENT), isRussian = false)
        stage.moveTo(0.2f, 0.3f)
        assertTrue(stage.isPinned.value)
        assertEquals(0.2f, stage.pinnedPosition.value?.x ?: 0f, 0.001f)
        assertEquals(0.2f, reported?.first ?: 0f, 0.001f)
    }

    @Test
    fun `a remembered position puts him back where he was`() {
        val stage = PotsikStage()
        stage.setSettings(PotsikSettings(placementMode = PotsikPlacementMode.PERSISTENT), isRussian = false)
        stage.restorePinned(0.25f, 0.75f)
        assertTrue(stage.isPinned.value)
        assertEquals(0.25f, stage.pinnedPosition.value?.x ?: 0f, 0.001f)
        assertEquals(0.75f, stage.pinnedPosition.value?.y ?: 0f, 0.001f)

        // The system mode does not restore it.
        stage.setSettings(PotsikSettings(placementMode = PotsikPlacementMode.SYSTEM), isRussian = false)
        stage.restorePinned(0.25f, 0.75f)
        assertFalse(stage.isPinned.value)
    }

    @Test
    fun `the session mode keeps the place in memory but does not write it down`() {
        val stage = PotsikStage()
        stage.setSettings(PotsikSettings(placementMode = PotsikPlacementMode.SESSION), isRussian = false)
        var reported = 0
        stage.onMoved = { _, _ -> reported++ }
        stage.moveTo(0.2f, 0.3f)
        // The place is kept for as long as the app is open...
        assertTrue(stage.isPinned.value)
        assertEquals(0.2f, stage.pinnedPosition.value?.x ?: 0f, 0.001f)
        // ...but it is not written down, because the session ends with
        // the app.
        assertEquals(0, reported)
    }

    @Test
    fun `the persistent mode writes the place down`() {
        val stage = PotsikStage()
        stage.setSettings(PotsikSettings(placementMode = PotsikPlacementMode.PERSISTENT), isRussian = false)
        var reported: Pair<Float, Float>? = null
        stage.onMoved = { x, y -> reported = x to y }
        stage.moveTo(0.2f, 0.3f)
        assertNotNull(reported)
        assertEquals(0.2f, reported?.first ?: 0f, 0.001f)
    }

    @Test
    fun `a bigger size setting makes him bigger`() {
        val stage = stage()
        val before = stage.placement.value.scale
        stage.setSizePreference(1.5f)
        // The slider has to make him bigger, not smaller: the size it
        // produces is the one the drawing and the bounds both use.
        assertTrue(stage.placement.value.scale > before)
    }

    @Test
    fun `every animation has an id, a description and a group`() {
        val ids = PotsikAction.entries.map { it.id }
        assertEquals(ids.size, ids.distinct().size, "Animation ids are not unique")
        assertTrue(PotsikAction.entries.all { it.description.isNotBlank() })
        assertTrue(PotsikAction.entries.all { it.group in setOf("idle", "working", "speaking", "error") })
    }

    @Test
    fun `an animation is found by its id however it is written`() {
        assertEquals(PotsikAction.IDLE_WAVE, PotsikAction.fromId("idle_wave"))
        assertEquals(PotsikAction.IDLE_WAVE, PotsikAction.fromId("IDLE-WAVE"))
        assertEquals(PotsikAction.IDLE_WAVE, PotsikAction.fromId("Idle Wave"))
        assertNull(PotsikAction.fromId("idle_waving"))
    }

    /**
     * A stage with a screen and one place in the layout: the character in the
     * middle of the screen, at half his size.
     */
    private fun stage(): PotsikStage = PotsikStage().apply {
        setArea(IntSize(screenWidth.toInt(), screenHeight.toInt()))
        setBaseCharacterSize(characterSize)
        setAnchor(
            PotsikAnchorKind.FOOTER,
            PotsikAnchorSpec(centerX = 500f, feetY = 1600f, sizePx = 100f),
        )
    }
}

/**
 * The stage's anchor maths and the clamp have to agree: the character's feet
 * land on the line the anchor reports and his centre on its middle.
 */
class PotsikAnchorAlignmentTest {

    @Test
    fun `the character stands on the line the anchor reports`() {
        val stage = PotsikStage()
        stage.setArea(IntSize(1000, 2000))
        stage.setBaseCharacterSize(200f)
        stage.setAnchor(PotsikAnchorKind.EMPTY, PotsikAnchorSpec(centerX = 500f, feetY = 1200f, sizePx = 200f))
        val placement = stage.placement.value
        assertEquals(0.5f, placement.x, 0.001f)
        assertEquals(0.6f, placement.y, 0.001f)
    }

    @Test
    fun `the engine is rebuilt when the app language becomes Russian`() {
        val stage = PotsikStage()
        val englishEngine = stage.engine

        stage.setSettings(PotsikSettings(), isRussian = true)

        assertTrue(englishEngine !== stage.engine)
    }
}
