package com.pusios.pots.build

import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The panel state machine and its parsing, without a rootfs: every guest call
 * goes through a fake, so these run on the desktop JVM like the rest.
 */
class WebPanelsTest {

    // --- port choice ------------------------------------------------------

    @Test
    fun `the preferred port is used when it is free`() {
        assertEquals(4096, WebPanels.pickPort(4096) { false })
    }

    @Test
    fun `a taken port moves the panel to the next free one`() {
        val busy = setOf(4096, 4097)
        assertEquals(4098, WebPanels.pickPort(4096) { it in busy })
    }

    @Test
    fun `every port taken is an error, not a silent random one`() {
        assertNull(WebPanels.pickPort(4096) { true })
    }

    @Test
    fun `the search window is bounded`() {
        val probed = mutableListOf<Int>()
        assertNull(
            WebPanels.pickPort(4096) { port ->
                probed += port
                true
            },
        )
        assertEquals(WebPanels.PORT_ATTEMPTS, probed.size)
        assertEquals(4096 + WebPanels.PORT_ATTEMPTS - 1, probed.last())
    }

    @Test
    fun `a port that would run past 65535 is refused`() {
        assertNull(WebPanels.pickPort(65535) { true })
    }

    // --- commands ---------------------------------------------------------

    @Test
    fun `each panel's command binds loopback and names its port`() {
        for (panel in WebPanels.all) {
            val command = WebPanels.command(panel, 1234)
            if (panel.id == WebPanels.HERMES) {
                // Hermes takes the port from its config, not from a flag.
                assertEquals("hermes webapp --no-open", command)
            } else {
                assertContains(command, "--port 1234")
                assertContains(command, "--hostname 127.0.0.1")
                assertFalse(command.contains("0.0.0.0"), command)
            }
        }
    }

    @Test
    fun `pi-web is told not to open a browser of its own`() {
        assertContains(WebPanels.command(WebPanels.get(WebPanels.PI)!!, 30141), "--no-open")
    }

    // --- state ------------------------------------------------------------

    @Test
    fun `an agent that was never started is not running`() = runTest {
        val runtime = runtime(this, FakeGuest())
        runtime.refreshPanels()
        assertEquals(WebPanelStatus.NotRunning, status("opencode"))
    }

    @Test
    fun `a live pid with a listening port is running`() = runTest {
        val guest = FakeGuest().apply {
            pid = 4242
            port = 4096
            alive += 4242
            listening += 4096
        }
        val runtime = runtime(this, guest)
        runtime.refreshPanels()
        assertEquals(WebPanelStatus.Running("http://127.0.0.1:4096"), status("opencode"))
    }

    @Test
    fun `a live pid whose port is not listening is a failure with a reason`() = runTest {
        val guest = FakeGuest().apply {
            pid = 4242
            port = 4096
            alive += 4242
        }
        val runtime = runtime(this, guest)
        runtime.refreshPanels()
        val failed = assertIs<WebPanelStatus.Failed>(status("opencode"))
        assertContains(failed.reason, "not listening")
    }

    @Test
    fun `a dead pid is not running`() = runTest {
        val guest = FakeGuest().apply {
            pid = 4242
            port = 4096
        }
        val runtime = runtime(this, guest)
        runtime.refreshPanels()
        assertEquals(WebPanelStatus.NotRunning, status("opencode"))
    }

    @Test
    fun `only the panels of installed agents are listed`() = runTest {
        val runtime = runtime(this, FakeGuest(), installed = setOf(BuildAgents.PI))
        runtime.refreshPanels()
        assertEquals(setOf("pi"), published.keys)
    }

    // --- starting ---------------------------------------------------------

    @Test
    fun `starting a panel launches it on the preferred port and reports that URL`() = runTest {
        val guest = FakeGuest()
        val runtime = runtime(this, guest)
        runtime.startPanel("opencode")

        assertEquals(4096, guest.port)
        assertContains(guest.commands.single(), "opencode serve --port 4096")
        assertEquals(WebPanelStatus.Running("http://127.0.0.1:4096"), status("opencode"))
    }

    @Test
    fun `a busy port starts the panel on the next one and the URL says so`() = runTest {
        val guest = FakeGuest().apply { busy += 4096 }
        val runtime = runtime(this, guest)
        runtime.startPanel("opencode")

        assertEquals(4097, guest.port)
        assertEquals(WebPanelStatus.Running("http://127.0.0.1:4097"), status("opencode"))
    }

