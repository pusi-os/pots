package com.pusios.pots.build

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `PersistentSandboxShell` lives in androidMain, which the desktop target never
 * compiles, so the startup line cannot be exercised from a test — the source is
 * the artifact, the same way [BuildAgentsTest] treats the install script. What
 * this pins is exactly what broke: the shell must send the shared export line,
 * and it must send it before the pid probe and any user command.
 */
class AgentPathWiringTest {

    private val shellSource: String? = listOf(
        "src/androidMain/kotlin/com/pusios/pots/sandbox/PersistentSandboxShell.kt",
        "composeApp/src/androidMain/kotlin/com/pusios/pots/sandbox/PersistentSandboxShell.kt",
        "../composeApp/src/androidMain/kotlin/com/pusios/pots/sandbox/PersistentSandboxShell.kt",
    ).map { path -> File(path) }.firstOrNull { file -> file.isFile }?.readText()

    private val where = "from " + File(".").absolutePath

    /** The two startup writes, anchored on the calls — not on names that also appear in comments. */
    private val exportLine = "h.writeLine(BuildAgents.AGENT_PATH_EXPORT)"
    private val pidProbeLine = "h.writeLine(\"printf"

    @Test
    fun `the chat terminal sends the agent PATH export, from the shared constant`() {
        val source = assertNotNull(
            shellSource,
            "PersistentSandboxShell.kt not found $where",
        )
        assertTrue(
            source.contains(exportLine),
            "the startup export is gone — agents are back to \"command not found\"",
        )
        assertTrue(
            !source.contains("export PATH=\"/root/.local"),
            "a PATH literal in the shell will drift from BuildAgents.AGENT_BIN_PATH",
        )
    }

    @Test
    fun `the export goes out before the pid probe, which precedes every user command`() {
        val source = assertNotNull(shellSource, "PersistentSandboxShell.kt not found")
        val sent = source.indexOf(exportLine)
        val probe = source.indexOf(pidProbeLine)
        assertTrue(sent >= 0, "the startup export is gone")
        assertTrue(probe >= 0, "the pid probe is gone — this test needs re-anchoring")
        assertTrue(
            sent < probe,
            "the export must be written before the pid probe, i.e. before any user command",
        )
    }
}
