package com.pusios.pots.build.runtime

import android.util.Log
import com.pusios.pots.build.SandboxAgentConfig
import com.pusios.pots.build.WebPanel
import com.pusios.pots.build.WebPanelGuest
import com.pusios.pots.build.WebPanels
import com.pusios.pots.linux.LinuxPaths
import com.pusios.pots.tools.hostProcessAlive
import java.io.File

private const val TAG = "PotsWebPanels"

/** How much of a failed panel's log the reason line quotes. */
private const val LOG_TAIL_CHARS = 400

/**
 * The sandbox side of [com.pusios.pots.build.WebPanelRuntime]: shell calls and
 * file reads inside the Debian rootfs.
 *
 * Pid, port and log files live in the host's tmp dir, which is bind-mounted as
 * the guest's `/tmp` — so the runtime reads them without starting a process, and
 * they survive the app being killed.
 */
class AndroidWebPanelGuest(
    private val paths: LinuxPaths,
    private val executor: (Map<String, String>) -> BuildProotExecutor,
) : WebPanelGuest {

    override fun run(command: String, timeoutSeconds: Long): WebPanelGuest.Output {
        val result = executor(emptyMap()).execute(command, timeoutSeconds)
        return WebPanelGuest.Output(result.success, (result.stdout + result.stderr).trim())
    }

    override fun launch(panel: WebPanel, port: Int, command: String, env: Map<String, String>): Boolean = runCatching {
        paths.tmpDir.mkdirs()
        executor(env).startBackground(
            command = command,
            pidFileName = panel.pidFile,
            logFile = logFile(panel),
        )
        // Written next to the pid file so a panel found after an app restart still
        // knows which port it is on — neither `opencode serve` nor `pi-web` can be
        // asked afterwards.
        File(paths.tmpDir, panel.portFile).writeText(port.toString())
        true
    }.onFailure { Log.e(TAG, "could not start ${panel.id}", it) }.getOrDefault(false)

    /**
     * Hermes takes its panel port from `webapp.port`, so the port is written into
     * its config. Only that block is replaced: the rest of the file belongs to
     * Hermes and to the provider config Pots writes on every agent start.
     */
    override fun configure(panel: WebPanel, port: Int): Boolean {
        if (panel.id != WebPanels.HERMES) return true
        val marker = paths.readMarker() ?: return false
        return runCatching {
            val file = File(paths.homeDir(marker), ".hermes/config.yaml")
            file.parentFile?.mkdirs()
            val current = if (file.exists()) file.readText() else ""
            file.writeText(SandboxAgentConfig.withWebappPort(current, port))
            true
        }.onFailure { Log.e(TAG, "could not write the Hermes webapp port", it) }.getOrDefault(false)
    }

    override fun pid(panel: WebPanel): Int? =
        runCatching { File(paths.tmpDir, panel.pidFile).readText().trim().toIntOrNull() }.getOrNull()

    override fun port(panel: WebPanel): Int? =
        runCatching { File(paths.tmpDir, panel.portFile).readText().trim().toIntOrNull() }.getOrNull()

    override fun alive(pid: Int): Boolean = hostProcessAlive(pid)

    /** The guest's own loopback check: a bash `/dev/tcp` connect fails when nothing listens. */
    override fun portFree(port: Int): Boolean = !executor(emptyMap())
        .execute("(exec 3<>/dev/tcp/127.0.0.1/$port) 2>/dev/null", timeoutSeconds = 20)
        .success

    override fun forget(panel: WebPanel) {
        runCatching { File(paths.tmpDir, panel.pidFile).delete() }
        runCatching { File(paths.tmpDir, panel.portFile).delete() }
    }

    override fun logTail(panel: WebPanel): String {
        val file = logFile(panel)
        if (!file.exists()) return "no log"
        val text = runCatching { file.readText() }.getOrDefault("")
        val tail = text.takeLast(LOG_TAIL_CHARS).trim()
        return tail.ifEmpty { "no output" }
    }

    private fun logFile(panel: WebPanel): File = File(paths.tmpDir, panel.logFile)
}