    @Test
    fun `every port busy fails with the reason instead of starting`() = runTest {
        val guest = FakeGuest().apply { busy += (4096..4105).toSet() }
        val runtime = runtime(this, guest)
        runtime.startPanel("opencode")

        val failed = assertIs<WebPanelStatus.Failed>(status("opencode"))
        assertContains(failed.reason, "busy")
        assertTrue(guest.commands.isEmpty(), "nothing should have been launched")
    }

    @Test
    fun `a panel that never listens fails with the tail of its log`() = runTest {
        val guest = FakeGuest().apply {
            listensAfterLaunch = false
            log = "Error: address already in use"
        }
        val runtime = runtime(this, guest)
        runtime.startPanel("opencode")

        val failed = assertIs<WebPanelStatus.Failed>(status("opencode"))
        assertContains(failed.reason, "address already in use")
    }

    @Test
    fun `starting twice does not start a second server`() = runTest {
        val guest = FakeGuest()
        val runtime = runtime(this, guest)
        runtime.startPanel("opencode")
        runtime.startPanel("opencode")

        assertEquals(1, guest.commands.size)
    }

    @Test
    fun `an agent that is not installed cannot be started`() = runTest {
        val guest = FakeGuest()
        val runtime = runtime(this, guest, installed = setOf(BuildAgents.PI))
        runtime.startPanel("opencode")

        val failed = assertIs<WebPanelStatus.Failed>(status("opencode"))
        assertContains(failed.reason, "not installed")
        assertTrue(guest.commands.isEmpty())
    }

    // --- stopping ---------------------------------------------------------

    @Test
    fun `stopping signals the recorded pid and clears the panel`() = runTest {
        val guest = FakeGuest().apply {
            pid = 4242
            port = 4096
            alive += 4242
            listening += 4096
        }
        val runtime = runtime(this, guest)
        runtime.refreshPanels()
        runtime.stopPanel("opencode")

        // `refreshPanels` also runs Hermes' own `--status` probe, so the stop is
        // asserted by content rather than by being the only command.
        assertTrue(guest.runs.any { it.contains("kill -TERM 4242") }, guest.runs.toString())
        assertEquals(WebPanelStatus.NotRunning, status("opencode"))
        assertTrue(guest.forgotten, "the pid and port files are dropped with the panel")
    }

    @Test
    fun `a port still taken after the stop is reported, not called a success`() = runTest {
        val guest = FakeGuest().apply {
            pid = 4242
            port = 4096
            alive += 4242
            listening += 4096
            ignoreSignals = true
        }
        val runtime = runtime(this, guest)
        runtime.refreshPanels()
        runtime.stopPanel("opencode")

        val failed = assertIs<WebPanelStatus.Failed>(status("opencode"))
        assertContains(failed.reason, "still taken")
    }

    @Test
    fun `stopping Hermes stops its gateway and confirms the port it was serving`() = runTest {
        val guest = FakeGuest().apply { status = "Serving at http://127.0.0.1:9120" }
        val runtime = runtime(this, guest)
        runtime.refreshPanels()
        runtime.stopPanel("hermes")

        assertTrue(guest.runs.any { it == "hermes gateway stop" }, guest.runs.toString())
        assertEquals(WebPanelStatus.NotRunning, status("hermes"))
    }

    @Test
    fun `a Hermes gateway that keeps serving after the stop is reported`() = runTest {
        val guest = FakeGuest().apply {
            status = "Serving at http://127.0.0.1:9120"
            listening += 9120
        }
        val runtime = runtime(this, guest)
        runtime.refreshPanels()
        runtime.stopPanel("hermes")

        val failed = assertIs<WebPanelStatus.Failed>(status("hermes"))
        assertContains(failed.reason, "still taken")
    }

    // --- autostart --------------------------------------------------------

    @Test
    fun `autostart brings up the toggled panel`() = runTest {
        val guest = FakeGuest()
        val runtime = runtime(this, guest, prefs = { id -> WebPanelPreferences(autostart = id == "opencode") })
        runtime.ensureAutostartPanels()

        assertEquals(1, guest.commands.size)
        assertContains(guest.commands.single(), "opencode serve")
        assertEquals(WebPanelStatus.Running("http://127.0.0.1:4096"), status("opencode"))
        assertEquals(WebPanelStatus.NotRunning, status("pi"), "a panel whose toggle is off stays down")
    }

    @Test
    fun `autostart leaves an already running panel alone`() = runTest {
        val guest = FakeGuest().apply {
            pid = 4242
            port = 4096
            alive += 4242
            listening += 4096
        }
        val runtime = runtime(this, guest, prefs = { id -> WebPanelPreferences(autostart = id == "opencode") })
        runtime.ensureAutostartPanels()

        assertTrue(guest.commands.isEmpty(), "a second server would be started on top of the first")
    }

