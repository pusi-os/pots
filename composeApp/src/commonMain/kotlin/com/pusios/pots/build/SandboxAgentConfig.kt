package com.pusios.pots.build

import androidx.compose.runtime.Immutable
import com.pusios.pots.data.DataRepository
import com.pusios.pots.data.Service
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * What Pots itself is talking to right now — provider, model, key, endpoint — in
 * the shape a sandbox agent's own config file wants it.
 *
 * The point of carrying these into the sandbox is that a coding agent there should
 * be the same agent the chat is: nobody should have to open each CLI and log it in
 * to a second account, or retype the endpoint, before it can answer.
 */
@Immutable
data class SandboxAgentCredentials(
    /** Provider id from Pots, e.g. `plusvibe`. Also the provider key the agents see. */
    val providerId: String,
    /** Display name, only for a config file's human-readable `name:` field. */
    val providerName: String,
    val model: String,
    val apiKey: String,
    /** OpenAI-compatible base URL, without `/chat/completions`. */
    val endpoint: String,
)

/** One file an agent reads at startup, at [path] relative to the Debian home (`/root`). */
@Immutable
data class SandboxAgentFile(val path: String, val content: String)

/**
 * Renders the active Pots provider into each sandbox agent's own configuration.
 *
 * Each agent reads a file in its home rather than taking flags, so the
 * propagation is a file write: OpenCode takes `~/.config/opencode/opencode.json`
 * (a `provider` entry plus the `model` it starts on), Pi takes
 * `~/.pi/agent/models.json` (custom providers) plus `~/.pi/agent/settings.json`
 * (the startup model), Hermes takes `~/.hermes/config.yaml` (a `providers:` entry
 * and the `model:` block that points at it) plus the key in `~/.hermes/.env`,
 * which it loads as dotenv.
 *
 * Everything here is pure: it takes the credentials and returns the bytes, so the
 * mapping is unit-tested without a sandbox, a network call, or a key.
 */
object SandboxAgentConfig {

    /**
     * What the agents send when Pots itself has no key: a keyless endpoint — a
     * local Ollama, say — still needs a non-empty value, because Pi rejects the
     * whole `models.json` on an empty `apiKey` and loses the provider, the model
     * and the endpoint along with it.
     */
    const val PLACEHOLDER_API_KEY = "pots-no-key"

    /**
     * The comment that introduces the `webapp:` block. Kept as one constant
     * because [withWebappPort] has to recognise and drop it along with the block
     * it belongs to — otherwise every rewrite leaves another copy behind.
     */
    private const val WEBAPP_MARKER = "# Written by Pots: the Webapp panel listens on loopback only."

    /**
     * Env var Hermes reads the API key from; `key_env` in its config points here.
     * OpenCode names the same variable in its own config (`{env:...}`) so the app
     * keeps one namespace for provider keys instead of one per agent — the value
     * is put into the agent process' environment either way.
     */
    fun apiKeyEnv(providerId: String): String =
        "HERMES_CUSTOM_" + providerId.uppercase().map { if (it.isLetterOrDigit()) it else '_' }.joinToString("") + "_API_KEY"

    /**
     * The key as Pi's `models.json` can hold it. Pi interpolates `$` anywhere in
     * the value, not just at the start: an unset name resolves to nothing, which
     * leaves the provider unconfigured, and a set one (`$HOME`) silently
     * substitutes the sandbox's own variable, so the key travels corrupted with
     * nothing in the file to show it. Every `$` is therefore doubled. A leading
     * `!` is a command to run and gets Pi's `$!` prefix, prepended after the
     * doubling so the prefix itself survives as an escape. An empty key becomes
     * [PLACEHOLDER_API_KEY], because Pi rejects the file rather than the
     * provider on a blank value.
     */
    fun piApiKey(apiKey: String): String = when {
        apiKey.isBlank() -> PLACEHOLDER_API_KEY
        // The `$!` prefix goes on last: doubling first would turn it into `$$!`,
        // which Pi reads as a literal `$` followed by the key's own `!`.
        apiKey.startsWith("!") -> "$!" + apiKey.drop(1).replace("$", "$$")
        else -> apiKey.replace("$", "$$")
    }

    /**
     * The files [agentId] needs to run on [credentials], or nothing when there is
     * no provider configured (or the agent takes no provider at all).
     *
     * [webappPort] is where Hermes' web panel should listen. It is a parameter
     * because the panel moves to the next free port when its preferred one is
     * taken, and a config written with the default would drag it back there.
     */
    fun filesFor(
        agentId: String,
        credentials: SandboxAgentCredentials?,
        webappPort: Int = WebPanels.HERMES_PORT,
    ): List<SandboxAgentFile> {
        if (credentials == null) return emptyList()
        return when (agentId) {
            BuildAgents.OPENCODE -> opencode(credentials)
            BuildAgents.PI -> pi(credentials)
            BuildAgents.HERMES -> hermes(credentials, webappPort)
            else -> emptyList()
        }
    }

