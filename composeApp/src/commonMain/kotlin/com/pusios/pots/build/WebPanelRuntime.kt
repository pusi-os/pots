package com.pusios.pots.build

import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What starting and stopping a panel needs from the sandbox.
 *
 * Everything here is a shell call or a file read inside the guest, so the state
 * machine above it — port choice, readiness, idempotency, error wording — is
 * plain Kotlin and unit-tested without a rootfs.
 */
interface WebPanelGuest {

    data class Output(val success: Boolean, val text: String = "")

    /**
     * Runs [command] to completion and returns what it printed.
     *
     * [env] is how a secret reaches the command without being written into the
     * command string itself: the string is quoted into the guest's own argv and
     * printed by every log that shows what was run.
     */
    fun run(command: String, timeoutSeconds: Long, env: Map<String, String> = emptyMap()): Output

    /** Starts [command] in the background, with [env], logging to the panel's log file. */
    fun launch(panel: WebPanel, port: Int, command: String, env: Map<String, String>): Boolean

    /**
     * Puts the port where the panel's own tool reads it, for the panels that take
     * it from a config file rather than from a flag. True for the ones that take a flag.
     */
    fun configure(panel: WebPanel, port: Int): Boolean

    /** Pid the launch script recorded, or null when the panel was never started. */
    fun pid(panel: WebPanel): Int?

    /** Port the launch script recorded, or null. */
    fun port(panel: WebPanel): Int?

    fun alive(pid: Int): Boolean

    /** Whether nothing is listening on [port] — the guest's own `/dev/tcp` check. */
    fun portFree(port: Int): Boolean

    /** Drops the pid and port files; the log stays for the next failure to quote. */
    fun forget(panel: WebPanel)

    /** Tail of the panel's log, for the reason line of a failure. */
    fun logTail(panel: WebPanel): String
}

/**
 * A loopback probe that answers in three distinguishable ways.
 *
 * `/dev/tcp` is a bash extension and the sandbox's `/bin/sh` is dash, so the
 * probe names bash explicitly — under `/bin/sh` the redirect simply fails, which
 * would read as "nothing is listening" forever. Exit codes: 0 = something
 * answered, 3 = the connection was refused (nothing listening), anything else =
 * the probe itself did not run (bash missing, proot error, timeout) and proves
 * nothing at all.
 */
internal fun portProbeCommand(port: Int): String =
    "/bin/bash -c 'if (exec 3<>/dev/tcp/127.0.0.1/$port) 2>/dev/null; then exit 0; else exit 3; fi'"

/**
 * Only a refused connection means free. An unanswered probe must never be read
 * as "the port is free" — that is how a panel ends up started on top of another.
 */
internal fun portIsFree(exitCode: Int): Boolean = exitCode == 3

/**
 * Starts, stops and reports the coding agents' web panels.
 *
 * The rules it exists to keep:
 * - a panel is started at most once — a second tap while it is up is a no-op,
 *   and autostart asks the same question the button does;
 * - a port that is taken moves the panel to the next free one, and the URL the
 *   user sees is the port it actually got;
 * - a stop is a real `kill -TERM`, a wait, then `kill -KILL`, and the panel only
 *   counts as stopped once the port is free again;
 * - a failure is a sentence with the reason in it, never an empty screen.
 */