    @Test
    fun `autostart twice still starts one server`() = runTest {
        val guest = FakeGuest()
        val runtime = runtime(this, guest, prefs = { id -> WebPanelPreferences(autostart = id == "opencode") })
        runtime.ensureAutostartPanels()
        runtime.ensureAutostartPanels()

        assertEquals(1, guest.commands.size)
    }

    @Test
    fun `a panel with autostart off is not started with the sandbox`() = runTest {
        val guest = FakeGuest()
        val runtime = runtime(this, guest, prefs = { WebPanelPreferences(autostart = false) })
        runtime.ensureAutostartPanels()

        assertTrue(guest.commands.isEmpty())
    }

    // --- passwords --------------------------------------------------------

    @Test
    fun `a generated password is long enough and has no look-alike characters`() {
        val password = WebPanelPasswords.generate(Random(7))
        assertEquals(WebPanelPasswords.LENGTH, password.length)
        assertTrue(password.all { it in WebPanelPasswords.ALPHABET }, password)
        for (ambiguous in listOf('l', 'I', 'O', '0', '1')) {
            assertFalse(password.contains(ambiguous), "$ambiguous is in $password")
        }
    }

    @Test
    fun `the password is generated once and then reused`() = runTest {
        val runtime = runtime(this, FakeGuest())
        val first = runtime.password("opencode")
        val second = runtime.password("opencode")

        assertTrue(!first.isNullOrEmpty())
        assertEquals(first, second)
    }

    @Test
    fun `the password goes into the environment, never into the command line or the log`() = runTest {
        val guest = FakeGuest()
        val runtime = runtime(this, guest)
        runtime.startPanel("opencode")

        val password = runtime.password("opencode").orEmpty()
        assertTrue(password.isNotEmpty())
        assertEquals(1, guest.env.size, "only the panel's own password variable is added")
        assertEquals(password, guest.env["OPENCODE_SERVER_PASSWORD"])
        assertFalse(guest.commands.single().contains(password), "the password must not be visible in ps")
        assertFalse(guest.log.contains(password))
    }

    @Test
    fun `Hermes has no panel password of its own`() = runTest {
        val runtime = runtime(this, FakeGuest())
        assertNull(runtime.password("hermes"))
    }

    // --- Hermes -----------------------------------------------------------

    @Test
    fun `a one-use Hermes link is parsed with its lifetime`() {
        val link = HermesWebappOutput.parse(
            """
            Open this link in your browser within 60 seconds. It works once and signs
            the browser in as you, so keep it private:
              http://127.0.0.1:9120/?ticket=abc123
            """.trimIndent(),
        )
        assertEquals("http://127.0.0.1:9120/?ticket=abc123", link?.url)
        assertTrue(link?.oneTime == true)
        assertEquals(60, link?.expiresInSeconds)
    }

    @Test
    fun `a sign-in link is permanent and reusable`() {
        val link = HermesWebappOutput.parse(
            "Open the Webapp and sign in with your dashboard account: http://127.0.0.1:9120/",
        )
        assertEquals("http://127.0.0.1:9120/", link?.url)
        assertFalse(link?.oneTime == true)
        assertNull(link?.expiresInSeconds)
    }

    @Test
    fun `two taps on a one-use Hermes link give two different links`() = runTest {
        val guest = FakeGuest().apply {
            hermesLinks += listOf(
                "Open this link in your browser within 60 seconds. It works once:\n  http://127.0.0.1:9120/?ticket=first",
                "Open this link in your browser within 60 seconds. It works once:\n  http://127.0.0.1:9120/?ticket=second",
            )
        }
        val runtime = runtime(this, guest)
        val first = runtime.link("hermes")
        val second = runtime.link("hermes")

        assertNotEquals(first, second)
        assertContains(first.orEmpty(), "ticket=first")
        assertContains(second.orEmpty(), "ticket=second")
    }

    @Test
    fun `a running Hermes panel is read from its own status, not from a pid`() = runTest {
        val guest = FakeGuest().apply {
            status = "Serving at http://127.0.0.1:9120"
        }
        val runtime = runtime(this, guest)
        runtime.refreshPanels()
        assertEquals(WebPanelStatus.Running("http://127.0.0.1:9120"), status("hermes"))
    }

    @Test
    fun `a Hermes without the webapp command says so`() = runTest {
        val guest = FakeGuest().apply { hasWebapp = false }
        val runtime = runtime(this, guest)
        runtime.startPanel("hermes")

        val failed = assertIs<WebPanelStatus.Failed>(status("hermes"))
        assertContains(failed.reason, "no webapp command")
    }

