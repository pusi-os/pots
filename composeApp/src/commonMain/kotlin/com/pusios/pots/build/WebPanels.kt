package com.pusios.pots.build

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlin.random.Random

/**
 * A coding agent's own web UI, as something Pots Build can start and stop.
 *
 * OpenCode and Pi listen on loopback. proot creates no network namespace, so a
 * server bound to `127.0.0.1` inside the sandbox *is* the phone's loopback and
 * Chrome reaches it.
 *
 * Hermes is the exception, and deliberately so: it binds every interface, so the
 * owner can open it from another device on the same network while the phone sits
 * on a charger. A non-loopback bind turns Hermes' sign-in gate on, and Pots gives
 * it a dashboard account ([HERMES_USER] plus a generated password) — a gated panel
 * with no account is not an open panel, it is one that cannot be opened at all.
 *
 * The pid, port and log files live under the guest's `/tmp`, which is a host
 * directory bind-mounted in — so they survive an app restart and the host can
 * read them without starting a process.
 */
@Immutable
data class WebPanel(
    val id: String,
    /** The agent whose install brings this panel; a panel exists only with it. */
    val agentId: String,
    val title: String,
    /** Tried first; a busy port moves the panel to the next free one. */
    val preferredPort: Int,
    /** Env var carrying the panel's password, or null for a panel that asks for none. */
    val passwordEnv: String?,
    /** Env var carrying the panel's login, for the panels that ask for one by name. */
    val usernameEnv: String? = null,
    /**
     * The login the panel asks for, as its row shows it — a property of the panel,
     * not of the one agent that happens to be first to want one. Null for a panel
     * that asks for a password alone (pi-web) or for neither.
     */
    val username: String? = null,
) {
    val pidFile: String get() = "pots-web-$id.pid"
    val portFile: String get() = "pots-web-$id.port"
    val logFile: String get() = "pots-web-$id.log"
}

/**
 * The three panels, declared once. The command line of each lives in
 * [WebPanels.command] and nowhere else, so a flag the vendor renames is one edit.
 */
object WebPanels {

    const val OPENCODE = "opencode"
    const val PI = "pi"
    const val HERMES = "hermes"

    /**
     * Where Pi's panel version sits in [PotsBuildState.agentVersions][PotsBuildState].
     * Not an agent id — `pi` is taken by Pi itself — but the same map, so there is
     * still one place a version is read from.
     */
    const val PI_WEB_VERSION_KEY = "pi-web"

    /** How far past the preferred port a busy one may push a panel. */
    const val PORT_ATTEMPTS = 10

    /**
     * Hermes' preferred panel port. Also the default `webapp.port` its config is
     * written with, so the two cannot drift apart.
     */
    const val HERMES_PORT = 9120

    val all: ImmutableList<WebPanel> = persistentListOf(
        WebPanel(
            id = OPENCODE,
            agentId = BuildAgents.OPENCODE,
            title = "OpenCode",
            // `opencode serve` defaults to port 0 — a random one — so it is always named.
            preferredPort = 4096,
            passwordEnv = "OPENCODE_SERVER_PASSWORD",
            // v2 takes Basic credentials on its API and the user name is fixed.
            username = OPENCODE_USER,
        ),
        WebPanel(
            id = PI,
            agentId = BuildAgents.PI,
            title = "Pi",
            preferredPort = 30141,
            passwordEnv = "PI_WEB_PASSWORD",
        ),
        WebPanel(
            id = HERMES,
            agentId = BuildAgents.HERMES,
            title = "Hermes",
            // Read from `webapp.port` in the Hermes config, written by [SandboxAgentConfig].
            preferredPort = HERMES_PORT,
            // The panel is on the LAN, so Hermes' sign-in gate is on, and these are
            // the account it asks for. Passed in the launcher's environment — the
            // config file is rewritten on every agent start and must not carry it.
            passwordEnv = "HERMES_DASHBOARD_BASIC_AUTH_PASSWORD",
            usernameEnv = "HERMES_DASHBOARD_BASIC_AUTH_USERNAME",
            username = HERMES_USER,
        ),
    )

    fun get(id: String?): WebPanel? = all.firstOrNull { it.id == id }

    /** The panel an installed agent brings, if it brings one. */
    fun forAgent(agentId: String?): WebPanel? = all.firstOrNull { it.agentId == agentId }

