package com.pusios.pots.build

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The version flow's two jobs that must never lie: reading a version out of what
 * a CLI or a registry actually printed, and deciding whether a newer release
 * exists. The fixtures are the real lines the report took off the devices.
 */
class AgentVersionsTest {

    @Test
    fun `the four real --version formats all yield one version`() {
        assertEquals("1.19.6", AgentVersion.parse("1.19.6"))
        assertEquals("2.3.2", AgentVersion.parse("opencode v2.3.2"))
        assertEquals("1.5.2", AgentVersion.parse("pi 1.5.2"))
        assertEquals(
            "0.23.3",
            AgentVersion.parse("Hermes Agent v0.23.3 (2026.9.24) · stable · local caafe5389ae5"),
        )
    }

    @Test
    fun `pi-web's npm tree line yields the package version`() {
        assertEquals(
            "0.17.0",
            AgentVersion.parse("/root/.local/share/pi-node\n└── @agegr/pi-web@0.17.0"),
        )
    }

    @Test
    fun `the newest version is read from each registry's own answer`() {
        assertEquals(
            "2.3.2",
            AgentVersions.latest(
                """{"channel":"latest","name":"cli","version":"2.3.2","metadata":{"package":"@opencode/cli"}}""",
                LatestSource.Version,
            ),
        )
        assertEquals(
            "1.5.2",
            AgentVersions.latest(
                """{"name":"@earendil-works/pi-coding-agent","dist-tags":{"latest":"1.5.2","legacy-node20":"0.74.2"}}""",
                LatestSource.NpmDistTag,
            ),
        )
        assertEquals(
            "0.23.3",
            AgentVersions.latest("""{"tag_name":"v0.23.3","name":"v0.23.3"}""", LatestSource.GithubTag),
        )
    }

    @Test
    fun `an answer that carries no version is no answer at all`() {
        assertNull(AgentVersions.latest("", LatestSource.Version))
        assertNull(AgentVersions.latest("<html>429 Too Many Requests</html>", LatestSource.NpmDistTag))
        assertNull(AgentVersions.latest("""{"message":"API rate limit exceeded"}""", LatestSource.GithubTag))
        // The wrong field for the wrong source is not silently accepted.
        assertNull(AgentVersions.latest("""{"tag_name":"v0.23.3"}""", LatestSource.Version))
    }

    @Test
    fun `versions are compared as numbers, not as strings`() {
        assertTrue(AgentVersions.compare("2.3.10", "2.3.9") > 0)
        assertTrue(AgentVersions.compare("2.3.9", "2.3.10") < 0)
        assertEquals(0, AgentVersions.compare("2.3.2", "2.3.2"))
        assertTrue(AgentVersions.compare("1.19.6", "2.3.2") < 0)
        // The `v` a GitHub tag carries is not part of the version.
        assertEquals(0, AgentVersions.compare("v0.23.3", "0.23.3"))
    }

    @Test
    fun `a newer release is offered by comparison, not by inequality`() {
        assertEquals(
            AgentUpdate.Available("2.3.9", "2.3.10"),
            AgentVersions.decide(present = true, installed = "2.3.9", latest = "2.3.10"),
        )
        assertEquals(
            AgentUpdate.UpToDate("2.3.10"),
            AgentVersions.decide(present = true, installed = "2.3.10", latest = "2.3.10"),
        )
        // Ahead of the channel — a pinned build — is nothing to offer, not a downgrade.
        assertEquals(
            AgentUpdate.UpToDate("2.3.11"),
            AgentVersions.decide(present = true, installed = "2.3.11", latest = "2.3.10"),
        )
    }

    @Test
    fun `a failed check never reads as up to date`() {
        val failed = AgentVersions.decide(
            present = true,
            installed = "2.3.2",
            latest = null,
            checkFailed = true,
            checkedAt = "14:32",
        )
        assertEquals(AgentUpdate.CheckFailed("2.3.2", "14:32"), failed)
        assertFalse(failed is AgentUpdate.UpToDate)
        assertFalse(failed is AgentUpdate.Available)

        // A registry answer that could not be read is a failure too, even though
        // the call itself went through.
        assertFalse(
            AgentVersions.decide(present = true, installed = "2.3.2", latest = null) is AgentUpdate.UpToDate,
        )
        // An installed version that could not be parsed cannot be compared, so it
        // is not "up to date" either.
        assertFalse(
            AgentVersions.decide(present = true, installed = null, latest = "2.3.2") is AgentUpdate.UpToDate,
        )
    }

    @Test
    fun `a date in the version slot is a failed check, never up to date`() {
        // The bug in one line: `2026.9.24` against `0.23.3` compares as 2026 > 0,
        // so a build whose version never resolved read as "up to date".
        assertEquals(
            AgentUpdate.CheckFailed("2026.9.24", "14:32"),
            AgentVersions.decide(present = true, installed = "2026.9.24", latest = "0.23.3", checkedAt = "14:32"),
        )
        assertFalse(
            AgentVersions.decide(present = true, installed = "2026.9.24", latest = "0.23.3") is AgentUpdate.UpToDate,
        )
        // Nothing an agent really reports is caught by it: no major is near 100.
        assertEquals(
            AgentUpdate.UpToDate("99.0.0"),
            AgentVersions.decide(present = true, installed = "99.0.0", latest = "99.0.0"),
        )
    }

