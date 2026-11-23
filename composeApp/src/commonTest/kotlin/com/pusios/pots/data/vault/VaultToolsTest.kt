// The leak test (card t_33958b4d criterion 1): "Save SECRET-MARKER-42, call every tool —
// the marker is nowhere: not in the JSON responses, not in the chat history, not in the
// outgoing provider requests." Plus the confirmation, lock and headless behaviors.

package com.pusios.pots.data.vault

import com.pusios.pots.data.Conversation
import com.pusios.pots.data.ConversationIdElement
import com.pusios.pots.data.ConversationJson
import com.pusios.pots.data.HeadlessRunElement
import com.pusios.pots.data.providers.buildAnthropicMessages
import com.pusios.pots.data.providers.sanitizeToolMessages
import com.pusios.pots.network.dtos.anthropic.AnthropicChatRequestDto
import com.pusios.pots.network.dtos.openaicompatible.OpenAICompatibleChatRequestDto
import com.pusios.pots.network.tools.Tool
import com.pusios.pots.ui.chat.History
import com.pusios.pots.ui.chat.ToolCallInfo
import com.pusios.pots.ui.chat.toGroqMessageDto
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLParameter
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val MARKER = "SECRET-MARKER-42"
private const val MASTER_PASSWORD = "test-master"

private class FakeVaultFileAccess : VaultFileAccess {
    private var bytes: ByteArray? = null

    override fun exists(): Boolean = bytes != null

    override fun read(): ByteArray? = bytes

    override fun writeAtomic(newBytes: ByteArray): Boolean {
        bytes = newBytes
        return true
    }

    override fun delete(): Boolean {
        bytes = null
        return true
    }

    override fun quarantineUnreadable(): String? = null
}

// Deterministic, non-cryptographic stand-in: these tests exercise channeling and gating,
// not the cipher. Real crypto is covered by the vault core tests.
private class FakeVaultCryptoEngine : VaultCryptoEngine {
    override fun randomBytes(size: Int) = ByteArray(size).also { kotlin.random.Random.Default.nextBytes(it) }

    @OptIn(ExperimentalEncodingApi::class)
    override fun encodeBase64(bytes: ByteArray): String = Base64.encode(bytes)

    @OptIn(ExperimentalEncodingApi::class)
    override fun decodeBase64(value: String): ByteArray? = try {
        Base64.decode(value)
    } catch (_: Exception) {
        null
    }

    override fun pbkdf2HmacSha256(
        password: CharArray,
        salt: ByteArray,
        iterations: Int,
        keyLengthBytes: Int,
    ): ByteArray {
        val pw = password.concatToString().encodeToByteArray()
        return ByteArray(keyLengthBytes) { i ->
            (pw[i % pw.size].toInt() xor salt[i % salt.size].toInt() xor iterations).toByte()
        }
    }

    override fun aesGcmEncrypt(key: ByteArray, iv: ByteArray, aad: ByteArray, plaintext: ByteArray) = plaintext.mapIndexed { i, b -> (b.toInt() xor key[i % key.size].toInt()).toByte() }.toByteArray()

    override fun aesGcmDecrypt(key: ByteArray, iv: ByteArray, aad: ByteArray, ciphertext: ByteArray) = ciphertext.mapIndexed { i, b -> (b.toInt() xor key[i % key.size].toInt()).toByte() }.toByteArray()
}

/** Records the requests the tools make so tests can assert what went on the wire. */
private class RecordingMock {
    val requests = mutableListOf<HttpRequestData>()

    fun client(): HttpClient = HttpClient(
        MockEngine { requestData ->
            requests.add(requestData)
            val body = when (val content = requestData.body) {
                is TextContent -> content.text
                is OutgoingContent.ByteArrayContent -> content.bytes().decodeToString()
                else -> ""
            }
            respond(
                content = "echo auth=${requestData.headers[HttpHeaders.Authorization].orEmpty()} body=$body",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/plain"),
            )
        },
    )
}

private object VaultTestHarness {
    const val ITEM_NAME = "GitHub"

