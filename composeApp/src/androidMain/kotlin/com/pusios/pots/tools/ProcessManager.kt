package com.pusios.pots.tools

import com.pusios.pots.linux.ProotHandle
import com.pusios.pots.sandbox.LinuxSandboxManager
import org.koin.java.KoinJavaComponent.inject
import java.util.concurrent.CompletableFuture

private const val MAX_OUTPUT_LENGTH = 15_000

/**
 * Ceiling on a single background command, mirroring [com.pusios.pots.sandbox.ProotExecutor]'s
 * own cap: a nonsensical timeout must not turn into a session that never ends.
 */
private const val MAX_TIMEOUT_SECONDS = 900L

/** How long the timeout path waits for the process to actually die. */
private const val KILL_WAIT_MILLIS = 5_000L

/**
 * Android's process is a proot handle. `cancel()` is the whole story here: it
 * kills the guest bridge and closes the PTY, which is what makes proot exit.
 */
private class ProotProcess(private val handle: ProotHandle) : BackgroundProcess {
    override fun terminate() = handle.cancel()

    override fun awaitDeath(timeoutMillis: Long): Boolean = handle.awaitDeath(timeoutMillis)
}

class ProcessManager(private val sandboxManager: LinuxSandboxManager) {

    private val registry = BackgroundSessionRegistry(MAX_OUTPUT_LENGTH)

    fun startBackground(
        command: String,
        timeoutSeconds: Long,
        workingDir: String,
        envMap: Map<String, String>,
    ): Map<String, Any> {
        val session = registry.start(command)
        val executor = sandboxManager.createProotExecutor()
        val handle = runCatching {
            executor.executeStreaming(
                command = command,
                workingDir = workingDir,
                extraEnv = envMap,
                onStdout = { session.appendStdout("$it\n") },
                onStderr = { session.appendStderr("$it\n") },
            )
        }.getOrElse { error ->
            registry.remove(session.id)
            return mapOf("success" to false, "error" to (error.message ?: "Failed to start process in the sandbox"))
        }
        session.process = ProotProcess(handle)

        // One watcher owns the session's end: it enforces the timeout and then
        // reads the exit code, so a killed session never reports a made-up one.
        CompletableFuture.runAsync {
            val timeoutMillis = timeoutSeconds.coerceIn(1, MAX_TIMEOUT_SECONDS) * 1000
            if (!handle.awaitDeath(timeoutMillis)) {
                session.timedOut = true
                handle.cancel()
                handle.awaitDeath(KILL_WAIT_MILLIS)
            }
            session.exitCode = handle.awaitExit()
            session.finished = true
        }

        return session.startedMessage()
    }

    fun list(): Map<String, Any> = registry.list()

    fun log(sessionId: String, offset: Int, limit: Int): Map<String, Any> = registry.log(sessionId, offset, limit)

    fun kill(sessionId: String): Map<String, Any> = registry.kill(sessionId)

    fun remove(sessionId: String): Map<String, Any> = registry.remove(sessionId)
}

/**
 * Android's background processes run inside the proot sandbox, so the manager needs the
 * sandbox to hand it an executor. See the shared `ProcessManagerTool` in `src/jvmShared`.
 */
internal fun createProcessManager(): ProcessManager {
    val sandboxManager: LinuxSandboxManager by inject(LinuxSandboxManager::class.java)
    return ProcessManager(sandboxManager)
}
