package com.pusios.pots.build

import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * What the row reads out of `hermes webapp --status`, and what it says when the
 * answer carries no address.
 *
 * The samples are what the launcher really prints: the plain serving line, a bind
 * address with the loopback one in parentheses, and the warnings an out-of-sync
 * install puts in front of the answer. Reading only the strict `Serving at` shape
 * made a serving gateway look stopped, and the row then offered to start a panel
 * that was already open in the browser.
 */
class HermesWebappStatusTest {

    @Test
    fun `the address is read from the serving line`() {
        assertEquals("http://127.0.0.1:9120", HermesWebappOutput.servingUrl("Serving at http://127.0.0.1:9120"))
    }

    @Test
    fun `warnings ahead of the answer do not hide the address`() {
        assertEquals("http://127.0.0.1:9449", HermesWebappOutput.servingUrl(STATUS_WITH_WARNINGS))
    }

    @Test
    fun `a bind address is the one read, and the loopback in parentheses is not`() {
        assertEquals(
            "http://192.168.1.50:9120",
            HermesWebappOutput.servingUrl(
                "Serving at http://192.168.1.50:9120 (this machine's own clients use http://127.0.0.1:9120)",
            ),
        )
    }

    @Test
    fun `an address without the serving prefix is still the address`() {
        assertEquals(
            "https://pots.local:9449/",
            HermesWebappOutput.servingUrl("The gateway serves the Webapp at https://pots.local:9449/ right now."),
        )
    }

    @Test
    fun `an answer with no address is no address`() {
        assertNull(HermesWebappOutput.servingUrl("The gateway is not running. Run hermes webapp to start it."))
    }

    @Test
    fun `a gateway whose status says nothing readable is still running`() = runTest {
        val guest = FakeGuest().apply {
            status = WARNING
            pid = 4242
            port = 9120
            alive += 4242
            listening += 9120
        }
        val runtime = runtime(this, guest)

        runtime.refreshPanels()

        // The pid answers and the port listens: running, with the address Pots
        // knows, and not a line of the launcher in place of a status.
        assertEquals(
            WebPanelStatus.Running("http://192.168.1.50:9120", WebPanelOpen.DASHBOARD_SIGN_IN),
            status(WebPanels.HERMES),
        )
    }

    @Test
    fun `a start whose launcher prints no address reports the panel running`() = runTest {
        val guest = FakeGuest().apply { launcher = WARNING }
        val runtime = runtime(this, guest)

        runtime.startPanel(WebPanels.HERMES)

        // The port answered before the launcher ran, so the gateway is up: the
        // launcher's wording cannot turn a serving panel into a failed one.
        assertEquals(
            WebPanelStatus.Running("http://192.168.1.50:9120", WebPanelOpen.DASHBOARD_SIGN_IN),
            status(WebPanels.HERMES),
        )
        assertEquals(1, guest.launches, "one gateway, started once")
    }

    @Test
    fun `a start that leaves no pid and no address still fails with its line`() = runTest {
        val guest = FakeGuest().apply {
            launcher = WARNING
            recordsPid = false
        }
        val runtime = runtime(this, guest)

        runtime.startPanel(WebPanels.HERMES)

        // Nothing proves the panel is up — no pid to signal, so nothing Pots could
        // stop — and the launcher's own first line is the honest reason.
        val failed = assertIs<WebPanelStatus.Failed>(status(WebPanels.HERMES))
        assertContains(failed.reason, "install out of sync")
    }

    // --- helpers ----------------------------------------------------------

    /** What the runtime last published; one runtime per test, so one map is enough. */
    private var published: ImmutableMap<String, WebPanelState> = persistentMapOf()

    private fun status(panelId: String): WebPanelStatus? = published[panelId]?.status

    private fun runtime(scope: CoroutineScope, guest: WebPanelGuest): WebPanelRuntime = WebPanelRuntime(
        guest = guest,
        prefs = { WebPanelPreferences() },
        savePrefs = { _, _ -> },
        installed = { BuildAgents.all.map { it.id }.toSet() },
        lanAddress = { LAN_IP },
        publish = { panels -> published = panels },
        scope = scope,
        startTimeoutSeconds = 5,
    )

    /**
     * A guest that answers the Hermes start the way a sandbox does, without one:
     * a checkout built on the device, a gateway that records its pid, and the two
     * launcher outputs under the test's control.
     */
    private class FakeGuest : WebPanelGuest {
        var status = ""
        var launcher = ""
        var recordsPid = true
        var pid: Int? = null
        var port: Int? = null
        var launches = 0
        val alive = mutableSetOf<Int>()
        val listening = mutableSetOf<Int>()

        override fun run(command: String, timeoutSeconds: Long, env: Map<String, String>): WebPanelGuest.Output =
            when {
                command == WebPanels.HERMES_HELP -> WebPanelGuest.Output(true, "usage: hermes webapp")
                command == HermesWebapp.headCommand(WebPanels.HERMES_CHECKOUT) ->
                    WebPanelGuest.Output(true, HEAD)
                command == HermesWebapp.stampCommand() -> WebPanelGuest.Output(true, HEAD)
                command == HermesWebapp.BUILT_CHECK -> WebPanelGuest.Output(true)
                command == STATUS_COMMAND -> WebPanelGuest.Output(status.isNotEmpty(), status)
                command == LAUNCH -> WebPanelGuest.Output(true, launcher)
                command.startsWith("/bin/bash -c 'i=0; while") -> WebPanelGuest.Output(listening.isNotEmpty())
                else -> WebPanelGuest.Output(true)
            }

        override fun launch(panel: WebPanel, port: Int, command: String, env: Map<String, String>): Boolean {
            launches++
            this.port = port
            if (recordsPid) {
                pid = 4242
                alive += 4242
            }
            listening += port
            return true
        }

        override fun configure(panel: WebPanel, port: Int): Boolean = true

        override fun pid(panel: WebPanel): Int? = pid

        override fun port(panel: WebPanel): Int? = port

        override fun alive(pid: Int): Boolean = pid in alive

        override fun portFree(port: Int): Boolean = port !in listening

        override fun forget(panel: WebPanel) {
            pid = null
            port = null
        }

        override fun logText(panel: WebPanel): String = ""

        override fun stageHermesBundle(): String? = null

        override fun authPluginDisabled(panel: WebPanel): Boolean = false
    }
}

/** The hermes-agent commit the fake checkout is on. */
private const val HEAD = "caafe5389ae585ef77387a962aee05b1bcf17029"

/** The phone's own address on the network the test pretends to be on. */
private const val LAN_IP = "192.168.1.50"

private const val STATUS_COMMAND = "hermes webapp --status"

private const val LAUNCH = "hermes webapp --no-open"

/** The line an out-of-sync install puts in front of whatever the command prints. */
private const val WARNING =
    "hermes: install out of sync (venv: out of sync with uv.lock) - run `hermes pm install`"

/** What `hermes webapp --status` really printed on such a host, warnings and all. */
private val STATUS_WITH_WARNINGS = """
    hermes: running with the previous dependencies after 3 failed syncs; next automatic retry in 3 min, or run `hermes update` to finish it
    -> Restarting gateway(s) paused by an interrupted `hermes update`...
      Paused gateway(s) left stopped: dependencies are not current for the updated code yet.
    install out of sync (venv: out of sync with uv.lock) - run `hermes pm install`
    Serving at http://127.0.0.1:9449
""".trimIndent()