    /**
     * The address the row shows: where the panel listens, and nothing more.
     *
     * Kept free of credentials on purpose — this is the string that is rendered
     * on screen, screenshotted and read aloud, so the password never rides in it.
     */
    fun address(port: Int): String = "http://127.0.0.1:$port"

    /**
     * The same panel, dialled from another device: [lanIp] is the phone's own
     * address in the local network.
     */
    fun lanAddress(port: Int, lanIp: String): String = "http://$lanIp:$port"

    /**
     * The address the Hermes row shows, given what its launcher printed.
     *
     * A published address — `webapp.public_url`, or an origin Web Access
     * registered with Nous — is already reachable from other devices and is kept
     * as printed. The loopback one is not: Pots binds the panel to the LAN, so the
     * row gets the phone's LAN address, or loopback (and the row says the panel is
     * reachable only here) when the phone is on no network at all.
     */
    fun hermesAddress(printed: String?, port: Int, lanIp: String?): String = when {
        printed != null && !isLoopback(printed) -> printed
        lanIp.isNullOrBlank() -> address(port)
        else -> lanAddress(port, lanIp)
    }

    /**
     * The URL handed to the browser for the panels that need nothing minted.
     *
     * None of them takes credentials in a URL. pi-web guards its API only — its
     * HTML wants a session cookie — so its URL points at the login form instead;
     * OpenCode 2 answers `/` with a client that asks for a pairing link (see
     * [pairCommand]) and takes nothing from the address, so its URL is the plain
     * address; Hermes needs neither, because the link `link()` mints *is* the
     * sign-in.
     */
    fun url(panel: WebPanel, port: Int): String = when (panel.id) {
        OPENCODE -> address(port)
        PI -> "${address(port)}/login"
        // The gate asks for the account, not for anything in the URL: the address
        // alone is what the row hands over, and [hermesAddress] picks the LAN one.
        HERMES -> address(port)
        else -> error("no URL for panel ${panel.id}")
    }

    /**
     * How a browser gets into [panel] once it is up — the panel whose address
     * alone is not the whole answer says so here, once, for [WebPanelRuntime].
     */
    fun openKind(panel: WebPanel): WebPanelOpen = when (panel.id) {
        OPENCODE -> WebPanelOpen.PAIRING_CODE
        else -> WebPanelOpen.DIRECT
    }

    /**
     * The user half of OpenCode's Basic credentials. v2 accepts no other name:
     * `OPENCODE_SERVER_USERNAME` is gone and the user is always `opencode`.
     */
    const val OPENCODE_USER = "opencode"

    /** The login Pots gives Hermes' dashboard; its password is the panel's own. */
    const val HERMES_USER = "pots"

    /**
     * The command that mints a pairing code for OpenCode's panel.
     *
     * OpenCode 2's web client no longer accepts Basic credentials in the URL: `/`
     * answers with "Connect to a server. Paste the pairing link…" and the password
     * does nothing there. The sign-in is a code from `POST /api/pair` — good for
     * 300 seconds and for one browser — so it is asked for on the tap and never
     * stored; [pairUrl] is what the code opens.
     *
     * The password is not on this command line in either direction. The shell's
     * `printf` is a builtin, so the expanded `$passwordEnv` stays inside the shell
     * process and no child is exec'd with it; curl reads its credentials from
     * stdin (`-K -`), so curl's own argv holds `-K -` and nothing else. `ps` in
     * the sandbox sees the string below — with the variable name in it, not the
     * value.
     *
     * The HTTP status is printed on a line of its own after the body, so one call
     * says both "the server refused" and "the server answered something that is
     * not a code" — see [OpenCodePairOutput].
     */
    fun pairCommand(panel: WebPanel, port: Int): String {
        val passwordEnv = panel.passwordEnv ?: error("panel ${panel.id} has no password")
        // curl's config takes `user = "name:password"`, and the generated alphabet
        // is alphanumeric, so nothing in the value needs escaping inside the quotes.
        return "printf 'user = \"$OPENCODE_USER:%s\"\\n' \"\$$passwordEnv\" | " +
            "curl -sS -K - -X POST -w '\\n%{http_code}' ${address(port)}/api/pair"
    }

