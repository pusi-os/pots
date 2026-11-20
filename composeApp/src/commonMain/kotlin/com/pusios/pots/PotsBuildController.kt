package com.pusios.pots

import com.pusios.pots.build.HermesWebappLogin
import com.pusios.pots.build.PotsBuildState
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
     * Hermes' dashboard login — the account its Webapp asks for — so the row can
     * show it. Null for the panels that have none.
     */
    fun webPanelLogin(panelId: String): HermesWebappLogin?
}

/** Pots Build is Android-only; every other target gets this. */
class NoOpPotsBuildController : PotsBuildController {
    override val state = MutableStateFlow(PotsBuildState())
    override val files: FileBrowserSource = NoOpFileBrowserSource

    override fun install(agentIds: Set<String>) {}
    override fun cancel() {}
    override fun uninstall() {}
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
    override fun webPanelLogin(panelId: String): HermesWebappLogin? = null
}

expect fun createPotsBuildController(): PotsBuildController
