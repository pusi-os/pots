package com.pusios.pots.build.runtime

import android.content.res.AssetManager
import android.util.Log
import com.pusios.pots.build.HermesWebapp
import com.pusios.pots.build.SandboxAgentConfig
import com.pusios.pots.build.WebPanel
import com.pusios.pots.build.WebPanelGuest
import com.pusios.pots.build.WebPanelLog
import com.pusios.pots.build.WebPanels
import com.pusios.pots.build.cleanStaleInstallDirs
import com.pusios.pots.build.portIsFree
import com.pusios.pots.build.portProbeCommand
import com.pusios.pots.linux.LinuxPaths
import com.pusios.pots.tools.hostProcessAlive
import java.io.File

private const val TAG = "PotsWebPanels"

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
    /** This app's own assets, where CI leaves the Hermes panel bundle. */
    private val assets: AssetManager,
    /**
     * A proot call in the guest: its extra environment, and whether it may run
     * under the hardlink emulation. Panels never do (see
     * [WebPanels.PANEL_HARDLINK_EMULATION]); a renderer build attempt asks for
     * what it was written to ask for.
     */
    private val executor: (Map<String, String>, Boolean) -> BuildProotExecutor,
) : WebPanelGuest {

    override fun run(command: String, timeoutSeconds: Long, env: Map<String, String>): WebPanelGuest.Output {
        val result = executor(env, WebPanels.PANEL_HARDLINK_EMULATION).execute(command, timeoutSeconds)
        return WebPanelGuest.Output(result.success, (result.stdout + result.stderr).trim())
    }

    /**
     * One attempt of the renderer chain, in the proot call that attempt describes:
     * the pnpm settings travel in the environment (the only channel pnpm 12 reads),
     * and the emulation flag is the only difference between two of the attempts.
     */
    override fun buildRenderer(
        attempt: HermesWebapp.BuildAttempt,
        timeoutSeconds: Long,
    ): WebPanelGuest.Output {
        val result = executor(attempt.env, attempt.hardlinkEmulation)
            .execute(HermesWebapp.BUILD_COMMAND, timeoutSeconds)
        return WebPanelGuest.Output(result.success, (result.stdout + result.stderr).trim())
    }

    /** Host-side append: the row's log section is where a failed chain explains itself. */
    override fun appendLog(panel: WebPanel, line: String) {
        runCatching { logFile(panel).appendText(line + "\n") }
    }

    /**
     * Stages the renderer this app ships, or answers null when this build carries
     * none (a developer build). A staging failure still answers the pin: the unpack
     * then fails on the missing archive and the row quotes that, which is truer
     * than "this app ships no bundle".
     */
    override fun stageHermesBundle(): String? {
        val pin = HermesWebappAssets.pin(assets) ?: return null
        val marker = paths.readMarker() ?: return pin
        HermesWebappAssets.stageArchive(assets, pin, paths.homeDir(marker))
        return pin
    }

    override fun launch(panel: WebPanel, port: Int, command: String, env: Map<String, String>): Boolean = runCatching {
        paths.tmpDir.mkdirs()
        cleanStaleInstallDebris(panel)
        executor(env, WebPanels.PANEL_HARDLINK_EMULATION).startBackground(
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
     * One sweep of the `.install-<rand>` debris failed plugin installs left
     * behind while the panel still ran under the hardlink emulation (see
     * [WebPanels.PANEL_HARDLINK_EMULATION]): under it the guest cannot even
     * delete the debris itself. Host side, before the launch — these are
     * ordinary files in the rootfs as far as this app is concerned, and the
     * panel's log gets one line naming what went, so the sweep is never a
     * silent delete.
     */
    private fun cleanStaleInstallDebris(panel: WebPanel) {
        if (panel.id != WebPanels.HERMES) return
        val marker = paths.readMarker() ?: return
        val home = paths.homeDir(marker)
        val removed = cleanStaleInstallDirs(File(home, ".hermes/plugins"), File(home, ".hermes/skills"))
        if (removed > 0) {
            val line = "pots: cleaned $removed stale .install-* dir(s)"
            Log.i(TAG, line)
            runCatching { logFile(panel).appendText(line + "\n") }
        }
    }

    /**
     * Hermes takes its panel port from `webapp.port`, so the port is written into
     * its config — and the dashboard's login plugin is dropped from
     * `plugins.disabled` in the same write. The panel binds the LAN, so Hermes
     * gates it, and a disabled `basic` plugin would leave that gate with no
     * provider and the page saying "sign-in unavailable". Only those two things
     * change: the rest of the file belongs to Hermes and to the provider config
     * Pots writes on every agent start.
     */
    override fun configure(panel: WebPanel, port: Int): Boolean {
        if (panel.id != WebPanels.HERMES) return true
        val file = hermesConfig(panel) ?: return false
        return runCatching {
            file.parentFile?.mkdirs()
            val current = if (file.exists()) file.readText() else ""
            file.writeText(
                SandboxAgentConfig.withWebappPort(SandboxAgentConfig.withBasicAuthPluginEnabled(current), port),
            )
            true
        }.onFailure { Log.e(TAG, "could not write the Hermes webapp config", it) }.getOrDefault(false)
    }

    /**
     * The read-back of the fix [configure] makes: a config that still disables the
     * plugin means the gate would open on "sign-in unavailable", and the runtime
     * says so instead of reporting a panel nobody can get into.
     */
    override fun authPluginDisabled(panel: WebPanel): Boolean {
        val file = hermesConfig(panel) ?: return false
        if (!file.exists()) return false
        val text = runCatching { file.readText() }.getOrNull() ?: return false
        return SandboxAgentConfig.disabledPlugins(text).any { it in SandboxAgentConfig.BASIC_AUTH_PLUGINS }
    }

    private fun hermesConfig(panel: WebPanel): File? {
        if (panel.id != WebPanels.HERMES) return null
        val marker = paths.readMarker() ?: return null
        return File(paths.homeDir(marker), ".hermes/config.yaml")
    }

    override fun pid(panel: WebPanel): Int? =
        runCatching { File(paths.tmpDir, panel.pidFile).readText().trim().toIntOrNull() }.getOrNull()

    override fun port(panel: WebPanel): Int? =
        runCatching { File(paths.tmpDir, panel.portFile).readText().trim().toIntOrNull() }.getOrNull()

    override fun alive(pid: Int): Boolean = hostProcessAlive(pid)

    /**
     * The guest's own loopback check: a bash `/dev/tcp` connect is refused when
     * nothing listens, and only a refusal counts as free. A probe that did not
     * run at all (bash missing, proot error, timeout — exit -1) says nothing
     * about the port, so it is reported as taken: `pickPort` then walks on and
     * fails loudly instead of handing out a port someone else is on.
     */
    override fun portFree(port: Int): Boolean {
        val result = executor(emptyMap(), WebPanels.PANEL_HARDLINK_EMULATION)
            .execute(portProbeCommand(port), timeoutSeconds = 20)
        if (result.exitCode != 0 && result.exitCode != 3) {
            Log.w(
                TAG,
                "port probe on $port gave no answer (exit ${result.exitCode}): " +
                    (result.error ?: result.stderr.take(200)),
            )
        }
        return portIsFree(result.exitCode)
    }

    override fun forget(panel: WebPanel) {
        runCatching { File(paths.tmpDir, panel.pidFile).delete() }
        runCatching { File(paths.tmpDir, panel.portFile).delete() }
    }

    override fun logText(panel: WebPanel): String {
        val file = logFile(panel)
        if (!file.exists()) return WebPanelLog.NO_FILE
        return runCatching { file.readText() }.getOrDefault("")
    }

    private fun logFile(panel: WebPanel): File = File(paths.tmpDir, panel.logFile)
}