    /**
     * The address a minted code opens: the server sets the session cookie and
     * redirects into the app. Encoded because one `/` or `?` in a code would end
     * the path early and land on a 404 nobody could explain.
     */
    fun pairUrl(port: Int, code: String): String = "${address(port)}/auth/connect/${percentEncode(code)}"

    /**
     * The port a URL points at, or null when it names none. Used to report the
     * port Hermes actually came up on rather than the one that was asked for.
     */
    fun portOf(url: String): Int? {
        val authority = url.substringAfter("://", "").substringBefore('/')
        return authority.substringAfterLast(':', "").toIntOrNull()
    }

    /**
     * Whether [url] points at the guest's own loopback — the address only the
     * phone can reach, which is where a panel serves when it is not published.
     */
    fun isLoopback(url: String): Boolean {
        val authority = url.substringAfter("://", "").substringBefore('/')
        val host = authority.substringBeforeLast(':', authority)
        return host == "127.0.0.1" || host == "localhost" || host == "[::1]"
    }

    /**
     * How a panel is started. Hermes starts its gateway on the way — that is what
     * its own `--no-open` does — and prints the one-use link as its last line.
     */
    fun command(panel: WebPanel, port: Int): String = when (panel.id) {
        OPENCODE -> "opencode serve --port $port --hostname 127.0.0.1"
        PI -> "pi-web --port $port --hostname 127.0.0.1 --no-open"
        HERMES -> "hermes webapp --no-open"
        else -> error("no command for panel ${panel.id}")
    }

    /** The command that reports a panel's own state, for the panels that have one. */
    fun statusCommand(panel: WebPanel): String? = when (panel.id) {
        HERMES -> "hermes webapp --status"
        else -> null
    }

    /**
     * Answers whether this Hermes build has the panel at all. Hermes is the only
     * agent whose panel is a subcommand rather than a binary of its own, so it is
     * the only one that can be missing from an install.
     */
    const val HERMES_HELP = "hermes webapp --help"

    /**
     * The process that keeps Hermes' panel up: the webapp is served by the
     * gateway, and [command] only mints a link from it.
     *
     * A long-lived background session, not a foreground call. Every proot Pots
     * starts runs with `--kill-on-exit`, so a gateway a short-lived CLI brought up
     * dies with that CLI's proot the moment the link is printed — the row then
     * shows an address nobody listens on. `hermes gateway run` is Hermes' own
     * foreground mode for hosts without systemd, which is what a sandbox is.
     *
     * The marker line is echoed first so the panel's log says what was run. It
     * sits inside `sh -c` because the background launcher execs the command as it
     * is given: `exec echo …; hermes gateway run` would replace the shell with
     * `echo` and never start the gateway. The inner shell keeps the pid the
     * launch recorded, so a stop still signals the server.
     */
    const val HERMES_SERVER = "sh -c 'echo pots: hermes gateway run; exec hermes gateway run'"

    /**
     * The checkout Hermes' own installer lays down. Its commit is the version the
     * panel must match: see [HermesWebapp].
     */
    const val HERMES_CHECKOUT = "/root/.hermes/hermes-agent"

    /**
     * The renderer bundle `hermes webapp` serves, inside the checkout Hermes'
     * installer lays down. `hermes webapp --build-only` produces it; when that
     * step failed the install is still usable but the panel would open blank, so
     * the row says so instead.
     *
     * Only a build that ships no bundle of its own (a developer build) is left
     * with this one: a release APK carries the renderer CI built, unpacked to
     * [HermesWebapp.DIST_DIR].
     */
    const val HERMES_BUNDLE_DIR = "$HERMES_CHECKOUT/apps/desktop/dist-webapp"

    /** The page the panel serves. */
    const val HERMES_BUNDLE = "$HERMES_BUNDLE_DIR/index.html"

    /**
     * [preferred] when free, otherwise the next free port within
     * [PORT_ATTEMPTS]; null when that whole window is taken, which the caller
     * reports as an error instead of starting on a port nobody can open.
     */
    fun pickPort(preferred: Int, isBusy: (Int) -> Boolean): Int? {
        for (offset in 0 until PORT_ATTEMPTS) {
            val port = preferred + offset
            if (port > 65535) return null
            if (!isBusy(port)) return port
        }
        return null
    }
}

