// Agent tools over the encrypted vault. Every response carries metadata and vault://
// references only — a secret value never enters a tool result, the chat history, or a
// provider payload. The only paths a value takes are inside the bridges: the form write,
// the reveal overlay, and the HTTP substitution, all before serialization.

package com.pusios.pots.data.vault

import com.pusios.pots.data.HeadlessRunElement
import com.pusios.pots.data.currentConversationIdOrNull
import com.pusios.pots.httpClient
import com.pusios.pots.network.tools.ParameterSchema
import com.pusios.pots.network.tools.Tool
import com.pusios.pots.network.tools.ToolInfo
import com.pusios.pots.network.tools.ToolSchema
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.seconds
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.tool_vault_description
import pots.composeapp.generated.resources.tool_vault_form_fill_description
import pots.composeapp.generated.resources.tool_vault_form_fill_name
import pots.composeapp.generated.resources.tool_vault_get_description
import pots.composeapp.generated.resources.tool_vault_get_name
import pots.composeapp.generated.resources.tool_vault_http_request_description
import pots.composeapp.generated.resources.tool_vault_http_request_name
import pots.composeapp.generated.resources.tool_vault_list_description
import pots.composeapp.generated.resources.tool_vault_list_name
import pots.composeapp.generated.resources.tool_vault_name
import pots.composeapp.generated.resources.tool_vault_reveal_description
import pots.composeapp.generated.resources.tool_vault_reveal_name
import pots.composeapp.generated.resources.tool_vault_request_form_description
import pots.composeapp.generated.resources.tool_vault_request_form_name
import pots.composeapp.generated.resources.tool_vault_save_description
import pots.composeapp.generated.resources.tool_vault_save_name

/** Everything the vault tools need. Platforms build it from Koin; tests inject fakes. */
data class VaultToolDependencies(
    val session: VaultSession,
    val store: VaultStore,
    val formBridge: VaultFormBridge,
    val revealBridge: VaultRevealBridge,
    val gate: VaultGate,
    // Null in production (the platform client is created lazily); tests pass a mock.
    val httpClient: HttpClient? = null,
)

/**
 * The seven vault tools. Registered in `buildAgentToolSet` behind one master toggle
 * ([MASTER_ID], on by default); the spend-tools additionally ask the owner on every use.
 */
object VaultTools {

    const val MASTER_ID = "vault_tools"

    /** Names of the tool schemas [create] registers — the prompt builder keys its section on these. */
    val TOOL_NAMES = listOf(
        "vault_list",
        "vault_get",
        "vault_http_request",
        "vault_form_fill",
        "vault_reveal",
        "vault_save",
        "vault_request_form",
    )

    private val REF_REGEX = Regex("""vault://([A-Za-z0-9._-]+)/([A-Za-z0-9._-]+)""")
    private const val FIELD_KEY_LIMIT = 12
    private const val NOTES_LIMIT = 4_000
    private const val REASON_LIMIT = 200
    private const val RESPONSE_BODY_LIMIT = 20_000
    private val ALLOWED_HTTP_METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")
    private const val REVEAL_TTL_SEC = 30
    private val FIELD_KEY_REGEX = Regex("""[a-z0-9_.-]{1,32}""")
    private val HTTP_METHODS = mapOf(
        "GET" to HttpMethod.Get,
        "POST" to HttpMethod.Post,
        "PUT" to HttpMethod.Put,
        "PATCH" to HttpMethod.Patch,
        "DELETE" to HttpMethod.Delete,
        "HEAD" to HttpMethod.Head,
    )

