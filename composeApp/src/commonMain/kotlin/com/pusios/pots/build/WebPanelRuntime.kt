package com.pusios.pots.build

import com.pusios.pots.getBackgroundDispatcher
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
     * Runs one renderer build attempt to completion — [HermesWebapp.BUILD_COMMAND]
     * in the guest, with the attempt's own environment and hardlink emulation.
     *
     * Apart from [run] because the attempt decides both: pnpm reads its settings
     * only from the environment proot hands it, and the emulation flag is what
     * separates one attempt from the next. A build that ran without them would be
     * the same attempt three times.
     */
    fun buildRenderer(attempt: HermesWebapp.BuildAttempt, timeoutSeconds: Long): Output

    /**
     * Appends one line to the panel's log, host-side and without a process.
     *
     * A renderer chain prints nothing the row can read — it runs while the panel
     * is down — so what it decided has to be written where the row's log section
     * already looks. Best effort: a log that cannot be written is not a reason to
     * stop a build.
     */
    fun appendLog(panel: WebPanel, line: String)

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

    /**
     * The panel's log file as it stands, or [WebPanelLog.NO_FILE] when there is
     * none.
     *
     * The whole text, not a tail: the runtime masks the panel's password before
     * it cuts, and a cut taken here would land before the mask.
     */
    fun logText(panel: WebPanel): String

    /**
     * Puts the Hermes panel bundle this app ships where the guest can unpack it
     * ([HermesWebapp.ARCHIVE]), and answers the hermes-agent commit it was built
     * from.
     *
     * Null means this build ships no bundle — a developer build, where the only
     * candidate is the checkout's own renderer. A bundle that is shipped but could
     * not be staged answers its pin too: the unpack then fails on a missing
     * archive, which is a reason the row can quote.
     */
    fun stageHermesBundle(): String?

    /**
     * Whether Hermes' config still lists the dashboard's login plugin as disabled
     * — the state in which a gated panel opens on "sign-in unavailable" instead of
     * on a login form.
     */
    fun authPluginDisabled(panel: WebPanel): Boolean
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
    /**
     * The phone's own address in the local network, or null when it is on none.
     * Hermes' row shows it in place of the loopback address its launcher prints;
     * the other panels never leave loopback.
     */
    private val lanAddress: () -> String? = { null },
    private val publish: (ImmutableMap<String, WebPanelState>) -> Unit,
    private val scope: CoroutineScope,
    /**
     * The clock the renderer budget is measured on. A parameter, not a call:
     * commonMain has no clock, and a test needs one it can move — the same reason
     * [HermesWebapp.BuildBudget] takes its own.
     */
    private val nowMillis: () -> Long,
    /** How long a panel gets to start listening before the attempt counts as failed. */
    private val startTimeoutSeconds: Long = 90,
) {

    private val mutex = Mutex()

    /**
     * Guards [passwordLocked], and nothing else.
     *
     * Its own lock rather than [mutex]: a start holds [mutex] for the whole
     * readiness wait (up to [startTimeoutSeconds]), and the row's sign-in section
     * is most wanted exactly then — queueing a password read behind a start would
     * leave the section blank for as long as the panel takes to come up. The two
     * locks are taken in one order only, [mutex] then this one, so they cannot
     * deadlock.
     */
    private val passwordMutex = Mutex()
    private val states = LinkedHashMap<String, WebPanelState>()

    /**
     * Guards the renderer chain and the rollback — the two operations that write
     * the checkout and its bundle — and nothing else.
     *
     * Its own lock, taken for the whole chain (up to [HermesWebapp.BUILD_BUDGET_SECONDS]):
     * [mutex] would be held that long, and a start holding it freezes every other
     * panel's row. Taken with `tryLock` by its users, so a second tap while a
     * build runs is a no-op rather than a second build beside the first.
     */
    private val rendererMutex = Mutex()

    /**
     * Commits a renderer chain was already run for in this run of the app.
     *
     * A chain that failed for a commit is not repeated on the next tap — the owner
     * waited once, and the row offers the button instead of spending the budget
     * again unasked. Cleared for a commit when a chain succeeds, and by a rollback,
     * which moves the checkout somewhere a rebuild has not been tried.
     */
    private val rebuiltHeads = mutableSetOf<String>()

    /**
     * The unpacked Hermes renderer this app ships, once a start has verified it
     * against the sandbox's own Hermes. Null means "no bundled renderer is being
     * served": this build ships none, or the last start refused the one it has.
     */
    private var hermesDist: String? = null

    fun refresh() = scope.launch { refreshPanels() }

    fun start(panelId: String) = scope.launch { startPanel(panelId) }

    fun stop(panelId: String) = scope.launch { stopPanel(panelId) }

    /**
     * Builds the renderer from the checkout the sandbox is on, whether or not a
     * chain was already tried for it — the row's own button.
     *
     * The build is the owner's way out of a refusal, so the panel comes up when it
     * succeeds: a build that only filled the disk would leave the owner looking at
     * the same failed row after seven minutes.
     */
    fun rebuild(panelId: String) = scope.launch {
        if (rebuildRenderer(panelId, force = true, startsAfterBuild = true) == RendererStep.PROCEED) {
            mutex.withLock { startLocked(panelId) }
        }
    }

    /**
     * Moves Hermes back to the commit the shipped renderer was built from, then
     * starts the panel on it — the row's own button, and the owner's decision:
     * the checkout this gives up is a newer Hermes.
     */
    fun rollback(panelId: String) = scope.launch { rollbackToPin(panelId) }

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
     * The password a panel answers with: the one it already has, or a fresh one
     * stored now — so the row can show it before the panel has ever started.
     *
     * Generated in one place only, [passwordLocked], under [passwordMutex]: two
     * readers must never be handed two different values, and a value that changes
     * would lock out a browser tab that is already signed in.
     *
     * Suspending, and resolved on the background dispatcher: storing the value
     * writes the settings file, and that write must not happen on the UI thread
     * while a frame is being drawn.
     */
    suspend fun password(panelId: String): String? {
        val panel = WebPanels.get(panelId) ?: return null
        if (panel.passwordEnv == null) return null
        return passwordFor(panel)
    }

    /**
     * The panel's log, as the row shows it: the tail of the file, with the panel's
     * password cut out before the tail is taken (see [WebPanelLog.show]).
     *
     * Read on demand — when the row's log section is opened, when a start fails,
     * or when its refresh button is tapped — and never cached: the file grows
     * while the panel starts, and a copy taken at composition time would be stale
     * by the time anyone looked at it.
     *
     * The lock is deliberately not taken. A start holds it for the whole readiness
     * wait (up to [startTimeoutSeconds]), and a log read is most wanted exactly
     * then — queueing it behind the start would hide the log at the moment it
     * explains something.
     */
    fun panelLog(panelId: String): String {
        val panel = WebPanels.get(panelId) ?: return WebPanelLog.NO_FILE
        // The stored value, never [password]: masking a log must not generate one
        // — that writes settings, and a log read is a file read. A password can
        // only be in the log if the panel ran, which means it is already stored.
        return WebPanelLog.show(guest.logText(panel), storedPassword(panel))
    }

    /**
     * A URL to hand to the browser right now.
     *
     * What a browser needs in order to open [panel] now.
     *
     * Two panels sign a browser in with something minted per tap: OpenCode 2 hands
     * out a pairing code that dies within 300 seconds, and Hermes' launcher mints a
     * one-use link whenever the sign-in gate is off (a config Pots did not write).
     * Both are minted **here**, on the tap, and never cached:
     * [WebPanelStatus.Running] carries the address and the kind of open it needs
     * ([WebPanelOpen]), not the ticket — a link fetched at start-up would be dead by
     * the time it is tapped. When the gate is on — the config Pots writes — there is
     * no ticket to mint: the address alone gets in, and the browser is handed the
     * panel's loopback, not the LAN address the row shows. `getUserMedia` needs a
     * secure context, which loopback is and `http://` on a LAN address is not, and
     * the Hermes panel is opened to use its microphone (see [browserAddress]).
     * pi-web answers with the address it is already listening on, whose login form
     * takes the password (see [WebPanels.url]). Secrets are read here and never
     * stored in the state, so every screen that renders the state stays
     * credential-free.
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
            // A permanent address with no ticket in it — nothing to mint, nothing
            // to cache — and the browser gets the loopback one (see [browserAddress]).
            WebPanelOpen.DASHBOARD_SIGN_IN -> browserAddress(running.url)
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
    private suspend fun mintOpenCodeLink(panel: WebPanel, port: Int): String? {
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

    /**
     * A start, in two steps: the renderer the panel needs, then the panel itself.
     *
     * The first step is the long one — building Hermes' renderer takes minutes and
     * holds [rendererMutex], not [mutex] — and it is what makes "updated the agent,
     * opened the panel" work again: see [rebuildRenderer].
     */
    suspend fun startPanel(panelId: String) {
        // A chain already in flight owns the row and the start that follows it;
        // starting beside it would serve a renderer that is still half-built.
        when (rebuildRenderer(panelId, force = false, startsAfterBuild = true)) {
            RendererStep.PROCEED -> Unit
            RendererStep.BUSY, RendererStep.SETTLED -> return
        }
        mutex.withLock { startLocked(panelId) }
    }

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

    suspend fun restartAfterInstall(agentId: String) {
        val panelId = mutex.withLock {
            val panel = WebPanels.forAgent(agentId) ?: return
            if (states[panel.id]?.status !is WebPanelStatus.Running) return
            stopLocked(panel.id)
            // A stop that could not free the port is the reason to show, not
            // something to paper over by starting a second server beside it.
            if (states[panel.id]?.status !is WebPanelStatus.NotRunning) return
            panel.id
        }
        startPanel(panelId)
    }

    /**
     * What an agent's update leaves behind: a checkout the renderer does not
     * match, and a panel that was stopped for the update.
     *
     * `hermes update` moves the checkout to a new commit; the renderer on disk was
     * built from the commit before it, and the panel refuses that pair. So the
     * renderer is rebuilt here — before the panel is asked to start, so the row
     * says "building" rather than "refused" — and [restart] brings back a panel
     * that was up before the update. A panel that was down is left down; its
     * renderer is still rebuilt, because the next tap should not have to wait.
     */
    fun onAgentUpdated(agentId: String, restart: Boolean) = scope.launch {
        val panel = WebPanels.forAgent(agentId) ?: return@launch
        // The build's own row wording follows [restart]: a panel that stays down
        // must not be promised as "opens by itself when the build is done".
        val step = rebuildRenderer(panel.id, force = false, startsAfterBuild = restart)
        if (!restart || step != RendererStep.PROCEED) return@launch
        mutex.withLock { startLocked(panel.id) }
    }

    suspend fun ensureAutostartPanels() {
        val due = mutex.withLock {
            refreshLocked()
            WebPanels.all.filter { panel ->
                panel.agentId in installed() &&
                    prefs(panel.id).autostart &&
                    states[panel.id]?.status !is WebPanelStatus.Running
            }
        }
        // Outside the lock: a start may have to build a renderer first, and that
        // is minutes — holding [mutex] for it would freeze the other rows.
        for (panel in due) startPanel(panel.id)
    }

    private fun refreshLocked() {
        val installed = installed()
        val next = LinkedHashMap<String, WebPanelState>()
        for (panel in WebPanels.all) {
            if (panel.agentId !in installed) continue
            val previous = states[panel.id]?.status
            // A start in flight owns the state until it lands; a probe would
            // otherwise report "not running" for a server that is still coming up —
            // and a renderer being built is the same promise, minutes longer.
            val status = if (previous is WebPanelStatus.Starting || previous is WebPanelStatus.BuildingRenderer) {
                previous
            } else {
                probe(panel)
            }
            next[panel.id] = WebPanelState(panel.id, status, prefs(panel.id).autostart)
        }
        states.clear()
        states.putAll(next)
        publishStates {}
    }

    private suspend fun startLocked(panelId: String) {
        val panel = WebPanels.get(panelId) ?: return
        if (panel.agentId !in installed()) {
            setState(panel, WebPanelStatus.Failed("${panel.title} is not installed"))
            return
        }
        when (states[panel.id]?.status) {
            is WebPanelStatus.Starting, is WebPanelStatus.BuildingRenderer, is WebPanelStatus.Running -> return
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
            // The renderer. Pots Build builds one on the device right after the
            // install, and a release APK also carries the one CI built — that one
            // is the fallback when the build could not run. Each is only served
            // against the Hermes it was built from, and the facts below are what
            // that is decided on: the checkout's commit, the commit the device
            // build stamped, and the commit CI's bundle was built from.
            val facts = rendererFacts()
            hermesDist = null
            val renderer = when (val decision = HermesWebapp.decide(facts)) {
                // Nothing is servable yet: unpack the bundle this app ships for the
                // pin, once per bundle — the stamp inside names the commit it was
                // unpacked for — and the panel is not dead.
                is HermesWebapp.Renderer.Unpack -> {
                    val unpacked = guest.run(HermesWebapp.unpack(decision.pin), UNPACK_TIMEOUT_SECONDS)
                    if (!unpacked.success ||
                        !guest.run(HermesWebapp.shipped(decision.pin), STATUS_TIMEOUT_SECONDS).success
                    ) {
                        setState(panel, WebPanelStatus.Failed(HermesWebapp.unpackFailed(reasonLine(unpacked.text))))
                        return
                    }
                    HermesWebapp.Renderer.Shipped
                }
                else -> decision
            }
            if (renderer is HermesWebapp.Renderer.Refused) {
                // The two ways out are buttons in this row: a chain that failed is
                // not retried unasked on every tap, and moving Hermes back to the
                // pin is the owner's call, never the app's.
                setState(
                    panel,
                    WebPanelStatus.Failed(renderer.reason, HermesWebapp.recoveryActions(facts)),
                )
                return
            }
            // Name the shipped bundle only when it is the one being served: a
            // renderer built on the device is Hermes' own, and naming the bundle
            // there would pin the panel to CI's copy for good. Both can be there —
            // an earlier install unpacked the fallback, a later one built the
            // renderer — and then the build is what gets served.
            if (renderer !is HermesWebapp.Renderer.Device) hermesDist = HermesWebapp.DIST_DIR
            // The panel binds every interface, so Hermes' sign-in gate is on, and a
            // disabled `basic` plugin leaves it with no provider at all: the page
            // then reads "sign-in unavailable" and looks like a broken panel.
            // `configure` has already dropped the plugin from `plugins.disabled`;
            // this is the read-back that says the fix took.
            if (guest.authPluginDisabled(panel)) {
                setState(
                    panel,
                    WebPanelStatus.Failed(
                        "Hermes' dashboard login is off: the basic auth plugin is still in plugins.disabled",
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
            // A line, not the log: the full tail is one tap away in the row's own
            // section, and the password is cut out of both.
            setState(
                panel,
                WebPanelStatus.Failed(
                    "${panel.title} is not listening on $port after ${startTimeoutSeconds}s: " +
                        WebPanelLog.brief(guest.logText(panel), storedPassword(panel)),
                ),
            )
            return
        }
        setState(panel, WebPanelStatus.Running(WebPanels.address(port), WebPanels.openKind(panel)))
    }

    // --- the renderer ------------------------------------------------------

    /**
     * What the sandbox reports about the renderers it could serve: one probe per
     * field, and [HermesWebapp.decide] turns them into the answer.
     *
     * Staging the shipped archive is idempotent — it copies once per bundle — so
     * asking twice, once before a start and once for the start itself, costs a
     * file check rather than fourteen megabytes.
     */
    private fun rendererFacts(): HermesWebapp.RendererFacts {
        val pin = guest.stageHermesBundle()
        val head = guest.run(HermesWebapp.headCommand(WebPanels.HERMES_CHECKOUT), STATUS_TIMEOUT_SECONDS)
        val stamp = guest.run(HermesWebapp.stampCommand(), STATUS_TIMEOUT_SECONDS)
        val builtFrom = stamp.text.trim().takeIf { stamp.success && it.isNotEmpty() }
        return HermesWebapp.RendererFacts(
            head = if (head.success) head.text.trim() else "",
            pin = pin,
            builtFrom = builtFrom,
            // Only a stamped build is worth looking at: without the stamp the
            // page in the checkout belongs to some earlier run or to nothing.
            built = builtFrom != null && guest.run(HermesWebapp.BUILT_CHECK, STATUS_TIMEOUT_SECONDS).success,
            shipped = pin != null && guest.run(HermesWebapp.shipped(pin), STATUS_TIMEOUT_SECONDS).success,
        )
    }

    /**
     * What the renderer step of a start leaves for the start itself to do.
     */
    private enum class RendererStep {
        /** Nothing was built and nothing stands in the way: the start goes on. */
        PROCEED,

        /**
         * Another chain owns the checkout right now — this call started nothing.
         * Whoever started that chain finishes the row, so a start that sees this
         * must not bring a panel up beside a half-built renderer.
         */
        BUSY,

        /**
         * A chain this call ran produced no renderer, and the row already says why
         * and offers the ways out: the start stops here instead of probing the
         * same refusal again.
         */
        SETTLED,
    }

    /**
     * Builds the renderer from the checkout the sandbox is on, when what is on
     * disk was built from another commit.
     *
     * This is the defect this method exists for: `hermes update` advances the
     * checkout and leaves both stamps on the commit before it, so every panel
     * start after an update used to end in a refusal. The chain is the install's
     * own — see [buildChain] — and the row says "building" for as long as it runs.
     *
     * Runs outside [mutex] on purpose: the chain can spend the whole budget, and a
     * start holding the panel lock that long would freeze every other row. One
     * chain at a time; a second tap while one runs gets [RendererStep.BUSY] and
     * leaves the row to the chain that is already spending the budget.
     */
    private suspend fun rebuildRenderer(panelId: String, force: Boolean, startsAfterBuild: Boolean): RendererStep {
        val panel = WebPanels.get(panelId) ?: return RendererStep.PROCEED
        if (panel.id != WebPanels.HERMES || panel.agentId !in installed()) return RendererStep.PROCEED
        if (!rendererMutex.tryLock()) return RendererStep.BUSY
        try {
            val facts = rendererFacts()
            val head = facts.head
            if (!HermesWebapp.shouldRebuild(facts, rebuiltHeads, force)) return RendererStep.PROCEED
            val outcome = buildChain(panel, startsAfterBuild)
            if (outcome == HermesWebapp.RendererBuild.BUILT) {
                rebuiltHeads.remove(head)
                // The start that follows decides again, and now finds this build.
                setStateOutsideLock(panel, WebPanelStatus.NotRunning)
                return RendererStep.PROCEED
            }
            // Nothing was built, and no start may be following: the row has to say
            // what is on disk and offer the two ways out by itself.
            rebuiltHeads += head
            note(
                panel,
                when (outcome) {
                    // No bundle is served here — [settle] publishes the refusal — so
                    // the line says that instead of promising the install's fallback.
                    HermesWebapp.RendererBuild.OUT_OF_BUDGET -> HermesWebapp.BUDGET_SPENT_NOTHING_BUILT
                    HermesWebapp.RendererBuild.BUILT, HermesWebapp.RendererBuild.FAILED -> null
                },
            )
            settle(panel)
            return RendererStep.SETTLED
        } finally {
            rendererMutex.unlock()
        }
    }

    /**
     * The row's answer to what is on disk now: a refusal is published as it is —
     * with the ways out — and anything servable is left to the start that follows.
     */
    private suspend fun settle(panel: WebPanel) {
        val facts = rendererFacts()
        val decision = HermesWebapp.decide(facts)
        setStateOutsideLock(
            panel,
            if (decision is HermesWebapp.Renderer.Refused) {
                WebPanelStatus.Failed(decision.reason, HermesWebapp.recoveryActions(facts))
            } else {
                WebPanelStatus.NotRunning
            },
        )
    }

    /**
     * The chain of build attempts, exactly as the install runs it.
     *
     * A build cannot share a proot call with anything else: `hermes webapp
     * --build-only` is a `pnpm install` whose staged renames answer ENOENT under
     * proot's `--link2symlink`, so each attempt is its own call, with the
     * emulation that attempt asks for — see [HermesWebapp.buildAttempts]. Every
     * attempt starts from an empty pnpm store, because a store a failed attempt
     * leaves behind is made of `.l2s.` symlinks the next build dies on.
     *
     * The budget bounds the chain, not one attempt: an attempt it cannot pay for
     * is not started, and a build that runs past the deadline ends the chain
     * rather than starting another. A blocking guest call cannot be cut short, so
     * the chain overruns by one attempt at most — the same bound the install's
     * chain has.
     *
     * Every step writes a line into the panel's own log, because a chain that
     * failed has to be readable somewhere after the fact.
     *
     * [startsAfterBuild] is carried into the row's own state: it is what the row
     * needs to know before promising a panel that will open by itself.
     */
    private suspend fun buildChain(panel: WebPanel, startsAfterBuild: Boolean): HermesWebapp.RendererBuild {
        val budget = HermesWebapp.BuildBudget(nowMillis)
        val ticker = scope.launch {
            while (true) {
                delay(REBUILD_TICK_MILLIS)
                // Only while the row is still saying "building": a stop or a
                // refresh that already moved on must not be overwritten.
                mutex.withLock {
                    if (states[panel.id]?.status is WebPanelStatus.BuildingRenderer) {
                        setState(panel, WebPanelStatus.BuildingRenderer(budget.leftMinutes(), startsAfterBuild))
                    }
                }
            }
        }
        try {
            for (attempt in HermesWebapp.buildAttempts) {
                if (!budget.attemptFits()) return HermesWebapp.RendererBuild.OUT_OF_BUDGET
                setStateOutsideLock(panel, WebPanelStatus.BuildingRenderer(budget.leftMinutes(), startsAfterBuild))
                note(panel, HermesWebapp.buildingOnDevice(attempt, budget.leftMinutes()))
                guest.run(HermesWebapp.CLEAN_COMMAND, CLEAN_TIMEOUT_SECONDS)
                val outcome = guest.buildRenderer(attempt, HermesWebapp.BUILD_ATTEMPT_TIMEOUT_SECONDS)
                // `--force-build` makes "up to date" impossible, so a line saying it
                // means the flag never reached Hermes and the bundle on disk is not
                // this attempt's build — stamping it would be the lie that costs a
                // whole update.
                val stale = outcome.text.contains(HermesWebapp.UP_TO_DATE)
                val built = outcome.success && !stale &&
                    guest.run(HermesWebapp.BUILT_CHECK, STATUS_TIMEOUT_SECONDS).success
                val stamped = built && guest.run(HermesWebapp.stampBuilt(), STATUS_TIMEOUT_SECONDS).success
                note(
                    panel,
                    when {
                        stamped -> HermesWebapp.builtOnDevice(attempt)
                        built -> "renderer: attempt ${attempt.number} built a bundle it could not stamp"
                        stale -> HermesWebapp.builtNothing(attempt)
                        else -> "renderer: attempt ${attempt.number} failed: " + reasonLine(outcome.text)
                    },
                )
                if (stamped) return HermesWebapp.RendererBuild.BUILT
            }
            return HermesWebapp.RendererBuild.FAILED
        } finally {
            ticker.cancel()
        }
    }

    /**
     * Moves the sandbox's Hermes back to the commit the shipped renderer was built
     * from, and serves that bundle.
     *
     * The one path that gives up a newer Hermes, and it runs only from the row's
     * own button: the bundle is from that commit and from nothing else, so the
     * checkout has to go there first. The start that follows is the ordinary one —
     * the checkout is now the pin, so the bundle is served without another build.
     */
    private suspend fun rollbackToPin(panelId: String) {
        val panel = WebPanels.get(panelId) ?: return
        if (!rendererMutex.tryLock()) return
        try {
            val pin = guest.stageHermesBundle() ?: return
            note(panel, "renderer: rolling Hermes back to the shipped bundle's ${pin.take(8)}")
            val moved = guest.run(HermesWebapp.pinCheckout(pin), ROLLBACK_TIMEOUT_SECONDS)
            if (!moved.success) {
                setStateOutsideLock(
                    panel,
                    WebPanelStatus.Failed(
                        "could not move Hermes to ${pin.take(8)}: ${reasonLine(moved.text)}",
                        HermesWebapp.recoveryActions(rendererFacts()),
                    ),
                )
                return
            }
            // The version of the commit just checked out: without its tag the row
            // reads `git.<sha>` even though HEAD is a release.
            guest.run(HermesWebapp.TAGS_FETCH, ROLLBACK_TIMEOUT_SECONDS)
            val unpacked = guest.run(HermesWebapp.unpack(pin), UNPACK_TIMEOUT_SECONDS)
            if (!unpacked.success) {
                setStateOutsideLock(
                    panel,
                    WebPanelStatus.Failed(HermesWebapp.unpackFailed(reasonLine(unpacked.text))),
                )
                return
            }
            // A checkout that moved is one no chain was tried for.
            rebuiltHeads.clear()
        } finally {
            rendererMutex.unlock()
        }
        startPanel(panelId)
    }

    /** One line into the panel's own log — where a failed chain explains itself. */
    private fun note(panel: WebPanel, line: String?) {
        if (line != null) guest.appendLog(panel, "pots: $line")
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
        // The pid file is the launch's own record; for Hermes it names the
        // gateway. The port to check afterwards comes from that file, or — when
        // there is none — from the address the row is showing.
        val port = guest.port(panel)
            ?: (states[panel.id]?.status as? WebPanelStatus.Running)?.let { WebPanels.portOf(it.url) }
        val pid = guest.pid(panel)
        if (pid != null) guest.run(stopCommand(pid), STOP_TIMEOUT_SECONDS)
        // Hermes' panel is its gateway, which also has its own documented stop:
        // the signal above ends the process, this ends the gateway's own state.
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
     * Hermes' panel is its gateway, so the gateway is what Pots keeps up.
     *
     * Three steps, in this order, because the panel is only real once the gateway
     * is: start it as a background session (which records its pid and its log —
     * see [WebPanels.HERMES_SERVER]), wait for its port, and only then run the
     * launcher, whose whole job is to mint the address out of the gateway that is
     * already there. The launcher itself is a foreground call that exits, and
     * every proot Pots starts runs with `--kill-on-exit`: a gateway it brought up
     * would die with its proot, leaving the row showing an address nobody serves.
     *
     * The account rides in the launcher's environment in both steps: the gateway
     * reads it from there when it registers the sign-in provider.
     */
    private suspend fun startHermes(panel: WebPanel, port: Int) {
        if (!guest.launch(panel, port, WebPanels.HERMES_SERVER, hermesEnv(panel))) {
            setState(panel, WebPanelStatus.Failed("${panel.title} did not start"))
            return
        }
        val ready = guest.run(waitForPort(port, startTimeoutSeconds), startTimeoutSeconds + 10)
        if (!ready.success) {
            // The log is the answer here — a gateway that refuses to start says
            // why on its last line — and it is masked before it is quoted.
            setState(
                panel,
                WebPanelStatus.Failed(
                    "${panel.title} is not listening on $port after ${startTimeoutSeconds}s: " +
                        WebPanelLog.brief(guest.logText(panel), storedPassword(panel)),
                ),
            )
            return
        }
        val output = guest.run(WebPanels.command(panel, port), HERMES_LINK_TIMEOUT_SECONDS, hermesEnv(panel))
        val link = HermesWebappOutput.parse(output.text)
        if (link == null) {
            // The port answered a moment ago, so the gateway is up. The launcher's
            // wording is Hermes' own — a release may print an address Pots cannot
            // read, or a warning line before it — and quoting that at the owner
            // while the panel is serving in the browser is the bug this asks a
            // refresh's question to avoid: a live gateway is Running, and only a
            // panel that really is not serving gets the launcher's sentence.
            val probed = probe(panel)
            setState(
                panel,
                if (probed is WebPanelStatus.NotRunning) {
                    WebPanelStatus.Failed("hermes webapp: ${reasonLine(output.text)}")
                } else {
                    probed
                },
            )
            return
        }
        val servedPort = WebPanels.portOf(link.url) ?: port
        setState(
            panel,
            if (link.oneTime) {
                WebPanelStatus.Running(WebPanels.address(servedPort), WebPanelOpen.MINTED_LINK)
            } else {
                // Behind the dashboard's sign-in there is no ticket at all: the
                // address is the whole answer, it needs the account above, and the
                // one it printed is this phone's loopback.
                WebPanelStatus.Running(hermesRowAddress(servedPort, link.url), WebPanelOpen.DASHBOARD_SIGN_IN)
            },
        )
    }

    /**
     * Runs the launcher for a fresh ticket, the way a tap needs it.
     *
     * The command is idempotent — its gateway start is a no-op when the gateway is
     * already up — so a tap is always answered with a live link. When it cannot
     * mint one, the panel says why instead of leaving a dead button behind: the
     * last non-empty line of the output is the reason, as on a failed start.
     */
    private suspend fun mintHermesLink(panel: WebPanel, port: Int): String? {
        val output = guest.run(WebPanels.command(panel, port), HERMES_LINK_TIMEOUT_SECONDS, hermesEnv(panel))
        val link = HermesWebappOutput.parse(output.text)
        if (link == null) {
            setState(panel, WebPanelStatus.Failed("hermes webapp: ${reasonLine(output.text)}"))
            return null
        }
        if (link.oneTime) return link.url
        // The gateway came up behind the dashboard's sign-in since the panel
        // started: from here the address is constant, and the row has to say so —
        // while the browser still gets loopback (see [browserAddress]).
        val address = hermesRowAddress(WebPanels.portOf(link.url) ?: port, link.url)
        setState(panel, WebPanelStatus.Running(address, WebPanelOpen.DASHBOARD_SIGN_IN))
        return browserAddress(address)
    }

    /**
     * The dashboard account Hermes' launcher is given: the login Pots chose and
     * the password generated for this panel.
     *
     * The environment is the only place for it. `~/.hermes/config.yaml` is
     * rewritten from scratch on every agent start, so an account written there
     * would be erased, and a plaintext password in a config file is worse than
     * none. Hermes reads both variables, and env wins over the file.
     */
    private suspend fun hermesEnv(panel: WebPanel): Map<String, String> = buildMap {
        // The account is the panel's own, so the login the row shows and the login
        // the gateway is given cannot drift apart.
        panel.usernameEnv?.let { env -> panel.username?.let { put(env, it) } }
        panel.passwordEnv?.let { put(it, passwordFor(panel)) }
        // The shipped renderer, when that is the one being served. Naming it is
        // what stops the launcher from building one itself; a renderer the device
        // built is left unnamed so Hermes keeps owning it.
        hermesDist?.let { put(HermesWebapp.DIST_ENV, it) }
    }

    /** [WebPanels.hermesAddress], with the address this phone has right now. */
    private fun hermesRowAddress(port: Int, printed: String?): String =
        WebPanels.hermesAddress(printed, port, lanAddress())

    /**
     * The address a browser is handed for a panel that needs nothing minted.
     *
     * The row's LAN address is the one shape that is not a secure context:
     * `getUserMedia` refuses it outright, so the panel's microphone stays dead.
     * Loopback is a potentially trustworthy origin, so the browser gets this
     * panel's own loopback at the port the row carries. Anything else the row can
     * show — loopback itself, or a published address Hermes printed — is handed
     * over as it is.
     */
    private fun browserAddress(rowUrl: String): String {
        val port = WebPanels.portOf(rowUrl) ?: return rowUrl
        val lan = lanAddress()
        if (lan.isNullOrBlank() || rowUrl != WebPanels.lanAddress(port, lan)) return rowUrl
        return WebPanels.address(port)
    }

    /**
     * The last line the guest said, for a failure the owner has to read.
     *
     * Last, not first: the lines before it are the install's warnings, and the
     * line that says why the command stopped is the one printed when it does.
     */
    private fun reasonLine(output: String): String =
        output.lineSequence().lastOrNull { it.isNotBlank() }?.trim().orEmpty()
            .ifEmpty { "no link printed" }

    /**
     * What the panel is doing, from the strongest evidence there is.
     *
     * A panel with its own status command is asked first, and a serving address
     * in the answer settles it. Its wording is Hermes' own and moves between
     * releases, so an answer without a readable address — including one that only
     * quotes a link, which [HermesWebappOutput.servingUrl] refuses — falls through
     * to the process check instead of reading as "not running": a gateway whose
     * pid is alive and whose port answers is running whatever its status line
     * says, and that is the case the owner was left staring at — a panel that
     * opened in the browser while the row offered to start it again.
     */
    private fun probe(panel: WebPanel): WebPanelStatus {
        val statusCommand = WebPanels.statusCommand(panel)
        val serving = statusCommand?.let { command ->
            HermesWebappOutput.servingUrl(guest.run(command, STATUS_TIMEOUT_SECONDS).text)
        }
        if (serving != null) {
            // `--status` reports the address the gateway serves on, which is its
            // loopback dial origin even when the bind is every interface. Pots
            // writes that bind, so the panel sits behind the sign-in gate: the row
            // gets the LAN address and says an account is needed.
            val port = WebPanels.portOf(serving) ?: WebPanels.HERMES_PORT
            return WebPanelStatus.Running(hermesRowAddress(port, serving), WebPanelOpen.DASHBOARD_SIGN_IN)
        }
        val pid = guest.pid(panel) ?: return WebPanelStatus.NotRunning
        if (!guest.alive(pid)) return WebPanelStatus.NotRunning
        val port = guest.port(panel) ?: return WebPanelStatus.Failed("pid $pid is alive but its port is unknown")
        if (guest.portFree(port)) {
            return WebPanelStatus.Failed("pid $pid is alive, port $port is not listening")
        }
        return if (statusCommand != null) {
            // The launcher's own address, without one printed: the gateway is up
            // and listening, and the bind Pots writes puts it behind the account.
            WebPanelStatus.Running(hermesRowAddress(port, null), WebPanelOpen.DASHBOARD_SIGN_IN)
        } else {
            WebPanelStatus.Running(WebPanels.address(port), WebPanels.openKind(panel))
        }
    }

    /**
     * The panel's password: the one it already has, or a fresh one stored now.
     * Stable for the life of the install — the agent keeps answering with it, so a
     * new one per start would lock out an already open browser tab.
     *
     * Every caller goes through here, and here is the only place a value is
     * generated. The stored write is the settings file, so it happens on the
     * background dispatcher: the row asks for a password while it is drawing, and
     * a disk write there is a dropped frame at best.
     */
    private suspend fun passwordFor(panel: WebPanel): String =
        passwordMutex.withLock {
            withContext(getBackgroundDispatcher()) { passwordLocked(panel) }
        }

    /** [passwordFor] without the lock and the dispatcher hop; the caller holds [passwordMutex]. */
    private fun passwordLocked(panel: WebPanel): String {
        val stored = storedPassword(panel)
        if (stored != null) return stored
        val password = WebPanelPasswords.generate()
        savePrefs(panel.id, prefs(panel.id).copy(password = password))
        return password
    }

    /** The password already in settings, or null — reads, never generates. */
    private fun storedPassword(panel: WebPanel): String? =
        prefs(panel.id).password?.takeIf { it.isNotEmpty() }

    private fun setState(panel: WebPanel, status: WebPanelStatus) {
        publishStates {
            states[panel.id] = WebPanelState(panel.id, status, prefs(panel.id).autostart)
        }
    }

    /**
     * Publishes a state from outside [mutex].
     *
     * [states] is a plain map and every write to it happens under [mutex]: the
     * renderer chain and the rollback are the only writers that run unlocked, and
     * they take the lock here for the write alone, so a progress tick cannot
     * corrupt a refresh that is rebuilding the map beside it.
     */
    private suspend fun setStateOutsideLock(panel: WebPanel, status: WebPanelStatus) {
        mutex.withLock { setState(panel, status) }
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

        /**
         * Unpacking the shipped renderer: 14 MB of xz into ~1200 files, on a phone
         * under proot. Generous, because a timeout here reads as "the panel is
         * broken" when the truth is "the phone is slow".
         */
        const val UNPACK_TIMEOUT_SECONDS = 300L

        /**
         * Wiping what a failed build attempt left: a pnpm store, `node_modules`
         * and the previous generation of the workspace. Minutes on a phone, and
         * the same bound the install's chain gives it.
         */
        const val CLEAN_TIMEOUT_SECONDS = 300L

        /** Moving the checkout back to the pin: one shallow fetch and a checkout. */
        const val ROLLBACK_TIMEOUT_SECONDS = 300L

        /**
         * How often the row's remaining build time is refreshed. The chain itself
         * cannot report progress — `hermes webapp --build-only` prints its phases
         * and no percentage — so the budget is the number the owner gets.
         */
        const val REBUILD_TICK_MILLIS = 15_000L

        /** A pairing code is one POST to loopback; nothing here should take a minute. */
        const val PAIR_TIMEOUT_SECONDS = 30L
        const val GATEWAY_STOP = "hermes gateway stop"
    }
}
