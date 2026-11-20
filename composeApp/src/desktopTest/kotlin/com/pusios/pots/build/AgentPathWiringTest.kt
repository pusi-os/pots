package com.pusios.pots.build

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
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
    ).map(::File).firstOrNull { it.isFile }?.readText()

    @Test
    fun `the chat terminal sends the agent PATH export, from the shared constant`() {
        val source = assertNotNull(shellSource, "PersistentSandboxShell.kt not found from ${File(".").absolutePath}")
        val sent = source.indexOf("writeLine(BuildAgents.AGENT_PATH_EXPORT)")
        assertTrue(sent >= 0, "the startup export is gone — agents are back to \"command not found\"")
        assertFalse(
            source.contains("export PATH=\"/root/.local"),
            "a PATH literal in the shell will drift from BuildAgents.AGENT_BIN_PATH",
        )
    }

    @Test
    fun `the export goes out before the pid probe and before any user command`() {
        val source = assertNotNull(shellSource, "PersistentSandboxShell.kt not found")
        val sent = source.indexOf("writeLine(BuildAgents.AGENT_PATH_EXPORT)")
        val probe = source.indexOf("POTSBASHPID")
        assertTrue(sent >= 0, "the startup export is gone")
        assertTrue(probe >= 0, "the pid probe is gone — this test needs re-anchoring")
        assertTrue(sent < probe, "the export must be written before the pid probe")
    }
}