    /**
     * The environment [agentId] needs on top of its config file to reach the
     * provider — the key itself, for the agents that read it from the process
     * rather than from a file. Empty for the agents that carry it in their own
     * config file, so nothing that does not need the key is handed it.
     */
    fun envFor(agentId: String, credentials: SandboxAgentCredentials): Map<String, String> = when (agentId) {
        // OpenCode resolves `{env:...}` from the environment; a keyless provider
        // still needs a value there, or OpenCode treats the provider as
        // unconfigured and asks for an interactive `providers login`.
        BuildAgents.OPENCODE -> mapOf(
            apiKeyEnv(credentials.providerId) to credentials.apiKey.ifBlank { PLACEHOLDER_API_KEY },
        )
        else -> emptyMap()
    }

    private val prettyJson = Json { prettyPrint = true }

    /**
     * OpenCode's config: `provider.<id>` is an OpenAI-compatible provider, and the
     * top-level `model` is what it starts on. The key is not written here — the
     * file only names the variable (`{env:...}`, which OpenCode substitutes at
     * load time) and [envFor] puts the value into the agent's environment, so the
     * key never sits in a config file. The same file is what `opencode serve`
     * reads, so the web UI comes up on the same provider without its own setup.
     */
    private fun opencode(credentials: SandboxAgentCredentials): List<SandboxAgentFile> {
        val keyEnv = apiKeyEnv(credentials.providerId)
        val config = buildJsonObject {
            putJsonObject("provider") {
                putJsonObject(credentials.providerId) {
                    put("npm", "@ai-sdk/openai-compatible")
                    put("name", credentials.providerName)
                    putJsonObject("options") {
                        put("baseURL", credentials.endpoint)
                        put("apiKey", "{env:$keyEnv}")
                    }
                    putJsonObject("models") {
                        putJsonObject(credentials.model) {
                            put("name", credentials.model)
                        }
                    }
                }
            }
            put("model", "${credentials.providerId}/${credentials.model}")
        }
        return listOf(
            SandboxAgentFile(
                path = ".config/opencode/opencode.json",
                content = prettyJson.encodeToString(config) + "\n",
            ),
        )
    }

    /**
     * Pi's custom-provider file, and the startup model that points at it. The
     * provider id is Pots' own, so the entry a user sees in `/model` is the
     * provider they picked in Pots.
     */
    private fun pi(credentials: SandboxAgentCredentials): List<SandboxAgentFile> {
        val models = buildJsonObject {
            putJsonObject("providers") {
                putJsonObject(credentials.providerId) {
                    put("baseUrl", credentials.endpoint)
                    put("api", "openai-completions")
                    put("apiKey", piApiKey(credentials.apiKey))
                    putJsonArray("models") {
                        add(
                            buildJsonObject {
                                put("id", credentials.model)
                                put("name", credentials.model)
                            },
                        )
                    }
                }
            }
        }
        val settings = buildJsonObject {
            put("defaultProvider", credentials.providerId)
            put("defaultModel", credentials.model)
        }
        return listOf(
            SandboxAgentFile(
                path = ".pi/agent/models.json",
                content = prettyJson.encodeToString(models) + "\n",
            ),
            SandboxAgentFile(
                path = ".pi/agent/settings.json",
                content = prettyJson.encodeToString(settings) + "\n",
            ),
        )
    }

    /**
     * Hermes' config: the endpoint is a `providers:` entry (Hermes resolves a
     * user-defined provider before any built-in of the same name), and `model:`
     * selects it. The key itself stays out of the YAML — `key_env` names the
     * variable and `.env` carries the value, which is how Hermes loads keys.
     */
    private fun hermes(credentials: SandboxAgentCredentials, webappPort: Int): List<SandboxAgentFile> {
        val keyEnv = apiKeyEnv(credentials.providerId)
        val config = buildString {
            appendLine("# Written by Pots from the app's active provider. Rewritten on every agent start.")
            appendLine("model:")
            appendLine("  default: ${yamlQuote(credentials.model)}")
            appendLine("  provider: ${yamlQuote(credentials.providerId)}")
            appendLine("  base_url: ${yamlQuote(credentials.endpoint)}")
            appendLine("  api_mode: chat_completions")
            appendLine("  key_env: $keyEnv")
            appendLine("providers:")
            appendLine("  ${yamlKey(credentials.providerId)}:")
            appendLine("    name: ${yamlQuote(credentials.providerName)}")
            appendLine("    base_url: ${yamlQuote(credentials.endpoint)}")
            appendLine("    model: ${yamlQuote(credentials.model)}")
            appendLine("    key_env: $keyEnv")
            append(webappConfig(webappPort))
        }
        val env = buildString {
            appendLine("# Written by Pots from the app's active provider. Rewritten on every agent start.")
            // A keyless endpoint still needs a value here: an empty one reads as
            // "no key at all", which is a different thing from a key the server
            // ignores.
            appendLine("$keyEnv=${dotenvValue(credentials.apiKey.ifBlank { PLACEHOLDER_API_KEY })}")
        }
        return listOf(
            SandboxAgentFile(path = ".hermes/config.yaml", content = config),
            SandboxAgentFile(path = ".hermes/.env", content = env),
        )
    }