    suspend fun session(): VaultSession {
        val session = VaultSession(VaultStore(FakeVaultFileAccess(), FakeVaultCryptoEngine()))
        session.createVault(MASTER_PASSWORD)
        session.upsert(
            VaultItem(
                id = "",
                name = ITEM_NAME,
                url = "https://github.com",
                username = "alice",
                fields = listOf(
                    VaultField(key = "password", label = "Password", type = VaultFieldKind.SECRET.id),
                    VaultField(key = "username", label = "Username", type = VaultFieldKind.TEXT.id, value = "alice"),
                ),
            ),
            mapOf("password" to MARKER),
        )
        return session
    }

    fun deps(
        session: VaultSession,
        httpClient: HttpClient? = null,
        gateTimeout: Duration = 5.seconds,
    ): VaultToolDependencies = VaultToolDependencies(
        session = session,
        store = VaultStore(FakeVaultFileAccess(), FakeVaultCryptoEngine()),
        formBridge = VaultFormBridge(session),
        revealBridge = VaultRevealBridge(session),
        gate = VaultGate(gateTimeout),
        httpClient = httpClient,
    )

    fun tool(deps: VaultToolDependencies, name: String): Tool = VaultTools.create(deps).first { it.schema.name == name }
}

/** ToolExecutor-style JSON serialization of a tool's result map. */
private fun toJsonElement(value: Any?): JsonElement = when (value) {
    null -> JsonPrimitive("null")
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to toJsonElement(v) })
    is List<*> -> JsonArray(value.map { toJsonElement(it) })
    else -> JsonPrimitive(value.toString())
}

private fun resultJson(result: Any): String = Json.encodeToString(
    JsonObject.serializer(),
    toJsonElement(result) as JsonObject,
)

class VaultToolsTest {

    // --- schemas carry no secret-value parameter anywhere -------------------------

    @Test
    fun `tool schemas carry no value parameter`() = runTest {
        val deps = VaultTestHarness.deps(VaultTestHarness.session())
        val schemas = VaultTools.create(deps).map { it.schema }
        assertEquals(7, schemas.size)
        for (schema in schemas) {
            val json = resultJson(schema.parameters)
            assertFalse("\"value\"" in json, "${schema.name} must not carry a value parameter")
            assertFalse("\"secret\"" in json, "${schema.name} must not carry a secret parameter")
        }
        val save = schemas.first { it.name == "vault_save" }
        assertFalse("\"fields\"" in resultJson(save.parameters), "vault_save must not take fields[].value")
        // Boolean flags are declared as booleans: a provider pipeline can hand "true"
        // over as a string, and the schema must not lie about the type either way.
        for (name in listOf("copy", "generate_password", "allow_generate")) {
            val schema = schemas.firstOrNull { it.parameters.containsKey(name) }
            assertNotNull(schema, "$name parameter must exist")
            assertEquals("boolean", schema.parameters.getValue(name).type, "$name must be declared boolean")
        }
    }

    @Test
    fun `vault_request_form timeout is 300 seconds`() = runTest {
        val deps = VaultTestHarness.deps(VaultTestHarness.session())
        assertEquals(300.seconds, VaultTestHarness.tool(deps, "vault_request_form").timeout)
    }

    // --- criterion 1: the marker is nowhere ---------------------------------------

