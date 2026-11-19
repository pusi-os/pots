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

    /** Runs [command] to completion and returns what it printed. */
    fun run(command: String, timeoutSeconds: Long): Output

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

    /**
     * Passwords minted during this run, keyed by panel id. Settings hold the
     * durable copy; this is what makes the value stable before a save has
     * round-tripped, so two calls a moment apart cannot disagree.
     */
    private val passwords = LinkedHashMap<String, String>()

    fun refresh() = scope.launch { refreshPanels() }

    fun start(panelId: String) = scope.launch { startPanel(panelId) }

    fun stop(panelId: String) = scope.launch { stopPanel(panelId) }

    /** Brings up every panel whose toggle is on, and leaves the running ones alone. */
    fun ensureAutostart() = scope.launch { ensureAutostartPanels() }

    fun setAutostart(panelId: String, enabled: Boolean) {
        val panel = WebPanels.get(panelId) ?: return
        savePrefs(panel.id, prefs(panel.id).copy(autostart = enabled))
        publishStates { states[panel.id] = (states[panel.id] ?: WebPanelState(panel.id)).copy(autostart = enabled) }
    }

    /** The panel's password, generating and storing one on first ask. */
    fun password(panelId: String): String? {
        val panel = WebPanels.get(panelId) ?: return null
        if (panel.passwordEnv == null) return null
        return passwordFor(panel)
    }

    /**
     * A URL to hand to the browser right now.
     *
     * Hermes mints a one-use link per call, so this is never cached for it; the
     * other two answer with the address they are already listening on.
     */
    suspend fun link(panelId: String): String? = mutex.withLock {
        val panel = WebPanels.get(panelId) ?: return@withLock null
        if (panel.id != WebPanels.HERMES) {
            return@withLock (states[panel.id]?.status as? WebPanelStatus.Running)?.url
        }
        // One use, sixty seconds: a fresh one every tap, by construction.
        val output = guest.run(WebPanels.command(panel, panel.preferredPort), HERMES_LINK_TIMEOUT_SECONDS)
        HermesWebappOutput.parse(output.text)?.url
    }

    suspend fun refreshPanels() = mutex.withLock { refreshLocked() }

    suspend fun startPanel(panelId: String) = mutex.withLock { startLocked(panelId) }

    suspend fun stopPanel(panelId: String) = mutex.withLock { stopLocked(panelId) }

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
        setState(panel, WebPanelStatus.Running(WebPanels.url(port)))
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
     */
    private fun startHermes(panel: WebPanel, port: Int) {
        val output = guest.run(WebPanels.command(panel, port), HERMES_LINK_TIMEOUT_SECONDS)
        val link = HermesWebappOutput.parse(output.text)
        if (link == null) {
            val reason = output.text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            setState(panel, WebPanelStatus.Failed("hermes webapp: ${reason.ifEmpty { "no link printed" }}"))
            return
        }
        setState(panel, WebPanelStatus.Running(WebPanels.url(WebPanels.portOf(link.url) ?: port)))
    }

    private fun probe(panel: WebPanel): WebPanelStatus {
        val statusCommand = WebPanels.statusCommand(panel)
        if (statusCommand != null) {
            val output = guest.run(statusCommand, STATUS_TIMEOUT_SECONDS)
            val url = HermesWebappOutput.servingUrl(output.text) ?: return WebPanelStatus.NotRunning
            return WebPanelStatus.Running(url)
        }
        val pid = guest.pid(panel) ?: return WebPanelStatus.NotRunning
        if (!guest.alive(pid)) return WebPanelStatus.NotRunning
        val port = guest.port(panel) ?: return WebPanelStatus.Failed("pid $pid is alive but its port is unknown")
        return if (guest.portFree(port)) {
            WebPanelStatus.Failed("pid $pid is alive, port $port is not listening")
        } else {
            WebPanelStatus.Running(WebPanels.url(port))
        }
    }

    /**
     * The panel's password: the one it was given before, or a fresh one stored on
     * first ask. Stable for the life of the install — the agent keeps answering
     * with it, so a new one per start would lock out an already open browser tab.
     */
    private fun passwordFor(panel: WebPanel): String {
        passwords[panel.id]?.let { return it }
        val stored = prefs(panel.id).password?.takeIf { it.isNotEmpty() }
        val password = stored ?: WebPanelPasswords.generate()
        passwords[panel.id] = password
        if (stored == null) savePrefs(panel.id, prefs(panel.id).copy(password = password))
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
        append("i=0; while [ ${'$'}i -lt $seconds ]; do ")
        append("if (exec 3<>/dev/tcp/127.0.0.1/$port) 2>/dev/null; then exit 0; fi; ")
        append("sleep 1; i=${'$'}((i+1)); done; exit 1")
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
        const val GATEWAY_STOP = "hermes gateway stop"
    }
}
