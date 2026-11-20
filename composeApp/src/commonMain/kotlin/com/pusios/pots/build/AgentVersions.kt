package com.pusios.pots.build

/**
 * The version flow for the coding agents: where each one's installed and newest
 * versions come from, how the two are compared, and what removing one may take
 * off the device.
 *
 * Plain Kotlin on purpose. The decisions that must never lie — a failed check is
 * not "up to date", `2.3.10` is newer than `2.3.9`, removing an agent leaves its
 * configuration alone — are testable on the JVM, where the guest, the network
 * and the clock can be pinned down; on a phone they cannot.
 */

/** Which field of a registry's JSON answer carries the newest version. */
enum class LatestSource {
    /** `{"channel":"latest","version":"2.3.2"}` — OpenCode's own update endpoint. */
    Version,

    /** `{"dist-tags":{"latest":"1.5.2"}}` — an npm package document. */
    NpmDistTag,

    /** `{"tag_name":"v0.23.3"}` — a GitHub release. */
    GithubTag,
}

/** What an update costs, in the terms the confirmation dialog has to say out loud. */
enum class UpdateCost {
    /** Tens of megabytes at most — Pi and its web panel. */
    Light,

    /** OpenCode's v2 package: ~115 MiB, minutes on a weak connection. */
    Heavy,

    /** Hermes: `hermes update` restarts the gateway and drops an open panel. */
    Gateway,
}

/**
 * One agent's whole flow.
 *
 * [installPaths] is the installation and nothing else; [keepPaths] is what the
 * user would lose and therefore must survive a removal. [bulkPath] is an
 * installation big enough that removing it is a second question — Hermes' 5 GiB
 * checkout is the only one, and the safe answer is the default.
 */
data class AgentVersionSpec(
    val id: String,
    /** Product name — shown as-is, not translated. */
    val title: String,
    /** Runs in the guest and prints the version this device has. */
    val installedCommand: String,
    val latestUrl: String,
    val latestSource: LatestSource,
    /** Null when another agent's install brings this one (pi-web rides with Pi). */
    val installCommand: String?,
    val updateCommand: String,
    val updateCost: UpdateCost,
    val installPaths: List<String>,
    val keepPaths: List<String>,
    val bulkPath: String? = null,
    val updateTimeoutSeconds: Long = 1800,
)

/** What the version row for one agent is showing, and therefore which buttons it offers. */
sealed interface AgentUpdate {

    /** Installed, but the newest version has not been asked for yet. */
    data class Unchecked(val installed: String?) : AgentUpdate

    data object Checking : AgentUpdate

    data class UpToDate(val installed: String, val checkedAt: String? = null) : AgentUpdate

    data class Available(
        val installed: String,
        val latest: String,
        val checkedAt: String? = null,
    ) : AgentUpdate

    /**
     * No usable answer from the registry. Deliberately its own state: a check
     * that did not happen says nothing about the version, and showing
     * [UpToDate] here is exactly the lie the button must never tell.
     */
    data class CheckFailed(val installed: String?, val checkedAt: String? = null) : AgentUpdate

    data object Updating : AgentUpdate

    /** The update command ran and did not finish its job. */
    data class UpdateFailed(val installed: String?) : AgentUpdate

    data object NotInstalled : AgentUpdate
}

/**
 * One row of the agents card: what the device has, and what the last check said.
 * [update] is never null — an agent nothing has been asked about is [AgentUpdate.Unchecked],
 * which is a state the UI can render honestly.
 */
data class AgentVersionRow(
    val id: String,
    val title: String,
    val present: Boolean,
    val version: String?,
    val update: AgentUpdate,
)

object AgentVersions {

    /**
     * Every directory an agent binary can live in, for the probes that have to
     * find one. `pi --version` dies with a stack trace under the system's Node 18
     * and only answers under the runtime Pi's own install unpacks, so the probe
     * cannot rely on the PATH the shell happens to have.
     */
    const val AGENT_PATH =
        "/root/.local/share/pi-node/bin:/root/.local/bin:/root/.opencode/bin" +
            ":/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    /** One HTTP GET, no redirect chain to wait on, and a bounded wait. */
    private const val CURL = "curl -fsS --max-time 15"