    @Test
    fun `form entry saves to the vault and leaks nowhere`() = runTest {
        val session = VaultTestHarness.session()
        val mock = RecordingMock()
        val deps = VaultTestHarness.deps(session, httpClient = mock.client())
        val form = VaultTestHarness.tool(deps, "vault_request_form")

        val formArgs = mapOf(
            "mode" to "create",
            "name" to "Home server",
            "reason" to "Connect to the home server over SSH",
            "fields" to listOf(
                mapOf("key" to "password", "label" to "Password", "input" to "secret"),
                mapOf("key" to "username", "label" to "Username", "input" to "text"),
            ),
        )
        val formResult = CompletableDeferred<Any>()
        launch { formResult.complete(form.execute(formArgs)) }
        delay(100)
        val formId = assertNotNull(deps.formBridge.pending.value).id
        // The "user typing": the value enters through the sheet, never through arguments.
        val outcome = deps.formBridge.submit(formId, mapOf("password" to MARKER, "username" to "bob"))
        assertTrue(outcome is VaultFormOutcome.Saved)
        val savedJson = resultJson(formResult.await())
        val itemId = assertNotNull((formResult.await() as Map<*, *>)["item_id"]).toString()

        // Every other tool over the card that now holds the marker.
        val responses = mutableMapOf("vault_request_form" to savedJson)
        responses["vault_list"] = resultJson(
            VaultTestHarness.tool(deps, "vault_list").execute(emptyMap()),
        )
        responses["vault_get"] = resultJson(
            VaultTestHarness.tool(deps, "vault_get").execute(mapOf("item_id" to itemId)),
        )
        responses["vault_save"] = resultJson(
            VaultTestHarness.tool(deps, "vault_save").execute(mapOf("name" to "GitLab", "generate_password" to true)),
        )

        // vault_reveal with the owner's explicit confirmation; the value goes to the
        // overlay via confirm()'s return — never into a tool response.
        val revealResult = CompletableDeferred<Any>()
        launch {
            revealResult.complete(
                VaultTestHarness.tool(deps, "vault_reveal")
                    .execute(mapOf("item_id" to itemId, "field" to "password")),
            )
        }
        delay(100)
        val revealed = deps.revealBridge.confirm()
        assertEquals(MARKER, revealed, "the overlay receives the value from confirm()")
        responses["vault_reveal"] = resultJson(revealResult.await())

        // vault_http_request with the reference substitution, owner confirmed.
        val httpResult = CompletableDeferred<Any>()
        launch {
            httpResult.complete(
                VaultTestHarness.tool(deps, "vault_http_request").execute(
                    mapOf(
                        "url" to "https://api.github.com/user",
                        "headers" to mapOf("Authorization" to "Bearer vault://$itemId/password"),
                    ),
                ),
            )
        }
        delay(100)
        deps.gate.respond(assertNotNull(deps.gate.pending.value).id, allowed = true)
        responses["vault_http_request"] = resultJson(httpResult.await())

        // vault_form_fill with mapping, owner confirmed.
        val fillResult = CompletableDeferred<Any>()
        launch {
            fillResult.complete(
                VaultTestHarness.tool(deps, "vault_form_fill").execute(
                    mapOf(
                        "item_id" to itemId,
                        "url" to "https://github.com/login",
                        "mapping" to mapOf("login" to "username", "password" to "password"),
                    ),
                ),
            )
        }
        delay(100)
        deps.gate.respond(assertNotNull(deps.gate.pending.value).id, allowed = true)
        responses["vault_form_fill"] = resultJson(fillResult.await())

        // (а) JSON responses: no marker.
        for ((name, json) in responses) {
            assertFalse(MARKER in json, "marker leaked through the $name response")
        }

        // (б)/(в) History entries of every role, serialized the way messageJson
        // persists them (encodeDefaults = true): no marker.
        val messages = responses.map { (name, json) ->
            Conversation.Message(id = name, role = "tool", content = json)
        } + Conversation.Message(
            id = "assistant",
            role = "assistant",
            content = "Saved your credentials as vault://$itemId/password.",
        )
        val persisted = ConversationJson.encodeToString(messages)
        assertFalse(MARKER in persisted, "marker leaked into the persisted history")

        // (г) The provider payload, built the way RemoteDataRepository builds it for
        // both provider families: [OI]-compatible (toGroqMessageDto + sanitizeToolMessages)
        // and Anthropic (toAnthropicContentBlocks). The assistant tool-call turn is
        // included so the round-tripped arguments path is covered too.
        val callId = "call_1"
        val history = buildList {
            add(History(role = History.Role.USER, content = "Store my credentials"))
            add(
                History(
                    role = History.Role.ASSISTANT,
                    content = "",
                    toolCalls = persistentListOf(
                        ToolCallInfo(
                            id = callId,
                            name = "vault_request_form",
                            arguments = resultJson(formArgs),
                        ),
                    ),
                ),
            )
            responses.forEach { (name, json) ->
                add(History(role = History.Role.TOOL, content = json, toolCallId = callId, toolName = name))
            }
            add(History(role = History.Role.ASSISTANT, content = "Saved as vault://$itemId/password."))
        }
        val dtoMessages = sanitizeToolMessages(history.map { it.toGroqMessageDto() })
        assertTrue(dtoMessages.any { it.role == "tool" }, "the payload must actually carry the tool results")
        assertTrue(dtoMessages.any { it.tool_calls != null }, "the payload must carry the assistant tool-call turn")
        val providerPayload = Json.encodeToString(
            ListSerializer(OpenAICompatibleChatRequestDto.Message.serializer()),
            dtoMessages,
        )
        assertFalse(MARKER in providerPayload, "marker leaked into the [OI]-compatible provider payload")
        val anthropicPayload = Json.encodeToString(
            ListSerializer(AnthropicChatRequestDto.Message.serializer()),
            buildAnthropicMessages(history),
        )
        assertFalse(MARKER in anthropicPayload, "marker leaked into the Anthropic provider payload")

        // The target server legitimately sees the value (transport substitution) and
        // the echoing response is scrubbed.
        val httpJson = responses.getValue("vault_http_request")
        assertTrue("[redacted]" in httpJson, "the echoed secret must be scrubbed from the response")
        assertTrue(mock.requests.isNotEmpty(), "the mock server should have seen the requests")
        val wireAuth = mock.requests.mapNotNull { it.headers[HttpHeaders.Authorization] }
        assertTrue(wireAuth.any { MARKER in it }, "the transport substitution must place the value on the wire")

        // The generated password of vault_save is also nowhere in the responses.
        val generated = session.document()!!.items
            .first { it.name == "GitLab" }
            .fields.first { it.key == "password" }.value
        assertTrue(generated.isNotEmpty())
        for (json in responses.values) {
            assertFalse(generated in json, "the generated password leaked through a response")
        }
    }