    /**
     * Display definitions for Settings and chat. The master switch is the set's single
     * user toggle: flipping it in the Tools tab writes the per-profile
     * `tool_enabled_vault_tools` override ([AppSettings.setToolEnabled]) and the vault
     * tab exposes no separate switch, so both surfaces read one state. The individual
     * tools are not per-tool toggleable — they are declared for chat to resolve names.
     */
    val toolDefinitions: List<ToolInfo> = listOf(
        ToolInfo(
            id = MASTER_ID,
            name = "Password vault",
            description = "Master switch for the agent's password vault tools",
            nameRes = Res.string.tool_vault_name,
            descriptionRes = Res.string.tool_vault_description,
            isEnabled = true,
        ),
        ToolInfo(
            "vault_list",
            "Vault: list",
            "List vault cards without secret values",
            nameRes = Res.string.tool_vault_list_name,
            descriptionRes = Res.string.tool_vault_list_description,
            userToggleable = false,
        ),
        ToolInfo(
            "vault_get",
            "Vault: get reference",
            "Get a vault:// reference for a card field",
            nameRes = Res.string.tool_vault_get_name,
            descriptionRes = Res.string.tool_vault_get_description,
            userToggleable = false,
        ),
        ToolInfo(
            "vault_http_request",
            "Vault: authenticated request",
            "Send an HTTP request with a vault secret substituted at transport level",
            nameRes = Res.string.tool_vault_http_request_name,
            descriptionRes = Res.string.tool_vault_http_request_description,
            userToggleable = false,
        ),
        ToolInfo(
            "vault_form_fill",
            "Vault: fill form",
            "Submit credentials to a form with values substituted at transport level",
            nameRes = Res.string.tool_vault_form_fill_name,
            descriptionRes = Res.string.tool_vault_form_fill_description,
            userToggleable = false,
        ),
        ToolInfo(
            "vault_reveal",
            "Vault: reveal",
            "Show a secret to the owner in an overlay after explicit confirmation",
            nameRes = Res.string.tool_vault_reveal_name,
            descriptionRes = Res.string.tool_vault_reveal_description,
            userToggleable = false,
        ),
        ToolInfo(
            "vault_save",
            "Vault: save card",
            "Save card metadata; values only via the request form",
            nameRes = Res.string.tool_vault_save_name,
            descriptionRes = Res.string.tool_vault_save_description,
            userToggleable = false,
        ),
        ToolInfo(
            "vault_request_form",
            "Vault: request secrets",
            "Show a form that saves user input straight into the vault",
            nameRes = Res.string.tool_vault_request_form_name,
            descriptionRes = Res.string.tool_vault_request_form_description,
            userToggleable = false,
        ),
    )

    fun create(deps: VaultToolDependencies): List<Tool> = listOf(
        listTool(deps),
        getTool(deps),
        httpRequestTool(deps),
        formFillTool(deps),
        revealTool(deps),
        saveTool(deps),
        requestFormTool(deps),
    )

    private fun lockedError() = mapOf(
        "success" to false,
        "error" to "vault_locked",
        "hint" to "The vault is locked. Ask the user to unlock it in Pots.",
    )

    private fun error(message: String) = mapOf("success" to false, "error" to message)

    private fun ref(itemId: String, field: String) = "vault://$itemId/$field"

    private fun jsonEscape(value: String) = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

    private fun str(args: Map<String, Any>, key: String): String? = args[key]?.toString()?.trim()?.ifEmpty { null }

    // The model sends booleans both as JSON booleans and as "true"/"false" strings
    // depending on the provider's argument pipeline; accept both.
    private fun boolArg(args: Map<String, Any>, key: String): Boolean = when (val raw = args[key]) {
        is Boolean -> raw
        is String -> raw.toBooleanStrictOrNull() ?: false
        else -> false
    }

    // The response body is scrubbed of every encoding a substitution can produce: the
    // raw value, its URL-encoded form (form bodies) and its JSON-escaped form (JSON
    // bodies), so an echoing server cannot leak the secret in an encoded shape.
    private fun scrub(text: String, secret: String): String {
        var result = text.replace(secret, "[redacted]")
        val urlEncoded = secret.encodeURLParameter()
        if (urlEncoded != secret) result = result.replace(urlEncoded, "[redacted]")
        val jsonEscaped = jsonEscape(secret)
        if (jsonEscaped != secret) result = result.replace(jsonEscaped, "[redacted]")
        val jsonThenUrl = jsonEscaped.encodeURLParameter()
        if (jsonThenUrl != jsonEscaped && jsonThenUrl != urlEncoded) result = result.replace(jsonThenUrl, "[redacted]")
        return result
    }

    private fun tagList(args: Map<String, Any>): List<String> = (args["tags"] as? List<*>)?.map { it.toString() }.orEmpty()

    // --- vault_list -------------------------------------------------------------

