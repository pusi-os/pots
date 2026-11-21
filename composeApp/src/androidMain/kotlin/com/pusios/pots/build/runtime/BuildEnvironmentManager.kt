package com.pusios.pots.build.runtime

import android.content.res.AssetManager
import android.os.StatFs
import android.util.Log
import com.pusios.pots.build.AgentRemovalProgress
import com.pusios.pots.build.AgentUpdate
import com.pusios.pots.build.AgentVersion
import com.pusios.pots.build.AgentVersionSpec
import com.pusios.pots.build.AgentVersions
import com.pusios.pots.build.BuildAgent
import com.pusios.pots.build.BuildAgents
import com.pusios.pots.build.BuildEnvironmentState
import com.pusios.pots.build.BuildStep
import com.pusios.pots.build.BuildSystemInfo
import com.pusios.pots.build.BuildTerminalSession
import com.pusios.pots.build.HermesWebapp
import com.pusios.pots.build.InstallLog
import com.pusios.pots.build.PotsBuildState
import com.pusios.pots.build.RemovedPath
import com.pusios.pots.build.SandboxAgentConfig
import com.pusios.pots.build.SandboxAgentCredentials
import com.pusios.pots.build.WebPanelPreferences
import com.pusios.pots.build.WebPanelRuntime
import com.pusios.pots.build.WebPanelStatus
import com.pusios.pots.build.WebPanels
import com.pusios.pots.build.deleteGuestPath
import com.pusios.pots.build.deleteTree
import com.pusios.pots.build.directorySize
import com.pusios.pots.build.terminal.DEFAULT_COLUMNS
import com.pusios.pots.build.terminal.DEFAULT_ROWS
import com.pusios.pots.build.terminal.MAX_COLUMNS
import com.pusios.pots.build.terminal.MAX_ROWS
import com.pusios.pots.build.terminal.MIN_COLUMNS
import com.pusios.pots.build.terminal.MIN_ROWS
import com.pusios.pots.build.terminal.TerminalScreen
import com.pusios.pots.build.terminal.TerminalSnapshot
import com.pusios.pots.linux.DEFAULT_GUEST_PATH
import com.pusios.pots.linux.DebianSpec
import com.pusios.pots.linux.InstallStep
import com.pusios.pots.linux.LinuxDistro
import com.pusios.pots.linux.LinuxInstaller
import com.pusios.pots.linux.LinuxPaths
import com.pusios.pots.linux.ProotHandle
import com.pusios.pots.linux.ProotLauncher
import com.pusios.pots.openUrl
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "PotsBuild"

private val INVALID_NAME_CHARS = Regex("[^A-Za-z0-9._-]+")

/** Floor on the gap between two terminal repaints — roughly one display frame. */
private const val REPAINT_INTERVAL_MS = 16L

/** Captured login / OSC 8 links leave the bar after this (matches the UI hide). */
private const val HYPERLINK_TTL_MS = 2 * 60 * 1000L

/**
 * How long a project's shells keep running after the user steps out of it. Long
 * enough that leaving a build to read something else, or to work in another
 * project, never costs the session; short enough that a phone is not holding a
 * proot process per project the user has forgotten about.
 */
private const val IDLE_PROJECT_TTL_MS = 60 * 60 * 1000L

/**
 * How often a running install's log reaches the screen. git and uv write in
 * bursts of kilobytes; republishing per chunk would recompose the setup screen
 * dozens of times a second for output nobody can read that fast.
 */
private const val INSTALL_LOG_FLUSH_MS = 200L

/**
 * An install that has printed nothing for this long is treated as hung. It is
 * the only signal available for a step blocked on a socket — the installer's
 * own `curl` calls have no timeout — and it is deliberately far longer than the
 * quietest real step: `npm install` in a pipe prints nothing at all until it is
 * done, so a shorter silence would kill a working Pi install on a slow phone.
 */
private const val INSTALL_STALL_MS = 20 * 60 * 1000L

/** How often a waiting install re-checks its deadline and its silence. */
private const val INSTALL_POLL_MS = 1000L

/**
 * How long one on-device renderer build may run. The measured build is about
 * seven minutes (pnpm 1m 56s, vite 4m 3s); a phone with a slow flash and a cold
 * store is several times that, and a timeout here costs the whole attempt.
 *
 * Twenty-five minutes is a hard deadline for the command, but it is not the
 * bound a hung build hits first: [INSTALL_STALL_MS] (20 min of silence) fires
 * earlier and the log then reads "no output for 20 min", not "timed out".
 *
 * Nothing bounds the chain as a whole. [BuildAgent.installTimeoutSeconds]
 * (5400 s) is spent by the installer alone — [buildHermesRenderer] runs after
 * it, under no outer deadline — so the worst case is three attempts of 1500 s
 * plus their cleans, about 90 minutes past the install. That is why every step
 * of the chain publishes its own line to the log instead of relying on the
 * install's clock.
 */
private const val RENDERER_BUILD_TIMEOUT_SECONDS = 1500L

/**
 * One live PTY session: its own VT screen, geometry, and capture files. Several
 * can run at once, which is what the terminal's session tabs switch between.
 */
private class BuildSession(
    val id: String,
    val project: String,
    val agentId: String?,
    val number: Int,
    columns: Int,
    rows: Int,
) {
    private val screenLock = Any()
    private val screen = TerminalScreen(columns, rows)

    val columns = AtomicInteger(columns)
    val rows = AtomicInteger(rows)

    /** Host-side file names inside the bind-mounted tmp dir — one set per session. */
    val winsizeFile: String get() = "pots-winsize-$id"
    val openUrlFile: String get() = "pots-open-url-$id"
    val pidFile: String get() = "pots-pid-$id"

    @Volatile
    var handle: ProotHandle? = null

    @Volatile
    var job: Job? = null

    @Volatile
    var busy: Boolean = true

    /** Cancels/reschedules when a new URL is captured so the bar stays for a full TTL. */
    var hyperlinkClearJob: Job? = null

    /** Last published screen — recomputed only for the session that produced output. */
    @Volatile
    var snapshot: TerminalSnapshot = TerminalSnapshot.blank()
        private set

    fun <T> withScreen(block: (TerminalScreen) -> T): T = synchronized(screenLock) { block(screen) }

    /**
     * Caches a fresh snapshot and hands back the visible text (for device-code
     * scans). URLs are scraped from a space-trimmed join of rows so a line-wrapped
     * OAuth URL reassembles into one link instead of one stump per row.
     * Snapshotting allocates a cell object per grid position, so the second
     * snapshot only happens when a new link actually changed the screen.
     */
    fun refreshSnapshot(): String = synchronized(screenLock) {
        val snap = screen.snapshot()
        val text = visibleText(snap)
        val urlText = visibleTextForUrlScan(snap)
        snapshot = if (screen.noteTextUrls(urlText)) screen.snapshot() else snap
        text
    }

    fun resize(cols: Int, rws: Int) {
        columns.set(cols)
        rows.set(rws)
        synchronized(screenLock) { screen.resize(cols, rws) }
    }

    fun stop() {
        handle?.cancel()
        handle = null
        job?.cancel()
        job = null
        hyperlinkClearJob?.cancel()
        hyperlinkClearJob = null
        busy = false
    }

    fun toUiSession() = BuildTerminalSession(
        id = id,
        project = project,
        agentId = agentId,
        number = number,
        terminal = snapshot,
        busy = busy,
    )
}

private fun visibleText(snap: TerminalSnapshot): String =
    buildString(snap.columns * snap.rows + snap.rows) {
        for (row in 0 until snap.rows) {
            for (col in 0 until snap.columns) {
                append(snap.cellAt(col, row).char)
            }
            append('\n')
        }
    }

/**
 * Like [visibleText] but trims trailing spaces on each row and joins without
 * newlines. Terminal wrap pads the rest of the line with spaces; keeping those
 * (or the newline) splits one long OAuth URL into many partial matches.
 */
private fun visibleTextForUrlScan(snap: TerminalSnapshot): String =
    buildString(snap.columns * snap.rows) {
        for (row in 0 until snap.rows) {
            val start = length
            for (col in 0 until snap.columns) {
                append(snap.cellAt(col, row).char)
            }
            // Trim trailing spaces from this row only.
            var end = length
            while (end > start && this[end - 1] == ' ') end--
            if (end < length) deleteRange(end, length)
        }
    }