    // --- criterion 2: confirmations -----------------------------------------------

    @Test
    fun `reveal does nothing without the owner's confirmation`() = runTest {
        val session = VaultTestHarness.session()
        val deps = VaultTestHarness.deps(session)
        val reveal = VaultTestHarness.tool(deps, "vault_reveal")
        val itemId = session.document()!!.items.first { it.name == VaultTestHarness.ITEM_NAME }.id

        val result = CompletableDeferred<Any>()
        launch { result.complete(reveal.execute(mapOf("item_id" to itemId, "field" to "password"))) }
        delay(100)
        // The owner has not answered: no outcome yet. The value is held for the overlay
        // only — it is not in any result.
        assertFalse(result.isCompleted, "reveal must wait for the owner")
        assertNotNull(deps.revealBridge.pending.value)
        deps.revealBridge.deny()
        val denied = result.await() as Map<*, *>
        assertEquals("denied by user", denied["error"])
        assertNull(deps.revealBridge.pending.value)
        assertNull(deps.revealBridge.confirm(), "a denied reveal hands over no value")
    }

    @Test
    fun `reveal hands the value to the owner exactly once`() = runTest {
        val session = VaultTestHarness.session()
        val deps = VaultTestHarness.deps(session)
        val itemId = session.document()!!.items.first { it.name == VaultTestHarness.ITEM_NAME }.id

        val result = CompletableDeferred<Any>()
        launch {
            result.complete(
                VaultTestHarness.tool(deps, "vault_reveal")
                    .execute(mapOf("item_id" to itemId, "field" to "password")),
            )
        }
        delay(100)
        assertNotNull(deps.revealBridge.pending.value)
        // confirm() is the atomic handoff: the value comes back exactly once and cannot
        // be raced away by the tool coroutine's cleanup.
        assertEquals(MARKER, deps.revealBridge.confirm())
        assertNull(deps.revealBridge.confirm(), "the value is handed over exactly once")
        assertNull(deps.revealBridge.pending.value)
        val response = result.await() as Map<*, *>
        assertEquals(true, response["shown"])
        assertFalse(MARKER in resultJson(response), "the revealed value is not in the tool response")
    }

    @Test
    fun `form write does not happen without the user submitting`() = runTest {
        val session = VaultTestHarness.session()
        val deps = VaultTestHarness.deps(session)
        val form = VaultTestHarness.tool(deps, "vault_request_form")
        val before = session.document()!!.items.size

        val result = CompletableDeferred<Any>()
        launch {
            result.complete(
                form.execute(
                    mapOf(
                        "mode" to "create",
                        "name" to "Cancelled card",
                        "reason" to "Store a token",
                        "fields" to listOf(mapOf("key" to "token", "label" to "Token")),
                    ),
                ),
            )
        }
        delay(100)
        assertFalse(result.isCompleted)
        assertEquals(before, session.document()!!.items.size, "nothing is written before submit")
        deps.formBridge.cancel(deps.formBridge.pending.value!!.id)
        val cancelled = result.await() as Map<*, *>
        assertEquals(true, cancelled["cancelled"])
        assertEquals(before, session.document()!!.items.size, "cancel writes nothing")
        assertNull(deps.formBridge.pending.value, "cancel clears the pending form")
    }