    private fun listTool(deps: VaultToolDependencies) = object : Tool {
        override val schema = ToolSchema(
            name = "vault_list",
            description = "List password vault cards: names, URLs, usernames, tags and field keys. " +
                "Never returns secret values. Use vault_get to obtain vault:// references for fields.",
            parameters = mapOf(
                "query" to ParameterSchema("string", "Text to match against name, url, username, notes or tags", required = false),
                "type" to ParameterSchema("string", "Card type: login|card|identity|note|ssh_key|host|token", required = false),
                "tag" to ParameterSchema("string", "Tag filter", required = false),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            if (deps.session.locked.value) return lockedError()
            val kind = str(args, "type")?.let { VaultItemKind.fromId(it) ?: return error("unknown type: $it") }
            val items = deps.session.search(
                query = str(args, "query").orEmpty(),
                kind = kind,
                tag = str(args, "tag"),
            )
            return mapOf(
                "success" to true,
                "count" to items.size,
                "items" to items.map { item ->
                    mapOf(
                        "id" to item.id,
                        "name" to item.name,
                        "type" to item.type,
                        "url" to item.url,
                        "username" to item.username,
                        "tags" to item.tags,
                        "fields" to item.fields.map { field ->
                            mapOf(
                                "key" to field.key,
                                "label" to field.label,
                                "type" to field.type,
                                "has_value" to field.value.isNotEmpty(),
                            )
                        },
                    )
                },
            )
        }
    }

    // --- vault_get --------------------------------------------------------------

    private fun getTool(deps: VaultToolDependencies) = object : Tool {
        override val schema = ToolSchema(
            name = "vault_get",
            description = "Return vault:// references for a vault card's fields — never the values. " +
                "A reference is a placeholder you pass to vault_http_request or vault_form_fill; " +
                "the app substitutes the secret at transport level and it never enters the conversation.",
            parameters = mapOf(
                "item_id" to ParameterSchema("string", "The card id from vault_list", required = true),
                "field" to ParameterSchema("string", "Field key; omit to get references for all fields", required = false),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            if (deps.session.locked.value) return lockedError()
            val itemId = str(args, "item_id") ?: return error("item_id is required")
            val item = deps.session.document()?.items?.firstOrNull { it.id == itemId }
                ?: return error("no vault item with id $itemId")
            val field = str(args, "field")
            return if (field == null) {
                mapOf(
                    "success" to true,
                    "item" to item.name,
                    "refs" to item.fields.associate { it.key to ref(item.id, it.key) },
                )
            } else {
                if (item.fields.none { it.key == field }) {
                    return error("item '${item.name}' has no field '$field'")
                }
                mapOf(
                    "success" to true,
                    "item" to item.name,
                    "field" to field,
                    "ref" to ref(item.id, field),
                )
            }
        }
    }

    // --- vault_http_request -----------------------------------------------------

    private fun httpRequestTool(deps: VaultToolDependencies) = object : Tool {
        override val schema = ToolSchema(
            name = "vault_http_request",
            description = "Send an authenticated HTTP request: every vault://<item>/<field> reference " +
                "found in a header value or in the body is replaced with the real secret at transport " +
                "level, and the response is scrubbed of secret occurrences. The secret never appears " +
                "in this conversation. The owner is asked to confirm each use.",
            parameters = mapOf(
                "url" to ParameterSchema("string", "Absolute http(s) URL to call", required = true),
                "method" to ParameterSchema("string", "HTTP method: GET (default), POST, PUT, PATCH, DELETE or HEAD", required = false),
                "headers" to ParameterSchema(
                    "object",
                    "Header map; values may contain vault://<item>/<field> references, e.g. {\"Authorization\": \"Bearer vault://<id>/token\"}",
                    required = false,
                ),
                "body" to ParameterSchema("string", "Request body; may contain vault:// references", required = false),
                "content_type" to ParameterSchema("string", "Content-Type for the body", required = false),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            if (deps.session.locked.value) return lockedError()
            val urlArg = str(args, "url") ?: return error("url is required")
            val methodArg = (args["method"]?.toString() ?: "GET").uppercase()
            if (methodArg !in ALLOWED_HTTP_METHODS) {
                return error("method must be one of $ALLOWED_HTTP_METHODS")
            }
            val headerArgs = (args["headers"] as? Map<*, *>)
                ?.entries?.associate { (k, v) -> k.toString() to v.toString() }
                ?: emptyMap()
            val bodyArg = str(args, "body")
            val refs = (headerArgs.values + listOfNotNull(bodyArg))
                .flatMap { REF_REGEX.findAll(it) }
                .map { it.groupValues[1] to it.groupValues[2] }
                .distinct()
            if (refs.isEmpty()) return error("no vault:// reference in headers or body")

            val itemNames = itemNamesFor(deps, refs)
            if (!deps.gate.confirm(
                    "vault_http_request",
                    "Send a request to $urlArg using credentials of ${itemNames.joinToString()}",
                )
            ) {
                return error("denied by user")
            }
            val values = readFieldValues(deps, refs) ?: return error("referenced vault item or field not found")

            fun substitute(text: String) = REF_REGEX.replace(text) { match ->
                values[ref(match.groupValues[1], match.groupValues[2])] ?: match.value
            }

            val client = deps.httpClient ?: platformClient()
            val response = withContext(Dispatchers.Default) {
                client.request(urlArg) {
                    method = HTTP_METHODS.getValue(methodArg)
                    headerArgs.forEach { (name, value) -> header(name, substitute(value)) }
                    str(args, "content_type")?.let { contentType(ContentType.parse(it)) }
                    bodyArg?.let { setBody(substitute(it)) }
                }
            }
            // Scrub any occurrence of a used secret out of the response before it can
            // reach the result, the history and the provider.
            val rawBody = response.bodyAsText().take(RESPONSE_BODY_LIMIT)
            val scrubbed = values.values.fold(rawBody) { acc, secret -> scrub(acc, secret) }
            return mapOf(
                "success" to true,
                "status" to response.status.value,
                "ok" to response.status.isSuccess(),
                "body" to scrubbed,
            )
        }
    }

    // --- vault_form_fill --------------------------------------------------------

    private fun formFillTool(deps: VaultToolDependencies) = object : Tool {
        override val schema = ToolSchema(
            name = "vault_form_fill",
            description = "Submit a login/form request using a vault card's fields: mapping maps " +
                "form field names to the card's field keys, and the values are substituted at " +
                "transport level — they never appear in this conversation. The owner is asked to " +
                "confirm each use.",
            parameters = mapOf(
                "item_id" to ParameterSchema("string", "The card id from vault_list", required = true),
                "url" to ParameterSchema("string", "Absolute http(s) URL that accepts the form", required = true),
                "mapping" to ParameterSchema(
                    "object",
                    "Form field name -> card field key, e.g. {\"login\": \"username\", \"password\": \"password\"}",
                    required = true,
                ),
                "method" to ParameterSchema("string", "HTTP method for the submit: POST (default) or PUT", required = false),
                "content_type" to ParameterSchema(
                    "string",
                    "Content-Type: application/x-www-form-urlencoded (default) or application/json",
                    required = false,
                ),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            if (deps.session.locked.value) return lockedError()
            val itemId = str(args, "item_id") ?: return error("item_id is required")
            val urlArg = str(args, "url") ?: return error("url is required")
            val mapping = (args["mapping"] as? Map<*, *>)
                ?.entries?.associate { (k, v) -> k.toString() to v.toString() }
                ?: return error("mapping must be an object of form field -> card field key")
            if (mapping.isEmpty()) return error("mapping is empty")

            val item = deps.session.document()?.items?.firstOrNull { it.id == itemId }
                ?: return error("no vault item with id $itemId")
            val missing = mapping.values.filterNot { key -> item.fields.any { it.key == key } }
            if (missing.isNotEmpty()) {
                return error("card '${item.name}' has no field(s): ${missing.joinToString()}")
            }
            if (!deps.gate.confirm(
                    "vault_form_fill",
                    "Submit credentials of '${item.name}' to $urlArg",
                )
            ) {
                return error("denied by user")
            }
            val values = readFieldValues(deps, mapping.values.map { itemId to it })
                ?: return error("referenced vault item or field not found")

            val contentTypeArg = str(args, "content_type") ?: "application/x-www-form-urlencoded"
            val body = when (contentTypeArg) {
                "application/json" -> mapping.entries.joinToString(",", prefix = "{", postfix = "}") { (formField, fieldKey) ->
                    val value = values.getValue(ref(itemId, fieldKey))
                    "\"${jsonEscape(formField)}\":\"${jsonEscape(value)}\""
                }

                else -> mapping.entries.joinToString("&") { (formField, fieldKey) ->
                    "${formField.encodeURLParameter()}=${values.getValue(ref(itemId, fieldKey)).encodeURLParameter()}"
                }
            }

            val client = deps.httpClient ?: platformClient()
            val response = withContext(Dispatchers.Default) {
                client.request(urlArg) {
                    method = if (str(args, "method")?.uppercase() == "PUT") HttpMethod.Put else HttpMethod.Post
                    contentType(ContentType.parse(contentTypeArg))
                    setBody(body)
                }
            }
            val rawBody = response.bodyAsText().take(RESPONSE_BODY_LIMIT)
            val scrubbed = values.values.fold(rawBody) { acc, secret -> scrub(acc, secret) }
            return mapOf(
                "success" to true,
                "status" to response.status.value,
                "ok" to response.status.isSuccess(),
                "body" to scrubbed,
            )
        }
    }

    // --- vault_reveal -----------------------------------------------------------

    private fun revealTool(deps: VaultToolDependencies) = object : Tool {
        override val schema = ToolSchema(
            name = "vault_reveal",
            description = "Show a vault secret to the owner in a UI overlay (or copy it, marked " +
                "sensitive) — only after the owner explicitly confirms. The value is never placed " +
                "in this tool's response, the chat or any provider payload.",
            parameters = mapOf(
                "item_id" to ParameterSchema("string", "The card id from vault_list", required = true),
                "field" to ParameterSchema("string", "Field key to reveal", required = true),
                "copy" to ParameterSchema("boolean", "true to copy the value to the clipboard instead of showing it", required = false),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            // Heartbeat / scheduled runs have no overlay to confirm in: refuse now
            // instead of hanging for the full tool timeout.
            if (coroutineContext[HeadlessRunElement] != null) {
                return mapOf(
                    "success" to false,
                    "error" to "vault_reveal_unavailable",
                    "hint" to "No chat UI is in the foreground; ask the user to open Pots",
                )
            }
            if (deps.session.locked.value) return lockedError()
            val itemId = str(args, "item_id") ?: return error("item_id is required")
            val field = str(args, "field") ?: return error("field is required")
            return when (val outcome = deps.revealBridge.request(itemId, field, boolArg(args, "copy"))) {
                VaultRevealOutcome.Shown -> mapOf(
                    "shown" to true,
                    "ttlSec" to REVEAL_TTL_SEC,
                    "copy" to boolArg(args, "copy"),
                )

                VaultRevealOutcome.Denied -> error("denied by user")

                VaultRevealOutcome.Locked -> lockedError()

                is VaultRevealOutcome.Failed -> error(outcome.reason)
            }
        }
    }

    // --- vault_save ---------------------------------------------------------------

    private fun saveTool(deps: VaultToolDependencies) = object : Tool {
        override val schema = ToolSchema(
            name = "vault_save",
            description = "Create or update a vault card's metadata (name, url, username, tags, " +
                "notes). Takes no secret values: to store a password, key or token, call " +
                "vault_request_form and let the user type it. With generate_password=true a strong " +
                "random password is generated inside the app and never returned.",
            parameters = mapOf(
                "name" to ParameterSchema("string", "Card title, e.g. 'GitHub'", required = true),
                "type" to ParameterSchema("string", "Card type: login (default)|card|identity|note|ssh_key|host|token", required = false),
                "item_id" to ParameterSchema("string", "Card id to update; omit to create", required = false),
                "url" to ParameterSchema("string", "Card URL", required = false),
                "username" to ParameterSchema("string", "Login name", required = false),
                "tags" to ParameterSchema("array", "Tags", required = false),
                "notes" to ParameterSchema("string", "Non-secret notes only, max 4000 chars", required = false),
                "generate_password" to ParameterSchema("boolean", "true to generate a random password into the card (never returned)", required = false),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            if (deps.session.locked.value) return lockedError()
            val name = str(args, "name") ?: return error("name is required")
            val existing = str(args, "item_id")?.let { id ->
                deps.session.document()?.items?.firstOrNull { it.id == id }
                    ?: return error("no vault item with id $id")
            }
            // An update without an explicit type keeps the card's type: silently
            // resetting an ssh_key or host card to login corrupts the card's template.
            val typeArg = str(args, "type") ?: existing?.type ?: VaultItemKind.LOGIN.id
            VaultItemKind.fromId(typeArg) ?: return error("unknown type: $typeArg")
            val notes = str(args, "notes").orEmpty()
            if (notes.length > NOTES_LIMIT) return error("notes must be at most $NOTES_LIMIT characters")
            val generatePassword = boolArg(args, "generate_password")

            var item = (existing ?: VaultItem(id = "", type = typeArg, name = name)).copy(
                name = name,
                type = typeArg,
                url = str(args, "url") ?: existing?.url.orEmpty(),
                username = str(args, "username") ?: existing?.username.orEmpty(),
                notes = notes.ifEmpty { existing?.notes.orEmpty() },
                tags = tagList(args).ifEmpty { existing?.tags.orEmpty() },
            )
            var values: Map<String, String> = emptyMap()
            var generated = false
            if (generatePassword) {
                val engine = deps.store.engine ?: return error("vault is not supported on this platform")
                // The generated password lives in the values map and is written by
                // VaultSession.upsert — it never reaches this tool's response.
                val password = engine.encodeBase64(engine.randomBytes(GENERATED_PASSWORD_BYTES))
                values = mapOf(PASSWORD_KEY to password)
                generated = true
                if (item.fields.none { it.key == PASSWORD_KEY }) {
                    item = item.copy(
                        fields = item.fields +
                            VaultField(key = PASSWORD_KEY, label = "Password", type = VaultFieldKind.SECRET.id),
                    )
                }
            }
            val saved = deps.session.upsert(item, values)
            return buildMap {
                put("success", true)
                put("saved", true)
                put("id", saved.id)
                put("name", saved.name)
                put("fields", saved.fields.size)
                if (generated) {
                    put("generated", true)
                    put("password_ref", ref(saved.id, PASSWORD_KEY))
                }
            }
        }
    }

    // --- vault_request_form -------------------------------------------------------

    private fun requestFormTool(deps: VaultToolDependencies) = object : Tool {
        // The default 30 s would cut off a form the user is still typing into.
        override val timeout = 300.seconds

        override val schema = ToolSchema(
            name = "vault_request_form",
            description = "Ask the user to enter credentials directly into the vault. Shows a form " +
                "in the chat UI; what the user types is saved to the encrypted vault without ever " +
                "entering this conversation. Returns only the item id and vault:// references — " +
                "never the values. Use this whenever you need a password, SSH key, token or host " +
                "credential. Never ask the user to paste secrets into the chat.",
            parameters = mapOf(
                "mode" to ParameterSchema("string", "create (default) or update", required = true),
                "item_id" to ParameterSchema("string", "Required when mode=update", required = false),
                "type" to ParameterSchema("string", "Template id: login|card|identity|note|ssh_key|host|token", required = false),
                "name" to ParameterSchema("string", "Item title, e.g. 'GitHub'", required = false),
                "url" to ParameterSchema("string", "Item URL", required = false),
                "username" to ParameterSchema("string", "Login name", required = false),
                "tags" to ParameterSchema("array", "Tags", required = false),
                "notes" to ParameterSchema("string", "Non-secret notes only, max 4000 chars", required = false),
                "reason" to ParameterSchema(
                    "string",
                    "Why the secret is needed — shown to the user, max 200 chars",
                    required = true,
                ),
                "fields" to ParameterSchema(
                    "array",
                    "Max 12 field descriptors: {key, label, input: text|secret|multiline|secret_multiline|url, required, placeholder}",
                    required = true,
                ),
                "allow_generate" to ParameterSchema("boolean", "true to show the user a generate-password button", required = false),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            // Heartbeat / scheduled runs have no chat UI to show the sheet in: refuse now
            // instead of hanging for the full timeout.
            if (coroutineContext[HeadlessRunElement] != null) {
                return mapOf(
                    "success" to false,
                    "error" to "vault_form_unavailable",
                    "hint" to "No chat UI is in the foreground; ask the user to open Pots",
                )
            }
            val mode = str(args, "mode") ?: "create"
            if (mode != "create" && mode != VaultFormBridge.MODE_UPDATE) {
                return error("mode must be create or update")
            }
            // str() already yields null on blank values, so the check is on null: without
            // it an update request would fall through and silently create a duplicate.
            val itemId = str(args, "item_id")
            if (mode == VaultFormBridge.MODE_UPDATE && itemId == null) {
                return error("item_id is required when mode=update")
            }
            val reason = str(args, "reason") ?: return error("reason is required")
            if (reason.length > REASON_LIMIT) return error("reason must be at most $REASON_LIMIT characters")
            val notes = str(args, "notes").orEmpty()
            if (notes.length > NOTES_LIMIT) return error("notes must be at most $NOTES_LIMIT characters")
            val specs = parseFieldSpecs(args["fields"])
                ?: return error("fields must be a non-empty array of at most $FIELD_KEY_LIMIT descriptors {key, label, input, required, placeholder}")

            val form = VaultFormRequest(
                id = newVaultFormId(),
                mode = mode,
                itemId = itemId,
                // Null when the agent did not pick a template: an update keeps the
                // existing card's type instead of resetting it to login.
                itemType = str(args, "type"),
                name = str(args, "name").orEmpty(),
                url = str(args, "url").orEmpty(),
                username = str(args, "username").orEmpty(),
                tags = tagList(args),
                notes = notes,
                reason = reason,
                fields = specs,
                allowGenerate = boolArg(args, "allow_generate") || args["allow_generate"] == null,
                conversationId = currentConversationIdOrNull(),
            )
            return when (val outcome = deps.formBridge.request(form)) {
                is VaultFormOutcome.Saved -> mapOf(
                    "saved" to true,
                    "item_id" to outcome.itemId,
                    "item" to outcome.itemName,
                    "created" to outcome.created,
                    "fields" to outcome.savedKeys,
                    "refs" to outcome.savedKeys.associate { key -> key to ref(outcome.itemId, key) },
                )

                VaultFormOutcome.Cancelled -> mapOf("saved" to false, "cancelled" to true)

                VaultFormOutcome.Locked -> lockedError()

                is VaultFormOutcome.Failed -> error(outcome.reason)
            }
        }

        private fun parseFieldSpecs(raw: Any?): List<VaultFormFieldSpec>? {
            val list = raw as? List<*> ?: return null
            if (list.isEmpty() || list.size > FIELD_KEY_LIMIT) return null
            return list.map { entry ->
                val map = entry as? Map<*, *> ?: return null
                val key = (map["key"] as? String)?.trim() ?: return null
                if (!FIELD_KEY_REGEX.matches(key)) return null
                val label = (map["label"] as? String)?.trim()?.takeIf { it.isNotEmpty() }?.take(60) ?: key
                val input = (map["input"] as? String)?.uppercase()?.let { input ->
                    VaultFieldInput.entries.firstOrNull { it.name == input } ?: return null
                } ?: VaultFieldInput.TEXT
                VaultFormFieldSpec(
                    key = key,
                    label = label,
                    input = input,
                    // Same tolerant parsing as boolArg: a provider pipeline may hand
                    // "required" over as the string "true".
                    required = when (val req = map["required"]) {
                        is Boolean -> req
                        is String -> req.toBooleanStrictOrNull() ?: false
                        else -> false
                    },
                    placeholder = (map["placeholder"] as? String)?.takeIf { it.isNotBlank() },
                )
            }
        }
    }

    // --- shared helpers -----------------------------------------------------------

    private fun itemNamesFor(deps: VaultToolDependencies, refs: List<Pair<String, String>>): List<String> {
        val doc = deps.session.document() ?: return refs.map { it.first }
        return refs.map { (id, _) -> doc.items.firstOrNull { it.id == id }?.name ?: id }.distinct()
    }

    private fun readFieldValues(deps: VaultToolDependencies, refs: List<Pair<String, String>>): Map<String, String>? {
        val doc = deps.session.document() ?: return null
        val values = HashMap<String, String>()
        for ((itemId, field) in refs) {
            val item = doc.items.firstOrNull { it.id == itemId } ?: return null
            val value = item.fields.firstOrNull { it.key == field }?.value ?: return null
            if (value.length > VAULT_VALUE_LIMIT) return null
            values[ref(itemId, field)] = value
        }
        return values
    }

    private val platformClient by lazy {
        httpClient {
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 15_000
            }
        }
    }

    private fun platformClient(): HttpClient = platformClient

    private const val GENERATED_PASSWORD_BYTES = 24
    private const val PASSWORD_KEY = "password"
}