    /**
     * Hermes' web panel settings: on, on loopback, on [port], with no public URL.
     *
     * `public_url` stays empty on purpose — a filled one replaces the address in
     * the link `hermes webapp` prints, and in a sandbox that link is a one-use
     * ticket for this phone's own browser, not a hostname.
     */
    fun webappConfig(port: Int): String = buildString {
        appendLine(WEBAPP_MARKER)
        appendLine("webapp:")
        appendLine("  enabled: true")
        appendLine("  host: 127.0.0.1")
        appendLine("  port: $port")
        appendLine("  public_url: \"\"")
    }

    /**
     * [yaml] with its top-level `webapp:` block replaced by one on [port].
     *
     * The panel rewrites the port this way when its preferred one is taken: the
     * rest of the file is somebody else's (Hermes writes into it too), so only the
     * block Pots owns is replaced — no YAML parser, no reformatting of the rest.
     * The block's own marker comment is dropped with it and re-emitted by
     * [webappConfig], so rewriting twice does not pile up copies.
     */
    fun withWebappPort(yaml: String, port: Int): String {
        val kept = mutableListOf<String>()
        var inWebapp = false
        for (line in yaml.lines()) {
            if (line.trimEnd() == WEBAPP_MARKER) continue
            val topLevel = line.isNotEmpty() && !line.first().isWhitespace() && !line.startsWith("#")
            if (topLevel) inWebapp = line.trimEnd() == "webapp:"
            if (!inWebapp) kept += line
        }
        val body = kept.joinToString("\n").trimEnd()
        return (if (body.isEmpty()) "" else body + "\n") + webappConfig(port)
    }

    /**
     * A single-quoted YAML scalar. Single quotes are the only quoting a YAML
     * parser reads literally — no escape sequences — and an embedded quote is
     * doubled, so a model id or endpoint can never end the scalar early.
     */
    private fun yamlQuote(value: String): String = "'" + value.replace("'", "''") + "'"

    /**
     * A YAML mapping key, left bare for the ordinary ids Pots' catalog uses and
     * quoted only when it holds something a bare key cannot.
     */
    private fun yamlKey(value: String): String =
        if (value.isNotEmpty() && value.all { it.isLetterOrDigit() || it in "-_." }) value else yamlQuote(value)

    /**
     * A dotenv value: bare when it is an ordinary token, double-quoted otherwise,
     * because a key with a space, `#` or quote would otherwise be truncated or
     * misread by the parser.
     */
    private fun dotenvValue(value: String): String {
        val plain = value.isNotEmpty() && value.all { it.isLetterOrDigit() || it in "-_.:/+@=" }
        if (plain) return value
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}

/**
 * The provider Pots is using right now: the first configured service instance,
 * which is what chat sends its requests to.
 *
 * Null when nothing is configured yet, when the active provider has no HTTP
 * endpoint of its own (the on-device model), or before a model is chosen — there
 * is nothing an agent could be pointed at in those cases, and a half-written
 * config is worse than none.
 */
fun DataRepository.activeSandboxCredentials(): SandboxAgentCredentials? {
    val entry = getServiceEntries().firstOrNull() ?: return null
    val service = Service.fromId(entry.serviceId) ?: return null
    val endpoint = when (service) {
        Service.OpenAICompatible ->
            Service.normalizeOpenAICompatibleBaseUrl(getInstanceBaseUrl(entry.instanceId, service))
        else -> getInstanceBaseUrl(entry.instanceId, service).trim().trimEnd('/')
            .ifBlank { service.chatUrl.removeSuffix("/chat/completions").trimEnd('/') }
    }
    val model = entry.modelId.ifBlank { service.defaultModel.orEmpty() }
    if (endpoint.isBlank() || model.isBlank()) return null
    return SandboxAgentCredentials(
        providerId = service.id,
        providerName = service.displayName,
        model = model,
        apiKey = getInstanceApiKey(entry.instanceId),
        endpoint = endpoint,
    )
}