    // --- update keeps the card shape ------------------------------------------------

    @Test
    fun `vault_save update keeps the card type and parses string booleans`() = runTest {
        val session = VaultTestHarness.session()
        val deps = VaultTestHarness.deps(session)
        val save = VaultTestHarness.tool(deps, "vault_save")

        // Providers may hand booleans over as strings ("true"); generation must work.
        val created = save.execute(mapOf("name" to "Router", "type" to "ssh_key", "generate_password" to "true")) as Map<*, *>
        assertEquals(true, created["generated"], "generate_password=\"true\" must generate a password")
        assertEquals(true, created["saved"])
        val id = created["id"].toString()

        // An update without a type keeps ssh_key instead of silently resetting to login.
        save.execute(mapOf("item_id" to id, "name" to "Router v2"))
        val card = session.document()!!.items.first { it.id == id }
        assertEquals("ssh_key", card.type, "update must not reset the card type")
        assertEquals("Router v2", card.name)
    }

    @Test
    fun `form update without item_id fails and a typed update keeps the card type`() = runTest {
        val session = VaultTestHarness.session()
        val deps = VaultTestHarness.deps(session)
        val form = VaultTestHarness.tool(deps, "vault_request_form")

        // mode=update without item_id is an error — not a silent Untitled duplicate.
        val missing = form.execute(
            mapOf(
                "mode" to "update",
                "reason" to "Rotate a key",
                "fields" to listOf(mapOf("key" to "password", "label" to "Password")),
            ),
        ) as Map<*, *>
        assertEquals("item_id is required when mode=update", missing["error"])
        assertNull(deps.formBridge.pending.value, "the rejected form must not reach the UI")

        // Create an ssh_key card through the form, then update it without a type.
        val created = CompletableDeferred<Any>()
        launch {
            created.complete(
                form.execute(
                    mapOf(
                        "mode" to "create",
                        "name" to "Router",
                        "type" to "ssh_key",
                        "reason" to "Store the key",
                        "fields" to listOf(mapOf("key" to "private_key", "label" to "Private key", "input" to "secret_multiline")),
                    ),
                ),
            )
        }
        delay(100)
        val createOutcome = deps.formBridge.submit(
            deps.formBridge.pending.value!!.id,
            mapOf("private_key" to MARKER),
        ) as VaultFormOutcome.Saved
        assertTrue(createOutcome.created)
        assertEquals("Router", createOutcome.itemName, "the form response must carry the item name")
        val itemId = (created.await() as Map<*, *>)["item_id"].toString()

        val updated = CompletableDeferred<Any>()
        launch {
            updated.complete(
                form.execute(
                    mapOf(
                        "mode" to "update",
                        "item_id" to itemId,
                        "reason" to "Rotate the key",
                        "fields" to listOf(mapOf("key" to "private_key", "label" to "Private key", "input" to "secret_multiline")),
                    ),
                ),
            )
        }
        delay(100)
        val updateOutcome = deps.formBridge.submit(
            deps.formBridge.pending.value!!.id,
            mapOf("private_key" to "$MARKER-rotated"),
        ) as VaultFormOutcome.Saved
        assertFalse(updateOutcome.created, "update must not create a card")
        val card = session.document()!!.items.first { it.id == itemId }
        assertEquals("ssh_key", card.type, "form update must not reset the card type")
        assertFalse(session.document()!!.items.any { it.name == "Untitled" }, "no duplicate Untitled card")
        assertEquals(true, (updated.await() as Map<*, *>)["saved"])
    }

