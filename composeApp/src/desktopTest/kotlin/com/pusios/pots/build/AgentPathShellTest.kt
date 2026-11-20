package com.pusios.pots.build

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Runs the startup line the way the chat terminal does — `bash --noprofile --norc`,
 * the line written to its stdin — because a quoting mistake in a Kotlin string
 * still reads fine as a string and breaks only when bash parses it.
 */
class AgentPathShellTest {

    @Test
    fun `the startup export puts the agent dirs ahead of the shell's own PATH`() {
        val bash = listOf("/bin/bash", "/usr/bin/bash").firstOrNull { File(it).canExecute() } ?: return
        val probe = ProcessBuilder(bash, "--noprofile", "--norc")
            .apply { environment()["PATH"] = "/usr/bin:/bin" }
            .start()
        try {
            probe.outputStream.bufferedWriter().use { stdin ->
                stdin.write(BuildAgents.AGENT_PATH_EXPORT + "\n")
                stdin.write("echo \"\$PATH\"\n")
            }
            val printed = probe.inputStream.bufferedReader().readText().trim()
            assertEquals(BuildAgents.AGENT_BIN_PATH + ":/usr/bin:/bin", printed)
        } finally {
            probe.destroy()
        }
    }
}
