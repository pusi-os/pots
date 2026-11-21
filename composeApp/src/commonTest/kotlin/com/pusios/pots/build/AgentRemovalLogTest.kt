package com.pusios.pots.build

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A removal prints nothing of its own — no installer, no download — so the log it
 * leaves behind is the only sign it ran: one line per path it was pointed at, then
 * what those paths added up to. The count is the same one the progress bar shows,
 * so a path the bar counted but the log never named is a removal the owner cannot
 * see — and a path that is still on disk must not be logged as taken.
 */
class AgentRemovalLogTest {

    @Test
    fun `each removed path is a line of its own with what it freed`() {
        val lines = listOf(
            RemovedPath("/root/.local/bin/hermes", RemovedOutcome.Deleted(bytes = null)),
            RemovedPath("/root/.hermes/hermes-agent", RemovedOutcome.Deleted(bytes = 5_200_000_000L)),
        ).map(AgentVersions::removalLine)

        assertEquals("удалено: /root/.local/bin/hermes", lines[0])
        assertEquals("удалено: /root/.hermes/hermes-agent (5.2 GB)", lines[1])
    }

    @Test
    fun `a path that was not there is not logged as deleted`() {
        val line = AgentVersions.removalLine(
            RemovedPath("/root/.pi/agent", RemovedOutcome.Absent),
        )

        assertEquals("не было: /root/.pi/agent", line)
    }

    @Test
    fun `a path that would not go is logged with its cause, not as deleted`() {
        val line = AgentVersions.removalLine(
            RemovedPath("/root/.opencode", RemovedOutcome.Failed("Permission denied")),
        )

        assertEquals("не удалось: /root/.opencode — Permission denied", line)
    }

    @Test
    fun `a tree that stopped halfway reports only the bytes it actually took`() {
        val line = AgentVersions.removalLine(
            RemovedPath(
                "/root/.hermes/hermes-agent",
                RemovedOutcome.Failed("lock: Permission denied", freed = 4_100_000_000L),
            ),
        )

        assertTrue(line.startsWith("не удалось: /root/.hermes/hermes-agent — lock: Permission denied"), line)
        assertTrue(line.endsWith("(частично освобождено 4.1 GB)"), line)
    }

    @Test
    fun `the summary counts only the paths that went and the bytes they freed`() {
        val removed = listOf(
            RemovedPath("/root/.opencode", RemovedOutcome.Deleted(115_000_000L)),
            RemovedPath("/root/.local/bin/opencode", RemovedOutcome.Deleted(bytes = null)),
            RemovedPath("/root/.cache/opencode", RemovedOutcome.Absent),
            RemovedPath("/root/.config/opencode", RemovedOutcome.Failed("Permission denied", freed = 5_000_000L)),
        )

        assertEquals(
            "итог: удалено путей 2, освобождено 120 MB, не было: 1, не удалось: 1",
            AgentVersions.removalSummary(removed),
        )
    }

    @Test
    fun `a clean removal keeps the summary it always had`() {
        val removed = listOf(
            RemovedPath("/root/.opencode", RemovedOutcome.Deleted(115_000_000L)),
            RemovedPath("/root/.local/bin/opencode", RemovedOutcome.Deleted(bytes = null)),
        )

        assertEquals("итог: удалено путей 2, освобождено 115 MB", AgentVersions.removalSummary(removed))
    }

    @Test
    fun `the log names every path the removal was given`() {
        val hermes = AgentVersions.get(BuildAgents.HERMES)!!
        val paths = AgentVersions.removalPaths(hermes, includeBulk = true)
        val removed = paths.map { RemovedPath(it, RemovedOutcome.Deleted(bytes = null)) }

        val log = (removed.map(AgentVersions::removalLine) + AgentVersions.removalSummary(removed))
            .joinToString("\n")

        assertEquals(paths.size, log.lines().count { it.startsWith("удалено: ") })
        assertTrue(
            log.endsWith("итог: удалено путей ${paths.size}, освобождено 0 B"),
            "the summary has to agree with the lines above it: $log",
        )
    }

    @Test
    fun `the progress is the share of paths already gone`() {
        assertEquals(0f, AgentRemovalProgress(done = 0, total = 4).fraction())
        assertEquals(0.5f, AgentRemovalProgress(done = 2, total = 4).fraction())
        assertEquals(1f, AgentRemovalProgress(done = 4, total = 4).fraction())
        // Nothing to divide by is not a full bar.
        assertNull(AgentRemovalProgress(done = 0, total = 0).fraction())
    }
}