    @Test
    fun `form submit refuses values over the 8 KB limit`() = runTest {
        val session = VaultTestHarness.session()
        val deps = VaultTestHarness.deps(session)
        val form = VaultTestHarness.tool(deps, "vault_request_form")
        val before = session.document()!!.items.size

        val result = CompletableDeferred<Any>()
        launch {
            result.complete(
                form.execute(
                    mapOf(
                        "mode" to "create",
                        "name" to "Too big",
                        "reason" to "Store a key",
                        "fields" to listOf(mapOf("key" to "private_key", "label" to "Private key", "input" to "secret_multiline")),
                    ),
                ),
            )
        }
        delay(100)
        val oversized = "x".repeat(VAULT_VALUE_LIMIT + 1)
        val outcome = deps.formBridge.submit(deps.formBridge.pending.value!!.id, mapOf("private_key" to oversized))
        assertTrue(outcome is VaultFormOutcome.Failed, "an over-limit value must be refused")
        assertEquals(before, session.document()!!.items.size, "a refused submit writes nothing")
    }

    // --- scrubbing covers encoded forms --------------------------------------------

    @Test
    fun `form fill scrubs raw and encoded forms of the secret`() = runTest {
        val session = VaultTestHarness.session()
        val deps = VaultTestHarness.deps(session, httpClient = RecordingMock().client())
        // A password with special characters: its URL-encoded and JSON-escaped shapes
        // differ from the raw value, so a raw-only scrub would leak them.
        val secret = "p@ss w0rd&x"
        session.upsert(
            VaultItem(
                id = "",
                name = "Special",
                fields = listOf(VaultField(key = "password", label = "Password", type = VaultFieldKind.SECRET.id)),
            ),
            mapOf("password" to secret),
        )
        val itemId = session.document()!!.items.first { it.name == "Special" }.id

        for (contentType in listOf("application/x-www-form-urlencoded", "application/json")) {
            val result = CompletableDeferred<Any>()
            launch {
                result.complete(
                    VaultTestHarness.tool(deps, "vault_form_fill").execute(
                        mapOf(
                            "item_id" to itemId,
                            "url" to "https://echo.test/login",
                            "content_type" to contentType,
                            "mapping" to mapOf("password" to "password"),
                        ),
                    ),
                )
            }
            delay(100)
            deps.gate.respond(deps.gate.pending.value!!.id, allowed = true)
            val response = result.await() as Map<*, *>
            assertEquals(true, response["ok"])
            val body = response["body"].toString()
            assertTrue("[redacted]" in body, "the echoed secret must be scrubbed ($contentType)")
            assertFalse(secret in body, "raw secret must be scrubbed ($contentType)")
            assertFalse(secret.encodeURLParameter() in body, "url-encoded secret must be scrubbed ($contentType)")
        }
    }

    // --- criterion 3: a locked vault answers with an error, never crashes ----------

    @Test
    fun `locked vault answers vault_locked for every tool`() = runTest {
        val session = VaultTestHarness.session()
        session.lock()
        val deps = VaultTestHarness.deps(session)

        val lockedResponses = mapOf(
            "vault_list" to VaultTestHarness.tool(deps, "vault_list").execute(emptyMap()),
            "vault_get" to VaultTestHarness.tool(deps, "vault_get").execute(mapOf("item_id" to "x")),
            "vault_save" to VaultTestHarness.tool(deps, "vault_save").execute(mapOf("name" to "X")),
            "vault_reveal" to VaultTestHarness.tool(deps, "vault_reveal")
                .execute(mapOf("item_id" to "x", "field" to "password")),
            "vault_http_request" to VaultTestHarness.tool(deps, "vault_http_request")
                .execute(mapOf("url" to "https://x", "headers" to mapOf("A" to "Bearer vault://i/f"))),
        )
        for ((name, response) in lockedResponses) {
            assertEquals("vault_locked", (response as Map<*, *>)["error"], "$name must report the lock")
        }
        // The request form is not a locked-vault error: the sheet offers an unlock step
        // first, so it stays cancellable.
        val formResult = CompletableDeferred<Any>()
        launch {
            formResult.complete(
                VaultTestHarness.tool(deps, "vault_request_form").execute(
                    mapOf(
                        "mode" to "create",
                        "reason" to "Store a password",
                        "fields" to listOf(mapOf("key" to "password", "label" to "Password")),
                    ),
                ),
            )
        }
        delay(100)
        deps.formBridge.cancel(deps.formBridge.pending.value!!.id)
        assertEquals(true, (formResult.await() as Map<*, *>)["cancelled"])
    }

