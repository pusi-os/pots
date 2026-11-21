package com.pusios.pots.ui.build

import com.pusios.pots.build.AgentRemovalProgress
import com.pusios.pots.build.AgentUpdate
import com.pusios.pots.build.AgentVersionRow
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.pots_build_agents_installing
import pots.composeapp.generated.resources.pots_build_agents_removing
import pots.composeapp.generated.resources.pots_build_agents_removing_progress
import pots.composeapp.generated.resources.pots_build_agents_updating_until
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The row's own decisions, kept out of the composition so they can be asserted:
 * a removal in flight says so and takes the buttons away, and only the agent that
 * owns the running job sees its log.
 */
class AgentVersionsCardTest {

    private fun row(update: AgentUpdate) = AgentVersionRow(
        id = "hermes",
        title = "Hermes",
        present = true,
        version = "0.9.1",
        update = update,
    )

    @Test
    fun `installing and removing have a status line of their own`() {
        assertEquals(Res.string.pots_build_agents_installing, statusLine(row(AgentUpdate.Installing)).template)
        assertEquals(Res.string.pots_build_agents_removing, statusLine(row(AgentUpdate.Removing)).template)

        // The removal's count is the paths it was given, not an estimate.
        val counting = statusLine(row(AgentUpdate.Removing), AgentRemovalProgress(done = 1, total = 2))
        assertEquals(Res.string.pots_build_agents_removing_progress, counting.template)
        assertEquals(listOf(1, 2), counting.args)

        // An update keeps its own line: the three running tasks are not one state.
        assertEquals(Res.string.pots_build_agents_updating_until, statusLine(row(AgentUpdate.Updating)).template)
    }

    @Test
    fun `installing and removing replace the buttons with the progress block`() {
        assertTrue(showsProgress(AgentUpdate.Installing))
        assertTrue(showsProgress(AgentUpdate.Updating))
        assertTrue(showsProgress(AgentUpdate.Removing))
        assertTrue(inFlight(AgentUpdate.Installing))
        assertTrue(inFlight(AgentUpdate.Removing))

        // A check hides the buttons too, but has no bar to show.
        assertTrue(inFlight(AgentUpdate.Checking))
        assertFalse(showsProgress(AgentUpdate.Checking))

        // A row at rest keeps its buttons.
        assertFalse(inFlight(AgentUpdate.Unchecked("0.9.1")))
        assertFalse(inFlight(AgentUpdate.NotInstalled))
        assertFalse(showsProgress(AgentUpdate.NotInstalled))
    }

    @Test
    fun `only the agent that owns the job shows the log`() {
        assertEquals("line", rowLog("hermes", "hermes", "line"))
        assertNull(rowLog("pi", "hermes", "line"))
        assertNull(rowLog("hermes", null, "line"))
        // An empty buffer is not a log to show.
        assertNull(rowLog("hermes", "hermes", ""))
    }
}