/**
 * Owns the Debian rootfs, agent installs, and the project terminal sessions for
 * Pots Build. Process-scoped: held by the Koin-managed AndroidPotsBuildController.
 */
class BuildEnvironmentManager(
    /**
     * The install to work in. When the chat sandbox is also Debian this is the
     * *same* install it uses, so setting up either one sets up both.
     */
    private val paths: LinuxPaths,
    /** This app's assets: where CI leaves the Hermes panel bundle. */
    private val assets: AssetManager,
) {

    private val installer = LinuxInstaller(paths)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Fired whenever this install appears or disappears. Set when the rootfs is
     * shared with the chat sandbox, which otherwise has no way to learn that
     * Pots Build just gave it a Linux (or took one away).
     */
    @Volatile
    var onEnvironmentChanged: (() -> Unit)? = null
    private var installJob: Job? = null

    /** The version check in flight; a second one replaces it rather than queueing. */
    private var checkJob: Job? = null

    /**
     * The provider Pots is using right now — supplied by the controller, which is
     * the only place that can reach the settings repository. Read at every agent
     * start rather than cached, so switching provider or model in Settings is
     * enough for the sandbox agents to follow.
     */
    @Volatile
    var activeCredentials: (() -> SandboxAgentCredentials?)? = null

    /**
     * What Pots remembers about an agent's web panel — password and autostart.
     * Supplied by the controller for the same reason as [activeCredentials].
     */
    @Volatile
    var webPanelPreferences: (String) -> WebPanelPreferences = { WebPanelPreferences() }

    @Volatile
    var saveWebPanelPreferences: (String, WebPanelPreferences) -> Unit = { _, _ -> }

    /**
     * The agents' web panels. Started and stopped here because this is what owns
     * the rootfs, the executor and the provider; the state machine itself is in
     * [WebPanelRuntime], away from Android so it can be tested.
     */
    private val webPanels: WebPanelRuntime by lazy {
        WebPanelRuntime(
            guest = AndroidWebPanelGuest(paths, assets) { extraEnv -> executor(extraEnv = extraEnv) },
            prefs = { webPanelPreferences(it) },
            savePrefs = { id, prefs -> saveWebPanelPreferences(id, prefs) },
            installed = { _state.value.installedAgents },
            lanAddress = { LanAddress.current() },
            publish = { panels -> _state.update { it.copy(webPanels = panels) } },
            scope = scope,
        )
    }

    private val sessionsLock = Any()
    private val sessions = LinkedHashMap<String, BuildSession>()
    private var activeSessionId: String? = null
    private val sessionCounter = AtomicInteger()

    /** Pending reaps, one per project the user has stepped out of. Guarded by [sessionsLock]. */
    private val reapJobs = HashMap<String, Job>()

    /**
     * Project whose terminal is on screen, if any. Only its sessions push repaints
     * to the UI — a background shell still parses everything it is sent, but a
     * screen nobody is looking at should not recompose the app sixty times a
     * second. Its snapshot is current whenever the project is opened again.
     */
    @Volatile
    private var foregroundProject: String? = null

    /** Geometry of the last measured viewport — new sessions start there, not at 80×24. */
    private val lastColumns = AtomicInteger(DEFAULT_COLUMNS)
    private val lastRows = AtomicInteger(DEFAULT_ROWS)

    private val _state = MutableStateFlow(PotsBuildState())
    val state: StateFlow<PotsBuildState> = _state.asStateFlow()

    init {
        paths.ensureLayout()
        scope.launch { sync() }
    }

    fun install(agentIds: Set<String>) {
        if (installJob?.isActive == true) return
        installJob = scope.launch {
            // One agent from the versions card is the row that owns the progress
            // and the log; a batch from the setup screen has no single row to own
            // them, and that screen shows the log itself.
            val single = agentIds.singleOrNull()?.takeIf { AgentVersions.get(it) != null }
            // A new install owns the log: the previous one's reason is not this
            // one's, and leaving it would read as if the new run already failed.
            _state.update {
                it.copy(
                    installLog = "",
                    installLogDropped = false,
                    activeAgentId = single,
                    removalProgress = null,
                )
            }
            if (single != null) setAgentUpdate(single, AgentUpdate.Installing)
            try {
                installInternal(agentIds) { isActive }
            } catch (e: CancellationException) {
                cleanUpPartialInstall(error = null)
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Install failed", e)
                cleanUpPartialInstall(error = e.message ?: "Install failed")
            } finally {
                // What the row shows next is what is installed — sync() has just
                // read it — not the task that was running.
                if (single != null) setAgentUpdate(single, null)
                _state.update { it.copy(activeAgentId = null, removalProgress = null) }
            }
        }
    }

    fun cancel() {
        installJob?.cancel()
        installJob = null
    }

    /**
     * Removes the Linux, not the projects. When this install is shared with the
     * chat sandbox that sandbox loses its Linux too — the setup card says so.
     */
    fun uninstall() {
        if (installJob?.isActive == true) return
        installJob = scope.launch {
            closeAllSessions()
            // A panel is a process outside this app: it has to be signalled before
            // its rootfs disappears under it, or it keeps the port with nothing
            // left to stop it.
            stopWebPanels()
            paths.deleteInstall()
            paths.ensureLayout()
            _state.value = PotsBuildState(projects = scanProjects())
            onEnvironmentChanged?.invoke()
        }
    }

    /** Called when something else is about to delete the rootfs under us. */
    fun onEnvironmentRemoved() {
        closeAllSessions()
        for (panel in WebPanels.all) {
            if (_state.value.webPanels[panel.id]?.status is WebPanelStatus.Running) webPanels.stop(panel.id)
        }
        _state.value = PotsBuildState(projects = scanProjects())
    }

    private suspend fun stopWebPanels() {
        for (panel in WebPanels.all) {
            if (_state.value.webPanels[panel.id] != null) webPanels.stopPanel(panel.id)
        }
    }

    // --- agent versions --------------------------------------------------

    /**
     * Asks each agent's registry for its newest version.
     *
     * One GET per agent, run one after another: four proot processes and four
     * sockets at once cost a phone more than the seconds they save, and every row
     * already says it is being checked. What comes back is compared with what the
     * agent's own `--version` printed, and a failed check stays a failed check —
     * see [AgentVersions.decide].
     */
    fun checkAgents(agentIds: Set<String>) {
        val specs = agentIds.mapNotNull { AgentVersions.get(it) }
        if (specs.isEmpty()) return
        checkJob?.cancel()
        checkJob = scope.launch {
            for (spec in specs) setAgentUpdate(spec.id, AgentUpdate.Checking)
            for (spec in specs) {
                val update = runCatching { checkOne(spec) }
                    .getOrElse { error ->
                        Log.w(TAG, "version check of ${spec.id} failed", error)
                        AgentUpdate.CheckFailed(_state.value.agentVersions[spec.id], clockNow())
                    }
                setAgentUpdate(spec.id, update)
            }
        }
    }

    /**
     * Runs the agent's own update command, then re-reads what it installed.
     *
     * The panel is stopped first: it is a process running the binary the update
     * replaces, so leaving it up would show the old version until the app is
     * restarted. The re-read matters as much — `opencode upgrade` on a current
     * install exits 0 without changing anything, so success is not proof.
     */
    fun updateAgent(agentId: String) {
        val spec = AgentVersions.get(agentId) ?: return
        if (installJob?.isActive == true) return
        installJob = scope.launch {
            _state.update {
                it.copy(
                    installLog = "",
                    installLogDropped = false,
                    activeAgentId = agentId,
                    removalProgress = null,
                )
            }
            setAgentUpdate(agentId, AgentUpdate.Updating)
            try {
                stopPanelsFor(agentId)
                val log = InstallLog()
                publishInstallLog(log)
                val outcome = try {
                    streamInstall(spec.updateCommand, spec.updateTimeoutSeconds, executor(), log)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "could not run the $agentId update", e)
                    InstallOutcome(exit = -1, error = e.message ?: "could not start the update")
                }
                if (outcome.exit != 0) {
                    log.appendText("pots: $agentId update ended (${outcome.describe()})\n")
                    publishInstallLog(log)
                    setAgentUpdate(agentId, AgentUpdate.UpdateFailed(_state.value.agentVersions[agentId]))
                    return@launch
                }
                sync()
                setAgentUpdate(agentId, checkOne(spec))
            } finally {
                _state.update { it.copy(activeAgentId = null) }
            }
        }
    }

    /**
     * Removes the agent's installation and leaves the user's data alone: only the
     * paths [AgentVersionSpec.installPaths] names go, which is the binary and the
     * runtime it brought — never the config or the session history beside them.
     * [includeBulk] is the answer to the second question Hermes' 5 GiB checkout
     * asks; everything else has no such path and ignores it.
     */
    fun removeAgent(agentId: String, includeBulk: Boolean) {
        val spec = AgentVersions.get(agentId) ?: return
        if (installJob?.isActive == true) return
        installJob = scope.launch {
            // The paths are known before the first delete, so the progress is a
            // count, not a guess — and the log gets a line per path, because a
            // removal prints nothing of its own.
            val guestPaths = AgentVersions.removalPaths(spec, includeBulk)
            _state.update {
                it.copy(
                    installLog = "",
                    installLogDropped = false,
                    activeAgentId = agentId,
                    removalProgress = AgentRemovalProgress(done = 0, total = guestPaths.size),
                )
            }
            setAgentUpdate(agentId, AgentUpdate.Removing)
            val log = InstallLog()
            publishInstallLog(log)
            try {
                stopPanelsFor(agentId)
                val removed = mutableListOf<RemovedPath>()
                for ((index, guestPath) in guestPaths.withIndex()) {
                    removed += deleteGuestPath(paths.rootfsDir, guestPath)
                    log.appendText(AgentVersions.removalLine(removed.last()) + "\n")
                    publishInstallLog(log)
                    _state.update { it.copy(removalProgress = AgentRemovalProgress(index + 1, guestPaths.size)) }
                }
                log.appendText(AgentVersions.removalSummary(removed) + "\n")
                publishInstallLog(log)
                setAgentUpdate(agentId, null)
                sync()
            } finally {
                _state.update { it.copy(activeAgentId = null, removalProgress = null) }
            }
        }
    }

    /** One agent's check: what it has, what the registry offers, and the comparison. */
    private fun checkOne(spec: AgentVersionSpec): AgentUpdate {
        val executor = executor()
        val probe = executor.execute(spec.installedCommand, timeoutSeconds = 30)
        // stdout only: a failing `pi --version` prints its stack trace, whose last
        // line is Node's own version — merged in, that would be read as Pi's.
        val installed = probe.takeIf { it.success }?.stdout?.let(AgentVersion::parse)
            ?: _state.value.agentVersions[spec.id]
        val answer = executor.execute(AgentVersions.latestCommand(spec), timeoutSeconds = 30)
        val latest = answer.takeIf { it.success }?.stdout?.let { AgentVersions.latest(it, spec.latestSource) }
        return AgentVersions.decide(
            present = installed != null || presence(spec.id),
            installed = installed,
            latest = latest,
            checkFailed = !answer.success,
            checkedAt = clockNow(),
        )
    }

    /**
     * Whether the app already knows this agent is on the device — its own probe,
     * not this check's. pi-web has no entry in [BuildAgents], so its version in
     * [PotsBuildState.agentVersions] is the whole record of it.
     */
    private fun presence(agentId: String): Boolean =
        agentId in _state.value.installedAgents || _state.value.agentVersions.containsKey(agentId)

    private fun setAgentUpdate(agentId: String, update: AgentUpdate?) {
        _state.update {
            val updates = it.agentUpdates.toMutableMap()
            if (update == null) updates.remove(agentId) else updates[agentId] = update
            it.copy(agentUpdates = updates.toImmutableMap())
        }
    }

    /**
     * Stops the panels that run [agentId]'s binary. pi-web is Pi's panel, so its
     * own row stops the same process: the panel and the agent are one install.
     */
    private suspend fun stopPanelsFor(agentId: String) {
        val panelIds = WebPanels.all
            .filter { it.agentId == agentId || (agentId == WebPanels.PI_WEB_VERSION_KEY && it.id == WebPanels.PI) }
            .map { it.id }
        for (panelId in panelIds) {
            if (_state.value.webPanels[panelId]?.status is WebPanelStatus.Running) webPanels.stopPanel(panelId)
        }
    }

    /** Local wall clock, formatted here so the UI needs no date library. */
    private fun clockNow(): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

    fun refresh() {
        scope.launch { sync() }
    }

    fun createProject(name: String): String? {
        val folder = sanitizeProjectName(name) ?: return null
        File(paths.projectsDir, folder).mkdirs()
        scope.launch { _state.update { it.copy(projects = scanProjects()) } }
        return folder
    }

    /**
     * Deletes the project folder and everything in it. Its shells go first: they
     * are rooted in that folder, and one whose working directory has been unlinked
     * is no longer a session anybody can use.
     */
    fun deleteProject(name: String) {
        val dir = projectDir(name) ?: return
        closeProjectSessions(name)
        scope.launch {
            deleteTree(dir)
            _state.update { it.copy(projects = scanProjects()) }
        }
    }

    /**
     * Renames the project folder, returning the sanitized new name — or null when
     * that name is unusable or already taken. Shells are closed for the same reason
     * as a delete: their working directory is the path that just moved.
     */
    fun renameProject(name: String, newName: String): String? {
        val dir = projectDir(name) ?: return null
        val folder = sanitizeProjectName(newName) ?: return null
        if (folder == name) return folder
        val target = File(paths.projectsDir, folder)
        if (target.exists()) return null
        closeProjectSessions(name)
        if (!dir.renameTo(target)) return null
        scope.launch { _state.update { it.copy(projects = scanProjects()) } }
        return folder
    }

    private fun sanitizeProjectName(name: String): String? = name.trim()
        .replace(INVALID_NAME_CHARS, "-")
        .trim('-', '.')
        .take(64)
        .takeIf { it.isNotEmpty() }

    /**
     * An existing project folder, addressed by the name the list shows. Matched
     * as-is rather than sanitized — the list is a directory listing, so it can hold
     * names a shell created that sanitizing would rewrite into a different folder —
     * but never one that reaches outside the projects directory.
     */
    private fun projectDir(name: String): File? {
        if (name.isEmpty() || name == "." || name == ".." || name.contains('/')) return null
        return File(paths.projectsDir, name).takeIf { it.isDirectory }
    }

    // --- sessions --------------------------------------------------------

    /**
     * Opens another PTY session in [project] and makes it active. A non-null
     * [agentId] runs that agent first and drops back to a shell when it exits.
     */
    fun startSession(project: String, agentId: String?) {
        if (!_state.value.isReady) return
        foregroundProject = project
        val id = "s${sessionCounter.incrementAndGet()}"
        val session = synchronized(sessionsLock) {
            val number = (sessions.values.filter { it.project == project }.maxOfOrNull { it.number } ?: 0) + 1
            BuildSession(
                id = id,
                project = project,
                agentId = agentId?.takeIf { BuildAgents.get(it) != null },
                number = number,
                columns = lastColumns.get(),
                rows = lastRows.get(),
            ).also {
                sessions[id] = it
                activeSessionId = id
            }
        }
        publishSessions()
        session.job = scope.launch { runShell(session) }
    }

    fun selectSession(id: String) {
        synchronized(sessionsLock) {
            if (!sessions.containsKey(id)) return
            activeSessionId = id
        }
        publishSessions()
    }

    fun closeSession(id: String) {
        val session = synchronized(sessionsLock) {
            val removed = sessions.remove(id) ?: return
            // Its neighbour in the same project, or nothing: other projects have
            // sessions of their own now, and none of them is what the user is
            // looking at.
            if (activeSessionId == id) {
                activeSessionId = sessions.values.lastOrNull { it.project == removed.project }?.id
            }
            removed
        }
        endSession(session)
        publishSessions()
    }

    /**
     * Re-enters [project]: its shells kept running while the user was away, so
     * this only calls off the reaper and puts the tab they left on back in front.
     */
    fun resumeProject(project: String): Boolean {
        foregroundProject = project
        val resumed = synchronized(sessionsLock) {
            reapJobs.remove(project)?.cancel()
            val last = sessions.values.lastOrNull { it.project == project }
            if (last != null) activeSessionId = last.id
            last != null
        }
        if (resumed) publishSessions()
        return resumed
    }

    /**
     * Steps out of [project] without touching its shells — a build keeps building
     * while the user reads something else, and closing a session stays the user's
     * call. The reaper is the backstop: each session is a proot process with a PTY
     * and a shell behind it, and a project reopened days later is not one the user
     * is still waiting on.
     */
    fun leaveProject(project: String) {
        if (foregroundProject == project) foregroundProject = null
        synchronized(sessionsLock) {
            reapJobs.remove(project)?.cancel()
            if (sessions.values.none { it.project == project }) return
            reapJobs[project] = scope.launch {
                delay(IDLE_PROJECT_TTL_MS)
                // Drop the handle before closing: cancelling the job that is running
                // this block is not how it should end.
                synchronized(sessionsLock) { reapJobs.remove(project) }
                closeProjectSessions(project)
            }
        }
    }

    private fun closeProjectSessions(project: String) {
        val closing = synchronized(sessionsLock) {
            reapJobs.remove(project)?.cancel()
            val matches = sessions.values.filter { it.project == project }
            matches.forEach { sessions.remove(it.id) }
            if (sessions[activeSessionId] == null) activeSessionId = sessions.values.lastOrNull()?.id
            matches
        }
        closing.forEach(::endSession)
        publishSessions()
    }

    /**
     * Writes text to the active session's shell/agent (UTF-8). Callers that want
     * a submitted line should include the trailing carriage return themselves.
     */
    fun writeToTerminal(text: String) {
        activeSession()?.handle?.writeText(text)
    }

    /**
     * Resize the active session's VT buffer and notify its live PTY
     * (desktop-style). Safe to call from the UI on every layout pass — no-ops
     * when geometry is unchanged.
     */
    fun resizeTerminal(columns: Int, rows: Int) {
        val c = columns.coerceIn(MIN_COLUMNS, MAX_COLUMNS)
        val r = rows.coerceIn(MIN_ROWS, MAX_ROWS)
        lastColumns.set(c)
        lastRows.set(r)
        val session = activeSession() ?: return
        // Cheap enough to answer on the caller's thread, and the common case: the
        // UI calls this on every layout pass, including each keyboard-animation frame.
        if (c == session.columns.get() && r == session.rows.get()) return
        scope.launch {
            // Read the geometry back rather than using the captured one: two resizes
            // in flight at once would otherwise race, and the stale one could land
            // last. Re-reading makes every pending pass apply the newest viewport.
            val currentC = lastColumns.get()
            val currentR = lastRows.get()
            session.resize(currentC, currentR)
            publishScreen(session)
            writeWinsize(session, columns = currentC, rows = currentR)
        }
    }

    // --- agent web panels -------------------------------------------------

    fun startWebPanel(panelId: String) = webPanels.start(panelId)

    fun stopWebPanel(panelId: String) = webPanels.stop(panelId)

    fun setWebPanelAutostart(panelId: String, enabled: Boolean) {
        webPanels.setAutostart(panelId, enabled)
    }

    fun webPanelPassword(panelId: String): String? = webPanels.password(panelId)

    /** The row's log section: read on the tap, never stored in the state. */
    fun webPanelLog(panelId: String): String = webPanels.panelLog(panelId)

    /**
     * Opens the panel in the device's own browser.
     *
     * Two panels sign a browser in with something minted per tap — Hermes' one-use
     * link and OpenCode 2's pairing code — so the URL is fetched first and only
     * then handed to the browser, never kept. A mint that failed leaves the row
     * saying why and opens nothing.
     *
     * The URL is deliberately absent from every log line on this path: it carries
     * a sign-in ticket that is good for one browser, and `logcat` is readable by
     * whatever holds the debug bridge. The panel id says everything a reader of
     * the log needs.
     */
    fun openWebPanel(panelId: String) {
        scope.launch {
            val url = webPanels.link(panelId) ?: return@launch
            if (!openUrl(url)) Log.w(TAG, "no browser took the $panelId panel link")
        }
    }

    /**
     * Hands the session's PTY bridge its new geometry — it polls this file and
     * applies TIOCSWINSZ + SIGWINCH.
     *
     * Written to a sibling and renamed rather than in place: the bridge reads on
     * its own clock, and a read landing inside a truncate-then-write sees a torn
     * number — "24 6" for "24 62" — which resizes the guest to a width nothing
     * asked for until the next tick.
     */
    private fun writeWinsize(session: BuildSession, columns: Int, rows: Int) {
        runCatching {
            paths.tmpDir.mkdirs()
            val target = File(paths.tmpDir, session.winsizeFile)
            val staged = File(paths.tmpDir, "${session.winsizeFile}.tmp")
            staged.writeText("$rows $columns\n")
            if (!staged.renameTo(target)) {
                target.writeText("$rows $columns\n")
                staged.delete()
            }
        }
    }

    private fun activeSession(): BuildSession? = synchronized(sessionsLock) { sessions[activeSessionId] }

    private fun closeAllSessions() {
        foregroundProject = null
        val closing = synchronized(sessionsLock) {
            reapJobs.values.forEach { it.cancel() }
            reapJobs.clear()
            val all = sessions.values.toList()
            sessions.clear()
            activeSessionId = null
            all
        }
        closing.forEach(::endSession)
        publishSessions()
    }

    /** Kills the process and drops the per-session capture files. */
    private fun endSession(session: BuildSession) {
        session.stop()
        runCatching {
            File(paths.tmpDir, session.winsizeFile).delete()
            File(paths.tmpDir, session.openUrlFile).delete()
            File(paths.tmpDir, session.pidFile).delete()
        }
    }

    private fun publishSessions() {
        val list: List<BuildTerminalSession>
        val active: String?
        synchronized(sessionsLock) {
            list = sessions.values.map { it.toUiSession() }
            active = activeSessionId
        }
        _state.update { it.copy(sessions = list.toImmutableList(), activeSessionId = active) }
    }

    // --- install ---------------------------------------------------------

    private suspend fun installInternal(agentIds: Set<String>, isActive: () -> Boolean) {
        // Whoever installs first wins: a Debian chat sandbox already put a rootfs
        // here, in which case this only adds the agents the user ticked.
        if (paths.readMarker() == null) {
            installDebian()
            ensureAgentPathProfile()
            onEnvironmentChanged?.invoke()
        }

        val failed = mutableListOf<String>()
        for (agentId in agentIds) {
            if (!isActive()) throw CancellationException()
            val agent = BuildAgents.get(agentId)
            // An agent without a [BuildAgents] entry is one whose install is a
            // plain command — pi-web's npm package — and is run from its spec.
            val spec = AgentVersions.get(agentId)?.takeIf { agent == null }
            val installed = when {
                agent != null -> installAgent(agent)
                spec?.installCommand != null -> installSpec(spec)
                else -> false
            }
            if (!installed) failed += agent?.title ?: spec?.title ?: agentId
        }
        sync(error = if (failed.isEmpty()) null else "Could not install ${failed.joinToString()}")
    }

    private suspend fun installDebian() {
        installer.install(LinuxDistro.DEBIAN) { step ->
            when (step) {
                is InstallStep.Download -> setStep(BuildStep.Download, progress = step.fraction)
                is InstallStep.Extract -> setStep(BuildStep.Extract)
                is InstallStep.Configure -> setStep(BuildStep.Configure)
                is InstallStep.Packages -> setStep(BuildStep.BasePackages)
            }
        }
    }

    private suspend fun installAgent(agent: BuildAgent): Boolean = LinuxInstaller.packageLock.withLock {
        setStep(BuildStep.Agent, agentId = agent.id)
        ensureAgentPathProfile()
        val executor = executor()
        executor.execute(
            "mkdir -p /root/.local/bin /root/.local/share/pi-node /root/.hermes " +
                "/root/.pi/agent /root/.opencode/bin /usr/local/bin",
            timeoutSeconds = 30,
        )
        val log = InstallLog()
        publishInstallLog(log)
        // Hermes' panel ships with this app, built from one hermes-agent commit:
        // the install reads that commit from here and leaves the checkout on it.
        // Written before the install, because that is the run that checks out.
        if (agent.id == BuildAgents.HERMES) pinHermesWebapp()
        val outcome = streamInstall(agent.installCommand, agent.installTimeoutSeconds, executor, log)
        // Vendor scripts may leave the binary only in their private dir and only
        // update shell rc files — which Pots Build never sources. Link into PATH
        // and re-probe so a successful download still counts as installed. The
        // probe's output is the agent's own `--version`, which is the only place
        // the version the Settings card shows may come from.
        val probe = executor.ensureAgentBinary(agent.binary)
        val installed = probe != null
        if (!installed) {
            Log.w(TAG, "${agent.id} install failed (exit detail): ${outcome.describe()}")
            val probeOutput = executor.execute(
                "command -v ${agent.binary} 2>/dev/null; " +
                    "ls -la /root/.local/bin /root/.local/share/pi-node/bin /root/.opencode/bin /usr/local/bin 2>/dev/null; " +
                    "ls -la /root/.hermes/hermes-agent/.hermes/bin 2>/dev/null | tail -5; " +
                    "ls -la /root/.pi 2>/dev/null",
                timeoutSeconds = 30,
            )
            Log.w(TAG, "${agent.id} post-install probe: ${probeOutput.stdout} ${probeOutput.stderr}")
            // "Could not install X" on its own says nothing; the log stays on
            // screen next to it, so the reason goes there.
            log.appendText("pots: ${agent.id} is not on PATH after the install (${outcome.describe()})\n")
            log.appendText(probeOutput.stdout.ifBlank { probeOutput.stderr }.takeLast(2_000) + "\n")
            publishInstallLog(log)
        }
        // The agent is only worth having if it can answer on the provider the app
        // is already using, so the config lands with the install, not on first run.
        if (installed) {
            // Hermes' panel is a separate build of `apps/desktop`, and it runs in
            // a proot call of its own — after the agent is on PATH, because the
            // build needs the `hermes` command the install just published.
            if (agent.id == BuildAgents.HERMES) buildHermesRenderer(log)
            applyAgentConfig(agent.id)
            setAgentVersion(agent.id, AgentVersion.parse(probe))
            // A reinstall is also an update: the binary under a running panel was
            // just replaced, and the live process is still the old one.
            webPanels.onAgentInstalled(agent.id)
        }
        installed
    }

    /**
     * Installs an agent whose installer is a single command — pi-web's npm
     * package. Success is the probe's verdict, not the command's: npm exits 0 on
     * a tree it could not link, and a row must not claim an install it cannot run.
     */
    private suspend fun installSpec(spec: AgentVersionSpec): Boolean = LinuxInstaller.packageLock.withLock {
        val command = spec.installCommand ?: return@withLock false
        setStep(BuildStep.Agent, agentId = spec.id)
        ensureAgentPathProfile()
        val executor = executor()
        val log = InstallLog()
        publishInstallLog(log)
        val outcome = streamInstall(command, spec.updateTimeoutSeconds, executor, log)
        val probe = executor.execute(spec.installedCommand, timeoutSeconds = 60)
        val version = probe.takeIf { it.success }?.stdout?.let(AgentVersion::parse)
        if (version != null) {
            setAgentVersion(spec.id, version)
        } else {
            log.appendText("pots: ${spec.id} is not installed after the command (${outcome.describe()})\n")
            log.appendText(probe.stdout.ifBlank { probe.stderr }.takeLast(2_000) + "\n")
            publishInstallLog(log)
        }
        version != null
    }

    /**
     * Tells the sandbox's Hermes install which commit this app's panel was built
     * from. The install ends by checking the checkout out at it, and the panel
     * refuses to start on any other — a bundle and a backend from different
     * commits is a blank page with 404s on the assets.
     *
     * A build with no bundle (a developer build) writes nothing: the install then
     * keeps its own channel, and nothing is pinned. Either way the install never
     * builds the renderer itself — that is [buildHermesRenderer]'s own proot call.
     */
    private fun pinHermesWebapp() {
        val pin = HermesWebappAssets.pin(assets) ?: return
        val marker = paths.readMarker() ?: return
        HermesWebappAssets.writePin(pin, paths.homeDir(marker))
    }

    /**
     * Builds Hermes' panel renderer on the device, right after Hermes itself.
     *
     * The build cannot share the install's proot call: `hermes webapp --build-only`
     * is a `pnpm install` of `apps/desktop`, pnpm 12 renames a staged directory
     * into place for every package, and that rename answers ENOENT under proot's
     * `--link2symlink` — the flag dpkg needs for the base packages. So this is a
     * proot call of its own, without the emulation: measured, that is the
     * difference between a failure in twenty-four seconds and a complete 45 MB
     * bundle in about seven minutes.
     *
     * The checkout, the venv and the `pm` tools were written by the installer
     * *under* the emulation, though, and no measurement on a plain rootfs covers
     * that mixed tree — so one attempt keeps `--link2symlink` and changes nothing
     * else, which is the only way the device itself answers it.
     *
     * Every attempt starts from an empty pnpm store and an empty `node_modules`,
     * because a store a failed attempt leaves behind is made of `.l2s.` symlinks
     * and the next build dies in rolldown against them. Hermes forces that store
     * to `$HERMES_ROOT/cache/pnpm` itself, so it can only be deleted, not moved.
     *
     * The bundle this app ships is the last resort. Each path writes its own line
     * to the log — which renderer the panel serves is not something the owner
     * should have to guess at. A build that failed is not an install failure: the
     * panel then serves the shipped bundle, or says the renderer is missing.
     */
    private suspend fun buildHermesRenderer(log: InstallLog) {
        for (attempt in HermesWebapp.buildAttempts) {
            log.appendText(
                "pots: renderer: building on device (attempt ${attempt.number} of " +
                    "${HermesWebapp.buildAttempts.size}, ${attempt.why}) — about seven minutes\n",
            )
            publishInstallLog(log)
            val executor = executor(extraEnv = attempt.env, hardlinkEmulation = attempt.hardlinkEmulation)
            executor.execute(HermesWebapp.CLEAN_COMMAND, timeoutSeconds = 300)
            val outcome = streamInstall(HermesWebapp.BUILD_COMMAND, RENDERER_BUILD_TIMEOUT_SECONDS, executor, log)
            // `--force-build` makes "up to date" impossible, so a line saying it means
            // the flag never reached Hermes and the bundle on disk is not this
            // attempt's build. The whole log is read rather than this attempt's slice:
            // the flag is in the command line, so every attempt answers alike, and one
            // such line means the chain rebuilt nothing at all.
            val stale = log.text().contains(HermesWebapp.UP_TO_DATE)
            val built = outcome.exit == 0 && !stale &&
                executor.execute(HermesWebapp.BUILT_CHECK, timeoutSeconds = 30).success
            log.appendText(
                when {
                    built -> "pots: ${HermesWebapp.builtOnDevice(attempt)}\n"
                    stale -> "pots: ${HermesWebapp.builtNothing(attempt)}\n"
                    else -> "pots: renderer: attempt ${attempt.number} failed (${outcome.describe()})\n"
                },
            )
            publishInstallLog(log)
            if (built) return
        }
        val pin = HermesWebappAssets.pin(assets)
        val marker = paths.readMarker()
        if (pin == null || marker == null) {
            log.appendText("pots: ${HermesWebapp.NOT_BUILT}\n")
            publishInstallLog(log)
            return
        }
        HermesWebappAssets.stageArchive(assets, pin, paths.homeDir(marker))
        val executor = executor()
        val unpacked = executor.execute(HermesWebapp.unpack(pin), timeoutSeconds = 300)
        val served = unpacked.success &&
            executor.execute(HermesWebapp.shipped(pin), timeoutSeconds = 30).success
        log.appendText(
            if (served) {
                "pots: ${HermesWebapp.SHIPPED}\n"
            } else {
                "pots: renderer: the shipped bundle could not be unpacked (${unpacked.failureDetail()})\n"
            },
        )
        publishInstallLog(log)
    }

    /**
     * Runs an agent's installer with its output streamed into [log] and published
     * to the setup screen while it runs.
     *
     * The log is flushed at most every [INSTALL_LOG_FLUSH_MS]: git writes in
     * bursts of kilobytes, and republishing on every chunk would recompose the
     * screen hundreds of times a second. The last flush always happens, so the
     * line the install stopped on is the one on screen when it does.
     *
     * The wait is polled rather than blocked so the install can be given up on:
     * a step that prints nothing for [INSTALL_STALL_MS] is treated as hung, and
     * a hung guest is killed before it is reported, or the next attempt starts
     * on top of it.
     */
    private suspend fun streamInstall(
        command: String,
        timeoutSeconds: Long,
        executor: BuildProotExecutor,
        log: InstallLog,
    ): InstallOutcome {
        val chunks = Channel<ByteArray>(Channel.UNLIMITED)
        val lastOutputAt = AtomicLong(System.currentTimeMillis())
        val handle = try {
            executor.startInstall(command) { buf, n ->
                lastOutputAt.set(System.currentTimeMillis())
                chunks.trySend(buf.copyOf(n))
            }
        } catch (e: Exception) {
            return InstallOutcome(exit = -1, error = e.message ?: "could not start the installer")
        }
        return coroutineScope {
            launch {
                var lastFlush = 0L
                for (chunk in chunks) {
                    log.append(chunk)
                    val now = System.currentTimeMillis()
                    if (now - lastFlush >= INSTALL_LOG_FLUSH_MS) {
                        lastFlush = now
                        publishInstallLog(log)
                    }
                }
                publishInstallLog(log)
            }
            val deadline = System.currentTimeMillis() + timeoutSeconds * 1000
            var outcome = InstallOutcome(exit = -1, timeoutSeconds = timeoutSeconds)
            try {
                while (true) {
                    val exit = handle.awaitExitOrNull(INSTALL_POLL_MS)
                    if (exit != null) {
                        outcome = InstallOutcome(exit, timeoutSeconds = timeoutSeconds)
                        break
                    }
                    val now = System.currentTimeMillis()
                    if (now >= deadline) {
                        outcome = InstallOutcome(-1, timedOut = true, timeoutSeconds = timeoutSeconds)
                        break
                    }
                    if (now - lastOutputAt.get() >= INSTALL_STALL_MS) {
                        outcome = InstallOutcome(-1, stalled = true, timeoutSeconds = timeoutSeconds)
                        break
                    }
                }
            } catch (e: CancellationException) {
                // The user's Cancel has to mean the installer stops, not that the
                // app stops listening while git keeps writing into the rootfs.
                handle.cancel()
                throw e
            } finally {
                chunks.close()
            }
            if (outcome.timedOut || outcome.stalled) {
                handle.cancel()
                log.appendText("pots: install ended (${outcome.describe()})\n")
                publishInstallLog(log)
            }
            outcome
        }
    }

    private fun publishInstallLog(log: InstallLog) {
        _state.update { it.copy(installLog = log.text(), installLogDropped = log.dropped) }
    }

    /** Records what [agentId]'s own `--version` said; a null [version] clears the entry. */
    private fun setAgentVersion(agentId: String, version: String?) {
        _state.update {
            val versions = it.agentVersions.toMutableMap()
            if (version == null) versions.remove(agentId) else versions[agentId] = version
            it.copy(agentVersions = versions.toImmutableMap())
        }
    }

    /** The installer already removes a rootfs it could not finish; this reports it. */
    private fun cleanUpPartialInstall(error: String?) {
        sync(error)
    }

    /**
     * Writes [agentId]'s own configuration from the provider Pots is on, so the
     * agent in the sandbox is the same agent the chat is: same provider, same
     * model, same key, same endpoint, with nothing to set up by hand.
     *
     * Written on the host straight into the guest home rather than through the
     * shell: it is plain text, and a heredoc round-trip would only add a place for
     * a key to be mangled by quoting.
     */
    private fun applyAgentConfig(agentId: String?) {
        if (agentId == null) return
        val credentials = activeCredentials?.invoke() ?: return
        val marker = paths.readMarker() ?: return
        val home = paths.homeDir(marker)
        // Hermes' panel port lives in the same file. Writing the default over a
        // port the panel had to move to would point the gateway at a busy port
        // while the running panel listens on another one.
        val webappPort = runningPanelPort(WebPanels.HERMES) ?: WebPanels.HERMES_PORT
        for (file in SandboxAgentConfig.filesFor(agentId, credentials, webappPort)) {
            val target = File(home, file.path)
            runCatching {
                target.parentFile?.mkdirs()
                target.writeText(file.content)
                // The env file carries the key: owner-only, like the shell's own .env.
                if (target.name == ".env") {
                    target.setReadable(false, false)
                    target.setReadable(true, true)
                }
            }.onFailure { Log.w(TAG, "could not write ${file.path} for $agentId", it) }
        }
    }

    /** The port a panel is listening on right now, or null when it is not up. */
    private fun runningPanelPort(panelId: String): Int? {
        val running = _state.value.webPanels[panelId]?.status as? WebPanelStatus.Running ?: return null
        return WebPanels.portOf(running.url)
    }

    // --- terminal --------------------------------------------------------

    /** Snapshot the screen and push it to the UI. */
    private fun publishScreen(session: BuildSession) {
        val linksBefore = session.snapshot.hyperlinks
        session.refreshSnapshot()
        val linksAfter = session.snapshot.hyperlinks
        // New URLs (OSC 8 / stream) restart the auto-hide TTL.
        if (linksAfter.isNotEmpty() && linksAfter != linksBefore) {
            scheduleHyperlinkClear(session)
        }
        // The snapshot above is kept current either way; only a session the user
        // is actually looking at is worth pushing to the UI.
        if (session.project == foregroundProject) publishSessions()
    }

    private fun runShell(session: BuildSession) {
        session.refreshSnapshot()
        publishSessions()
        var urlPollJob: Job? = null
        // Repaint is coalesced to at most one per frame. A repaint snapshots the
        // whole grid and re-lays out the text, and the PTY hands us a chunk every
        // few hundred microseconds under heavy output — repainting per chunk is
        // what made the app unusable while a command was running. CONFLATED means
        // a burst collapses into a single repaint without ever dropping the last
        // state: whatever arrived during the wait is still pending afterwards.
        val repaint = Channel<Unit>(Channel.CONFLATED)
        val repaintJob = scope.launch(Dispatchers.Default) {
            while (true) {
                if (repaint.receiveCatching().isClosed) break
                publishScreen(session)
                delay(REPAINT_INTERVAL_MS)
            }
            // The channel closes once the shell is done writing, so this last pass
            // renders the tail — including the exit line — after every writer.
            publishScreen(session)
        }
        try {
            ensureBrowserCaptureHelpers()
            ensureAgentPathProfile()
            runCatching {
                paths.tmpDir.mkdirs()
                File(paths.tmpDir, session.openUrlFile).writeText("")
            }
            // An agent session runs the CLI first and leaves a usable shell behind.
            // The config is rewritten right before the CLI starts, so a provider or
            // model switched in Pots a second ago is the one the agent comes up on.
            applyAgentConfig(session.agentId)
            // Resolve an absolute path under the non-login probe PATH: login shells
            // (`bash -lc`) reset PATH via /etc/profile, so the launch does not depend
            // on whatever the agent's installer did or did not write into shell rc.
            val agentBinary = BuildAgents.get(session.agentId)?.binary
            val agentCmd = agentBinary?.let { resolveAgentCommand(it) }
            val launch = if (agentCmd != null) "$agentCmd; exec bash -l" else "exec bash -l"
            // Geometry is taken as late as possible, and from the newest measurement
            // rather than the one this session was created with: resolving the agent
            // binary runs a probe inside the rootfs, and the viewport is normally
            // measured while that is still going. Reading it any earlier bakes the
            // 80x24 default into the PTY, and an agent that paints its banner once
            // paints it at a width the grid never had — every line wraps, and it
            // stays wrapped because that output is never redrawn.
            val cols = lastColumns.get()
            val rows = lastRows.get()
            session.resize(cols, rows)
            writeWinsize(session, columns = cols, rows = rows)
            publishScreen(session)
            // Interactive login shell rooted at the project folder. No stty here: the
            // bridge already owns the winsize, and re-asserting a captured one would
            // only overwrite a resize that landed while the shell was starting.
            val handle = executor(columns = cols, rows = rows, session = session).executeStreaming(
                command = launch,
                workingDir = "/root/projects/${session.project}",
                // Runs on the PTY reader thread: keep it to parsing, and let the
                // repaint pump do the snapshotting and publishing on its own clock.
                onOutput = { buf, n ->
                    // Always scan raw stream for URLs (OSC 8/52 may be incomplete across chunks).
                    val text = buf.decodeToString(0, n, throwOnInvalidSequence = false)
                    session.withScreen { screen ->
                        screen.noteTextUrls(text)
                        screen.writeText(text)
                    }
                    repaint.trySend(Unit)
                },
            )
            session.handle = handle
            // Poll the agent's URL capture file (and our BROWSER wrapper) while the shell runs.
            urlPollJob = scope.launch { pollOpenUrlFile(session) }
            val exit = handle.awaitExit()
            if (exit > 0) {
                session.withScreen { it.writeText("\r\n[shell exited $exit]\r\n") }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Shell failed", e)
            session.withScreen { it.writeText("\r\n${e.message ?: "Shell failed"}\r\n") }
        } finally {
            urlPollJob?.cancel()
            // One last read in case the URL landed as the process exited.
            ingestOpenUrlFile(session)
            session.handle = null
            session.busy = false
            // Closing lets the pump run its final pass, which renders whatever the
            // blocks above wrote. Nothing writes to the screen after this point.
            repaint.close()
            if (!repaintJob.isActive) publishScreen(session)
        }
    }

    /**
     * Login shells (`bash -l` / `bash -lc`) reset PATH from `/etc/profile`, which
     * drops the proot-injected agent dirs. Vendors do not reliably write PATH into
     * shell rc either, and Pi's own Node runtime lives in a third directory that
     * `npm` links from but does not put on PATH.
     *
     * A tiny `/etc/profile.d` snippet puts every agent dir back for login shells
     * without depending on vendor rc edits. Written on the host so it does not
     * need a proot round-trip. The dirs come from [BuildAgents.AGENT_BIN_PATH], the
     * same list the chat terminal exports at startup — that shell runs with
     * `--noprofile` and never reads this file.
     */
    private fun ensureAgentPathProfile() {
        val dir = File(paths.rootfsDir, "etc/profile.d")
        dir.mkdirs()
        File(dir, "pots-build-path.sh").writeText(BuildAgents.AGENT_PATH_PROFILE)
    }

    /**
     * Absolute path (quoted) for [binary], or the bare name if resolution fails.
     * Ensures the binary is linked into a known dir before probing.
     */
    private fun resolveAgentCommand(binary: String): String {
        // Safe: [binary] is a fixed catalog name (pi/hermes).
        val probe = executor()
        probe.ensureAgentBinary(binary)
        val path = probe.execute(
            "command -v $binary 2>/dev/null",
            timeoutSeconds = 20,
        ).stdout.trim().lineSequence().firstOrNull().orEmpty()
        return if (path.startsWith("/")) "'$path'" else binary
    }

    /**
     * Installs `/usr/local/bin/pots-browser` into the rootfs so `$BROWSER` captures
     * the URLs an agent tries to open when no real display/browser exists under
     * proot. The target file is per session, passed in as `POTS_OPEN_URL_FILE`.
     */
    private fun ensureBrowserCaptureHelpers() {
        val script = File(paths.rootfsDir, "usr/local/bin/pots-browser")
        script.parentFile?.mkdirs()
        script.writeText(
            """
            |#!/bin/sh
            |# Pots Build: capture browser-open URLs for the in-app link bar.
            |url="${'$'}1"
            |target="${'$'}{POTS_OPEN_URL_FILE:-/tmp/pots-open-url}"
            |if [ -n "${'$'}url" ]; then
            |  printf '%s\n' "${'$'}url" >> "${'$'}target"
            |fi
            |exit 0
            """.trimMargin(),
        )
        script.setExecutable(true, false)
    }

    private suspend fun pollOpenUrlFile(session: BuildSession) {
        while (true) {
            ingestOpenUrlFile(session)
            kotlinx.coroutines.delay(250)
        }
    }

    private fun ingestOpenUrlFile(session: BuildSession) {
        val file = File(paths.tmpDir, session.openUrlFile)
        if (!file.exists() || file.length() == 0L) return
        val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
        if (lines.isEmpty()) return
        // Consume the file so the same URLs are not re-applied after a TTL clear.
        runCatching { file.writeText("") }
        var added = false
        session.withScreen { screen ->
            for (line in lines) {
                val url = line.trim()
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    if (screen.noteHyperlink(url)) {
                        added = true
                        Log.i(TAG, "captured open-url: $url")
                    }
                }
            }
        }
        if (added) {
            session.refreshSnapshot()
            publishSessions()
            scheduleHyperlinkClear(session)
        }
    }

    /**
     * Drop the link bar a couple of minutes after the last new URL. Login flows
     * only need the links while the user completes them; leaving them forever
     * looks like they still need to sign in.
     */
    private fun scheduleHyperlinkClear(session: BuildSession) {
        session.hyperlinkClearJob?.cancel()
        session.hyperlinkClearJob = scope.launch {
            delay(HYPERLINK_TTL_MS)
            session.withScreen { it.clearHyperlinks() }
            session.refreshSnapshot()
            publishSessions()
        }
    }

    // --- state -----------------------------------------------------------

    /**
     * Publishes in two passes. The first is file stats only, so the screen learns
     * within milliseconds that Debian is installed; the second replaces the guess
     * with the authoritative probe. Doing it the other way round costs a proot
     * process per agent plus a walk of the whole rootfs before anything is shown,
     * which is what made the setup screen flash on every open.
     */
    private fun sync(error: String? = null) {
        // Debian only: an Alpine chat sandbox cannot host the agents, and Pots Build
        // then installs its own Debian somewhere else.
        val ready = paths.readMarker()?.distro == LinuxDistro.DEBIAN && File(paths.prootPath).canExecute()
        val wasReady = _state.value.isReady
        _state.update {
            it.copy(
                environment = if (ready) BuildEnvironmentState.Ready else BuildEnvironmentState.NotInstalled,
                installedAgents = if (ready) guessAgents() else persistentSetOf(),
                // Versions survive the file-stat pass and are replaced by the real
                // probe below; an uninstalled system has none to keep.
                agentVersions = if (ready) it.agentVersions else persistentMapOf(),
                // The check results outlive a refresh for the same reason: they are
                // a network answer, not a file stat, and re-asking per refresh would
                // be traffic nobody requested.
                agentUpdates = if (ready) it.agentUpdates else persistentMapOf(),
                projects = scanProjects(),
                // Keep the card's numbers up while they are re-measured; an
                // uninstalled system has none to keep.
                systemInfo = if (ready) it.systemInfo else null,
                // Same for the panels: with no Linux there is nothing to listen.
                webPanels = if (ready) it.webPanels else persistentMapOf(),
                lastError = error,
            )
        }
        if (!ready) return
        val agents = detectAgents()
        _state.update {
            it.copy(
                installedAgents = agents.keys.toImmutableSet(),
                agentVersions = agents.mapNotNull { (id, output) ->
                    AgentVersion.parse(output)?.let { version -> id to version }
                }.toMap().toImmutableMap(),
            )
        }
        val info = readSystemInfo()
        _state.update { it.copy(systemInfo = info) }
        // What each panel is doing, now that the agents are known to be there.
        webPanels.refresh()
        // Autostart is asked for on the transition into Ready only. Doing it on
        // every refresh would bring back a panel the user just stopped by hand.
        if (!wasReady) webPanels.ensureAutostart()
    }

    /**
     * Host-side guess at the installed agents: the symlink [detectAgents] leaves
     * in the guest's bin dir. The link is checked without following it — its
     * target is an absolute guest path that resolves only inside proot — so this
     * costs one stat per agent where the real probe costs a process.
     */
    private fun guessAgents(): ImmutableSet<String> = BuildAgents.all
        .filter {
            val link = File(paths.rootfsDir, "root/.local/bin/${it.binary}").toPath()
            Files.exists(link, LinkOption.NOFOLLOW_LINKS)
        }
        .map { it.id }
        .toImmutableSet()

    /** Read straight off the rootfs — no proot round-trip, so it is cheap enough for every refresh. */
    private fun readSystemInfo(): BuildSystemInfo {
        val pretty = runCatching {
            File(paths.rootfsDir, "etc/os-release").readLines()
                .firstOrNull { it.startsWith("PRETTY_NAME=") }
                ?.substringAfter('=')
                ?.trim('"')
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
        val version = runCatching {
            File(paths.rootfsDir, "etc/debian_version").readText().trim().takeIf { it.isNotBlank() }
        }.getOrNull()
        val packages = runCatching {
            File(paths.rootfsDir, "var/lib/dpkg/status").useLines { lines ->
                lines.count { it.startsWith("Package: ") }
            }
        }.getOrDefault(0)
        val free = runCatching { StatFs(paths.root.absolutePath).availableBytes }.getOrDefault(0L)
        return BuildSystemInfo(
            distribution = pretty ?: version?.let { "Debian $it" } ?: "Debian",
            architecture = DebianSpec.arch(),
            packageCount = packages,
            systemBytes = directorySize(paths.rootfsDir) + directorySize(paths.tmpDir),
            projectsBytes = directorySize(paths.projectsDir),
            freeBytes = free,
        )
    }

    private fun setStep(step: BuildStep, progress: Float? = null, agentId: String? = null) {
        _state.update {
            it.copy(environment = BuildEnvironmentState.Installing(step, progress, agentId), lastError = null)
        }
    }

    private fun detectAgents(): Map<String, String> {
        ensureAgentPathProfile()
        val executor = executor()
        // One probe per agent answers both questions at once: the binary runs, and
        // its own `--version` line is what the Settings card reports.
        val detected = BuildAgents.all.mapNotNull { agent ->
            executor.ensureAgentBinary(agent.binary)?.let { agent.id to it }
        }.toMap()
        // Pi's web panel is not an agent of its own — it is part of Pi's install —
        // so its version rides in the same map under its own key, and the Settings
        // card prints it next to Pi's.
        if (BuildAgents.PI in detected) {
            val probe = executor.execute("pi-web --version 2>&1", timeoutSeconds = 60)
            AgentVersion.parse(probe.takeIf { it.success }?.stdout)?.let { version ->
                detected + (WebPanels.PI_WEB_VERSION_KEY to version)
            }
        }
        return detected
    }

    private fun scanProjects() = paths.projectsDir.listFiles()
        .orEmpty()
        .filter { it.isDirectory }
        .map { it.name }
        .sorted()
        .toImmutableList()

    private fun executor(
        columns: Int = lastColumns.get(),
        rows: Int = lastRows.get(),
        session: BuildSession? = null,
        /** Panel processes need their own password in the environment. */
        extraEnv: Map<String, String> = emptyMap(),
        /**
         * Whether this call may run with proot's hardlink emulation. apt/dpkg
         * needs it and the Hermes renderer build must not have it — see
         * [DebianSpec.buildProotArgs]; everything else keeps it.
         */
        hardlinkEmulation: Boolean = true,
    ): BuildProotExecutor {
        paths.copyLibtalloc()
        paths.ensureLayout()
        paths.ensureMountPoints()
        val openUrlPath = "/tmp/${session?.openUrlFile ?: "pots-open-url"}"
        return BuildProotExecutor(
            launcher = ProotLauncher(
                prootPath = paths.prootPath,
                libDir = paths.libDir,
                rootfsPath = paths.rootfsDir.absolutePath,
                tmpPath = paths.tmpDir.absolutePath,
                // /root itself stays on the rootfs so agent binaries under the
                // vendor install dirs are executable — external storage is often
                // mounted noexec.
                binds = listOf(paths.projectsDir.absolutePath to "/root/projects"),
                extraArgs = if (hardlinkEmulation) DebianSpec.prootArgs else DebianSpec.buildProotArgs,
                env = DebianSpec.env + agentEnv(columns, rows, openUrlPath, session?.agentId) + extraEnv,
            ),
            tmpPath = paths.tmpDir.absolutePath,
            columns = columns,
            rows = rows,
            winsizePath = "/tmp/${session?.winsizeFile ?: "pots-winsize"}",
            pidFileName = session?.pidFile ?: "pots-pid",
        )
    }

    private fun agentEnv(columns: Int, rows: Int, openUrlPath: String, agentId: String?): Map<String, String> {
        val base = mapOf(
            "USER" to "root",
            // OpenCode → ~/.opencode/bin, Pi → ~/.local/bin (on
            // ~/.local/share/pi-node), Hermes → ~/.local/bin link into
            // ~/.hermes/venv/bin. Keep every agent dir on PATH; shell rc files are
            // not sourced for non-login probes.
            "PATH" to "/root/.local/bin:/root/.local/share/pi-node/bin:/root/.opencode/bin:$DEFAULT_GUEST_PATH",
            "COLORTERM" to "truecolor",
            "COLUMNS" to columns.toString(),
            "LINES" to rows.toString(),
            // Same target for the pots-browser wrapper, so both hooks feed one session's link bar.
            "POTS_OPEN_URL_FILE" to openUrlPath,
            // webbrowser crate falls back to ${'$'}BROWSER when xdg-open is missing.
            "BROWSER" to "/usr/local/bin/pots-browser",
        )
        // Only the session's own agent gets the provider key, and only the agent
        // that reads it from the process (OpenCode's `{env:...}`). Pi and Hermes
        // carry it in their own config files, so their env stays key-free.
        val credentials = activeCredentials?.invoke()
        val providerKey: Map<String, String> = if (agentId != null && credentials != null) {
            SandboxAgentConfig.envFor(agentId, credentials)
        } else {
            emptyMap()
        }
        return base + providerKey
    }
}

