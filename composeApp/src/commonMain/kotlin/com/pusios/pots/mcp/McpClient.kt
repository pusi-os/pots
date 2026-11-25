package com.pusios.pots.mcp

import com.pusios.pots.httpClient
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/** What the manager needs from a live connection; tests fake it, production uses [McpClient]. */
interface McpSession {
    suspend fun initialize()

    suspend fun listTools(): List<McpToolDefinition>

    suspend fun callTool(name: String, arguments: JsonObject): String

    fun close()
}

/**
 * Streamable-HTTP JSON-RPC client: one POST per request, JSON or request-scoped SSE back,
 * `Mcp-Session-Id` carried when the server issues one.
 *
 * [tokenProvider] is read on every request, so a token refreshed in the background applies to the
 * next call without rebuilding the client. A non-2xx answer throws [McpHttpException] carrying
 * the status and the `WWW-Authenticate` challenge — that pair is the only way to tell "log in"
 * apart from "the server is down".
 */
class McpClient(
    private val url: String,
    private val headers: Map<String, String>,
    private val tokenProvider: () -> String? = { null },
) : McpSession {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val client: HttpClient = httpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 60_000
        }
    }
    private var sessionId: String? = null
    private var requestId = 0

    private fun nextId(): Int = ++requestId

    private fun HttpRequestBuilder.applyHeaders() {
        contentType(ContentType.Application.Json)
        header("Accept", "application/json, text/event-stream")
        sessionId?.let { header("Mcp-Session-Id", it) }
        tokenProvider()?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        this@McpClient.headers.forEach { (key, value) -> header(key, value) }
    }

    private suspend fun sendRequest(method: String, params: JsonElement? = null): JsonRpcResponse {
        val request = JsonRpcRequest(
            id = nextId(),
            method = method,
            params = params,
        )
        val requestBody = json.encodeToString(JsonRpcRequest.serializer(), request)

        val response = client.post(url) {
            applyHeaders()
            setBody(requestBody)
        }

        if (!response.status.isSuccess()) {
            throw McpHttpException(
                status = response.status.value,
                wwwAuthenticate = response.headers[HttpHeaders.WWWAuthenticate],
                body = response.bodyAsText().take(300),
            )
        }

        // Track session ID from response
        response.headers["Mcp-Session-Id"]?.let { sessionId = it }

        val responseText = response.bodyAsText()

        // Handle SSE response
        if (response.headers["Content-Type"]?.contains("text/event-stream") == true) {
            return parseSseResponse(responseText)
        }

        return try {
            json.decodeFromString(JsonRpcResponse.serializer(), responseText)
        } catch (e: Exception) {
            throw McpProtocolException("Unparsable JSON-RPC response: ${e.message ?: "decode error"}")
        }
    }

    private fun parseSseResponse(sseText: String): JsonRpcResponse {
        // Parse SSE format: look for "data: " lines and find the JSON-RPC response
        val lines = sseText.lines()
        val dataLines = lines.filter { it.startsWith("data: ") }
        for (line in dataLines) {
            val data = line.removePrefix("data: ").trim()
            if (data.isEmpty()) continue
            try {
                return json.decodeFromString(JsonRpcResponse.serializer(), data)
            } catch (_: Exception) {
                // Not a valid JSON-RPC response, continue
            }
        }
        throw McpProtocolException("No valid JSON-RPC response found in SSE stream")
    }

    override suspend fun initialize() {
        val params = buildJsonObject {
            put("protocolVersion", JsonPrimitive("2024-11-05"))
            put(
                "capabilities",
                buildJsonObject {},
            )
            put(
                "clientInfo",
                buildJsonObject {
                    put("name", JsonPrimitive("Pots"))
                    put("version", JsonPrimitive("1.0"))
                },
            )
        }
        val response = sendRequest("initialize", params)
        if (response.error != null) {
            throw McpProtocolException("Initialize failed: ${response.error.message}")
        }

        // Send initialized notification (no id, no response expected)
        sendNotification("notifications/initialized")
    }

    private suspend fun sendNotification(method: String) {
        val body = buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0"))
            put("method", JsonPrimitive(method))
        }
        val requestBody = json.encodeToString(JsonObject.serializer(), body)

        val response = client.post(url) {
            applyHeaders()
            setBody(requestBody)
        }
        if (!response.status.isSuccess()) {
            throw McpHttpException(
                status = response.status.value,
                wwwAuthenticate = response.headers[HttpHeaders.WWWAuthenticate],
                body = "",
            )
        }
    }

    override suspend fun listTools(): List<McpToolDefinition> {
        val response = sendRequest("tools/list")
        if (response.error != null) {
            throw McpProtocolException("tools/list failed: ${response.error.message}")
        }
        val result = response.result ?: return emptyList()
        val toolsResult = try {
            json.decodeFromJsonElement<McpToolsResult>(result)
        } catch (e: Exception) {
            throw McpProtocolException("tools/list returned an unexpected shape: ${e.message ?: "decode error"}")
        }
        return toolsResult.tools
    }

    override suspend fun callTool(name: String, arguments: JsonObject): String {
        val params = buildJsonObject {
            put("name", JsonPrimitive(name))
            put("arguments", arguments)
        }
        val response = sendRequest("tools/call", params)
        if (response.error != null) {
            throw McpProtocolException("tools/call failed: ${response.error.message}")
        }
        val result = response.result ?: return ""
        val callResult = json.decodeFromJsonElement<McpCallToolResult>(result)
        if (callResult.isError) {
            val errorText = callResult.content.mapNotNull { it.text }.joinToString("\n")
            throw McpException("Tool error: $errorText")
        }
        return callResult.content.mapNotNull { it.text }.joinToString("\n")
    }

    override fun close() {
        client.close()
    }
}

class McpException(message: String) : Exception(message)

/** The transport answered and said no. [wwwAuthenticate] is the 401/403 challenge, if any. */
class McpHttpException(
    val status: Int,
    val wwwAuthenticate: String?,
    body: String,
) : Exception("HTTP $status${if (body.isBlank()) "" else ": $body"}")

/** A 2xx that is not a usable JSON-RPC answer — a wrong endpoint, or a broken server. */
class McpProtocolException(message: String) : Exception(message)
