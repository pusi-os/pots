package com.pusios.pots.build

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Removing an agent takes its installation off the device and nothing else. The
 * paths here are the ones the card promises to keep, spelled as the guest sees
 * them, so a removal list that grows a parent directory fails this test rather
 * than the owner's configuration.
 */
class AgentRemovalTest {

    /** Config and session data of the four agents, as the card names them. */
    private val userData = listOf(
        "/root/.config/opencode",
        "/root/.local/share/opencode",
        "/root/.pi/agent",
        "/root/.hermes/config.yaml",
        "/root/.hermes/.env",
    )

    @Test
    fun `a removal never deletes a path that holds the user's data`() {
        for (spec in AgentVersions.all) {
            val removed = AgentVersions.removalPaths(spec, includeBulk = true)
            for (keep in spec.keepPaths) {
                assertFalse(
                    removed.any { it == keep || it.startsWith("$keep/") || keep.startsWith("$it/") },
                    "${spec.id}: removing $removed would take $keep with it",
                )
            }
        }
    }

    @Test
    fun `no removal list reaches the configuration of any agent`() {
        for (spec in AgentVersions.all) {
            val removed = AgentVersions.removalPaths(spec, includeBulk = true)
            for (config in userData) {
                assertFalse(
                    removed.any { it == config || it.startsWith("$config/") || config.startsWith("$it/") },
                    "${spec.id}: $config is the user's, and $removed would take it",
                )
            }
        }
    }

    @Test
    fun `every config path the card names is claimed by the agent it belongs to`() {
        val kept = AgentVersions.all.flatMap { it.keepPaths }
        for (config in userData) {
            assertTrue(config in kept, "$config is not named by any agent's keepPaths")
        }
    }

    @Test
    fun `the checkout goes only when that question was answered`() {
        val hermes = AgentVersions.get(BuildAgents.HERMES)!!
        assertFalse(AgentVersions.removalPaths(hermes, includeBulk = false).contains("/root/.hermes/hermes-agent"))
        assertTrue(AgentVersions.removalPaths(hermes, includeBulk = true).contains("/root/.hermes/hermes-agent"))
        // It is the only agent with a second question, so the safe answer is the
        // only one the other three ever see.
        assertTrue(AgentVersions.all.filter { it.id != BuildAgents.HERMES }.all { it.bulkPath == null })
    }

    @Test
    fun `every agent removes something of its own`() {
        for (spec in AgentVersions.all) {
            assertTrue(spec.installPaths.isNotEmpty(), "${spec.id} has nothing to remove")
        }
    }

    @Test
    fun `removing pi-web leaves the node runtime Pi and the panel share`() {
        val piWeb = AgentVersions.get(WebPanels.PI_WEB_VERSION_KEY)!!
        val removed = AgentVersions.removalPaths(piWeb, includeBulk = false)
        assertFalse(removed.contains("/root/.local/share/pi-node"))
        assertFalse(removed.any { it.endsWith("/bin/node") || it.endsWith("/bin/npm") })
    }
}
