package com.pusios.pots

import com.pusios.pots.build.PotsBuildState
import com.pusios.pots.build.WebPanelLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Pots Build's Debian-under-proot environment. Separate from [SandboxController]
 * on purpose: different rootfs, different home, and resetting one must never
 * touch the other.
 */
interface PotsBuildController {
    val state: StateFlow<PotsBuildState>

    /** The Debian file tree, for the Files tab. Empty on platforms without Pots Build. */
    val files: FileBrowserSource

    /** Installs Debian if needed, then the given agents. Safe to call when already installed. */
    fun install(agentIds: Set<String>)
    fun cancel()
    fun uninstall()

    /**
     * Asks each agent's registry for its newest version — one HTTP GET per agent,
     * bounded by a short timeout — and publishes the comparison against what is
     * installed. A check that fails says so; it never reads as "up to date".
     */
    fun checkAgents(agentIds: Set<String>)

    /** Runs the agent's own update command, then re-reads what it installed. */
    fun updateAgent(agentId: String)

    /**
     * Removes the agent's installation — never its configuration. [includeBulk]
     * additionally removes the big checkout that a reinstall would have to fetch
     * again; it is a separate question, and the safe answer is the default.
     */
    fun removeAgent(agentId: String, includeBulk: Boolean = false)

    /** Re-scans projects, installed agents, and system facts. */
    fun refresh()

    /** Creates `projects/<name>` and returns the sanitized folder name, or null if invalid. */
    fun createProject(name: String): String?

    /** Deletes `projects/<name>` and everything in it. The project's sessions are closed with it. */
    fun deleteProject(name: String)

    /**
     * Renames `projects/<name>`, returning the sanitized new folder name — or null
     * when the name is unusable or already taken. The project's sessions are closed:
     * their working directory is the folder that just moved.
     */
    fun renameProject(name: String, newName: String): String?

    /**
     * Starts an interactive PTY session in [project] and makes it the active one.
     * A non-null [agentId] launches that agent's CLI first, leaving a shell behind
     * when it exits.
     */
    fun startSession(project: String, agentId: String?)

    /** Brings an existing session to the front; the terminal renders the active one. */
    fun selectSession(id: String)

    /** Ends one session and activates its neighbour, if any. */
    fun closeSession(id: String)

    /**
     * Re-enters [project]: activates the session it was left on and stops its
     * shells from being reaped. False when the project has none running, which is
     * the caller's cue to start one.
     */
    fun resumeProject(project: String): Boolean

    /**
     * Steps out of [project] with its shells still running — a command keeps going
     * while the user is elsewhere, and only the user closes a session. They are
     * ended for them if the project is never reopened; see the idle window in the
     * Android implementation.
     */
    fun leaveProject(project: String)

    /**
     * Sends raw bytes to the active session — typed text, a submitted line
     * (terminated with `\r`), or an encoded key sequence.
     */
    fun writeToTerminal(text: String)

    /**
     * Updates the active session's VT geometry and its live PTY winsize
     * (rows/cols). Call when the terminal viewport is measured or resized.
     */
    fun resizeTerminal(columns: Int, rows: Int)

    // --- agent web panels -------------------------------------------------

    /** Starts the agent's web UI, picking a free port. A no-op while it is already up. */
    fun startWebPanel(panelId: String)

    /** Stops it with a real SIGTERM → wait → SIGKILL, and reports the port it freed. */
    fun stopWebPanel(panelId: String)

    /** Opens the panel in the device's own browser, minting a fresh Hermes link when needed. */
    fun openWebPanel(panelId: String)

    /** Whether the panel comes up with the sandbox. */
    fun setWebPanelAutostart(panelId: String, enabled: Boolean)

    /**
     * The panel's password, for the reveal toggle. Deliberately not part of
     * [PotsBuildState]: nothing that renders a screen has to hold it.
     */
    fun webPanelPassword(panelId: String): String?

    /**
     * The panel's log, tail-first and with its password already cut out, for the
     * row's log section. Read on demand — never as part of [PotsBuildState]: the
     * file grows while the panel starts, and a polled copy would be both stale and
     * heavier than anything else in the state.
     */
    fun webPanelLog(panelId: String): String
}

/** Pots Build is Android-only; every other target gets this. */
class NoOpPotsBuildController : PotsBuildController {
    override val state = MutableStateFlow(PotsBuildState())
    override val files: FileBrowserSource = NoOpFileBrowserSource

    override fun install(agentIds: Set<String>) {}
    override fun cancel() {}
    override fun uninstall() {}
    override fun checkAgents(agentIds: Set<String>) {}
    override fun updateAgent(agentId: String) {}
    override fun removeAgent(agentId: String, includeBulk: Boolean) {}
    override fun refresh() {}
    override fun createProject(name: String): String? = null
    override fun deleteProject(name: String) {}
    override fun renameProject(name: String, newName: String): String? = null
    override fun startSession(project: String, agentId: String?) {}
    override fun selectSession(id: String) {}
    override fun closeSession(id: String) {}
    override fun resumeProject(project: String): Boolean = false
    override fun leaveProject(project: String) {}
    override fun writeToTerminal(text: String) {}
    override fun resizeTerminal(columns: Int, rows: Int) {}
    override fun startWebPanel(panelId: String) {}
    override fun stopWebPanel(panelId: String) {}
    override fun openWebPanel(panelId: String) {}
    override fun setWebPanelAutostart(panelId: String, enabled: Boolean) {}
    override fun webPanelPassword(panelId: String): String? = null
    override fun webPanelLog(panelId: String): String = WebPanelLog.NO_FILE
}

expect fun createPotsBuildController(): PotsBuildController