class WebPanelRuntime(
    private val guest: WebPanelGuest,
    private val prefs: (String) -> WebPanelPreferences,
    private val savePrefs: (String, WebPanelPreferences) -> Unit,
    /** Agent ids the sandbox currently has installed. */
    private val installed: () -> Set<String>,
    private val publish: (ImmutableMap<String, WebPanelState>) -> Unit,
    private val scope: CoroutineScope,
    /** How long a panel gets to start listening before the attempt counts as failed. */
    private val startTimeoutSeconds: Long = 90,
) {

    private val mutex = Mutex()
    private val states = LinkedHashMap<String, WebPanelState>()

    fun refresh() = scope.launch { refreshPanels() }

    fun start(panelId: String) = scope.launch { startPanel(panelId) }

    fun stop(panelId: String) = scope.launch { stopPanel(panelId) }

    /** Brings up every panel whose toggle is on, and leaves the running ones alone. */
    fun ensureAutostart() = scope.launch { ensureAutostartPanels() }

    /** Goes through the same lock as the rest of the state machine — one owner, no races. */
    fun setAutostart(panelId: String, enabled: Boolean) = scope.launch {
        mutex.withLock {
            val panel = WebPanels.get(panelId) ?: return@withLock
            savePrefs(panel.id, prefs(panel.id).copy(autostart = enabled))
            publishStates {
                states[panel.id] = (states[panel.id] ?: WebPanelState(panel.id)).copy(autostart = enabled)
            }
        }
    }

    /**
     * The password a panel was given on its first start, or null when it has
     * never started.
     *
     * Generated in one place only — [startLocked], under the lock — so two
     * readers can never be handed two different values, and nothing here writes
     * to shared state.
     */
    fun password(panelId: String): String? {
        val panel = WebPanels.get(panelId) ?: return null
        if (panel.passwordEnv == null) return null
        return prefs(panel.id).password?.takeIf { it.isNotEmpty() }
    }

    /**
     * A URL to hand to the browser right now.
     *
     * Hermes mints a one-use link per call and OpenCode 2 hands out a pairing code
     * that dies within 300 seconds, so both are minted **here**, on the tap, and
     * never cached: [WebPanelStatus.Running] carries the address and the kind of
     * open it needs ([WebPanelOpen]), not the ticket — a link fetched at start-up
     * would be dead by the time it is tapped. pi-web answers with the address it
     * is already listening on, whose login form takes the password (see
     * [WebPanels.url]). Secrets are read here and never stored in the state, so
     * every screen that renders the state stays credential-free.
     */
    suspend fun link(panelId: String): String? = mutex.withLock {
        val panel = WebPanels.get(panelId) ?: return@withLock null
        val running = states[panel.id]?.status as? WebPanelStatus.Running ?: return@withLock null
        when (running.open) {
            WebPanelOpen.DIRECT -> {
                val port = WebPanels.portOf(running.url) ?: return@withLock null
                WebPanels.url(panel, port)
            }
            // One use, sixty seconds: a fresh ticket per tap, by construction. The
            // launcher is run for the port the panel is actually on, not the one it
            // was asked for — the two differ when the preferred port was taken.
            WebPanelOpen.MINTED_LINK ->
                mintHermesLink(panel, WebPanels.portOf(running.url) ?: panel.preferredPort)
            // One browser, five minutes: a fresh code per tap for the same reason,
            // and the port comes from the address the row is showing.
            WebPanelOpen.PAIRING_CODE ->
                mintOpenCodeLink(panel, WebPanels.portOf(running.url) ?: panel.preferredPort)
            // A permanent address with no ticket in it — nothing to mint, nothing to cache.
            WebPanelOpen.DASHBOARD_SIGN_IN -> running.url
        }
    }

    /**
     * Mints OpenCode's pairing link, or leaves the row saying why it could not.
     *
     * The code lives 300 seconds and works for one browser, so a fresh one is
     * asked for on every tap. A mint that fails becomes [WebPanelStatus.Failed]
     * on purpose: handing the browser the bare address would open v2's "Connect
     * to a server" screen, which reads as a broken panel rather than as a failed
     * sign-in. The password travels in the guest's environment, never in the
     * command line and never in the link.
     */
    private fun mintOpenCodeLink(panel: WebPanel, port: Int): String? {
        val passwordEnv = panel.passwordEnv
        val secret = passwordEnv?.let { password(panel.id) }
        if (passwordEnv == null || secret == null) {
            setState(panel, WebPanelStatus.Failed("OpenCode's panel password is missing"))
            return null
        }
        val output = guest.run(WebPanels.pairCommand(panel, port), PAIR_TIMEOUT_SECONDS, mapOf(passwordEnv to secret))
        return when (val pairing = OpenCodePairOutput.parse(output.text)) {
            is OpenCodePairing.Code -> WebPanels.pairUrl(port, pairing.value)
            is OpenCodePairing.Failed -> {
                setState(panel, WebPanelStatus.Failed(pairing.reason))
                null
            }
        }
    }

    suspend fun refreshPanels() = mutex.withLock { refreshLocked() }

    suspend fun startPanel(panelId: String) = mutex.withLock { startLocked(panelId) }

    suspend fun stopPanel(panelId: String) = mutex.withLock { stopLocked(panelId) }

    /**
     * Brings a panel back after its agent's install — which is also its update.
     *
     * The installer replaces the binary underneath a running panel, and the
     * process started before it keeps serving the old one: an owner who updated
     * OpenCode 1 → 2 would still be looking at v1's interface, which reads as
     * "the update did not work". So a running panel is stopped and started again;
     * a panel that is down is left down, because it is not ours to bring up.
     */
    fun onAgentInstalled(agentId: String) = scope.launch { restartAfterInstall(agentId) }

    suspend fun restartAfterInstall(agentId: String) = mutex.withLock {
        val panel = WebPanels.forAgent(agentId) ?: return@withLock
        if (states[panel.id]?.status !is WebPanelStatus.Running) return@withLock
        stopLocked(panel.id)
        // A stop that could not free the port is the reason to show, not something
        // to paper over by starting a second server beside it.
        if (states[panel.id]?.status is WebPanelStatus.NotRunning) startLocked(panel.id)
    }

    suspend fun ensureAutostartPanels() = mutex.withLock {
        refreshLocked()
        for (panel in WebPanels.all) {
            if (panel.agentId !in installed()) continue
            if (!prefs(panel.id).autostart) continue
            if (states[panel.id]?.status is WebPanelStatus.Running) continue
            startLocked(panel.id)
        }
    }

    private fun refreshLocked() {
        val installed = installed()
        val next = LinkedHashMap<String, WebPanelState>()
        for (panel in WebPanels.all) {
            if (panel.agentId !in installed) continue
            val previous = states[panel.id]?.status
            // A start in flight owns the state until it lands; a probe would
            // otherwise report "not running" for a server that is still coming up.
            val status = if (previous is WebPanelStatus.Starting) previous else probe(panel)
            next[panel.id] = WebPanelState(panel.id, status, prefs(panel.id).autostart)
        }
        states.clear()
        states.putAll(next)
        publishStates {}
    }

    private fun startLocked(panelId: String) {
        val panel = WebPanels.get(panelId) ?: return
        if (panel.agentId !in installed()) {
            setState(panel, WebPanelStatus.Failed("${panel.title} is not installed"))
            return
        }
        when (states[panel.id]?.status) {
            is WebPanelStatus.Starting, is WebPanelStatus.Running -> return
            else -> Unit
        }
        if (adoptLiveLocked(panel)) return
        setState(panel, WebPanelStatus.Starting)
        // `pickPort` asks "is this one busy?", so the guest's free-port probe is negated.
        val port = WebPanels.pickPort(panel.preferredPort) { candidate -> !guest.portFree(candidate) }
        if (port == null) {
            setState(
                panel,
                WebPanelStatus.Failed(
                    "ports ${panel.preferredPort}-${panel.preferredPort + WebPanels.PORT_ATTEMPTS - 1} are all busy",
                ),
            )
            return
        }
        if (!guest.configure(panel, port)) {
            setState(panel, WebPanelStatus.Failed("could not write ${panel.title} config"))
            return
        }
        // A Hermes built without the `webapp` command cannot serve a panel at all.
        // Cheap to ask, and the answer is a sentence instead of a dead button.
        if (panel.id == WebPanels.HERMES) {
            if (!guest.run(WebPanels.HERMES_HELP, STATUS_TIMEOUT_SECONDS).success) {
                setState(panel, WebPanelStatus.Failed("this Hermes build has no webapp command"))
                return
            }
            // The command exists but the renderer was never built: the link would
            // open on a blank page, which reads as a broken app. Say it instead.
            if (!guest.run(WebPanels.HERMES_BUNDLE_CHECK, STATUS_TIMEOUT_SECONDS).success) {
                setState(
                    panel,
                    WebPanelStatus.Failed(
                        "Hermes' webapp is not built — run `hermes webapp --build-only` in the sandbox",
                    ),
                )
                return
            }
            startHermes(panel, port)
            return
        }
        val env = panel.passwordEnv?.let { mapOf(it to passwordFor(panel)) }.orEmpty()
        if (!guest.launch(panel, port, WebPanels.command(panel, port), env)) {
            setState(panel, WebPanelStatus.Failed("${panel.title} did not start"))
            return
        }
        val ready = guest.run(waitForPort(port, startTimeoutSeconds), startTimeoutSeconds + 10)
        if (!ready.success) {
            setState(
                panel,
                WebPanelStatus.Failed(
                    "${panel.title} is not listening on $port after ${startTimeoutSeconds}s: ${guest.logTail(panel)}",
                ),
            )
            return
        }
        setState(panel, WebPanelStatus.Running(WebPanels.address(port), WebPanels.openKind(panel)))
    }

    /**
     * Takes over a panel that is already up instead of starting a second one.
     *
     * A sign-in can fail while the server it talks to is perfectly healthy — the
     * row says [WebPanelStatus.Failed] with the process still holding its port and
     * its pid file. Starting another server would leave that one orphaned and
     * overwrite its pid, so a live panel is adopted: the row goes back to Running
     * on the port it is really on, and a new tap can sign in again.
     *
     * True when the panel is up and adopted, false when a start still has to happen.
     */
    private fun adoptLiveLocked(panel: WebPanel): Boolean {
        val live = probe(panel)
        if (live is WebPanelStatus.Running) {
            setState(panel, live)
            return true
        }
        // Alive but not answering on its port: it still owns the pid file, and the
        // next launch would overwrite that pid and lose the process. Stop it first.
        val pid = guest.pid(panel) ?: return false
        if (!guest.alive(pid)) return false
        guest.run(stopCommand(pid), STOP_TIMEOUT_SECONDS)
        guest.forget(panel)
        return false
    }

    private fun stopLocked(panelId: String) {
        val panel = WebPanels.get(panelId) ?: return
        // Hermes records no pid file — its panel is the gateway — so the port to
        // check afterwards comes from the address the row is showing.
        val port = guest.port(panel)
            ?: (states[panel.id]?.status as? WebPanelStatus.Running)?.let { WebPanels.portOf(it.url) }
        val pid = guest.pid(panel)
        if (pid != null) guest.run(stopCommand(pid), STOP_TIMEOUT_SECONDS)
        // Hermes' panel is its gateway, which has its own documented stop.
        if (panel.id == WebPanels.HERMES) guest.run(GATEWAY_STOP, STOP_TIMEOUT_SECONDS)
        guest.forget(panel)
        val stillListening = port != null && !guest.portFree(port)
        setState(
            panel,
            if (stillListening) {
                WebPanelStatus.Failed("port $port is still taken after the stop")
            } else {
                WebPanelStatus.NotRunning
            },
        )
    }

    /**
     * Hermes' panel is its gateway: `hermes webapp --no-open` starts the gateway
     * when it is not up, prints the link and exits. So there is no pid of ours to
     * record — the readiness answer is the link itself, and the port it reports
     * is the port the panel is really on.
     *
     * The link printed here is deliberately not kept: it signs one browser in
     * once and dies within the minute, so the row gets the address and the fact
     * that opening it means minting a fresh ticket ([WebPanelOpen.MINTED_LINK]).
     */
    private fun startHermes(panel: WebPanel, port: Int) {
        val output = guest.run(WebPanels.command(panel, port), HERMES_LINK_TIMEOUT_SECONDS)
        val link = HermesWebappOutput.parse(output.text)
        if (link == null) {
            setState(panel, WebPanelStatus.Failed("hermes webapp: ${reasonLine(output.text)}"))
            return
        }
        setState(
            panel,
            if (link.oneTime) {
                WebPanelStatus.Running(
                    WebPanels.address(WebPanels.portOf(link.url) ?: port),
                    WebPanelOpen.MINTED_LINK,
                )
            } else {
                // Behind the dashboard's sign-in there is no ticket at all; the
                // address is the whole answer, and it needs an account.
                WebPanelStatus.Running(link.url, WebPanelOpen.DASHBOARD_SIGN_IN)
            },
        )
    }

    /**
     * Runs the launcher for a fresh ticket, the way a tap needs it.
     *
     * The command is idempotent — its gateway start is a no-op when the gateway is
     * already up — so a tap is always answered with a live link. When it cannot
     * mint one, the panel says why instead of leaving a dead button behind: the
     * first non-empty line of the output is the reason, as on a failed start.
     */
    private fun mintHermesLink(panel: WebPanel, port: Int): String? {
        val output = guest.run(WebPanels.command(panel, port), HERMES_LINK_TIMEOUT_SECONDS)
        val link = HermesWebappOutput.parse(output.text)
        if (link == null) {
            setState(panel, WebPanelStatus.Failed("hermes webapp: ${reasonLine(output.text)}"))
            return null
        }
        if (link.oneTime) return link.url
        // The gateway came up behind the dashboard's sign-in since the panel
        // started: from here the address is constant, and the row has to say so.
        setState(panel, WebPanelStatus.Running(link.url, WebPanelOpen.DASHBOARD_SIGN_IN))
        return link.url
    }

    /** The first line the guest said, for a failure the owner has to read. */
    private fun reasonLine(output: String): String =
        output.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            .ifEmpty { "no link printed" }

    private fun probe(panel: WebPanel): WebPanelStatus {
        val statusCommand = WebPanels.statusCommand(panel)
        if (statusCommand != null) {
            val output = guest.run(statusCommand, STATUS_TIMEOUT_SECONDS)
            val url = HermesWebappOutput.servingUrl(output.text) ?: return WebPanelStatus.NotRunning
            // `--status` says where the gateway serves, not how a browser gets in:
            // a loopback address is the private launch, whose tickets are minted
            // per tap, and anything else is the published webapp behind sign-in.
            val open = if (WebPanels.isLoopback(url)) WebPanelOpen.MINTED_LINK else WebPanelOpen.DASHBOARD_SIGN_IN
            return WebPanelStatus.Running(url, open)
        }
        val pid = guest.pid(panel) ?: return WebPanelStatus.NotRunning
        if (!guest.alive(pid)) return WebPanelStatus.NotRunning
        val port = guest.port(panel) ?: return WebPanelStatus.Failed("pid $pid is alive but its port is unknown")
        return if (guest.portFree(port)) {
            WebPanelStatus.Failed("pid $pid is alive, port $port is not listening")
        } else {
            WebPanelStatus.Running(WebPanels.address(port), WebPanels.openKind(panel))
        }
    }

    /**
     * The panel's password: the one it was given on its first start, or a fresh
     * one stored now. Stable for the life of the install — the agent keeps
     * answering with it, so a new one per start would lock out an already open
     * browser tab. Only ever called under the lock, from [startLocked].
     */
    private fun passwordFor(panel: WebPanel): String {
        val stored = prefs(panel.id).password?.takeIf { it.isNotEmpty() }
        if (stored != null) return stored
        val password = WebPanelPasswords.generate()
        savePrefs(panel.id, prefs(panel.id).copy(password = password))
        return password
    }

    private fun setState(panel: WebPanel, status: WebPanelStatus) {
        publishStates {
            states[panel.id] = WebPanelState(panel.id, status, prefs(panel.id).autostart)
        }
    }

    private fun publishStates(change: () -> Unit) {
        change()
        publish(states.toMap().toImmutableMap())
    }

    /** Polls the guest's loopback until something answers there, or gives up. */
    private fun waitForPort(port: Int, seconds: Long): String = buildString {
        // bash by name: `/dev/tcp` is not POSIX, and the guest's `/bin/sh` is dash.
        append("/bin/bash -c 'i=0; while [ ${'$'}i -lt $seconds ]; do ")
        append("if (exec 3<>/dev/tcp/127.0.0.1/$port) 2>/dev/null; then exit 0; fi; ")
        append("sleep 1; i=${'$'}((i+1)); done; exit 1'")
    }

    /** `kill -TERM`, wait, then `kill -KILL` — a server gets a chance to close its port. */
    private fun stopCommand(pid: Int): String = buildString {
        append("kill -TERM $pid 2>/dev/null; ")
        append("i=0; while [ ${'$'}i -lt 10 ] && kill -0 $pid 2>/dev/null; do sleep 1; i=${'$'}((i+1)); done; ")
        append("kill -KILL $pid 2>/dev/null; exit 0")
    }

    private companion object {
        const val STATUS_TIMEOUT_SECONDS = 30L
        const val STOP_TIMEOUT_SECONDS = 30L
        const val HERMES_LINK_TIMEOUT_SECONDS = 180L

        /** A pairing code is one POST to loopback; nothing here should take a minute. */
        const val PAIR_TIMEOUT_SECONDS = 30L
        const val GATEWAY_STOP = "hermes gateway stop"
    }
}
