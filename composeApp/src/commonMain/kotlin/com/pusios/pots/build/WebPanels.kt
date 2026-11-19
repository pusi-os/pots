package com.pusios.pots.build

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlin.random.Random

/**
 * A coding agent's own web UI, as something Pots Build can start and stop.
 *
 * Every panel listens on loopback. proot creates no network namespace, so a
 * server bound to `127.0.0.1` inside the sandbox *is* the phone's loopback and
 * Chrome reaches it; binding `0.0.0.0` would put an unauthenticated panel on the
 * LAN instead, so nothing here ever asks for that.
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
    /**
     * Env var carrying the panel's password, or null for the panel that has no
     * password of its own (Hermes signs each browser in with a one-use link).
     */
    val passwordEnv: String?,
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
            passwordEnv = null,
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
     * The URL handed to the browser.
     *
     * Only OpenCode gets credentials in it: it asks for HTTP Basic on every
     * request, the HTML included, so `user:password@host` signs the browser in
     * and Chromium strips them from the address bar and history once the page
     * has loaded. pi-web guards its API only — its HTML wants a session cookie —
     * so its URL points at the login form instead: credentials in it would be
     * ignored and would leave the password sitting in the address bar. Hermes
     * needs neither, because the link `link()` mints *is* the sign-in.
     *
     * A null or empty password yields the plain address rather than a broken
     * `user:@host` — the panel still opens, it just asks for the password.
     */
    fun url(panel: WebPanel, port: Int, password: String? = null): String = when (panel.id) {
        OPENCODE ->
            if (password.isNullOrEmpty()) {
                address(port)
            } else {
                "http://$OPENCODE_USER:${percentEncode(password)}@127.0.0.1:$port/"
            }
        PI -> "${address(port)}/login"
        HERMES -> address(port)
        else -> error("no URL for panel ${panel.id}")
    }

    /** The user half of OpenCode's Basic credentials; its password is the one above. */
    const val OPENCODE_USER = "opencode"

    /**
     * The port a URL points at, or null when it names none. Used to report the
     * port Hermes actually came up on rather than the one that was asked for.
     */
    fun portOf(url: String): Int? {
        val authority = url.substringAfter("://", "").substringBefore('/')
        return authority.substringAfterLast(':', "").toIntOrNull()
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
     * The renderer bundle `hermes webapp` serves, inside the checkout Hermes'
     * installer lays down. `hermes webapp --build-only` produces it; when that
     * step failed the install is still usable but the panel would open blank, so
     * the row says so instead.
     */
    const val HERMES_BUNDLE = "/root/.hermes/hermes-agent/apps/desktop/dist-webapp/index.html"

    /** True when the bundle above is there — the honest answer to "is it built?". */
    const val HERMES_BUNDLE_CHECK = "test -f $HERMES_BUNDLE"

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
 * A generated password is drawn from an alphanumeric alphabet, so today it needs
 * no escaping — but that alphabet is a constant someone will widen, and one raw
 * `@`, `:` or `/` in the userinfo ends it early: the browser then dials a host
 * that does not exist, or a different one, and nothing says why the panel came
 * up unauthenticated. Encoding unconditionally means that cannot happen.
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
     * [WebPanelRuntime.link]). The browser-bound link is minted on the tap.
     */
    data class Running(val url: String) : WebPanelStatus

    /**
     * [reason] is shown to the user as it is — a busy port, a missing binary, a
     * process that died. Wording is the runtime's own (English), like the
     * sandbox's other failure lines.
     */
    data class Failed(val reason: String) : WebPanelStatus
}

@Immutable
data class WebPanelState(
    val id: String,
    val status: WebPanelStatus = WebPanelStatus.NotRunning,
    /** Whether the panel is brought up again when the sandbox becomes ready. */
    val autostart: Boolean = false,
)

/**
 * What Pots remembers about a panel between runs: its password (generated on
 * first start, never logged, never part of the UI state) and whether it should
 * come up with the sandbox.
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
