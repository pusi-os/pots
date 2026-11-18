package com.pusios.pots.build.runtime

import android.os.StatFs
import android.util.Log
import com.pusios.pots.build.AgentVersion
import com.pusios.pots.build.BuildAgent
import com.pusios.pots.build.BuildAgents
import com.pusios.pots.build.BuildEnvironmentState
import com.pusios.pots.build.BuildStep
import com.pusios.pots.build.BuildSystemInfo
import com.pusios.pots.build.BuildTerminalSession
import com.pusios.pots.build.PotsBuildState
import com.pusios.pots.build.SandboxAgentConfig
import com.pusios.pots.build.SandboxAgentCredentials
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicInteger

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

    /**
     * The provider Pots is using right now — supplied by the controller, which is
     * the only place that can reach the settings repository. Read at every agent
     * start rather than cached, so switching provider or model in Settings is
     * enough for the sandbox agents to follow.
     */
    @Volatile
    var activeCredentials: (() -> SandboxAgentCredentials?)? = null

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
            try {
                installInternal(agentIds) { isActive }
            } catch (e: CancellationException) {
                cleanUpPartialInstall(error = null)
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Install failed", e)
                cleanUpPartialInstall(error = e.message ?: "Install failed")
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
            paths.deleteInstall()
            paths.ensureLayout()
            _state.value = PotsBuildState(projects = scanProjects())
            onEnvironmentChanged?.invoke()
        }
    }

    /** Called when something else is about to delete the rootfs under us. */
    fun onEnvironmentRemoved() {
        closeAllSessions()
        _state.value = PotsBuildState(projects = scanProjects())
    }

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

    /**
     * Deletes [dir] and its contents without following symlinks. A project can hold
     * a link into the rootfs (agents leave plenty), and a walk that followed one
     * would delete the Debian install instead of the project.
     */
    private fun deleteTree(dir: File) {
        runCatching {
            Files.walkFileTree(
                dir.toPath(),
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        runCatching { Files.deleteIfExists(file) }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                        FileVisitResult.CONTINUE

                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                        runCatching { Files.deleteIfExists(dir) }
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        }
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
        for (agent in agentIds.mapNotNull { BuildAgents.get(it) }) {
            if (!isActive()) throw CancellationException()
            if (!installAgent(agent)) failed += agent.title
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
        val result = executor.execute(agent.installCommand, timeoutSeconds = agent.installTimeoutSeconds)
        // Vendor scripts may leave the binary only in their private dir and only
        // update shell rc files — which Pots Build never sources. Link into PATH
        // and re-probe so a successful download still counts as installed. The
        // probe's output is the agent's own `--version`, which is the only place
        // the version the Settings card shows may come from.
        val probe = executor.ensureAgentBinary(agent.binary)
        val installed = probe != null
        if (!installed) {
            Log.w(TAG, "${agent.id} install failed (exit detail): ${result.failureDetail()}")
            val probeOutput = executor.execute(
                "command -v ${agent.binary} 2>/dev/null; " +
                    "ls -la /root/.local/bin /root/.local/share/pi-node/bin /root/.opencode/bin /usr/local/bin 2>/dev/null; " +
                    "ls -la /root/.hermes/hermes-agent/.hermes/bin 2>/dev/null | tail -5; " +
                    "ls -la /root/.pi 2>/dev/null",
                timeoutSeconds = 30,
            )
            Log.w(TAG, "${agent.id} post-install probe: ${probeOutput.stdout} ${probeOutput.stderr}")
        }
        // The agent is only worth having if it can answer on the provider the app
        // is already using, so the config lands with the install, not on first run.
        if (installed) {
            applyAgentConfig(agent.id)
            setAgentVersion(agent.id, AgentVersion.parse(probe))
        }
        installed
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
        for (file in SandboxAgentConfig.filesFor(agentId, credentials)) {
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
     * need a proot round-trip.
     */
    private fun ensureAgentPathProfile() {
        val dir = File(paths.rootfsDir, "etc/profile.d")
        dir.mkdirs()
        File(dir, "pots-build-path.sh").writeText(
            """
            |# Managed by Pots Build — keep coding-agent CLIs on PATH for login shells.
            |export PATH="/root/.local/bin:/root/.local/share/pi-node/bin:/root/.opencode/bin${'$'}{PATH:+:${'$'}PATH}"
            |
            """.trimMargin(),
        )
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
        _state.update {
            it.copy(
                environment = if (ready) BuildEnvironmentState.Ready else BuildEnvironmentState.NotInstalled,
                installedAgents = if (ready) guessAgents() else persistentSetOf(),
                // Versions survive the file-stat pass and are replaced by the real
                // probe below; an uninstalled system has none to keep.
                agentVersions = if (ready) it.agentVersions else persistentMapOf(),
                projects = scanProjects(),
                // Keep the card's numbers up while they are re-measured; an
                // uninstalled system has none to keep.
                systemInfo = if (ready) it.systemInfo else null,
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
        return BuildAgents.all.mapNotNull { agent ->
            executor.ensureAgentBinary(agent.binary)?.let { agent.id to it }
        }.toMap()
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
                extraArgs = DebianSpec.prootArgs,
                env = DebianSpec.env + agentEnv(columns, rows, openUrlPath, session?.agentId),
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

/**
 * Total size of the regular files under [dir], without following symlinks — an
 * LXC rootfs contains link loops that would trap a naive recursive walk.
 */
private fun directorySize(dir: File): Long {
    if (!dir.exists()) return 0L
    var total = 0L
    runCatching {
        Files.walkFileTree(
            dir.toPath(),
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile) total += attrs.size()
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                    FileVisitResult.CONTINUE
            },
        )
    }
    return total
}
