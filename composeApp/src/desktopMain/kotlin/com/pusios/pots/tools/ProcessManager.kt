package com.pusios.pots.tools

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val MAX_OUTPUT_LENGTH = 30_000

/**
 * Desktop's process is the host one, and `sh -c` means the real work is usually a
 * child of it: killing the shell alone leaves the command running as an orphan
 * that keeps the port and the pipes. So the whole tree goes, SIGTERM first.
 */
private class HostProcess(private val process: Process) : BackgroundProcess {
    override fun terminate() {
        val tree = runCatching { process.descendants().toList() }.getOrDefault(emptyList())
        tree.forEach { runCatching { it.destroy() } }
        process.destroy()
        if (runCatching { process.waitFor(1, TimeUnit.SECONDS) }.getOrDefault(false)) return
        tree.forEach { runCatching { it.destroyForcibly() } }
        process.destroyForcibly()
    }

    override fun awaitDeath(timeoutMillis: Long): Boolean {
        if (!process.isAlive) return true
        runCatching { process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS) }
        return !process.isAlive
    }
}

class ProcessManager {

    private val registry = BackgroundSessionRegistry(MAX_OUTPUT_LENGTH)

    /**
     * Drains and monitors run on their own threads, not on the common pool: a
     * reader blocked on a pipe must never be able to starve the monitor of the
     * next session (it could before, and the "finished" flag then never came).
     */
    private val workers = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "pots-background-process").apply { isDaemon = true }
    }

    fun startBackground(
        command: String,
        timeoutSeconds: Long,
        workingDir: File?,
        envMap: Map<String, String>,
    ): Map<String, Any> {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val processBuilder = if (isWindows) {
            ProcessBuilder("cmd", "/c", command)
        } else {
            ProcessBuilder("sh", "-c", command)
        }

        processBuilder.redirectErrorStream(false)
        if (workingDir != null && workingDir.isDirectory) {
            processBuilder.directory(workingDir)
        }
        if (envMap.isNotEmpty()) {
            processBuilder.environment().putAll(envMap)
        }

        val process = processBuilder.start()
        val session = registry.start(command)
        val hostProcess = HostProcess(process)
        session.process = hostProcess

        // Drain stdout/stderr in background threads; the session caps both.
        workers.execute {
            runCatching {
                process.inputStream.bufferedReader().forEachLine { session.appendStdout(it + "\n") }
            }
        }
        workers.execute {
            runCatching {
                process.errorStream.bufferedReader().forEachLine { session.appendStderr(it + "\n") }
            }
        }

        // Monitor for completion/timeout.
        workers.execute {
            val completed = runCatching { process.waitFor(timeoutSeconds, TimeUnit.SECONDS) }.getOrDefault(false)
            if (!completed) {
                hostProcess.terminate()
                session.timedOut = true
                session.exitCode = -1
            } else {
                session.exitCode = runCatching { process.exitValue() }.getOrDefault(-1)
            }
            session.finished = true
        }

        return session.startedMessage()
    }

    fun list(): Map<String, Any> = registry.list()

    fun log(sessionId: String, offset: Int, limit: Int): Map<String, Any> = registry.log(sessionId, offset, limit)

    fun kill(sessionId: String): Map<String, Any> = registry.kill(sessionId)

    fun remove(sessionId: String): Map<String, Any> = registry.remove(sessionId)
}

/** Desktop spawns host processes directly, so no collaborators are needed. */
internal fun createProcessManager(): ProcessManager = ProcessManager()
