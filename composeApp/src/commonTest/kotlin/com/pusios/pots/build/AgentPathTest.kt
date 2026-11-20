package com.pusios.pots.build

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The chat terminal and the login-shell snippet must carry the same agent dirs.
 * They were two hand-written strings and the terminal's copy simply did not exist,
 * so `pi`, `hermes` and `opencode` answered "command not found" on a device where
 * all three were installed. String tests on purpose: the string is what bash runs.
 */
class AgentPathTest {

    private val export = BuildAgents.AGENT_PATH_EXPORT

    @Test
    fun `the startup export carries every agent directory, in install order`() {
        val positions = listOf(
            "/root/.local/bin",
            "/root/.local/share/pi-node/bin",
            "/root/.opencode/bin",
        ).map { dir ->
            assertTrue(export.contains(dir), "no $dir in: $export")
            export.indexOf(dir)
        }
        assertEquals(positions.sorted(), positions, "the agent dirs are out of order: $export")
    }

    @Test
    fun `the startup export keeps the system tail of PATH`() {
        // Without the tail, `export` replaces PATH and /usr/bin disappears — the
        // shell would then fail on every system command instead of three agents.
        assertTrue(export.startsWith("export PATH=\""), export)
        assertTrue(export.contains("\${PATH:+:\$PATH}"), "the system dirs are dropped: $export")
    }

    @Test
    fun `the login profile and the terminal export share one directory list`() {
        // Two sources of truth is how the terminal got left out in the first place.
        assertTrue(export.contains(BuildAgents.AGENT_BIN_PATH), export)
        assertTrue(
            BuildAgents.AGENT_PATH_PROFILE.contains(BuildAgents.AGENT_BIN_PATH),
            "the profile lists different dirs: ${BuildAgents.AGENT_PATH_PROFILE}",
        )
        assertTrue(
            BuildAgents.AGENT_PATH_PROFILE.contains(export),
            "the profile must reuse the terminal's export line: ${BuildAgents.AGENT_PATH_PROFILE}",
        )
    }
}