    /** Where the web panel's package lands, shared with Pi's Node runtime. */
    private const val PI_NODE = "/root/.local/share/pi-node"

    val all: List<AgentVersionSpec> = listOf(
        AgentVersionSpec(
            id = BuildAgents.OPENCODE,
            title = "OpenCode",
            installedCommand = "PATH=$AGENT_PATH opencode --version",
            // Not GitHub's releases/latest: it still points at OpenCode 1 (1.19.6)
            // while v2.3.2 is the current release. This is the endpoint v2's own
            // installer resolves the version from.
            latestUrl = "https://opencode.ai/update/api/latest/cli/npm",
            latestSource = LatestSource.Version,
            installCommand = null,
            updateCommand = "PATH=$AGENT_PATH opencode upgrade",
            updateCost = UpdateCost.Heavy,
            installPaths = listOf("/root/.opencode", "/root/.local/bin/opencode"),
            // Config and session history: v2 reads the same paths v1 wrote.
            keepPaths = listOf("/root/.config/opencode", "/root/.local/share/opencode"),
            updateTimeoutSeconds = 900,
        ),
        AgentVersionSpec(
            id = BuildAgents.PI,
            title = "Pi",
            installedCommand = "PATH=$AGENT_PATH pi --version",
            latestUrl = "https://registry.npmjs.org/@earendil-works/pi-coding-agent",
            latestSource = LatestSource.NpmDistTag,
            installCommand = null,
            updateCommand = "PATH=$AGENT_PATH pi update",
            updateCost = UpdateCost.Light,
            installPaths = listOf("/root/.local/bin/pi"),
            // The managed install under ~/.pi/agent is Pi's configuration as much
            // as its payload; only the command is removed.
            keepPaths = listOf("/root/.pi/agent"),
            updateTimeoutSeconds = 1800,
        ),
        AgentVersionSpec(
            id = WebPanels.PI_WEB_VERSION_KEY,
            title = "pi-web",
            installedCommand = "PATH=$AGENT_PATH npm ls -g --prefix $PI_NODE @agegr/pi-web",
            latestUrl = "https://registry.npmjs.org/@agegr/pi-web",
            latestSource = LatestSource.NpmDistTag,
            installCommand = "PATH=$AGENT_PATH npm install -g --prefix $PI_NODE --no-audit --no-fund @agegr/pi-web",
            updateCommand = "PATH=$AGENT_PATH npm install -g --prefix $PI_NODE --no-audit --no-fund @agegr/pi-web",
            updateCost = UpdateCost.Light,
            installPaths = listOf("$PI_NODE/lib/node_modules/@agegr/pi-web", "$PI_NODE/bin/pi-web"),
            // The Node runtime Pi and this panel both run on.
            keepPaths = listOf("$PI_NODE/bin/node", "$PI_NODE/bin/npm"),
            updateTimeoutSeconds = 900,
        ),
        AgentVersionSpec(
            id = BuildAgents.HERMES,
            title = "Hermes",
            installedCommand = "PATH=$AGENT_PATH hermes --version",
            latestUrl = "https://api.github.com/repos/NousResearch/hermes-agent/releases/latest",
            latestSource = LatestSource.GithubTag,
            installCommand = null,
            updateCommand = "PATH=$AGENT_PATH hermes update",
            updateCost = UpdateCost.Gateway,
            installPaths = listOf("/root/.local/bin/hermes"),
            // The checkout's own config: `hermes update` never touches it, and
            // neither does a removal.
            keepPaths = listOf("/root/.hermes/config.yaml", "/root/.hermes/.env"),
            // 5 GiB of git checkout. Removing it is offered as a second, explicit
            // answer, because reinstalling it costs tens of minutes.
            bulkPath = "/root/.hermes/hermes-agent",
            updateTimeoutSeconds = 5400,
        ),
    )

    fun get(id: String?): AgentVersionSpec? = all.firstOrNull { it.id == id }

