package com.pusios.pots.build

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Removing an agent takes its installation off the device and nothing else, and
 * cleaning before an install is the same promise from the other side: the paths
 * here are the ones the card promises to keep, spelled as the guest sees them, so
 * a cleanup that grows a parent directory fails this test rather than the owner's
 * configuration.
 */
class AgentRemovalTest {

    /** Config and state of the agents, as the card names them. */
    private val userData = listOf(
        "/root/.config/opencode",
        "/root/.local/share/opencode",
        "/root/.pi/agent",
        "/root/.hermes/config.yaml",
        "/root/.hermes/.env",
        // The rest of /root/.hermes: without these the directory itself looks
        // deletable, and it holds the owner's authorisations, state and board.
        "/root/.hermes/auth.json",
        "/root/.hermes/state.db",
        "/root/.hermes/profiles",
        "/root/.hermes/kanban",
        "/root/.hermes/cron",
        "/root/.hermes/sessions",
        "/root/.hermes/memories",
        "/root/.hermes/state-snapshots",
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
    fun `a cleanup takes every install path and nothing that is the user's`() {
        for (spec in AgentVersions.all) {
            // Everything a cleanup could be pointed at: the installation, and the
            // checkout it leaves to the installer.
            val existing = AgentVersions.removalPaths(spec, includeBulk = true).toSet()
            val plan = AgentVersions.cleanupPlan(spec, existing, binaryAnswers = false)
            assertEquals(
                existing - setOfNotNull(spec.bulkPath),
                plan.remove.toSet(),
                "${spec.id}: what a cleanup takes",
            )
            assertTrue(plan.refused.isEmpty(), "${spec.id}: $plan would delete the user's data")
            for (path in plan.remove) {
                for (keep in spec.keepPaths) {
                    assertFalse(
                        path == keep || path.startsWith("$keep/") || keep.startsWith("$path/"),
                        "${spec.id}: cleaning $path would take $keep with it",
                    )
                }
            }
        }
    }

    @Test
    fun `mayDelete refuses the user's data, its parents and its children`() {
        val hermes = AgentVersions.get(BuildAgents.HERMES)!!.copy(keepPaths = listOf("/root/.hermes/auth.json"))
        // The parent: /root/.hermes holds auth.json and the whole of profiles/.
        assertFalse(AgentVersions.mayDelete("/root/.hermes", hermes), "the directory that holds the user's data")
        assertFalse(AgentVersions.mayDelete("/root/.hermes/auth.json", hermes), "the file itself")
        assertFalse(AgentVersions.mayDelete("/root/.hermes/auth.json/backup", hermes), "a path inside it")
        // The checkout is a child of that parent and none of the user's.
        assertTrue(AgentVersions.mayDelete("/root/.hermes/hermes-agent", hermes), "the checkout")
    }

    @Test
    fun `a cleanup says what it found, what it took, and when there was nothing`() {
        val pi = AgentVersions.get(BuildAgents.PI)!!
        val leftovers = AgentVersions.cleanupPlan(pi, setOf("/root/.local/bin/pi"), binaryAnswers = false)
        assertEquals(listOf("/root/.local/bin/pi"), leftovers.found, "debris of a run that stopped halfway")
        assertEquals(
            "pots: pi: leftovers found (/root/.local/bin/pi)",
            AgentVersions.leftoversFound(pi, leftovers.found),
        )
        assertEquals("pots: pi: cleaned (/root/.local/bin/pi)", AgentVersions.cleaned(pi, leftovers.remove))

        // The binary answers: the path is the installation, and a reinstall is an
        // update, not a reason to delete what is about to be updated.
        val installed = AgentVersions.cleanupPlan(pi, setOf("/root/.local/bin/pi"), binaryAnswers = true)
        assertTrue(installed.found.isEmpty(), "a working binary is not a leftover")

        // Nothing there at all.
        val empty = AgentVersions.cleanupPlan(pi, emptySet(), binaryAnswers = false)
        assertTrue(empty.found.isEmpty())
        assertEquals("pots: pi: nothing to clean", AgentVersions.nothingToClean(pi))
    }

    @Test
    fun `a cleanup refuses a path that holds user data and names it in the log`() {
        // The card's worst case: a cleanup pointed at the directory that holds the
        // owner's credentials, state and board.
        val guarding = AgentVersions.get(BuildAgents.HERMES)!!.copy(
            installPaths = listOf("/root/.hermes"),
            keepPaths = listOf("/root/.hermes/auth.json"),
        )
        val plan = AgentVersions.cleanupPlan(guarding, setOf("/root/.hermes"), binaryAnswers = false)
        assertTrue(plan.remove.isEmpty(), "nothing of the user's may go")
        assertEquals(listOf("/root/.hermes"), plan.refused)
        assertEquals(
            "pots: hermes: refusing to remove /root/.hermes — it holds user data",
            AgentVersions.refusedToRemove(guarding, "/root/.hermes"),
        )
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
    fun `removing Pi takes the panel and leaves the node runtime they share`() {
        val pi = AgentVersions.get(BuildAgents.PI)!!
        val removed = AgentVersions.removalPaths(pi, includeBulk = false)
        // The panel has no row of its own, so its paths go with Pi's.
        assertTrue(removed.contains("/root/.local/share/pi-node/lib/node_modules/@agegr/pi-web"))
        assertTrue(removed.contains("/root/.local/share/pi-node/bin/pi-web"))
        assertTrue(removed.contains("/root/.local/bin/pi"))
        // The Node runtime both of them run on is nobody's to delete.
        assertFalse(removed.contains("/root/.local/share/pi-node"))
        assertFalse(removed.any { it.endsWith("/bin/node") || it.endsWith("/bin/npm") })
    }
}
