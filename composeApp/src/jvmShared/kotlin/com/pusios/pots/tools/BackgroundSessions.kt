package com.pusios.pots.tools

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** How long a kill waits for the OS to reap the process before admitting it failed. */
private const val KILL_WAIT_MILLIS = 5_000L

/** How long a kill waits for the monitor to publish the exit code it just caused. */
private const val EXIT_CODE_WAIT_MILLIS = 300L

/**
 * A background process, as the session table sees it.
 *
 * Desktop wraps a [java.lang.Process]; Android wraps the proot handle, which
 * additionally has to end the guest bridge or the shell under it survives.
 */
interface BackgroundProcess {
    /** Asks the process to stop and returns at once — the wait is [awaitDeath]. */
    fun terminate()

    /** Waits up to [timeoutMillis] for the process to actually exit. */
    fun awaitDeath(timeoutMillis: Long): Boolean
}

/**
 * One `background=true` shell run: its bounded output, its state, and the
 * process that has to be killed for real when the agent asks.
 *
 * [stdout] and [stderr] are plain strings rather than builders because both
 * platforms hand output over line by line from their own reader threads.
 */
class BackgroundSession internal constructor(
    val id: String,
    val command: String,
    val startTime: Long,
    private val maxOutputChars: Int,
    @Volatile var process: BackgroundProcess? = null,
    @Volatile var stdout: String = "",
    @Volatile var stderr: String = "",
    @Volatile var finished: Boolean = false,
    @Volatile var exitCode: Int? = null,
    @Volatile var timedOut: Boolean = false,
    /** True when the run ended because someone asked, not because it ended on its own. */
    @Volatile var killed: Boolean = false,
) {
    private val stdoutLock = Any()
    private val stderrLock = Any()

    fun appendStdout(text: String) {
        synchronized(stdoutLock) {
            val room = maxOutputChars - stdout.length
            if (room > 0) stdout += text.take(room)
        }
    }

    fun appendStderr(text: String) {
        synchronized(stderrLock) {
            val room = maxOutputChars - stderr.length
            if (room > 0) stderr += text.take(room)
        }
    }
}

/**
 * The background session table, shared by Android and desktop.
 *
 * The one rule worth keeping in one place: [kill] waits for the process to die
 * before it reports success. Marking a session finished while its process is
 * still holding the port is a lie the next `start` pays for.
 */
class BackgroundSessionRegistry(
    private val maxOutputChars: Int,
    private val killWaitMillis: Long = KILL_WAIT_MILLIS,
) {
    private val sessions = ConcurrentHashMap<String, BackgroundSession>()
    private val nextId = AtomicInteger(1)

    /** Registers a new session and returns it so the caller can attach its process. */
    fun start(command: String): BackgroundSession {
        val session = BackgroundSession(
            id = "bg-${nextId.getAndIncrement()}",
            command = command,
            startTime = System.currentTimeMillis(),
            maxOutputChars = maxOutputChars,
        )
        sessions[session.id] = session
        return session
    }

    fun list(): Map<String, Any> {
        val running = sessions.values.filter { !it.finished }.map { it.toInfo() }
        val finished = sessions.values.filter { it.finished }.map { it.toInfo() }
        return mapOf(
            "running" to running,
            "finished" to finished,
            "total" to sessions.size,
        )
    }

    fun log(sessionId: String, offset: Int, limit: Int): Map<String, Any> {
        val session = sessions[sessionId] ?: return unknown(sessionId)

        val stdoutLines = session.stdout.lines()
        val sliced = stdoutLines.drop(offset).take(limit).joinToString("\n")

        return mapOf(
            "success" to true,
            "session_id" to sessionId,
            "status" to if (session.finished) "finished" else "running",
            "exit_code" to (session.exitCode ?: -1),
            "stdout" to sliced,
            "stderr" to session.stderr.takeLast(2000),
            "total_stdout_lines" to stdoutLines.size,
            "offset" to offset,
            "timed_out" to session.timedOut,
            "killed" to session.killed,
        )
    }

    fun kill(sessionId: String): Map<String, Any> {
        val session = sessions[sessionId] ?: return unknown(sessionId)

        if (session.finished) {
            return mapOf(
                "success" to true,
                "message" to "Process already finished",
                "exit_code" to (session.exitCode ?: -1),
            )
        }

        val process = session.process
            ?: return mapOf("success" to false, "error" to "Session $sessionId has no process handle to terminate")

        session.killed = true
        process.terminate()
        if (!process.awaitDeath(killWaitMillis)) {
            // Say so instead of pretending: the process is still there and the
            // port it holds is still taken, so the session stays killable.
            return mapOf(
                "success" to false,
                "error" to "Process did not exit within ${killWaitMillis / 1000}s; it may still be running",
            )
        }

        // The monitor owns the exit code and publishes it right after the death,
        // so give it a moment: reporting -1 while the log already carries the real
        // code would be two answers to one question. A killed process has no exit
        // status of its own, so -1 is the honest answer when none arrives.
        val exit = awaitExitCode(session)
        session.exitCode = exit
        session.finished = true
        return mapOf(
            "success" to true,
            "message" to "Process terminated",
            "exit_code" to exit,
        )
    }

    fun remove(sessionId: String): Map<String, Any> {
        val session = sessions[sessionId] ?: return unknown(sessionId)

        val process = session.process
        if (!session.finished && process != null) {
            process.terminate()
            if (!process.awaitDeath(killWaitMillis)) {
                return mapOf(
                    "success" to false,
                    "error" to "Process is still running; session kept so it can be killed again",
                )
            }
        }
        sessions.remove(sessionId)
        return mapOf("success" to true, "message" to "Session removed")
    }

    private fun unknown(sessionId: String): Map<String, Any> = mapOf("success" to false, "error" to "Unknown session: $sessionId")

    /** Waits briefly for the monitor to publish the real exit code; -1 when it does not. */
    private fun awaitExitCode(session: BackgroundSession): Int {
        val deadline = System.currentTimeMillis() + EXIT_CODE_WAIT_MILLIS
        while (session.exitCode == null && System.currentTimeMillis() < deadline) {
            runCatching { Thread.sleep(10) }
        }
        return session.exitCode ?: -1
    }

    private fun BackgroundSession.toInfo(): Map<String, Any> = mapOf(
        "session_id" to id,
        "command" to command,
        "status" to if (finished) "finished" else "running",
        "exit_code" to (exitCode ?: -1),
        "duration_seconds" to ((System.currentTimeMillis() - startTime) / 1000),
        "timed_out" to timedOut,
        "killed" to killed,
        "stdout_length" to stdout.length,
    )
}

/** The map every `startBackground` answers with once the process is running. */
internal fun BackgroundSession.startedMessage(): Map<String, Any> = mapOf(
    "success" to true,
    "session_id" to id,
    "status" to "running",
    "message" to "Process started in background. Use manage_process tool to check status.",
)