    /**
     * The rows the card renders, in catalog order.
     *
     * pi-web only appears once Pi is there: the panel is part of Pi's install and
     * cannot be installed without the Node runtime that install unpacks, so a row
     * offering to install it on its own would only ever fail.
     */
    fun rows(
        installed: Set<String>,
        versions: Map<String, String>,
        updates: Map<String, AgentUpdate>,
    ): List<AgentVersionRow> {
        val piPresent = BuildAgents.PI in installed
        return all
            .filter { it.id != WebPanels.PI_WEB_VERSION_KEY || piPresent }
            .map { spec ->
                val version = versions[spec.id]
                val present = spec.id in installed || version != null
                AgentVersionRow(
                    id = spec.id,
                    title = spec.title,
                    present = present,
                    version = version,
                    update = updates[spec.id]
                        ?: if (present) AgentUpdate.Unchecked(version) else AgentUpdate.NotInstalled,
                )
            }
    }

    /** The newest version [body] announces, or null when this answer is not one. */
    fun latest(body: String, source: LatestSource): String? {
        val match = when (source) {
            LatestSource.Version -> VERSION_FIELD.find(body)
            LatestSource.NpmDistTag -> NPM_LATEST.find(body)
            LatestSource.GithubTag -> TAG_FIELD.find(body)
        } ?: return null
        return normalize(match.groupValues[1])
    }

    /**
     * Orders two dotted versions the way a person reads them: component by
     * component, numerically. Negative when [a] is older than [b].
     *
     * String comparison is not enough here — `"2.3.10" < "2.3.9"` — and that is
     * precisely the case an update button has to get right.
     */
    fun compare(a: String, b: String): Int {
        val left = parts(a)
        val right = parts(b)
        for (index in 0 until maxOf(left.size, right.size)) {
            val diff = (left.getOrNull(index) ?: 0) - (right.getOrNull(index) ?: 0)
            if (diff != 0) return diff
        }
        return 0
    }

    /**
     * What to show for one agent, from what was actually observed.
     *
     * [present] is the local answer ("the binary answers"), [installed] its
     * version, [latest] the registry's, [checkFailed] whether asking went wrong.
     * A failed check returns [AgentUpdate.CheckFailed] no matter what else is
     * known — and so does a registry answer that could not be read, or an
     * installed version that could not be parsed: without both numbers there is
     * no comparison to make, and "up to date" would be a guess.
     */
    fun decide(
        present: Boolean,
        installed: String?,
        latest: String?,
        checkFailed: Boolean = false,
        checkedAt: String? = null,
    ): AgentUpdate = when {
        !present -> AgentUpdate.NotInstalled
        checkFailed || latest == null -> AgentUpdate.CheckFailed(installed, checkedAt)
        installed == null -> AgentUpdate.CheckFailed(installed, checkedAt)
        compare(installed, latest) < 0 -> AgentUpdate.Available(installed, latest, checkedAt)
        else -> AgentUpdate.UpToDate(installed, checkedAt)
    }

    /**
     * Guest paths a removal deletes: the installation, plus [spec]'s checkout when
     * [includeBulk] was answered yes.
     */
    fun removalPaths(spec: AgentVersionSpec, includeBulk: Boolean): List<String> =
        spec.installPaths + listOfNotNull(spec.bulkPath?.takeIf { includeBulk })

    /** The command that asks the registry, with the same bounded wait for every source. */
    fun latestCommand(spec: AgentVersionSpec): String = "$CURL '${spec.latestUrl}'"

    private val VERSION_FIELD = Regex(""""version"\s*:\s*"([^"]+)"""")
    private val NPM_LATEST = Regex(""""dist-tags"\s*:\s*\{[^}]*?"latest"\s*:\s*"([^"]+)"""")
    private val TAG_FIELD = Regex(""""tag_name"\s*:\s*"([^"]+)"""")

    /** `v2.3.2` and `2.3.2` are the same version; the tag prefix is not part of it. */
    private fun normalize(raw: String): String? =
        raw.trim().removePrefix("v").takeIf { it.isNotEmpty() }

    private fun parts(version: String): List<Int> =
        version.removePrefix("v").split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
}
