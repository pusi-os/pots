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

    /** A provider Pots is configured with — the config files a start writes need one. */
    private val credentials = SandboxAgentCredentials(
        providerId = "plusvibe",
        providerName = "PlusVibe",
        model = "qwen3.8-flash",
        apiKey = "pots-test-key",
        endpoint = "https://plusvibeapi.ru/v1",
    )

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

    // --- the probe itself -------------------------------------------------

    // These two are string tests on purpose: the probe lives in the command that
    // goes to the guest, and the fake guest answers instead of executing it. A
    // probe written for `/bin/sh` (dash, in the image) fails to open `/dev/tcp`
    // at all — which reads as "nothing is listening" and as "the port is free",
    // both wrong — and no fake would ever notice.

    @Test
    fun `the port probe runs under bash, because dev tcp is not POSIX`() {
        val command = portProbeCommand(4096)
        assertTrue(command.startsWith("/bin/bash -c '"), command)
        assertContains(command, "/dev/tcp/127.0.0.1/4096")
        assertFalse(command.contains("/bin/sh"), command)
    }

    @Test
    fun `only a refused connection means the port is free`() {
        assertTrue(portIsFree(3), "connection refused")
        assertFalse(portIsFree(0), "something answered")
        assertFalse(portIsFree(127), "command not found — the probe never ran")
        assertFalse(portIsFree(-1), "proot error or timeout")
    }

    @Test
    fun `waiting for a panel runs under bash too`() = runTest {
        val guest = FakeGuest()
        runtime(this, guest).startPanel("opencode")

        val waited = guest.runs.single { it.contains("/dev/tcp") }
        assertTrue(waited.startsWith("/bin/bash -c '"), waited)
    }

    @Test
    fun `a probe that cannot run never hands out the port`() = runTest {
        // Every probe failing the way a broken shell fails: nothing may be
        // reported free, so the panel ends as a sentence, not on a taken port.
        val guest = FakeGuest().apply { probeAnswers = false }
        val runtime = runtime(this, guest)
        runtime.startPanel("opencode")

        val failed = assertIs<WebPanelStatus.Failed>(status("opencode"))
        assertContains(failed.reason, "all busy")
        assertTrue(guest.commands.isEmpty(), "no server on a port nobody could check")
    }

    // --- commands ---------------------------------------------------------

    @Test
    fun `each panel's command names its port, and the two flag-takers bind loopback`() {
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
        assertEquals(WebPanelStatus.Running("http://127.0.0.1:4096", WebPanelOpen.PAIRING_CODE), status("opencode"))
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
        assertEquals(WebPanelStatus.Running("http://127.0.0.1:4096", WebPanelOpen.PAIRING_CODE), status("opencode"))
    }

    @Test
    fun `a busy port starts the panel on the next one and the URL says so`() = runTest {
        val guest = FakeGuest().apply { busy += 4096 }
        val runtime = runtime(this, guest)
        runtime.startPanel("opencode")

        assertEquals(4097, guest.port)
        assertEquals(WebPanelStatus.Running("http://127.0.0.1:4097", WebPanelOpen.PAIRING_CODE), status("opencode"))
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
        assertEquals(WebPanelStatus.Running("http://127.0.0.1:4096", WebPanelOpen.PAIRING_CODE), status("opencode"))
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

    // --- after an agent update --------------------------------------------

    // The installer replaces the binary under a running panel, and the process
    // started before it keeps serving the old interface — which reads as "the
    // update did not work".

    @Test
    fun `an updated agent's running panel is stopped and started again`() = runTest {
        val guest = FakeGuest()
        val runtime = runtime(this, guest)
        runtime.startPanel("opencode")
        assertEquals(1, guest.commands.size)

        runtime.restartAfterInstall(BuildAgents.OPENCODE)

        assertTrue(guest.runs.any { it.contains("kill -TERM 4242") }, guest.runs.toString())
        assertEquals(2, guest.commands.size, "the old process was still serving the old binary")
        assertEquals(
            WebPanelStatus.Running("http://127.0.0.1:4096", WebPanelOpen.PAIRING_CODE),
            status("opencode"),
        )
    }

    @Test
    fun `an update does not start a panel that was down`() = runTest {
        val guest = FakeGuest()
        val runtime = runtime(this, guest)

        runtime.restartAfterInstall(BuildAgents.OPENCODE)

        assertTrue(guest.commands.isEmpty(), "a stopped panel is not ours to bring up")
    }

    @Test
    fun `a panel whose port could not be freed is not started beside the old one`() = runTest {
        val guest = FakeGuest().apply {
            pid = 4242
            port = 4096
            alive += 4242
            listening += 4096
            ignoreSignals = true
        }
        val runtime = runtime(this, guest)
        runtime.refreshPanels()

        runtime.restartAfterInstall(BuildAgents.OPENCODE)

        val failed = assertIs<WebPanelStatus.Failed>(status("opencode"))
        assertContains(failed.reason, "still taken")
        assertTrue(guest.commands.isEmpty(), "a second server would hold a second port")
    }

    // --- a panel that is up but says it failed ----------------------------

    // A sign-in can fail while the server it talks to is alive: the row says
    // Failed and the process still holds its port. Starting another one on the
    // next tap would leave the first orphaned and rewrite its pid, so the
    // runtime adopts what is already there.

    @Test
    fun `a panel whose sign-in failed is adopted on the next tap, not started again`() = runTest {
        val guest = FakeGuest().apply { pairAnswers += """{"message":"Unauthorized"}""" + "\n401" }
        val runtime = runtime(this, guest)
        runtime.startPanel("opencode")
        assertNull(runtime.link("opencode"))
        assertIs<WebPanelStatus.Failed>(status("opencode"))
        assertEquals(1, guest.commands.size)

        runtime.startPanel("opencode")

        assertEquals(1, guest.commands.size, "a second server would be an orphan on the old port")
        assertEquals(
            WebPanelStatus.Running("http://127.0.0.1:4096", WebPanelOpen.PAIRING_CODE),
            status("opencode"),
        )
    }

    @Test
    fun `a panel alive but not listening is stopped before the start`() = runTest {
        val guest = FakeGuest().apply {
            pid = 4242
            port = 4096
            alive += 4242
        }
        val runtime = runtime(this, guest)

        runtime.startPanel("opencode")

        // It holds the pid file: launching beside it would lose the old pid.
        assertTrue(guest.runs.any { it.contains("kill -TERM 4242") }, guest.runs.toString())
        assertEquals(1, guest.commands.size)
    }

    // --- links ------------------------------------------------------------

    // No panel takes credentials in a URL. OpenCode 2 stopped accepting them: `/`
    // answers with "Connect to a server. Paste the pairing link…", so the sign-in
    // is a one-use code minted on the tap. pi-web's HTML wants a session cookie,
    // so its URL is the login form; Hermes signs a browser in with the one-use
    // link `link()` mints.

    @Test
    fun `OpenCode's link is a minted pairing code and carries no password`() = runTest {
        val guest = FakeGuest().apply {
            pairAnswers += """{"code":"L9gVWvVVNxuFRupAPd8XPg","expires_in":300}""" + "\n200"
        }
        val runtime = runtime(this, guest)
        runtime.startPanel("opencode")
        val password = runtime.password("opencode").orEmpty()
        assertTrue(password.isNotEmpty())

        val link = runtime.link("opencode").orEmpty()

        assertEquals("http://127.0.0.1:4096/auth/connect/L9gVWvVVNxuFRupAPd8XPg", link)
        assertFalse(link.contains(password), link)
        assertFalse(link.contains("@"), "credentials in the URL are back: $link")
        // The address the row renders — and a screenshot of it — must not hold it.
        val shown = assertIs<WebPanelStatus.Running>(status("opencode")).url
        assertEquals("http://127.0.0.1:4096", shown)
    }

    @Test
    fun `the pairing request keeps the password out of every argv`() = runTest {
        val guest = FakeGuest().apply { pairAnswers += """{"code":"abc"}""" + "\n200" }
        val runtime = runtime(this, guest)
        runtime.startPanel("opencode")
        val password = runtime.password("opencode").orEmpty()

        runtime.link("opencode")

        val pair = guest.runs.single { it.contains("/api/pair") }
        assertFalse(pair.contains(password), "the guest's `ps` would show it: $pair")
        assertContains(pair, "POST")
        // `printf` is a shell builtin and curl reads its credentials from stdin:
        // neither a child of the shell nor curl's own argv holds the value.
        assertContains(pair, "printf 'user = \"opencode:%s\"\\n' \"\$OPENCODE_SERVER_PASSWORD\"")
        assertContains(pair, "curl -sS -K -")
        assertEquals(password, guest.runEnv["OPENCODE_SERVER_PASSWORD"], "the password rides in the environment")
    }

    @Test
    fun `a refused pairing request fails the panel instead of opening the bare address`() = runTest {
        val guest = FakeGuest().apply { pairAnswers += """{"message":"Unauthorized"}""" + "\n401" }
        val runtime = runtime(this, guest)
        runtime.startPanel("opencode")

        val link = runtime.link("opencode")

        assertNull(link, "a bare address would show v2's Connect-to-a-server screen")
        val failed = assertIs<WebPanelStatus.Failed>(status("opencode"))
        assertContains(failed.reason, "401")
        assertContains(failed.reason, "refused")
    }

    @Test
    fun `a pairing answer with no code in it fails with the reason`() = runTest {
        val guest = FakeGuest().apply { pairAnswers += "<html>hello</html>" + "\n200" }
        val runtime = runtime(this, guest)
        runtime.startPanel("opencode")

        assertNull(runtime.link("opencode"))
        val failed = assertIs<WebPanelStatus.Failed>(status("opencode"))
        assertContains(failed.reason, "no code")
    }

    @Test
    fun `two taps mint two different codes, because a code works once`() = runTest {
        val guest = FakeGuest().apply {
            pairAnswers += listOf(
                """{"code":"first","expires_in":300}""" + "\n200",
                """{"code":"second","expires_in":300}""" + "\n200",
            )
        }
        val runtime = runtime(this, guest)
        runtime.startPanel("opencode")

        val first = runtime.link("opencode")
        val second = runtime.link("opencode")

        assertNotEquals(first, second)
        assertContains(first.orEmpty(), "/auth/connect/first")
        assertContains(second.orEmpty(), "/auth/connect/second")
    }

    @Test
    fun `a code full of URL syntax is encoded instead of breaking the path`() {
        // One `/` or `?` in a code would end the path early and land on a 404.
        assertEquals(
            "http://127.0.0.1:4096/auth/connect/a%2Fb%3Fc",
            WebPanels.pairUrl(4096, "a/b?c"),
        )
        assertEquals(4096, WebPanels.portOf(WebPanels.pairUrl(4096, "a/b?c")))
    }

    @Test
    fun `only unreserved characters are left as they are`() {
        assertEquals("aZ0-._~", percentEncode("aZ0-._~"))
        assertEquals("%40%3A%2F%25%20%3F%23%5B%5D", percentEncode("@:/% ?#[]"))
        // Non-ASCII goes out as its UTF-8 bytes, not as the code point.
        assertEquals("%D0%BF", percentEncode("п"))
    }

    @Test
    fun `pi-web's link is the login form and carries no credentials`() {
        val pi = WebPanels.get(WebPanels.PI)!!

        val url = WebPanels.url(pi, 30141)
        assertEquals("http://127.0.0.1:30141/login", url)
        assertFalse(url.contains("@"), url)
        assertEquals(30141, WebPanels.portOf(url))
    }

    @Test
    fun `Hermes' link is the plain address, because the minted link is the sign-in`() {
        val hermes = WebPanels.get(WebPanels.HERMES)!!
        assertEquals("http://127.0.0.1:9120", WebPanels.url(hermes, 9120))
    }

    @Test
    fun `OpenCode's address is the plain address, because the sign-in is minted`() {
        val opencode = WebPanels.get(WebPanels.OPENCODE)!!
        assertEquals("http://127.0.0.1:4096", WebPanels.url(opencode, 4096))
    }

    @Test
    fun `opening pi-web hands the browser its login form`() = runTest {
        val guest = FakeGuest()
        val runtime = runtime(this, guest)
        runtime.startPanel("pi")
        val password = runtime.password("pi").orEmpty()
        assertTrue(password.isNotEmpty())

        val link = runtime.link("pi").orEmpty()
        assertEquals("http://127.0.0.1:30141/login", link)
        assertFalse(link.contains(password), "pi-web ignores URL credentials: $link")
    }

    @Test
    fun `a link for a panel that is not running is null, not a guess`() = runTest {
        val runtime = runtime(this, FakeGuest())
        assertNull(runtime.link("opencode"))
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
        val guest = FakeGuest()
        val runtime = runtime(this, guest)
        runtime.startPanel("opencode")
        val first = runtime.password("opencode")
        runtime.stopPanel("opencode")
        runtime.startPanel("opencode")
        val second = runtime.password("opencode")

        assertTrue(!first.isNullOrEmpty())
        assertEquals(first, second)
    }

    @Test
    fun `a panel that never started has no password yet`() = runTest {
        val runtime = runtime(this, FakeGuest())
        assertNull(runtime.password("opencode"))
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
    fun `Hermes gets a dashboard login, and its password goes into the environment`() = runTest {
        val guest = FakeGuest().apply { hermesLinks += listOf(SIGN_IN_LINK) }
        val runtime = runtime(this, guest)
        runtime.startPanel("hermes")

        val hermes = WebPanels.get(WebPanels.HERMES)!!
        val password = runtime.password("hermes").orEmpty()
        assertTrue(password.isNotEmpty(), "a gated panel with no password is a panel nobody can open")
        assertEquals(WebPanels.HERMES_USER, guest.runEnv[hermes.usernameEnv!!])
        assertEquals(password, guest.runEnv[hermes.passwordEnv!!])
        // The environment, not the command line: `ps` inside the sandbox is
        // readable by every process in it.
        assertFalse(guest.runs.last { it == LAUNCH_ONE_USE }.contains(password))
    }

    @Test
    fun `Hermes' password never reaches its config file`() = runTest {
        val guest = FakeGuest().apply { hermesLinks += listOf(SIGN_IN_LINK) }
        val runtime = runtime(this, guest)
        runtime.startPanel("hermes")
        val password = runtime.password("hermes").orEmpty()
        assertTrue(password.isNotEmpty())

        // The file Pots writes on every agent start, with the panel's port in it.
        val files = SandboxAgentConfig.filesFor(BuildAgents.HERMES, credentials, WebPanels.HERMES_PORT)
        val config = files.single { it.path == ".hermes/config.yaml" }.content
        assertFalse(config.contains(password), "the account leaked into config.yaml: $config")
        assertFalse(config.contains("basic_auth"), "the account belongs in the environment: $config")
        assertFalse(files.single { it.path == ".hermes/.env" }.content.contains(password))
    }

    // --- the log the row shows -------------------------------------------

    @Test
    fun `the row's log is masked, so a panel that echoes its environment leaks nothing`() = runTest {
        val guest = FakeGuest()
        val runtime = runtime(this, guest)
        runtime.startPanel("opencode")
        val password = runtime.password("opencode").orEmpty()
        assertTrue(password.isNotEmpty())
        // A panel that prints its environment, or a shell that fails with the
        // command line in the message, is the case this exists for.
        guest.log = "env: OPENCODE_SERVER_PASSWORD=$password\nlistening on 4096"

        val shown = runtime.panelLog("opencode")

        assertFalse(shown.contains(password), "the password reached the screen: $shown")
        assertContains(shown, "***")
        assertContains(shown, "listening on 4096")
    }

    @Test
    fun `a panel with no log file says so instead of throwing`() = runTest {
        val guest = FakeGuest().apply { log = WebPanelLog.NO_FILE }
        val runtime = runtime(this, guest)

        assertEquals(WebPanelLog.NO_FILE, runtime.panelLog("pi"))
    }

    @Test
    fun `a password cut by the tail boundary never comes back as a fragment`() = runTest {
        val guest = FakeGuest()
        val runtime = runtime(this, guest)
        runtime.startPanel("opencode")
        val password = runtime.password("opencode").orEmpty()
        assertTrue(password.isNotEmpty())
        // 13010 characters total, so the 8000-character tail starts at 5010 — ten
        // characters into the password, which begins at 5000. Masking after the
        // cut would leave the rest of it on screen, and `replace` would not find it.
        guest.log = "y".repeat(5_000) + password + "z".repeat(13_010 - 5_000 - password.length)

        val shown = runtime.panelLog("opencode")

        assertFalse(shown.contains(password.drop(10)), "a fragment of the password reached the screen")
        assertFalse(shown.contains(password))
        assertContains(shown, "***")
    }

    @Test
    fun `a failed start quotes one masked line, not the whole log`() = runTest {
        val password = "Xq7Rz2LmNp4Tv8Wk"
        val guest = FakeGuest().apply {
            listensAfterLaunch = false
            // A full tail of noise, and the password in the last thing it said.
            log = "y".repeat(WebPanelLog.TAIL_CHARS) +
                "\nenv: OPENCODE_SERVER_PASSWORD=$password\nport 4096 is taken"
        }
        val runtime = runtime(this, guest, prefs = { WebPanelPreferences(password = password) })

        runtime.startPanel("opencode")

        val failed = assertIs<WebPanelStatus.Failed>(status("opencode"))
        assertFalse(failed.reason.contains(password), "the password reached the status line: ${failed.reason}")
        assertContains(failed.reason, "port 4096 is taken")
        assertTrue(
            failed.reason.length < WebPanelLog.BRIEF_CHARS + 100,
            "the reason is the log, not a line: ${failed.reason.length} characters",
        )
    }

    @Test
    fun `an unknown panel has no log to show`() = runTest {
        val runtime = runtime(this, FakeGuest())
        assertEquals(WebPanelLog.NO_FILE, runtime.panelLog("claude-code"))
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
    fun `opening a one-use Hermes link hands the browser a fresh ticket, not the address`() = runTest {
        val guest = FakeGuest().apply {
            hermesLinks += listOf(
                ONE_USE_LINK.replace("TICKET", "start"),
                ONE_USE_LINK.replace("TICKET", "tap"),
            )
        }
        val runtime = runtime(this, guest)
        runtime.startPanel("hermes")
        val shown = assertIs<WebPanelStatus.Running>(status("hermes"))
        assertEquals(WebPanelOpen.MINTED_LINK, shown.open, "a loopback Hermes link signs in once")

        val link = runtime.link("hermes").orEmpty()

        // The address on the row cannot sign a browser in; the ticket can.
        assertContains(link, "ticket=tap")
        assertNotEquals(shown.url, link, "the browser gets the link, the row keeps the address")
    }

    @Test
    fun `every tap on a one-use link runs the launcher again instead of reusing the last link`() = runTest {
        val guest = FakeGuest().apply {
            hermesLinks += listOf(
                ONE_USE_LINK.replace("TICKET", "start"),
                ONE_USE_LINK.replace("TICKET", "first"),
                ONE_USE_LINK.replace("TICKET", "second"),
            )
        }
        val runtime = runtime(this, guest)
        runtime.startPanel("hermes")
        val mintedAtStart = guest.runs.count { it == LAUNCH_ONE_USE }

        val first = runtime.link("hermes").orEmpty()
        val second = runtime.link("hermes").orEmpty()

        assertEquals(mintedAtStart + 2, guest.runs.count { it == LAUNCH_ONE_USE }, "one launcher run per tap")
        assertContains(first, "ticket=first")
        assertContains(second, "ticket=second")
    }

    @Test
    fun `a minted ticket is kept nowhere but the browser handoff`() = runTest {
        val saved = mutableMapOf<String, WebPanelPreferences>()
        val guest = FakeGuest().apply {
            hermesLinks += listOf(ONE_USE_LINK.replace("TICKET", "start"), ONE_USE_LINK.replace("TICKET", "tap"))
        }
        val runtime = runtime(this, guest, saved = saved)
        runtime.startPanel("hermes")

        val link = runtime.link("hermes").orEmpty()

        val ticket = link.substringAfter("ticket=", "")
        assertEquals("tap", ticket)
        // A ticket in the published state or in settings would be a spent link on
        // the next screen that renders it — and on the next run.
        val published = published.values.joinToString("\n") { it.toString() }
        assertFalse(published.contains(ticket), published)
        assertFalse(saved.values.toString().contains(ticket), saved.values.toString())
        assertIs<WebPanelStatus.Running>(status("hermes"))
    }

    @Test
    fun `a tap that cannot mint a link says why on the panel`() = runTest {
        val guest = FakeGuest().apply {
            hermesLinks += listOf(ONE_USE_LINK.replace("TICKET", "start"))
            hermesFailure = "hermes webapp: error: the gateway did not answer within 5s"
        }
        val runtime = runtime(this, guest)
        runtime.startPanel("hermes")

        assertNull(runtime.link("hermes"))

        // Silence is the bug the owner sees: a tap that does nothing reads as a
        // dead button. The first line of the output is the reason.
        val failed = assertIs<WebPanelStatus.Failed>(status("hermes"))
        assertContains(failed.reason, "gateway did not answer")
    }

    @Test
    fun `a Hermes behind the dashboard sign-in shows the address it printed`() = runTest {
        val guest = FakeGuest().apply {
            hermesLinks += listOf("Open the Webapp and sign in with your dashboard account: https://pots.local:9449/")
        }
        val runtime = runtime(this, guest)

        runtime.startPanel("hermes")

        // Nothing to mint here: the address is permanent, and the row has to say
        // an account is needed rather than pretend the panel signs itself in.
        val running = assertIs<WebPanelStatus.Running>(status("hermes"))
        assertEquals("https://pots.local:9449/", running.url)
        assertEquals(WebPanelOpen.DASHBOARD_SIGN_IN, running.open)
        assertEquals(1, guest.runs.count { it == LAUNCH_ONE_USE }, "the start asks for the address once")

        // A tap hands over that same address and mints nothing: there is no ticket.
        assertEquals("https://pots.local:9449/", runtime.link("hermes"))
        assertEquals(1, guest.runs.count { it == LAUNCH_ONE_USE }, "a sign-in address is never re-minted")
    }

    @Test
    fun `the bundle check wants the assets directory, not only the page`() {
        // index.html alone passes while `assets/` is missing, and the page then
        // opens blank — the owner's "Web UI shows a link but nothing opens".
        assertContains(WebPanels.HERMES_BUNDLE_CHECK, "-f ${WebPanels.HERMES_BUNDLE_DIR}/index.html")
        assertContains(WebPanels.HERMES_BUNDLE_CHECK, "-d ${WebPanels.HERMES_BUNDLE_DIR}/assets")
    }

    @Test
    fun `a running Hermes panel is read from its own status, not from a pid`() = runTest {
        val guest = FakeGuest().apply {
            status = "Serving at http://127.0.0.1:9120"
        }
        val runtime = runtime(this, guest)
        runtime.refreshPanels()
        // `--status` reports the loopback dial origin even though the bind is every
        // interface, and Pots writes that bind: the panel is behind the sign-in
        // gate, so the row says an account is needed.
        assertEquals(
            WebPanelStatus.Running("http://127.0.0.1:9120", WebPanelOpen.DASHBOARD_SIGN_IN),
            status("hermes"),
        )
    }

    @Test
    fun `a Hermes serving a published address asks for the dashboard account`() = runTest {
        val guest = FakeGuest().apply {
            status = "Serving at https://pots.local:9449"
        }
        val runtime = runtime(this, guest)
        runtime.refreshPanels()

        val running = assertIs<WebPanelStatus.Running>(status("hermes"))
        assertEquals(WebPanelOpen.DASHBOARD_SIGN_IN, running.open, "no ticket is minted for a published address")
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
    fun `a Hermes whose webapp was never built says so instead of opening a blank page`() = runTest {
        val guest = FakeGuest().apply { hasBundle = false }
        val runtime = runtime(this, guest)
        runtime.startPanel("hermes")

        val failed = assertIs<WebPanelStatus.Failed>(status("hermes"))
        assertContains(failed.reason, "not built")
        assertContains(failed.reason, "--build-only")
        assertFalse(guest.runs.any { it.startsWith("hermes webapp --no-open") }, "no link on a blank page")
    }

    @Test
    fun `the shipped renderer is unpacked once and named for the launcher`() = runTest {
        val guest = FakeGuest().apply {
            hermesPin = BUNDLE_PIN
            hermesHead = BUNDLE_PIN
            hermesLinks += listOf(ONE_USE_LINK.replace("TICKET", "start"))
        }
        val runtime = runtime(this, guest)

        runtime.startPanel("hermes")

        // Naming the bundle is what stops `hermes webapp` from building one, and the
        // build is the step that cannot run on a phone at all.
        assertEquals(1, guest.runs.count { it == HermesWebapp.unpack(BUNDLE_PIN) })
        assertEquals(HermesWebapp.DIST_DIR, guest.runEnv[HermesWebapp.DIST_ENV])
        assertIs<WebPanelStatus.Running>(status("hermes"))
    }

    @Test
    fun `a bundle already unpacked for this Hermes is not unpacked again`() = runTest {
        val guest = FakeGuest().apply {
            hermesPin = BUNDLE_PIN
            hermesHead = BUNDLE_PIN
            hermesUnpacked = true
            hermesLinks += listOf(ONE_USE_LINK.replace("TICKET", "start"))
        }
        val runtime = runtime(this, guest)

        runtime.startPanel("hermes")

        assertFalse(guest.runs.any { it == HermesWebapp.unpack(BUNDLE_PIN) }, "the unpack is once per bundle")
        assertEquals(HermesWebapp.DIST_DIR, guest.runEnv[HermesWebapp.DIST_ENV])
    }

    @Test
    fun `a sandbox running another Hermes refuses the panel and names both commits`() = runTest {
        val guest = FakeGuest().apply {
            hermesPin = BUNDLE_PIN
            hermesHead = OTHER_HERMES
        }
        val runtime = runtime(this, guest)

        runtime.startPanel("hermes")

        val failed = assertIs<WebPanelStatus.Failed>(status("hermes"))
        assertContains(failed.reason, BUNDLE_PIN.take(8))
        assertContains(failed.reason, OTHER_HERMES.take(8))
        assertContains(failed.reason, "reinstall Hermes")
        // Nothing is served mismatched — and nothing is unpacked for a backend it
        // was not built for either.
        assertFalse(guest.runs.any { it.startsWith(LAUNCH_ONE_USE) }, "no panel on a version it was not built for")
        assertFalse(guest.runs.any { it == HermesWebapp.unpack(BUNDLE_PIN) })
    }

    @Test
    fun `a checkout that cannot be read says so instead of serving the bundle`() = runTest {
        val guest = FakeGuest().apply { hermesPin = BUNDLE_PIN }
        val runtime = runtime(this, guest)

        runtime.startPanel("hermes")

        val failed = assertIs<WebPanelStatus.Failed>(status("hermes"))
        assertContains(failed.reason, "could not be read")
        assertFalse(guest.runs.any { it.startsWith(LAUNCH_ONE_USE) })
    }

    @Test
    fun `a bundle that will not unpack says why`() = runTest {
        val guest = FakeGuest().apply {
            hermesPin = BUNDLE_PIN
            hermesHead = BUNDLE_PIN
            hermesUnpacks = false
        }
        val runtime = runtime(this, guest)

        runtime.startPanel("hermes")

        val failed = assertIs<WebPanelStatus.Failed>(status("hermes"))
        assertContains(failed.reason, "could not unpack")
        assertContains(failed.reason, "xz")
        assertFalse(guest.runs.any { it.startsWith(LAUNCH_ONE_USE) })
    }

    @Test
    fun `Hermes' gateway is a background session, and the link comes after it listens`() = runTest {
        val guest = FakeGuest().apply { hermesLinks += listOf(SIGN_IN_LINK) }
        val runtime = runtime(this, guest)

        runtime.startPanel("hermes")

        // The gateway is what is launched, on the port the config was written
        // with — and the launcher only mints the address out of it, so it runs
        // after the port answers, never before.
        assertEquals(listOf(WebPanels.HERMES_SERVER), guest.commands)
        assertEquals(9120, guest.port)
        val hermes = WebPanels.get(WebPanels.HERMES)!!
        assertEquals(WebPanels.HERMES_USER, guest.env[hermes.usernameEnv!!])
        // The launcher runs, and not as the first thing: the port has to answer
        // before there is a gateway to mint an address out of.
        assertTrue(guest.runs.indexOfFirst { it.startsWith(LAUNCH_ONE_USE) } > 0, guest.runs.toString())
        assertEquals(
            WebPanelStatus.Running("http://127.0.0.1:9120", WebPanelOpen.DASHBOARD_SIGN_IN),
            status("hermes"),
        )
    }

    @Test
    fun `a Hermes whose gateway session cannot start says so and mints nothing`() = runTest {
        val guest = FakeGuest().apply { launchFails = true }
        val runtime = runtime(this, guest)

        runtime.startPanel("hermes")

        val failed = assertIs<WebPanelStatus.Failed>(status("hermes"))
        assertContains(failed.reason, "did not start")
        assertFalse(guest.runs.any { it.startsWith(LAUNCH_ONE_USE) }, "no link without a gateway")
    }

    @Test
    fun `a Hermes gateway that never listens fails with the last line of its log`() = runTest {
        val guest = FakeGuest().apply {
            listensAfterLaunch = false
            log = "pots: hermes gateway run\nerror: gateway exited with status 1"
        }
        val runtime = runtime(this, guest)

        runtime.startPanel("hermes")

        val failed = assertIs<WebPanelStatus.Failed>(status("hermes"))
        assertContains(failed.reason, "is not listening on 9120")
        // The log is what explains a gateway that refused to come up.
        assertContains(failed.reason, "gateway exited with status 1")
        assertFalse(guest.runs.any { it.startsWith(LAUNCH_ONE_USE) }, "no link without a gateway")
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
        assertEquals(
            WebPanelStatus.Running("http://127.0.0.1:9121", WebPanelOpen.MINTED_LINK),
            status("hermes"),
        )
    }

    @Test
    fun `the webapp block is written at the chosen port`() {
        val config = SandboxAgentConfig.withWebappPort("model:\n  default: 'x'\n", 9121)
        assertContains(config, "webapp:")
        assertContains(config, "  host: 0.0.0.0")
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

    @Test
    fun `rewriting the webapp port does not pile up the block's comment`() {
        val once = SandboxAgentConfig.withWebappPort("model:\n  default: 'x'\n", 9121)
        val twice = SandboxAgentConfig.withWebappPort(once, 9122)

        assertEquals(1, twice.lines().count { it.startsWith("# Written by Pots") }, twice)
    }

    // --- the Hermes panel on the LAN --------------------------------------

    // The panel binds every interface, so another device on the network can open
    // it while the phone sits on a charger. Hermes gates any non-loopback bind,
    // and Pots hands it an account: without one the gate has no provider to ask,
    // and the page says so instead of showing a login form.

    @Test
    fun `the Hermes webapp block binds every interface, not loopback`() {
        val config = SandboxAgentConfig.webappConfig(9120)
        assertContains(config, "  host: 0.0.0.0")
        assertFalse(config.contains("127.0.0.1"), config)
    }

    @Test
    fun `the row shows the LAN address when the phone is on a network`() = runTest {
        val guest = FakeGuest().apply { hermesLinks += listOf(SIGN_IN_LINK) }
        val runtime = runtime(this, guest, lanIp = LAN_IP)
        runtime.startPanel("hermes")

        val running = assertIs<WebPanelStatus.Running>(status("hermes"))
        assertEquals("http://$LAN_IP:9120", running.url)
        assertEquals(WebPanelOpen.DASHBOARD_SIGN_IN, running.open)
        // Opening the row hands the browser the same address — the one another
        // device dials, not the loopback one Hermes printed.
        assertEquals("http://$LAN_IP:9120", runtime.link("hermes"))
    }

    @Test
    fun `without a LAN address the row falls back to loopback, and says which`() = runTest {
        val guest = FakeGuest().apply { hermesLinks += listOf(SIGN_IN_LINK) }
        val runtime = runtime(this, guest)
        runtime.startPanel("hermes")

        val running = assertIs<WebPanelStatus.Running>(status("hermes"))
        assertEquals("http://127.0.0.1:9120", running.url)
        // The row picks its wording from exactly this: a loopback address opens on
        // this phone alone, a LAN one from any device on the network.
        assertTrue(WebPanels.isLoopback(running.url))
        assertNotEquals(WebPanels.isLoopback("http://$LAN_IP:9120"), WebPanels.isLoopback(running.url))
    }

    @Test
    fun `a printed loopback address is replaced by the phone's LAN address`() {
        assertEquals("http://$LAN_IP:9120", WebPanels.hermesAddress("http://127.0.0.1:9120/", 9120, LAN_IP))
        // A published address is reachable as it is: kept as printed.
        assertEquals(
            "https://pots.local:9449/",
            WebPanels.hermesAddress("https://pots.local:9449/", 9449, LAN_IP),
        )
        assertEquals("http://127.0.0.1:9120", WebPanels.hermesAddress("http://127.0.0.1:9120/", 9120, null))
    }

    @Test
    fun `a Hermes whose login plugin is still off fails instead of serving a dead gate`() = runTest {
        val guest = FakeGuest().apply {
            hermesLinks += listOf(SIGN_IN_LINK)
            authPluginOff = true
        }
        val runtime = runtime(this, guest)
        runtime.startPanel("hermes")

        val failed = assertIs<WebPanelStatus.Failed>(status("hermes"))
        assertContains(failed.reason, "plugins.disabled")
        assertFalse(
            guest.runs.any { it == LAUNCH_ONE_USE },
            "no panel behind a gate with no provider to ask",
        )
    }

    @Test
    fun `the basic auth plugin is dropped from plugins disabled, and the rest is kept`() {
        val yaml = """
            plugins:
              disabled:
                - web/exa
                - basic
                - dashboard_auth/basic
              enabled:
                - web/firecrawl
            model:
              default: 'x'
        """.trimIndent() + "\n"

        val fixed = SandboxAgentConfig.withBasicAuthPluginEnabled(yaml)

        assertEquals(setOf("web/exa"), SandboxAgentConfig.disabledPlugins(fixed))
        assertContains(fixed, "    - web/firecrawl", message = "the enabled list is somebody else's")
        assertContains(fixed, "  default: 'x'")
        assertEquals(fixed, SandboxAgentConfig.withBasicAuthPluginEnabled(fixed), "idempotent")
    }

    // --- helpers ----------------------------------------------------------

    /** What the runtime last published; one runtime per test, so one map is enough. */
    private var published: ImmutableMap<String, WebPanelState> = persistentMapOf()

    private fun status(panelId: String): WebPanelStatus? = published[panelId]?.status

    private fun runtime(
        scope: CoroutineScope,
        guest: WebPanelGuest,
        installed: Set<String> = BuildAgents.all.map { it.id }.toSet(),
        prefs: ((String) -> WebPanelPreferences)? = null,
        /** The settings the runtime writes to — watched by the tests that prove nothing leaks into them. */
        saved: MutableMap<String, WebPanelPreferences> = mutableMapOf(),
        /** The phone's own address in the local network, or null when it is on none. */
        lanIp: String? = null,
    ): WebPanelRuntime {
        // Settings live here, the way they live in the app: a password written on
        // one start has to be readable on the next read, or "generated once"
        // cannot be told from "generated every time".
        return WebPanelRuntime(
            guest = guest,
            prefs = { id -> prefs?.invoke(id) ?: saved[id] ?: WebPanelPreferences() },
            savePrefs = { id, preferences -> saved[id] = preferences },
            installed = { installed },
            lanAddress = { lanIp },
            publish = { panels -> published = panels },
            scope = scope,
            startTimeoutSeconds = 5,
        )
    }

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

        /** When true, [launch] answers false the way a proot that would not start does. */
        var launchFails = false
        var ignoreSignals = false
        var hasWebapp = true
        var hasBundle = true

        /** The pin this build ships, or null for a build with no bundle (a developer build). */
        var hermesPin: String? = null

        /** The commit the sandbox's Hermes checkout is on; null for one that cannot be read. */
        var hermesHead: String? = null

        /** Whether the shipped bundle is already unpacked for [hermesPin]. */
        var hermesUnpacked = false

        /** When false, unpacking the shipped archive fails the way a missing xz does. */
        var hermesUnpacks = true

        /** What the launcher prints once its queued links run out — a failed tap, in words. */
        var hermesFailure: String? = null

        /** When false, every probe fails the way a shell without `/dev/tcp` does. */
        var probeAnswers = true
        var status = ""
        var log = ""
        var forgotten = false

        /** The environment of the last launch — where a panel's password must be. */
        var env: Map<String, String> = emptyMap()

        /** What successive `POST /api/pair` calls answer; the last one repeats. */
        val pairAnswers = mutableListOf<String>()

        /** The environment of the last `run` — where the account and pairing password must be. */
        var runEnv: Map<String, String> = emptyMap()

        /** Whether Hermes' config still lists the dashboard login plugin as off. */
        var authPluginOff = false

        override fun run(command: String, timeoutSeconds: Long, env: Map<String, String>): WebPanelGuest.Output {
            runs += command
            if (env.isNotEmpty()) runEnv = env
            return when {
                command.contains("/api/pair") -> {
                    val answer = if (pairAnswers.size > 1) pairAnswers.removeAt(0) else pairAnswers.firstOrNull()
                    WebPanelGuest.Output(true, answer.orEmpty())
                }
                command == WebPanels.HERMES_HELP -> WebPanelGuest.Output(hasWebapp, "usage: hermes webapp")
                command == WebPanels.HERMES_BUNDLE_CHECK -> WebPanelGuest.Output(hasBundle)
                command == HermesWebapp.headCommand(WebPanels.HERMES_CHECKOUT) ->
                    WebPanelGuest.Output(hermesHead != null, hermesHead.orEmpty())
                command == HermesWebapp.check(hermesPin.orEmpty()) -> WebPanelGuest.Output(hermesUnpacked)
                command == HermesWebapp.unpack(hermesPin.orEmpty()) -> {
                    if (hermesUnpacks) hermesUnpacked = true
                    WebPanelGuest.Output(hermesUnpacks, if (hermesUnpacks) "" else "tar: xz: Cannot exec")
                }
                command.startsWith(LAUNCH_ONE_USE) -> {
                    val queued = hermesLinks.removeFirstOrNull()
                    val failure = hermesFailure
                    when {
                        queued != null -> WebPanelGuest.Output(true, queued)
                        failure != null -> WebPanelGuest.Output(false, failure)
                        else -> WebPanelGuest.Output(true, "")
                    }
                }
                command == "hermes webapp --status" -> WebPanelGuest.Output(status.isNotEmpty(), status)
                command.startsWith("/bin/bash -c 'i=0; while") -> WebPanelGuest.Output(listening.isNotEmpty())
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
            // A sandbox that cannot start the process at all: nothing is recorded,
            // nothing listens.
            if (launchFails) return false
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

        override fun portFree(port: Int): Boolean = probeAnswers && port !in listening && port !in busy

        override fun forget(panel: WebPanel) {
            forgotten = true
            pid = null
            port = null
        }

        override fun logText(panel: WebPanel): String = log

        override fun stageHermesBundle(): String? = hermesPin

        override fun authPluginDisabled(panel: WebPanel): Boolean = authPluginOff
    }
}

/** What `hermes webapp --no-open` prints for the private launch, with its ticket left as [TICKET]. */
private const val ONE_USE_LINK =
    "Open this link in your browser within 60 seconds. It works once and signs\n" +
        "the browser in as you, so keep it private:\n" +
        "  http://127.0.0.1:9120/?ticket=TICKET"

private const val LAUNCH_ONE_USE = "hermes webapp --no-open"

/** The hermes-agent commit the tests pretend this app's panel was built from. */
private const val BUNDLE_PIN = "caafe5389ae585ef77387a962aee05b1bcf17029"

/** Another commit — a sandbox whose Hermes moved on after the panel was built. */
private const val OTHER_HERMES = "0123456789abcdef0123456789abcdef01234567"

/** What `hermes webapp --no-open` prints behind the dashboard's sign-in, verbatim from `webapp_launcher`. */
private const val SIGN_IN_LINK =
    "Open the Webapp and sign in with your dashboard account: http://127.0.0.1:9120/"

/** The phone's own address on the network the tests pretend to be on. */
private const val LAN_IP = "192.168.1.50"
