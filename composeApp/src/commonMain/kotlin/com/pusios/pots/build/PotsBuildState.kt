package com.pusios.pots.build

import androidx.compose.runtime.Immutable
import com.pusios.pots.build.terminal.TerminalSnapshot
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf

/** Ordered steps of a Pots Build install, shown as progress on the setup screen. */
enum class BuildStep {
    Download,
    Extract,
    Configure,
    BasePackages,
    Agent,
}

@Immutable
sealed interface BuildEnvironmentState {
    data object NotInstalled : BuildEnvironmentState

    /** [progress] is null while a step has no measurable progress. */
    data class Installing(
        val step: BuildStep,
        val progress: Float? = null,
        val agentId: String? = null,
    ) : BuildEnvironmentState

    data object Ready : BuildEnvironmentState
}

/**
 * One live shell inside a project — a tab in the terminal's session strip.
 * [agentId] is null for a plain shell; [number] disambiguates same-kind tabs.
 */
@Immutable
data class BuildTerminalSession(
    val id: String,
    val project: String,
    val agentId: String?,
    val number: Int,
    val terminal: TerminalSnapshot = TerminalSnapshot.blank(),
    val busy: Boolean = false,
)

/** What the installed Debian actually is, shown on the project list. */
@Immutable
data class BuildSystemInfo(
    /** e.g. "Debian GNU/Linux 12 (bookworm)". */
    val distribution: String,
    /** Linux architecture name of the rootfs (arm64, amd64, …). */
    val architecture: String,
    val packageCount: Int,
    /** Bytes taken by the Debian rootfs itself. */
    val systemBytes: Long,
    /** Bytes taken by the project folders (kept when Linux is uninstalled). */
    val projectsBytes: Long,
    val freeBytes: Long,
)

/** Everything the Pots Build screen renders, in one snapshot. */
@Immutable
data class PotsBuildState(
    val environment: BuildEnvironmentState = BuildEnvironmentState.NotInstalled,
    val installedAgents: ImmutableSet<String> = persistentSetOf(),
    /**
     * What each installed agent's own `--version` reported, keyed by agent id.
     * Only determined versions are here — an agent whose probe printed nothing
     * usable has no entry, and the UI says so rather than inventing one.
     */
    val agentVersions: ImmutableMap<String, String> = persistentMapOf(),
    /**
     * What each agent's version row is showing: unchecked, up to date, a newer
     * release available, a check that failed, or an update in flight. Separate
     * from [agentVersions] because "what is installed" and "what is newest" are
     * two different facts, and only their comparison may say "up to date".
     */
    val agentUpdates: ImmutableMap<String, AgentUpdate> = persistentMapOf(),
    /** Folder names under the Linux home's `projects` directory. */
    val projects: ImmutableList<String> = persistentListOf(),
    /**
     * The coding agents' web panels, keyed by panel id — only the panels whose
     * agent is installed. Each carries its own state, so a panel that failed says
     * why while the others keep running.
     */
    val webPanels: ImmutableMap<String, WebPanelState> = persistentMapOf(),
    /** Live shells, oldest first. Sessions of every open project, not just the visible one. */
    val sessions: ImmutableList<BuildTerminalSession> = persistentListOf(),
    val activeSessionId: String? = null,
    /** Null until Debian is installed and its facts have been read. */
    val systemInfo: BuildSystemInfo? = null,
    /** Last install failure, in shell/English wording — cleared when work restarts. */
    val lastError: String? = null,
    /**
     * The tail of the running agent installer's console output. Kept after the
     * install ends — a failed install's log is the explanation the error line
     * above it cannot give — and cleared when the next install starts.
     */
    val installLog: String = "",
    /** True when [installLog] lost its head to stay inside the buffer. */
    val installLogDropped: Boolean = false,
    /**
     * The agent whose install, update or removal owns [installLog]. One job runs
     * at a time (`installJob`), so one row owns the log — and only that row may
     * show it; another agent's output would read as its own.
     */
    val activeAgentId: String? = null,
    /** How far the running removal got; null when no removal is running. */
    val removalProgress: AgentRemovalProgress? = null,
) {
    val isReady: Boolean get() = environment is BuildEnvironmentState.Ready
    val isInstalling: Boolean get() = environment is BuildEnvironmentState.Installing
    val activeSession: BuildTerminalSession? get() = sessions.firstOrNull { it.id == activeSessionId }
}
