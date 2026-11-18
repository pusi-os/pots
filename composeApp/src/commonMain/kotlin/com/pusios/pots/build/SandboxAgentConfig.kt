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
 * Both agents read a file in their home rather than taking flags, so the
 * propagation is a file write: Pi takes `~/.pi/agent/models.json` (custom
 * providers) plus `~/.pi/agent/settings.json` (the startup model), Hermes takes
 * `~/.hermes/config.yaml` (a `providers:` entry and the `model:` block that points
 * at it) plus the key in `~/.hermes/.env`, which it loads as dotenv.
 *
 * Everything here is pure: it takes the credentials and returns the bytes, so the
 * mapping is unit-tested without a sandbox, a network call, or a key.
 */
object SandboxAgentConfig {

    /** Env var Hermes reads the API key from; `key_env` in its config points here. */
    fun apiKeyEnv(providerId: String): String =
        "HERMES_CUSTOM_" + providerId.uppercase().map { if (it.isLetterOrDigit()) it else '_' }.joinToString("") + "_API_KEY"

    /**
     * The files [agentId] needs to run on [credentials], or nothing when there is
     * no provider configured (or the agent takes no provider at all).
     */
    fun filesFor(agentId: String, credentials: SandboxAgentCredentials?): List<SandboxAgentFile> {
        if (credentials == null) return emptyList()
        return when (agentId) {
            BuildAgents.PI -> pi(credentials)
            BuildAgents.HERMES -> hermes(credentials)
            else -> emptyList()
        }
    }

    private val prettyJson = Json { prettyPrint = true }

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
                    put("apiKey", credentials.apiKey)
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
    private fun hermes(credentials: SandboxAgentCredentials): List<SandboxAgentFile> {
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
        }
        val env = buildString {
            appendLine("# Written by Pots from the app's active provider. Rewritten on every agent start.")
            appendLine("$keyEnv=${dotenvValue(credentials.apiKey)}")
        }
        return listOf(
            SandboxAgentFile(path = ".hermes/config.yaml", content = config),
            SandboxAgentFile(path = ".hermes/.env", content = env),
        )
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
