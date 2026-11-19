package com.pusios.pots.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The session table is what `manage_process kill` actually answers with, so it
 * is tested against a fake process: the question is whether the registry waits
 * for a death it can observe, not whether `Process.destroyForcibly` exists.
 */
class BackgroundSessionRegistryTest {

    private class FakeProcess(private val dies: Boolean = true) : BackgroundProcess {
        var terminateCalls = 0
        var deathWaited = false
        var streamClosed = false

        override fun terminate() {
            terminateCalls++
            // The reader thread in both real wrappers ends when the stream closes.
            if (dies) streamClosed = true
        }

        override fun awaitDeath(timeoutMillis: Long): Boolean {
            deathWaited = true
            return dies
        }
    }

    private fun registry() = BackgroundSessionRegistry(maxOutputChars = 64, killWaitMillis = 50)

    @Test
    fun killWaitsForTheProcessAndReportsItGone() {
        val registry = registry()
        val session = registry.start("sleep 60")
        val process = FakeProcess()
        session.process = process

        val result = registry.kill(session.id)

        assertEquals(true, result["success"])
        assertEquals("Process terminated", result["message"])
        assertTrue(process.deathWaited, "kill has to observe the death, not assume it")
        assertTrue(process.streamClosed, "the reading stream has to be released")
        assertTrue(session.finished)
        assertTrue(session.killed)
        assertEquals(0, (registry.list()["running"] as List<*>).size)
    }

    @Test
    fun killReportsFailureWhenTheProcessSurvives() {
        val registry = registry()
        val session = registry.start("sleep 60")
        session.process = FakeProcess(dies = false)

        val result = registry.kill(session.id)

        assertEquals(false, result["success"])
        assertTrue((result["error"] as String).contains("still be running"))
        assertFalse(session.finished, "a surviving process must not be reported as finished")
        assertEquals(1, (registry.list()["running"] as List<*>).size)
    }

    @Test
    fun killOfAFinishedSessionSaysSo() {
        val registry = registry()
        val session = registry.start("echo done")
        session.finished = true
        session.exitCode = 0

        val result = registry.kill(session.id)

        assertEquals(true, result["success"])
        assertEquals("Process already finished", result["message"])
        assertEquals(0, result["exit_code"])
    }

    @Test
    fun killReportsTheExitCodeTheMonitorPublished() {
        val registry = registry()
        val session = registry.start("sleep 60")
        session.process = object : BackgroundProcess {
            override fun terminate() {
                // The monitor only learns about the death after the kill observed
                // it, so the code arrives a moment later — the race that used to
                // surface as a -1 next to a real code in the log.
                Thread {
                    Thread.sleep(30)
                    session.exitCode = 143
                }.start()
            }

            override fun awaitDeath(timeoutMillis: Long) = true
        }

        val result = registry.kill(session.id)

        assertEquals(true, result["success"])
        assertEquals(143, result["exit_code"], "one question, one answer — not -1 here and 143 in the log")
        assertEquals(143, registry.log(session.id, 0, 10)["exit_code"])
    }

    @Test
    fun killWithoutAProcessHandleFailsInsteadOfLying() {
        val registry = registry()
        val session = registry.start("sleep 60")

        val result = registry.kill(session.id)

        assertEquals(false, result["success"])
        assertFalse(session.finished)
    }

    @Test
    fun killOfAnUnknownSessionFails() {
        assertEquals(false, registry().kill("bg-404")["success"])
    }

    @Test
    fun removeTerminatesARunningProcessFirst() {
        val registry = registry()
        val session = registry.start("sleep 60")
        val process = FakeProcess()
        session.process = process

        val result = registry.remove(session.id)

        assertEquals(true, result["success"])
        assertEquals(1, process.terminateCalls)
        assertEquals(0, registry.list()["total"])
    }

    @Test
    fun removeKeepsTheSessionWhenTheProcessSurvives() {
        val registry = registry()
        val session = registry.start("sleep 60")
        session.process = FakeProcess(dies = false)

        val result = registry.remove(session.id)

        assertEquals(false, result["success"])
        assertEquals(1, registry.list()["total"], "an orphan would be lost for good, so it stays listed")
    }

    @Test
    fun logAfterKillIsFinishedAndCarriesTheExitCode() {
        val registry = registry()
        val session = registry.start("sleep 60")
        session.process = FakeProcess()
        session.appendStdout("line one\n")

        registry.kill(session.id)
        val log = registry.log(session.id, 0, 10)

        assertEquals("finished", log["status"])
        assertEquals(true, log["killed"])
        assertEquals("line one", (log["stdout"] as String).trim())
    }

    @Test
    fun outputIsCappedAtTheSessionLimit() {
        val registry = registry()
        val session = registry.start("chatty")

        session.appendStdout("x".repeat(100))
        session.appendStderr("y".repeat(100))

        assertEquals(64, session.stdout.length)
        assertEquals(64, session.stderr.length)
    }
}
