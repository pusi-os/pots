package com.pusios.pots.tools

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Android side of a background kill ends a proot guest by the pid the guest
 * wrote into the bind-mounted /tmp, then walks its children — proot does not
 * namespace pids, so those children are ordinary host processes and killing only
 * the recorded one leaves the server holding its port.
 *
 * proot itself cannot be run here, so the pieces that can be checked for real are
 * checked for real: the pid capture runs a real `sh`, and the walk runs against
 * real /proc.
 */
class ProcessTreeTest {

    private val createdFiles = mutableListOf<File>()

    @AfterTest
    fun cleanUp() {
        createdFiles.forEach { runCatching { it.delete() } }
    }

    @Test
    fun killWalksTheTreeBottomUp() {
        val children = mapOf(1 to listOf(2, 3), 2 to listOf(4), 3 to emptyList(), 4 to emptyList())
        val killed = mutableListOf<Int>()

        killProcessTree(root = 1, childrenOf = { children[it].orEmpty() }, kill = { killed += it })

        assertEquals(listOf(4, 2, 3, 1), killed, "a child has to die before its parent can re-spawn it")
    }

    @Test
    fun killSurvivesAProcessThatVanishesMidWalk() {
        // /proc changes under the walk; a pid that disappears must not abort it.
        val children = mapOf(1 to listOf(2))
        val killed = mutableListOf<Int>()

        killProcessTree(
            root = 1,
            childrenOf = { children[it].orEmpty() },
            kill = { if (it == 2) error("already gone") else killed += it },
        )

        assertEquals(listOf(1), killed, "the root still gets its kill attempt")
    }

    @Test
    fun theGuestPidCommandRecordsTheShellPid() {
        val name = "pots-test-${System.nanoTime()}"
        val pidFile = File("/tmp", name)
        createdFiles += pidFile

        val process = ProcessBuilder("sh", "-c", guestPidCommand(name, "sleep 30")).start()
        try {
            val recorded = awaitPid(pidFile)
            assertTrue(recorded > 0, "the guest command has to write a pid before it runs anything")
            assertEquals(process.pid(), recorded.toLong(), "the recorded pid is the process the host has to kill")
        } finally {
            process.destroyForcibly()
            process.waitFor()
        }
    }

    @Test
    fun killingTheRecordedPidEndsTheCommandAndItsChildren() {
        val name = "pots-test-${System.nanoTime()}"
        val pidFile = File("/tmp", name)
        createdFiles += pidFile

        // A compound command, like a real background session: the shell stays the
        // recorded process and the sleep is its child.
        val process = ProcessBuilder("sh", "-c", guestPidCommand(name, "sleep 30 & sleep 30; wait")).start()
        try {
            val pid = awaitPid(pidFile)
            val tree = descendantsOf(pid)
            assertTrue(tree.isNotEmpty(), "the command's own children are what the walk is for")

            killProcessTree(pid, ::hostChildren) { ProcessHandle.of(it.toLong()).ifPresent { h -> h.destroyForcibly() } }

            assertTrue(awaitDead(pid), "the recorded process must be gone")
            tree.forEach { assertFalse(hostProcessAlive(it), "child $it outlived the kill and would keep the port") }
        } finally {
            process.destroyForcibly()
            process.waitFor()
        }
    }

    @Test
    fun hostChildrenFindsARealChild() {
        val process = ProcessBuilder("sh", "-c", "sleep 30; true").start()
        try {
            val children = awaitChildren(process.pid())
            assertTrue(children.isNotEmpty(), "a live shell running sleep has a child in /proc")
        } finally {
            process.destroyForcibly()
            process.waitFor()
        }
    }

    @Test
    fun anExitedProcessIsNotAlive() {
        val process = ProcessBuilder("sh", "-c", "true").start()
        process.waitFor()

        assertFalse(hostProcessAlive(process.pid().toInt()), "a reaped pid holds no port")
    }

    @Test
    fun aKilledServerReleasesItsPort() {
        // The whole point of ending the guest: the next run on the same port must
        // work. python3 stands in for whatever a background session starts; the
        // test only runs where it exists (it ships with every desktop CI image).
        val python = listOf("/usr/bin/python3", "/usr/local/bin/python3").firstOrNull { File(it).canExecute() } ?: return
        val port = freePort()
        val name = "pots-test-${System.nanoTime()}"
        val pidFile = File("/tmp", name)
        createdFiles += pidFile

        val process = ProcessBuilder("sh", "-c", guestPidCommand(name, "$python -m http.server $port")).start()
        try {
            val pid = awaitPid(pidFile)
            assertTrue(awaitListening(port), "the server has to be up before the kill means anything")

            killProcessTree(pid, ::hostChildren) { ProcessHandle.of(it.toLong()).ifPresent { h -> h.destroyForcibly() } }

            assertTrue(awaitDead(pid), "the recorded process must be gone")
            assertTrue(awaitPortFree(port), "the port must be free — a leftover server is what made the next start fail")
        } finally {
            process.destroyForcibly()
            process.waitFor()
        }
    }

    private fun freePort(): Int = java.net.ServerSocket(0).use { it.localPort }

    private fun awaitListening(port: Int): Boolean = await(5_000) {
        runCatching { java.net.Socket("127.0.0.1", port).close() }.isSuccess
    }

    private fun awaitPortFree(port: Int): Boolean = await(5_000) {
        runCatching { java.net.ServerSocket(port).close() }.isSuccess
    }

    private fun await(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }

    private fun awaitPid(file: File): Int {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val pid = runCatching { file.readText().trim().toIntOrNull() }.getOrNull()
            if (pid != null) return pid
            Thread.sleep(20)
        }
        error("the guest never wrote ${file.path}")
    }

    private fun awaitChildren(parent: Long): List<Int> {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val children = hostChildren(parent.toInt())
            if (children.isNotEmpty()) return children
            Thread.sleep(20)
        }
        return emptyList()
    }

    private fun awaitDead(pid: Int): Boolean {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (!hostProcessAlive(pid)) return true
            Thread.sleep(20)
        }
        return false
    }

    private fun descendantsOf(pid: Int): List<Int> {
        val children = hostChildren(pid)
        return children + children.flatMap { descendantsOf(it) }
    }
}