/**
 * Percent-encodes everything but RFC 3986's unreserved characters.
 *
 * A pairing code arrives from the server and is used as a path segment: one raw
 * `/`, `?` or `#` in it would end the path early and land the browser on a 404
 * that says nothing about the panel. Codes look like base64url today and need no
 * escaping, but the server picks that alphabet, not us — encoding unconditionally
 * means a future code cannot break the link silently.
 *
 * Hand-rolled because commonMain has no `URLEncoder` (`java.net`) and the app
 * targets iOS and wasm as well as Android.
 */
internal fun percentEncode(value: String): String = buildString {
    for (byte in value.encodeToByteArray()) {
        val code = byte.toInt() and 0xFF
        val char = code.toChar()
        val unreserved = char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' ||
            char == '-' || char == '.' || char == '_' || char == '~'
        if (unreserved) {
            append(char)
        } else {
            append('%').append(HEX_DIGITS[code shr 4]).append(HEX_DIGITS[code and 0x0F])
        }
    }
}

private const val HEX_DIGITS = "0123456789ABCDEF"

/** What a panel is doing, as the Pots Build section shows it. */
@Immutable
sealed interface WebPanelStatus {
    data object NotRunning : WebPanelStatus

    /** A start is in flight; the button is a spinner until it lands. */
    data object Starting : WebPanelStatus

    /**
     * [url] is the panel's address as the row shows it — no credentials in it,
     * even for the panel that takes them (see [WebPanels.url] and
     * [WebPanelRuntime.link]) — and [open] says what the browser needs beyond it.
     * The browser-bound link is minted on the tap, never stored here.
     */
    data class Running(val url: String, val open: WebPanelOpen = WebPanelOpen.DIRECT) : WebPanelStatus

    /**
     * [reason] is shown to the user as it is — a busy port, a missing binary, a
     * process that died. Wording is the runtime's own (English), like the
     * sandbox's other failure lines.
     */
    data class Failed(val reason: String) : WebPanelStatus
}

/**
 * How a running panel's address becomes a page in the browser.
 *
 * The distinction is what makes the "open" action honest: an address that needs
 * a ticket cannot be handed over as it is, and an address that needs an account
 * must not promise a signed-in page.
 */
enum class WebPanelOpen {
    /** The address alone: the panel serves it and asks nothing more. */
    DIRECT,

    /**
     * Only a link minted on the tap gets in — Hermes' private launch signs one
     * browser in once, and the ticket dies about a minute after it is printed.
     */
    MINTED_LINK,

    /**
     * Only a code minted on the tap gets in — OpenCode 2 answers `/` with a
     * client that wants a pairing link, and its code dies within 300 seconds.
     */
    PAIRING_CODE,

    /**
     * The webapp answers with a permanent address and asks for a dashboard
     * account. Hermes is here whenever the listener is gated — a non-loopback
     * bind, which is what Pots writes so the panel is reachable from the owner's
     * other devices — and the account is the one Pots passes in the launcher's
     * environment. The row says "sign in", because that is what such an address
     * does.
     */
    DASHBOARD_SIGN_IN,
}

@Immutable
data class WebPanelState(
    val id: String,
    val status: WebPanelStatus = WebPanelStatus.NotRunning,
    /** Whether the panel is brought up again when the sandbox becomes ready. */
    val autostart: Boolean = false,
)

/**
 * What Pots remembers about a panel between runs: its password (generated once,
 * on first need — a start or the row's sign-in section — never logged, never part
 * of the UI state) and whether it should come up with the sandbox.
 */
@Immutable
data class WebPanelPreferences(
    val password: String? = null,
    val autostart: Boolean = false,
)

/**
 * The password a panel gets on its first start.
 *
 * No look-alike characters (l/1, O/0): this is typed on a phone keyboard from a
 * screen that is showing it in a masked field.
 */
object WebPanelPasswords {
    const val ALPHABET = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    const val LENGTH = 24

    fun generate(random: Random = Random.Default): String = buildString(LENGTH) {
        repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
    }
}

/** A link `hermes webapp --no-open` printed, and whether it can be reused. */
@Immutable
data class HermesWebappLink(
    val url: String,
    /**
     * True for the private launch: the link signs the browser in, works once and
     * dies after [expiresInSeconds]. False when the webapp sits behind the
     * dashboard's sign-in, where the address is constant and no token rides in it.
     */
    val oneTime: Boolean,
    val expiresInSeconds: Int?,
)