    @Test
    fun `starting Hermes reports the port its link actually carries`() = runTest {
        val guest = FakeGuest().apply {
            busy += 9120
            hermesLinks += listOf(
                "Open this link in your browser within 60 seconds:\n  http://127.0.0.1:9121/?ticket=abc",
            )
        }
        val runtime = runtime(this, guest)
        runtime.startPanel("hermes")

        assertEquals(9121, guest.configuredPort)
        assertEquals(WebPanelStatus.Running("http://127.0.0.1:9121"), status("hermes"))
    }

    @Test
    fun `the webapp block is written at the chosen port`() {
        val config = SandboxAgentConfig.withWebappPort("model:\n  default: 'x'\n", 9121)
        assertContains(config, "webapp:")
        assertContains(config, "  host: 127.0.0.1")
        assertContains(config, "  port: 9121")
        assertContains(config, "  enabled: true")
        assertContains(config, "  default: 'x'")
    }

    @Test
    fun `rewriting the webapp port replaces the block instead of adding a second one`() {
        val once = SandboxAgentConfig.withWebappPort("model:\n  default: 'x'\n", 9121)
        val twice = SandboxAgentConfig.withWebappPort(once, 9122)

        assertEquals(1, twice.lines().count { it == "webapp:" })
        assertContains(twice, "  port: 9122")
        assertFalse(twice.contains("9121"), twice)
    }

    // --- helpers ----------------------------------------------------------

    /** What the runtime last published; one runtime per test, so one map is enough. */
    private var published: ImmutableMap<String, WebPanelState> = persistentMapOf()

    private fun status(panelId: String): WebPanelStatus? = published[panelId]?.status

    private fun runtime(
        scope: CoroutineScope,
        guest: WebPanelGuest,
        installed: Set<String> = BuildAgents.all.map { it.id }.toSet(),
        prefs: (String) -> WebPanelPreferences = { WebPanelPreferences() },
    ): WebPanelRuntime = WebPanelRuntime(
        guest = guest,
        prefs = prefs,
        savePrefs = { _, _ -> },
        installed = { installed },
        publish = { panels -> published = panels },
        scope = scope,
        startTimeoutSeconds = 5,
    )

    /**
     * A guest that answers the way a Debian rootfs would, without one: ports,
     * pids and the two Hermes outputs are all it knows about.
     */
    private class FakeGuest : WebPanelGuest {
        val busy = mutableSetOf<Int>()
        val listening = mutableSetOf<Int>()
        val alive = mutableSetOf<Int>()
        val commands = mutableListOf<String>()
        val runs = mutableListOf<String>()
        val hermesLinks = mutableListOf<String>()
        var pid: Int? = null
        var port: Int? = null
        var configuredPort: Int? = null
        var listensAfterLaunch = true
        var ignoreSignals = false
        var hasWebapp = true
        var status = ""
        var log = ""
        var forgotten = false

        /** The environment of the last launch — where a panel's password must be. */
        var env: Map<String, String> = emptyMap()

        override fun run(command: String, timeoutSeconds: Long): WebPanelGuest.Output {
            runs += command
            return when {
                command == WebPanels.HERMES_HELP -> WebPanelGuest.Output(hasWebapp, "usage: hermes webapp")
                command.startsWith("hermes webapp --no-open") ->
                    WebPanelGuest.Output(true, hermesLinks.removeFirstOrNull().orEmpty())
                command == "hermes webapp --status" -> WebPanelGuest.Output(status.isNotEmpty(), status)
                command.startsWith("i=0; while") -> WebPanelGuest.Output(listening.isNotEmpty())
                command.startsWith("kill -TERM") -> {
                    if (!ignoreSignals) {
                        alive.clear()
                        listening.clear()
                    }
                    WebPanelGuest.Output(true)
                }
                else -> WebPanelGuest.Output(true)
            }
        }

        override fun launch(panel: WebPanel, port: Int, command: String, env: Map<String, String>): Boolean {
            commands += command
            this.env = env
            this.port = port
            this.pid = 4242
            alive += 4242
            if (listensAfterLaunch) listening += port
            return true
        }

        override fun configure(panel: WebPanel, port: Int): Boolean {
            configuredPort = port
            return true
        }

        override fun pid(panel: WebPanel): Int? = pid

        override fun port(panel: WebPanel): Int? = port

        override fun alive(pid: Int): Boolean = pid in alive

        override fun portFree(port: Int): Boolean = port !in listening && port !in busy

        override fun forget(panel: WebPanel) {
            forgotten = true
            pid = null
            port = null
        }

        override fun logTail(panel: WebPanel): String = log
    }
}
