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

    // --- links ------------------------------------------------------------

    // Only OpenCode takes credentials in the URL: it asks for HTTP Basic on every
    // request, HTML included. pi-web's HTML wants a session cookie, so a password
    // in its URL would be ignored *and* left sitting in the address bar; Hermes
    // signs a browser in with the one-use link `link()` mints instead.

    @Test
    fun `OpenCode's link carries the password in its userinfo`() {
        val opencode = WebPanels.get(WebPanels.OPENCODE)!!
        assertEquals(
            "http://opencode:hunter2@127.0.0.1:4096/",
            WebPanels.url(opencode, 4096, "hunter2"),
        )
    }

    @Test
    fun `a password full of URL syntax is encoded instead of breaking the link`() {
        val opencode = WebPanels.get(WebPanels.OPENCODE)!!
        val password = "a@b:c/d%e f"

        val url = WebPanels.url(opencode, 4096, password)
        assertEquals("http://opencode:a%40b%3Ac%2Fd%25e%20f@127.0.0.1:4096/", url)
        // The raw characters would have ended the userinfo early: `@` would make
        // the browser dial `b:c` as the host, and the panel would come up
        // unauthenticated with nothing saying why.
        assertFalse(url.contains("b:c"), url)
        assertEquals(4096, WebPanels.portOf(url))
    }

    @Test
    fun `only unreserved characters are left as they are`() {
        assertEquals("aZ0-._~", percentEncode("aZ0-._~"))
        assertEquals("%40%3A%2F%25%20%3F%23%5B%5D", percentEncode("@:/% ?#[]"))
        // Non-ASCII goes out as its UTF-8 bytes, not as the code point.
        assertEquals("%D0%BF", percentEncode("п"))
    }

    @Test
    fun `a generated password needs no encoding, and survives the URL intact`() {
        val password = WebPanelPasswords.generate(Random(11))
        assertEquals(password, percentEncode(password))

        val opencode = WebPanels.get(WebPanels.OPENCODE)!!
        val url = WebPanels.url(opencode, 4096, password)
        assertContains(url, "opencode:$password@127.0.0.1:4096/")
        assertEquals(4096, WebPanels.portOf(url))
    }

    @Test
    fun `pi-web's link is the login form and carries no credentials`() {
        val pi = WebPanels.get(WebPanels.PI)!!

        val url = WebPanels.url(pi, 30141, "hunter2")
        assertEquals("http://127.0.0.1:30141/login", url)
        assertFalse(url.contains("hunter2"), "credentials there are ignored and only leak: $url")
        assertFalse(url.contains("@"), url)
        assertEquals(30141, WebPanels.portOf(url))
    }

    @Test
    fun `Hermes' link is the plain address, because the minted link is the sign-in`() {
        val hermes = WebPanels.get(WebPanels.HERMES)!!
        assertEquals("http://127.0.0.1:9120", WebPanels.url(hermes, 9120))
    }

    @Test
    fun `a panel with no password yet gets the plain address, not a broken userinfo`() {
        val opencode = WebPanels.get(WebPanels.OPENCODE)!!

        assertEquals("http://127.0.0.1:4096", WebPanels.url(opencode, 4096, null))
        assertEquals("http://127.0.0.1:4096", WebPanels.url(opencode, 4096, ""))
    }

    @Test
    fun `opening OpenCode hands the browser the password, while the row shows none`() = runTest {
        val guest = FakeGuest()
        val runtime = runtime(this, guest)
        runtime.startPanel("opencode")
        val password = runtime.password("opencode").orEmpty()

        val link = runtime.link("opencode").orEmpty()
        assertContains(link, "opencode:$password@127.0.0.1:4096/")
        // The address the row renders — and a screenshot of it — must not hold it.
        val shown = assertIs<WebPanelStatus.Running>(status("opencode")).url
        assertFalse(shown.contains(password), shown)
        assertEquals("http://127.0.0.1:4096", shown)
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

    // --- the dashboard login ----------------------------------------------

    @Test
    fun `Hermes' config carries a dashboard login, so no one-use link is needed`() {
        val login = HermesWebappLogin(username = "pots", password = "typing-this-once", secret = "ab".repeat(32))
        val config = SandboxAgentConfig.filesFor(BuildAgents.HERMES, credentials(), 9120, login)
            .single { it.path == ".hermes/config.yaml" }.content

        assertContains(config, "dashboard:")
        assertContains(config, "  basic_auth:")
        assertContains(config, "    username: 'pots'")
        assertContains(config, "    password: 'typing-this-once'")
        assertContains(config, "    secret: '${"ab".repeat(32)}'")
        // The panel block is still there: the login is written beside it, not over it.
        assertContains(config, "webapp:")
        assertContains(config, "  host: 127.0.0.1")
    }

    @Test
    fun `the session secret is a real random value, never shell text`() {
        val login = HermesWebappLogin.mint(Random(11))
        // A `$(openssl rand …)` left in the file is detected by Hermes' provider,
        // logged as an error and replaced by a per-process key — which signs the
        // owner out on every gateway restart.
        for (marker in listOf("\$(", "`", "openssl rand")) {
            assertFalse(login.secret.contains(marker), "shell text in ${login.secret}")
        }
        assertEquals(HermesWebappLogin.SECRET_HEX_CHARS, login.secret.length)
        // Hex in either case: Hermes decodes it with `bytes.fromhex`, which takes
        // both — what matters is that the value is bytes, not a sentence.
        assertTrue(login.secret.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }, login.secret)
        assertNotEquals(login.secret, HermesWebappLogin.mint(Random(12)).secret)
    }

    @Test
    fun `Pots never writes a disabled-plugins list, which would switch the login off`() {
        val config = SandboxAgentConfig.filesFor(BuildAgents.HERMES, credentials(), 9120, HermesWebappLogin.mint())
            .single { it.path == ".hermes/config.yaml" }.content

        // `plugins.disabled: [basic]` next to a configured basic_auth makes the
        // gateway refuse to bind, so the list is not written at all.
        assertFalse(config.contains("plugins:"), config)
        assertFalse(config.contains("disabled:"), config)
    }

    @Test
    fun `the bundle check wants the assets directory, not only the page`() {
        // index.html alone passes while `assets/` is missing, and the page then
        // opens blank — the owner's "Web UI shows a link but nothing opens".
        assertContains(WebPanels.HERMES_BUNDLE_CHECK, "-f ${WebPanels.HERMES_BUNDLE_DIR}/index.html")
        assertContains(WebPanels.HERMES_BUNDLE_CHECK, "-d ${WebPanels.HERMES_BUNDLE_DIR}/assets")
    }

    @Test
    fun `the dashboard login is minted once and survives an app restart`() = runTest {
        val saved = mutableMapOf<String, WebPanelPreferences>()

        val first = runtime(this, FakeGuest(), saved = saved).hermesLogin()
        val again = runtime(this, FakeGuest(), saved = saved).hermesLogin()

        // Regenerating would sign out a tab that is already open.
        assertEquals(first, again)
        assertEquals(HermesWebappLogin.USERNAME, first.username)
        assertTrue(first.password.isNotEmpty())
        assertTrue(first.secret.isNotEmpty())
    }

    @Test
    fun `the login and password reach the panel's own row`() = runTest {
        val runtime = runtime(this, FakeGuest())
        val login = runtime.hermesLogin()

        assertTrue(login.username.isNotEmpty())
        assertTrue(login.password.length >= 8, "long enough to not be guessable, short enough to type")
    }

    @Test
    fun `a running Hermes panel is read from its own status, not from a pid`() = runTest {
        val guest = FakeGuest().apply {
            status = "Serving at http://127.0.0.1:9120"
        }
        val runtime = runtime(this, guest)
        runtime.refreshPanels()
        // A loopback address is the private launch: the row shows it, and the open
        // has to mint a ticket of its own.
        assertEquals(
            WebPanelStatus.Running("http://127.0.0.1:9120", WebPanelOpen.MINTED_LINK),
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

    @Test
    fun `rewriting the webapp port does not pile up the block's comment`() {
        val once = SandboxAgentConfig.withWebappPort("model:\n  default: 'x'\n", 9121)
        val twice = SandboxAgentConfig.withWebappPort(once, 9122)

        assertEquals(1, twice.lines().count { it.startsWith("# Written by Pots") }, twice)
    }

    // --- helpers ----------------------------------------------------------

    /** What the runtime last published; one runtime per test, so one map is enough. */
    private var published: ImmutableMap<String, WebPanelState> = persistentMapOf()

    private fun status(panelId: String): WebPanelStatus? = published[panelId]?.status

    /** What a provider looks like to the config writers — nothing here is real. */
    private fun credentials() = SandboxAgentCredentials(
        providerId = "plusvibe",
        providerName = "PlusVibe",
        model = "qwen3.8-flash",
        apiKey = "sk-test-key",
        endpoint = "https://plusvibeapi.ru/v1",
    )

    private fun runtime(
        scope: CoroutineScope,
        guest: WebPanelGuest,
        installed: Set<String> = BuildAgents.all.map { it.id }.toSet(),
        prefs: ((String) -> WebPanelPreferences)? = null,
        /** The settings the runtime writes to — watched by the tests that prove nothing leaks into them. */
        saved: MutableMap<String, WebPanelPreferences> = mutableMapOf(),
    ): WebPanelRuntime {
        // Settings live here, the way they live in the app: a password written on
        // one start has to be readable on the next read, or "generated once"
        // cannot be told from "generated every time".
        return WebPanelRuntime(
            guest = guest,
            prefs = { id -> prefs?.invoke(id) ?: saved[id] ?: WebPanelPreferences() },
            savePrefs = { id, preferences -> saved[id] = preferences },
            installed = { installed },
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
        var ignoreSignals = false
        var hasWebapp = true
        var hasBundle = true

        /** What the launcher prints once its queued links run out — a failed tap, in words. */
        var hermesFailure: String? = null

        /** When false, every probe fails the way a shell without `/dev/tcp` does. */
        var probeAnswers = true
        var status = ""
        var log = ""
        var forgotten = false

        /** The environment of the last launch — where a panel's password must be. */
        var env: Map<String, String> = emptyMap()

        override fun run(command: String, timeoutSeconds: Long): WebPanelGuest.Output {
            runs += command
            return when {
                command == WebPanels.HERMES_HELP -> WebPanelGuest.Output(hasWebapp, "usage: hermes webapp")
                command == WebPanels.HERMES_BUNDLE_CHECK -> WebPanelGuest.Output(hasBundle)
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

        override fun logTail(panel: WebPanel): String = log
    }
}

/** What `hermes webapp --no-open` prints for the private launch, with its ticket left as [TICKET]. */
private const val ONE_USE_LINK =
    "Open this link in your browser within 60 seconds. It works once and signs\n" +
        "the browser in as you, so keep it private:\n" +
        "  http://127.0.0.1:9120/?ticket=TICKET"

private const val LAUNCH_ONE_USE = "hermes webapp --no-open"