/**
 * Reads what `hermes webapp --no-open` printed.
 *
 * Two shapes, and the difference decides whether a link may be cached:
 * - private launch — "Open this link in your browser within 60 seconds…" then
 *   the URL on its own line. One use, and it is spent by the first browser that
 *   opens it, so the button has to mint a fresh one on every tap;
 * - sign-in — "Open the Webapp and sign in with your dashboard account: <url>".
 *   A permanent address; nothing in it expires.
 */
object HermesWebappOutput {

    private val URL = Regex("""https?://\S+""")
    private val EXPIRES = Regex("""(?:within|valid for)\s+(\d+)\s+seconds""", RegexOption.IGNORE_CASE)
    private val SERVING = Regex("""Serving at\s+(https?://\S+)""")

    const val SIGN_IN_MARKER = "sign in with your dashboard account"

    fun parse(output: String): HermesWebappLink? {
        val url = URL.findAll(output).lastOrNull()?.value?.trimEnd('.', ',', ')') ?: return null
        val signIn = output.contains(SIGN_IN_MARKER, ignoreCase = true)
        return HermesWebappLink(
            url = url,
            oneTime = !signIn,
            expiresInSeconds = if (signIn) {
                null
            } else {
                EXPIRES.find(output)?.groupValues?.get(1)?.toIntOrNull() ?: 60
            },
        )
    }

    /** The address `hermes webapp --status` reports, without any one-use token. */
    fun servingUrl(status: String): String? = SERVING.find(status)?.groupValues?.get(1)?.trimEnd('.', ',', ')')
}

/**
 * What `POST /api/pair` gave back: a one-use code, or the sentence the row shows
 * instead of a link.
 *
 * Two failures have to stay distinguishable, because they have different cures:
 * the server refused (a wrong password — the panel's own password is stale) and
 * the server answered something that is not a code (a different build, or a
 * proxy in between). Opening the bare address is not an option for either: v2
 * answers `/` with "Connect to a server. Paste the pairing link…", which reads
 * as a broken panel.
 */
@Immutable
sealed interface OpenCodePairing {

    /** A code straight from the answer's `code` field, good for one browser. */
    data class Code(val value: String) : OpenCodePairing

    /** No link could be minted; [reason] is shown as it is. */
    data class Failed(val reason: String) : OpenCodePairing
}

/**
 * Reads what [WebPanels.pairCommand] printed: the JSON body, then the HTTP status
 * on a line of its own.
 *
 * The body is scanned rather than parsed — commonMain has no JSON parser, and the
 * answer is one field deep. A code is only taken from a 2xx: an error body may
 * carry anything, and half a link is worse than a sentence.
 */
object OpenCodePairOutput {

    private val CODE = Regex("""\"code\"\s*:\s*\"([^\"]+)\"""")
    private val STATUS = Regex("""(?m)^[ \t]*(\d{3})[ \t]*$""")

    /** How much of an unexpected body the reason line quotes. */
    private const val DETAIL_CHARS = 120

    fun parse(output: String): OpenCodePairing {
        val status = STATUS.findAll(output).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
        val body = output.trim().lineSequence()
            .filterNot { STATUS.matches(it) }
            .joinToString("\n")
            .trim()
        if (status == null) {
            // No status line at all: curl itself failed — refused, timed out, or
            // it is not installed. Its own message is the only honest reason.
            val reason = output.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            return OpenCodePairing.Failed(
                "OpenCode did not answer /api/pair: ${reason.ifEmpty { "no output" }.take(DETAIL_CHARS)}",
            )
        }
        if (status !in 200..299) {
            val detail = body.take(DETAIL_CHARS)
            return OpenCodePairing.Failed(
                "OpenCode refused the pairing code (HTTP $status)" + if (detail.isEmpty()) "" else ": $detail",
            )
        }
        val code = CODE.find(body)?.groupValues?.get(1)
        if (code.isNullOrEmpty()) {
            return OpenCodePairing.Failed(
                "OpenCode's pairing answer carried no code (HTTP $status)" +
                    body.take(DETAIL_CHARS).let { if (it.isEmpty()) "" else ": $it" },
            )
        }
        return OpenCodePairing.Code(code)
    }
}