/**
 * How a streamed install command ended.
 *
 * [exit] is the shell's own status; -1 when there is none to report — the
 * process never started, or it was killed for running too long.
 */
private class InstallOutcome(
    val exit: Int,
    val timedOut: Boolean = false,
    val stalled: Boolean = false,
    val error: String? = null,
    val timeoutSeconds: Long = 0,
) {
    /** One line naming why it ended, for the log and logcat alike. */
    fun describe(): String = when {
        error != null -> error
        stalled -> "no output for ${INSTALL_STALL_MS / 60_000} min"
        timedOut -> "timed out after ${timeoutSeconds}s"
        else -> "exit $exit"
    }
}

private fun BuildProotExecutor.hasBinary(binary: String): Boolean =
    execute("command -v $binary >/dev/null 2>&1", timeoutSeconds = 20).success

/**
 * What [binary]'s own `--version` printed, or null when the binary is missing or
 * does not answer. Always tries to place a stable symlink in `/root/.local/bin`
 * (even when already on the probe PATH) so login shells that only keep a subset
 * of dirs still find every agent by name.
 *
 * Known layouts:
 * - OpenCode: `~/.opencode/bin/opencode`, linked into `~/.local/bin`
 * - Pi: `~/.local/bin/pi` → the managed install's `~/.pi/agent/bin/pi`, Node runtime in `~/.local/share/pi-node/bin`
 * - Hermes: `~/.hermes/hermes-agent/.hermes/bin/hermes`, linked into `~/.local/bin`
 *
 * Reachable is not installed: a migrated home can carry Pi's npm payload and its
 * `.local/bin/pi` shim without the Node runtime it needs, and the shim then dies
 * on `#!/usr/bin/env node`. So the probe runs the binary — `--version` costs about
 * a second and only happens on install and environment refresh, never per session.
 *
 * The output is returned rather than discarded because it is also the version:
 * nothing else in the app may claim to know which release is installed.
 */
private fun BuildProotExecutor.ensureAgentBinary(binary: String): String? {
    // Safe: [binary] is a fixed catalog name (pi/hermes), not user input.
    execute(
        """
        set -e
        bin='$binary'
        dest="/root/.local/bin/${'$'}bin"
        mkdir -p /root/.local/bin
        # Prefer a real file under .local/bin; otherwise link from the agent's own dir.
        if [ -x "${'$'}dest" ] && [ ! -d "${'$'}dest" ]; then
            exit 0
        fi
        for candidate in \
            "/root/.hermes/hermes-agent/.hermes/bin/${'$'}bin" \
            "/root/.local/share/pi-node/bin/${'$'}bin" \
            "/root/.opencode/bin/${'$'}bin" \
            "/usr/local/bin/${'$'}bin"
        do
            if [ -x "${'$'}candidate" ] && [ ! -d "${'$'}candidate" ]; then
                ln -sfn "${'$'}candidate" "${'$'}dest"
                exit 0
            fi
        done
        exit 0
        """.trimIndent(),
        timeoutSeconds = 30,
    )
    if (!hasBinary(binary)) return null
    return execute("$binary --version 2>&1", timeoutSeconds = 60).takeIf { it.success }?.stdout
}