    @Test
    fun `an agent that is not installed has nothing to compare`() {
        assertEquals(
            AgentUpdate.NotInstalled,
            AgentVersions.decide(present = false, installed = null, latest = "2.3.2"),
        )
    }

    @Test
    fun `pi's probe carries the node runtime its version line needs`() {
        val pi = AgentVersions.get(BuildAgents.PI)!!
        assertTrue(AgentVersions.AGENT_PATH in pi.installedCommand)
        assertTrue(AgentVersions.AGENT_PATH.contains("/root/.local/share/pi-node/bin"))
    }

    @Test
    fun `every check is one bounded GET to the source the card names`() {
        assertEquals(
            "curl -fsS --max-time 15 'https://opencode.ai/update/api/latest/cli/npm'",
            AgentVersions.latestCommand(AgentVersions.get(BuildAgents.OPENCODE)!!),
        )
        // OpenCode's own update endpoint, never GitHub's releases/latest: that one
        // still points at v1.19.6 while v2.3.2 is the current release.
        assertFalse(AgentVersions.get(BuildAgents.OPENCODE)!!.latestUrl.contains("releases/latest"))
        assertEquals(
            "https://api.github.com/repos/NousResearch/hermes-agent/releases/latest",
            AgentVersions.get(BuildAgents.HERMES)!!.latestUrl,
        )
    }

    @Test
    fun `rows show three agents and carry the panel version inside Pi`() {
        val empty = AgentVersions.rows(installed = emptySet(), versions = emptyMap(), updates = emptyMap())
        assertEquals(listOf(BuildAgents.OPENCODE, BuildAgents.PI, BuildAgents.HERMES), empty.map { it.id })
        assertTrue(empty.all { it.update == AgentUpdate.NotInstalled })
        assertTrue(empty.all { it.childVersion == null }, "nothing rides with an agent that is not there")

        val withPi = AgentVersions.rows(
            installed = setOf(BuildAgents.PI),
            versions = mapOf(BuildAgents.PI to "1.5.2", WebPanels.PI_WEB_VERSION_KEY to "0.17.0"),
            updates = mapOf(BuildAgents.PI to AgentUpdate.UpToDate("1.5.2", "14:32")),
        )
        // The panel is not a row of its own, whatever it is installed as.
        assertEquals(listOf(BuildAgents.OPENCODE, BuildAgents.PI, BuildAgents.HERMES), withPi.map { it.id })
        assertTrue(withPi.none { it.id == WebPanels.PI_WEB_VERSION_KEY })

        val pi = withPi.single { it.id == BuildAgents.PI }
        assertEquals("1.5.2", pi.version)
        assertEquals(AgentUpdate.UpToDate("1.5.2", "14:32"), pi.update)
        // Its version rides in Pi's row, where the UI draws it under Pi's own.
        assertEquals("0.17.0", pi.childVersion)
        assertEquals(null, withPi.single { it.id == BuildAgents.HERMES }.childVersion)
    }

    @Test
    fun `pi-web is a part of Pi, not an agent of its own`() {
        val piWeb = AgentVersions.get(WebPanels.PI_WEB_VERSION_KEY)!!
        assertEquals(BuildAgents.PI, piWeb.parentId)
        // Its own record survives: the panel's stop and its version detection read it.
        assertEquals(listOf(piWeb), AgentVersions.children(BuildAgents.PI))
        assertTrue(AgentVersions.all.filter { it.parentId == null }.all { it.id != piWeb.id })
        // The key the state map uses is not renamed.
        assertEquals("pi-web", WebPanels.PI_WEB_VERSION_KEY)
    }

    @Test
    fun `pi's update runs the panel's install as its second step`() {
        val pi = AgentVersions.get(BuildAgents.PI)!!
        assertEquals(
            listOf(
                "PATH=${AgentVersions.AGENT_PATH} pi update",
                "PATH=${AgentVersions.AGENT_PATH} /root/.local/share/pi-node/bin/npm install -g " +
                    "--prefix /root/.local/share/pi-node --no-audit --no-fund @agegr/pi-web",
            ),
            pi.updateCommand,
        )
        // One proot call: the steps are chained, so a failed Pi update stops the panel's.
        assertEquals(pi.updateCommand.joinToString(" && "), AgentVersions.updateScript(pi))

        // Every other agent updates with one command, and the chain is that command.
        for (spec in AgentVersions.all.filter { it.id != BuildAgents.PI }) {
            assertEquals(1, spec.updateCommand.size, "${spec.id} updates in one step")
            assertEquals(spec.updateCommand.single(), AgentVersions.updateScript(spec))
        }
    }
}
