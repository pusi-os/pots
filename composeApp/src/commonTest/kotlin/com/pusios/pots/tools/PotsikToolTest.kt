package com.pusios.pots.tools

import com.pusios.pots.ui.potsik.PotsikAction
import com.pusios.pots.ui.potsik.PotsikStage
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The agent's handle on the character.
 *
 * Two things matter here: the agent can find out what the character can do,
 * and the agent cannot put him somewhere he would be lost.
 */
class PotsikToolTest {

    private fun tool(stage: PotsikStage = PotsikStage()) = PotsikTool.create(stage)

    @Test
    fun `listing the animations reports every one of them`() = runTest {
        val result = tool().execute(mapOf("action" to "list_animations")) as Map<*, *>
        assertEquals(true, result["success"])
        val animations = result["animations"] as List<*>
        assertEquals(PotsikAction.entries.size, animations.size)
        assertEquals(PotsikAction.entries.size, result["count"])
        val ids = animations.map { (it as Map<*, *>)["id"] }
        assertTrue(ids.contains("idle_wave"))
        assertTrue(ids.contains("work_search"))
        assertTrue(ids.contains("speak_talk"))
    }

    @Test
    fun `playing an animation starts it`() = runTest {
        val stage = PotsikStage()
        val result = tool(stage).execute(
            mapOf("action" to "play_animation", "animation" to "idle_dance"),
        ) as Map<*, *>
        assertEquals(true, result["success"])
        assertEquals("idle_dance", result["animation"])
        assertEquals(PotsikAction.IDLE_DANCE, stage.activeAnimation(stage.currentTimeMs()))
    }

    @Test
    fun `an animation that does not exist is refused with the list to hand`() = runTest {
        val result = tool().execute(
            mapOf("action" to "play_animation", "animation" to "idle_jumping"),
        ) as Map<*, *>
        assertEquals(false, result["success"])
        assertTrue((result["error"] as String).contains("list_animations"))
    }

    @Test
    fun `moving the character puts him where he was asked to go`() = runTest {
        val stage = PotsikStage()
        val result = tool(stage).execute(
            mapOf("action" to "move", "x" to 0.4, "y" to 0.6),
        ) as Map<*, *>
        assertEquals(true, result["success"])
        assertEquals(0.4f, result["x"] as Float, 0.001f)
        assertEquals(0.6f, result["y"] as Float, 0.001f)
    }

    @Test
    fun `the character cannot be moved off the screen`() = runTest {
        val stage = PotsikStage()
        stage.setArea(androidx.compose.ui.unit.IntSize(1000, 2000))
        stage.setBaseCharacterSize(200f)
        val result = tool(stage).execute(
            mapOf("action" to "move", "x" to 5.0, "y" to -3.0),
        ) as Map<*, *>
        assertEquals(true, result["success"])
        val x = result["x"] as Float
        val y = result["y"] as Float
        assertTrue(x in 0f..1f, "Character moved off the screen horizontally: $x")
        assertTrue(y in 0f..1f, "Character moved off the screen vertically: $y")
        // The character is 200 wide on a 1000 wide screen, so his centre can
        // come no closer to the edge than a tenth of the width.
        assertEquals(0.9f, x, 0.001f)
        assertEquals(0.1f, y, 0.001f)
    }

    @Test
    fun `a position sent as a string is understood`() = runTest {
        val stage = PotsikStage()
        val result = tool(stage).execute(
            mapOf("action" to "move", "x" to "0.25", "y" to "0.75"),
        ) as Map<*, *>
        assertEquals(true, result["success"])
        assertEquals(0.25f, result["x"] as Float, 0.001f)
        assertEquals(0.75f, result["y"] as Float, 0.001f)
    }

    @Test
    fun `the size can be set`() = runTest {
        val stage = PotsikStage()
        val result = tool(stage).execute(
            mapOf("action" to "set_scale", "scale" to 1.5),
        ) as Map<*, *>
        assertEquals(true, result["success"])
        assertEquals(1.5f, result["scale"] as Float, 0.001f)
    }

    @Test
    fun `an unknown action is refused with the ones that work`() = runTest {
        val result = tool().execute(mapOf("action" to "juggle")) as Map<*, *>
        assertEquals(false, result["success"])
        val error = result["error"] as String
        assertTrue(error.contains("list_animations"))
        assertTrue(error.contains("play_animation"))
        assertTrue(error.contains("move"))
        assertTrue(error.contains("set_scale"))
    }

    @Test
    fun `a missing action is refused`() = runTest {
        val result = tool().execute(emptyMap()) as Map<*, *>
        assertEquals(false, result["success"])
    }

    @Test
    fun `the tool declares every action it accepts`() {
        val schema = tool().schema
        assertEquals("potsik", schema.name)
        assertTrue(schema.parameters["action"]!!.required)
        assertFalse(schema.parameters["animation"]!!.required)
        assertFalse(schema.parameters["x"]!!.required)
        assertFalse(schema.parameters["y"]!!.required)
        assertFalse(schema.parameters["scale"]!!.required)
    }
}