    // --- headless runs -------------------------------------------------------------

    @Test
    fun `headless run refuses the request form immediately`() = runTest {
        val deps = VaultTestHarness.deps(VaultTestHarness.session())
        // §6.2: the refusal is immediate — well under a second, not a timeout wait.
        val result = withTimeout(1.seconds) {
            withContext(HeadlessRunElement()) {
                VaultTestHarness.tool(deps, "vault_request_form").execute(
                    mapOf(
                        "mode" to "create",
                        "reason" to "Heartbeat wants credentials",
                        "fields" to listOf(mapOf("key" to "password", "label" to "Password")),
                    ),
                )
            }
        } as Map<*, *>
        assertEquals("vault_form_unavailable", result["error"])
        assertNull(deps.formBridge.pending.value)
    }

    @Test
    fun `headless run refuses vault_reveal immediately`() = runTest {
        val deps = VaultTestHarness.deps(VaultTestHarness.session())
        val result = withTimeout(1.seconds) {
            withContext(HeadlessRunElement()) {
                VaultTestHarness.tool(deps, "vault_reveal").execute(
                    mapOf("item_id" to "x", "field" to "password"),
                )
            }
        } as Map<*, *>
        assertEquals("vault_reveal_unavailable", result["error"])
        assertNull(deps.revealBridge.pending.value)
    }

    // --- references round-trip -------------------------------------------------------

    @Test
    fun `form refs resolve through vault_get and drive vault_http_request`() = runTest {
        val session = VaultTestHarness.session()
        val deps = VaultTestHarness.deps(session, httpClient = RecordingMock().client())
        val form = VaultTestHarness.tool(deps, "vault_request_form")

        val formResult = CompletableDeferred<Any>()
        launch {
            formResult.complete(
                form.execute(
                    mapOf(
                        "mode" to "create",
                        "name" to "Router",
                        "reason" to "Update the router firmware",
                        "fields" to listOf(mapOf("key" to "password", "label" to "Password")),
                    ),
                ),
            )
        }
        delay(100)
        deps.formBridge.submit(deps.formBridge.pending.value!!.id, mapOf("password" to MARKER))
        val refs = (formResult.await() as Map<*, *>)["refs"] as Map<*, *>
        val routerRef = refs["password"].toString()

        // vault_get returns the same reference for the same item.
        val routerId = routerRef.removePrefix("vault://").substringBefore('/')
        val got = VaultTestHarness.tool(deps, "vault_get")
            .execute(mapOf("item_id" to routerId, "field" to "password")) as Map<*, *>
        assertEquals(routerRef, got["ref"])

        // And the reference drives an authenticated request.
        val httpResult = CompletableDeferred<Any>()
        launch {
            httpResult.complete(
                VaultTestHarness.tool(deps, "vault_http_request").execute(
                    mapOf(
                        "url" to "https://router.local/api",
                        "headers" to mapOf("Authorization" to "Bearer $routerRef"),
                    ),
                ),
            )
        }
        delay(100)
        deps.gate.respond(deps.gate.pending.value!!.id, allowed = true)
        val http = httpResult.await() as Map<*, *>
        assertEquals(true, http["ok"])
    }

    // --- conversation scope -----------------------------------------------------------

    @Test
    fun `submit from another conversation is cancelled`() = runTest {
        val session = VaultTestHarness.session()
        val deps = VaultTestHarness.deps(session)
        val form = VaultTestHarness.tool(deps, "vault_request_form")
        val before = session.document()!!.items.size

        val result = CompletableDeferred<Any>()
        launch {
            result.complete(
                form.execute(
                    mapOf(
                        "mode" to "create",
                        "name" to "Scoped",
                        "reason" to "Store a password",
                        "fields" to listOf(mapOf("key" to "password", "label" to "Password")),
                    ),
                ),
            )
        }
        delay(100)
        val formId = deps.formBridge.pending.value!!.id
        // A sheet answering from a different conversation must be refused.
        val outcome = withContext(ConversationIdElement("other-conversation")) {
            deps.formBridge.submit(formId, mapOf("password" to MARKER))
        }
        assertTrue(outcome is VaultFormOutcome.Cancelled)
        assertEquals(before, session.document()!!.items.size, "a foreign-conversation submit writes nothing")
    }
}
