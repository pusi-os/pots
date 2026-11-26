// MCP authorization, client half (MCP spec 2026-07-28 §basic/authorization).
//
// The chain is fixed by the spec and every step is a plain HTTP request, so it lives here as
// pure-ish functions plus one small client: 401 + WWW-Authenticate → Protected Resource
// Metadata (RFC 9728) → authorization-server metadata (RFC 8414 / OIDC) → Dynamic Client
// Registration → PKCE S256 authorize → loopback redirect → token exchange → refresh.
//
// Nothing in this file logs or returns a token except to the caller that stores it in the
// secure settings (McpTokenStore). Tokens never reach a tool result, the chat, or a log line.

package com.pusios.pots.mcp

import com.pusios.pots.data.AppSettings
import com.pusios.pots.httpClient
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Clock

// ---------------------------------------------------------------- WWW-Authenticate

/** A parsed `WWW-Authenticate` challenge. Only the parameters the flow needs are surfaced. */
data class WwwAuthenticate(
    val scheme: String,
    val params: Map<String, String>,
) {
    /** RFC 9728: where the protected-resource metadata document lives. */
    val resourceMetadataUrl: String? get() = params["resource_metadata"]

    val scope: String? get() = params["scope"]

    /** `invalid_token`, `insufficient_scope`, … — present only on a 401/403 body challenge. */
    val error: String? get() = params["error"]
}

/**
 * Parses the first Bearer challenge of a `WWW-Authenticate` header.
 *
 * Returns null when the header is absent or offers no Bearer scheme: a server that answers 401
 * without one has no OAuth metadata to follow, and the UI then offers a manual token instead.
 */
fun parseWwwAuthenticate(header: String?): WwwAuthenticate? {
    val text = header?.trim().orEmpty()
    if (text.isEmpty()) return null
    val schemeEnd = text.indexOfFirst { it == ' ' || it == '\t' }
    if (schemeEnd <= 0) return null
    val scheme = text.substring(0, schemeEnd)
    if (!scheme.equals("Bearer", ignoreCase = true)) return null
    return WwwAuthenticate(scheme, parseAuthParams(text.substring(schemeEnd + 1)))
}

/** `key=value` / `key="quoted, value"` pairs of one challenge, keys lowercased. */
internal fun parseAuthParams(text: String): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    var i = 0
    while (i < text.length) {
        while (i < text.length && (text[i] == ',' || text[i].isWhitespace())) i++
        if (i >= text.length) break
        val keyStart = i
        while (i < text.length && text[i] != '=' && text[i] != ',') i++
        if (i >= text.length || text[i] != '=') break
        val key = text.substring(keyStart, i).trim()
        i++
        val value: String
        if (i < text.length && text[i] == '"') {
            i++
            val sb = StringBuilder()
            while (i < text.length && text[i] != '"') {
                if (text[i] == '\\' && i + 1 < text.length) {
                    i++
                    sb.append(text[i])
                } else {
                    sb.append(text[i])
                }
                i++
            }
            if (i < text.length) i++ // closing quote
            value = sb.toString()
        } else {
            val start = i
            while (i < text.length && text[i] != ',') i++
            value = text.substring(start, i).trim()
        }
        if (key.isNotEmpty()) out[key.lowercase()] = value
    }
    return out
}

// ---------------------------------------------------------------- PKCE

/**
 * RFC 7636 S256. SHA-256 and base64url are implemented here because Kotlin common has no
 * crypto: the app's vault engine is platform-specific and async, and pulling a crypto library
 * into the common source set for 60 lines of digest is not worth the dependency.
 */
object Pkce {
    /** 43 base64url characters = 256 bits of entropy from two UUIDs' worth of platform randomness. */
    fun newVerifier(): String = base64UrlNoPad(randomBytes(32))

    fun challenge(verifier: String): String = base64UrlNoPad(sha256(verifier.encodeToByteArray()))
}

/** Platform RNG through [kotlin.uuid.Uuid], which is backed by SecureRandom / SecRandomCopyBytes. */
@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
internal fun randomBytes(size: Int): ByteArray {
    val out = ByteArray(size)
    var filled = 0
    while (filled < size) {
        val chunk = kotlin.uuid.Uuid.random().toByteArray()
        val take = minOf(chunk.size, size - filled)
        chunk.copyInto(out, filled, 0, take)
        filled += take
    }
    return out
}

private val BASE64URL_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray()

/** base64url without padding (RFC 4648 §5) — what PKCE and JWTs use. */
internal fun base64UrlNoPad(bytes: ByteArray): String {
    val sb = StringBuilder((bytes.size * 4 + 2) / 3)
    var i = 0
    while (i + 2 < bytes.size) {
        val n = ((bytes[i].toInt() and 0xFF) shl 16) or
            ((bytes[i + 1].toInt() and 0xFF) shl 8) or
            (bytes[i + 2].toInt() and 0xFF)
        sb.append(BASE64URL_ALPHABET[(n ushr 18) and 0x3F])
        sb.append(BASE64URL_ALPHABET[(n ushr 12) and 0x3F])
        sb.append(BASE64URL_ALPHABET[(n ushr 6) and 0x3F])
        sb.append(BASE64URL_ALPHABET[n and 0x3F])
        i += 3
    }
    when (bytes.size - i) {
        1 -> {
            val n = (bytes[i].toInt() and 0xFF) shl 16
            sb.append(BASE64URL_ALPHABET[(n ushr 18) and 0x3F])
            sb.append(BASE64URL_ALPHABET[(n ushr 12) and 0x3F])
        }

        2 -> {
            val n = ((bytes[i].toInt() and 0xFF) shl 16) or ((bytes[i + 1].toInt() and 0xFF) shl 8)
            sb.append(BASE64URL_ALPHABET[(n ushr 18) and 0x3F])
            sb.append(BASE64URL_ALPHABET[(n ushr 12) and 0x3F])
            sb.append(BASE64URL_ALPHABET[(n ushr 6) and 0x3F])
        }
    }
    return sb.toString()
}

private val SHA256_K = intArrayOf(
    1116352408, 1899447441, -1245643825, -373957723, 961987163, 1508970993, -1841331548, -1424204075,
    -670586216, 310598401, 607225278, 1426881987, 1925078388, -2132889090, -1680079193, -1046744716,
    -459576895, -272742522, 264347078, 604807628, 770255983, 1249150122, 1555081692, 1996064986,
    -1740746414, -1473132947, -1341970488, -1084653625, -958395405, -710438585, 113926993, 338241895,
    666307205, 773529912, 1294757372, 1396182291, 1695183700, 1986661051, -2117940946, -1838011259,
    -1564481375, -1474664885, -1035236496, -949202525, -778901479, -694614492, -200395387, 275423344,
    430227734, 506948616, 659060556, 883997877, 958139571, 1322822218, 1537002063, 1747873779,
    1955562222, 2024104815, -2067236844, -1933114872, -1866530822, -1538233109, -1090935817, -965641998,
)

internal fun sha256(input: ByteArray): ByteArray {
    val h = intArrayOf(
        1779033703,
        -1150833019,
        1013904242,
        -1521486534,
        1359893119,
        -1694144372,
        528734635,
        1541459225,
    )
    val bitLength = input.size.toLong() * 8
    val paddedSize = ((input.size + 9 + 63) / 64) * 64
    val message = ByteArray(paddedSize)
    input.copyInto(message)
    message[input.size] = 0x80.toByte()
    for (i in 0 until 8) {
        message[paddedSize - 1 - i] = ((bitLength ushr (8 * i)) and 0xFF).toByte()
    }

    val w = IntArray(64)
    var offset = 0
    while (offset < paddedSize) {
        for (i in 0 until 16) {
            val base = offset + i * 4
            w[i] = ((message[base].toInt() and 0xFF) shl 24) or
                ((message[base + 1].toInt() and 0xFF) shl 16) or
                ((message[base + 2].toInt() and 0xFF) shl 8) or
                (message[base + 3].toInt() and 0xFF)
        }
        for (i in 16 until 64) {
            val s0 = w[i - 15].rotateRight(7) xor w[i - 15].rotateRight(18) xor (w[i - 15] ushr 3)
            val s1 = w[i - 2].rotateRight(17) xor w[i - 2].rotateRight(19) xor (w[i - 2] ushr 10)
            w[i] = w[i - 16] + s0 + w[i - 7] + s1
        }

        var a = h[0]
        var b = h[1]
        var c = h[2]
        var d = h[3]
        var e = h[4]
        var f = h[5]
        var g = h[6]
        var hh = h[7]
        for (i in 0 until 64) {
            val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
            val ch = (e and f) xor (e.inv() and g)
            val temp1 = hh + s1 + ch + SHA256_K[i] + w[i]
            val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
            val maj = (a and b) xor (a and c) xor (b and c)
            val temp2 = s0 + maj
            hh = g
            g = f
            f = e
            e = d + temp1
            d = c
            c = b
            b = a
            a = temp1 + temp2
        }
        h[0] += a
        h[1] += b
        h[2] += c
        h[3] += d
        h[4] += e
        h[5] += f
        h[6] += g
        h[7] += hh
        offset += 64
    }

    val out = ByteArray(32)
    for (i in 0 until 8) {
        out[i * 4] = ((h[i] ushr 24) and 0xFF).toByte()
        out[i * 4 + 1] = ((h[i] ushr 16) and 0xFF).toByte()
        out[i * 4 + 2] = ((h[i] ushr 8) and 0xFF).toByte()
        out[i * 4 + 3] = (h[i] and 0xFF).toByte()
    }
    return out
}

private fun Int.rotateRight(bits: Int): Int = (this ushr bits) or (this shl (32 - bits))

// ---------------------------------------------------------------- discovery

/** Everything needed to run an authorization: where to send the owner, and where to exchange. */
data class McpAuthEndpoints(
    val issuer: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val registrationEndpoint: String?,
    val scopesSupported: List<String>,
    /** RFC 8707 resource indicator: the canonical URI of the MCP server itself. */
    val resource: String,
)

@Serializable
data class McpClientRegistration(
    val clientId: String,
    val clientSecret: String? = null,
)

@Serializable
data class McpTokenResponse(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresInSeconds: Long = 0,
    val scope: String? = null,
)

/** A token plus the registration and issuer it belongs to. Stored in the secure settings. */
@Serializable
data class McpStoredToken(
    val issuer: String,
    val tokenEndpoint: String,
    val clientId: String,
    val clientSecret: String? = null,
    val accessToken: String,
    val refreshToken: String? = null,
    val scope: String? = null,
    val resource: String,
    /** 0 = the server did not say; treat the token as long-lived and re-check on 401. */
    val expiresAtEpochMs: Long = 0,
) {
    fun isExpired(nowMs: Long = Clock.System.now().toEpochMilliseconds(), skewMs: Long = 60_000): Boolean = expiresAtEpochMs != 0L && nowMs + skewMs >= expiresAtEpochMs
}

/**
 * Tokens and client registrations, one record per server id, in the secure settings store
 * (Android Keystore / iOS Keychain / encrypted file). Kept out of `McpServerConfig` so an
 * export of the settings never carries a bearer token.
 */
class McpTokenStore(private val appSettings: AppSettings) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun load(serverId: String): McpStoredToken? = readAll()[serverId]

    fun save(serverId: String, token: McpStoredToken) {
        writeAll(readAll() + (serverId to token))
    }

    fun clear(serverId: String) {
        val all = readAll()
        if (serverId in all) writeAll(all - serverId)
    }

    fun has(serverId: String): Boolean = serverId in readAll()

    private fun readAll(): Map<String, McpStoredToken> = try {
        val raw = appSettings.getMcpTokensJson()
        if (raw.isBlank()) emptyMap() else json.decodeFromString(tokensSerializer, raw)
    } catch (_: Exception) {
        emptyMap()
    }

    private fun writeAll(tokens: Map<String, McpStoredToken>) {
        appSettings.setMcpTokensJson(json.encodeToString(tokensSerializer, tokens))
    }

    private companion object {
        val tokensSerializer = MapSerializer(String.serializer(), McpStoredToken.serializer())
    }
}

/** The discovery + registration + token half of the flow. Network only, no UI, no storage. */
class McpAuthClient(private val http: HttpClient = defaultMcpAuthHttp()) {

    /** The 401 challenge of the MCP endpoint, or null when it does not challenge at all. */
    suspend fun probeChallenge(url: String, headers: Map<String, String> = emptyMap()): WwwAuthenticate? = try {
        val response = http.post(url) {
            contentType(ContentType.Application.Json)
            header("Accept", "application/json, text/event-stream")
            headers.forEach { (key, value) -> header(key, value) }
            setBody("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""")
        }
        if (response.status.value == 401) {
            parseWwwAuthenticate(response.headers[HttpHeaders.WWWAuthenticate])
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Protected Resource Metadata → authorization-server metadata. Fails with a human-readable
     * reason: "no PRM", "no AS metadata", "no PKCE S256" — the UI shows it verbatim.
     */
    suspend fun discover(resourceUrl: String, challenge: WwwAuthenticate?): Result<McpAuthEndpoints> {
        val prm = fetchResourceMetadata(resourceUrl, challenge).getOrElse { return Result.failure(it) }
        val issuer = prm["authorization_servers"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content
            ?: return Result.failure(McpAuthException("Protected Resource Metadata has no authorization_servers"))
        val resource = prm["resource"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: canonicalResource(resourceUrl)

        val metadata = fetchAuthServerMetadata(issuer).getOrElse { return Result.failure(it) }
        val methods = metadata["code_challenge_methods_supported"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.content }?.toSet().orEmpty()
        if ("S256" !in methods) {
            return Result.failure(McpAuthException("Authorization server does not support PKCE S256"))
        }
        val authorizationEndpoint = metadata["authorization_endpoint"]?.jsonPrimitive?.content
            ?: return Result.failure(McpAuthException("Authorization server metadata has no authorization_endpoint"))
        val tokenEndpoint = metadata["token_endpoint"]?.jsonPrimitive?.content
            ?: return Result.failure(McpAuthException("Authorization server metadata has no token_endpoint"))

        return Result.success(
            McpAuthEndpoints(
                issuer = issuer,
                authorizationEndpoint = authorizationEndpoint,
                tokenEndpoint = tokenEndpoint,
                registrationEndpoint = metadata["registration_endpoint"]?.jsonPrimitive?.content,
                scopesSupported = metadata["scopes_supported"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.content }.orEmpty(),
                resource = resource,
            ),
        )
    }

    /** Dynamic Client Registration, native client, no secret (PKCE is the proof). */
    suspend fun register(
        endpoints: McpAuthEndpoints,
        redirectUri: String,
        scope: String?,
    ): Result<McpClientRegistration> {
        val registrationEndpoint = endpoints.registrationEndpoint
            ?: return Result.failure(McpAuthException("Authorization server does not offer dynamic registration"))
        val body = buildJsonObject {
            put("client_name", "Pots")
            put("application_type", "native")
            put(
                "grant_types",
                buildJsonArray {
                    add(JsonPrimitive("authorization_code"))
                    add(JsonPrimitive("refresh_token"))
                },
            )
            put("response_types", buildJsonArray { add(JsonPrimitive("code")) })
            put("token_endpoint_auth_method", "none")
            put("redirect_uris", buildJsonArray { add(JsonPrimitive(redirectUri)) })
            if (!scope.isNullOrBlank()) put("scope", scope)
        }
        return try {
            val response = http.post(registrationEndpoint) {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
            if (response.status.value !in 200..299) {
                return Result.failure(McpAuthException("Client registration failed: HTTP ${response.status.value}"))
            }
            val parsed = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val clientId = parsed["client_id"]?.jsonPrimitive?.content
                ?: return Result.failure(McpAuthException("Client registration returned no client_id"))
            Result.success(
                McpClientRegistration(
                    clientId = clientId,
                    clientSecret = parsed["client_secret"]?.jsonPrimitive?.content,
                ),
            )
        } catch (e: Exception) {
            Result.failure(McpAuthException("Client registration failed: ${e.message ?: "network error"}"))
        }
    }

    suspend fun exchangeCode(
        tokenEndpoint: String,
        code: String,
        codeVerifier: String,
        redirectUri: String,
        clientId: String,
        clientSecret: String?,
        resource: String,
    ): Result<McpTokenResponse> = tokenRequest(
        tokenEndpoint = tokenEndpoint,
        fields = listOfNotNull(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to redirectUri,
            "client_id" to clientId,
            "code_verifier" to codeVerifier,
            "resource" to resource,
            clientSecret?.let { "client_secret" to it },
        ),
    )

    suspend fun refresh(
        token: McpStoredToken,
    ): Result<McpTokenResponse> = tokenRequest(
        tokenEndpoint = token.tokenEndpoint,
        fields = listOfNotNull(
            "grant_type" to "refresh_token",
            token.refreshToken?.let { "refresh_token" to it },
            "client_id" to token.clientId,
            "resource" to token.resource,
            token.clientSecret?.let { "client_secret" to it },
        ),
    )

    private suspend fun tokenRequest(
        tokenEndpoint: String,
        fields: List<Pair<String, String>>,
    ): Result<McpTokenResponse> {
        val body = fields.joinToString("&") { (key, value) ->
            "${key.encodeURLParameter()}=${value.encodeURLParameter()}"
        }
        return try {
            val response = http.post(tokenEndpoint) {
                contentType(ContentType.Application.FormUrlEncoded)
                header("Accept", "application/json")
                setBody(body)
            }
            val text = response.bodyAsText()
            if (response.status.value !in 200..299) {
                // The body may carry `error=invalid_grant`; it never carries a token.
                return Result.failure(
                    McpAuthException("Token endpoint returned HTTP ${response.status.value}: ${text.take(200)}"),
                )
            }
            val parsed = Json.parseToJsonElement(text).jsonObject
            val accessToken = parsed["access_token"]?.jsonPrimitive?.content
                ?: return Result.failure(McpAuthException("Token response has no access_token"))
            val expiresIn = parsed["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
            Result.success(
                McpTokenResponse(
                    accessToken = accessToken,
                    refreshToken = parsed["refresh_token"]?.jsonPrimitive?.content,
                    expiresInSeconds = expiresIn,
                    scope = parsed["scope"]?.jsonPrimitive?.content,
                ),
            )
        } catch (e: Exception) {
            Result.failure(McpAuthException("Token request failed: ${e.message ?: "network error"}"))
        }
    }

    private suspend fun fetchResourceMetadata(
        resourceUrl: String,
        challenge: WwwAuthenticate?,
    ): Result<JsonObject> {
        val candidates = buildList {
            challenge?.resourceMetadataUrl?.let { add(it) }
            addAll(resourceMetadataCandidates(resourceUrl))
        }.distinct()
        var lastError: String = "no Protected Resource Metadata found"
        for (url in candidates) {
            val doc = fetchJson(url).getOrElse {
                lastError = it.message ?: lastError
                continue
            }
            if (doc["authorization_servers"] != null) return Result.success(doc)
            lastError = "$url has no authorization_servers"
        }
        return Result.failure(McpAuthException(lastError))
    }

    private suspend fun fetchAuthServerMetadata(issuer: String): Result<JsonObject> {
        var lastError: String = "no authorization server metadata found"
        for (url in authServerMetadataCandidates(issuer)) {
            val doc = fetchJson(url).getOrElse {
                lastError = it.message ?: lastError
                continue
            }
            val advertised = doc["issuer"]?.jsonPrimitive?.content
            // RFC 8414: the document must be for the issuer we asked about. A trailing slash is
            // the one difference real servers make and clients tolerate.
            if (advertised == null || advertised.trimEnd('/') != issuer.trimEnd('/')) {
                lastError = "$url advertises issuer $advertised, expected $issuer"
                continue
            }
            return Result.success(doc)
        }
        return Result.failure(McpAuthException(lastError))
    }

    private suspend fun fetchJson(url: String): Result<JsonObject> = try {
        val response: HttpResponse = http.get(url) { header("Accept", "application/json") }
        if (response.status.value !in 200..299) {
            Result.failure(McpAuthException("$url returned HTTP ${response.status.value}"))
        } else {
            Result.success(Json.parseToJsonElement(response.bodyAsText()).jsonObject)
        }
    } catch (e: Exception) {
        Result.failure(McpAuthException("$url failed: ${e.message ?: "network error"}"))
    }
}

/** Shared HTTP client for discovery, registration and token requests. */
private fun defaultMcpAuthHttp(): HttpClient = httpClient {
    install(HttpTimeout) {
        requestTimeoutMillis = 20_000
        connectTimeoutMillis = 10_000
    }
}

class McpAuthException(message: String) : Exception(message)

/** `scheme://host[:port]/path` without a fragment or trailing slash — the RFC 8707 resource. */
internal fun canonicalResource(url: String): String {
    val withoutFragment = url.substringBefore('#')
    return withoutFragment.trimEnd('/')
}

/** RFC 9728 §3.1 candidates: the path-suffixed document first, then the bare one. */
internal fun resourceMetadataCandidates(resourceUrl: String): List<String> {
    val canonical = canonicalResource(resourceUrl)
    val schemeEnd = canonical.indexOf("://")
    if (schemeEnd <= 0) return emptyList()
    val afterScheme = canonical.substring(schemeEnd + 3)
    val authorityEnd = afterScheme.indexOfFirst { it == '/' }
    val origin = if (authorityEnd < 0) canonical else canonical.substring(0, schemeEnd + 3 + authorityEnd)
    val path = if (authorityEnd < 0) "" else afterScheme.substring(authorityEnd)
    return buildList {
        if (path.isNotEmpty() && path != "/") add("$origin/.well-known/oauth-protected-resource$path")
        add("$origin/.well-known/oauth-protected-resource")
    }
}

/** RFC 8414 §3.1 + OIDC Discovery candidates for an issuer, with and without a path. */
internal fun authServerMetadataCandidates(issuer: String): List<String> {
    val canonical = canonicalResource(issuer)
    val schemeEnd = canonical.indexOf("://")
    if (schemeEnd <= 0) return emptyList()
    val afterScheme = canonical.substring(schemeEnd + 3)
    val authorityEnd = afterScheme.indexOfFirst { it == '/' }
    val origin = if (authorityEnd < 0) canonical else canonical.substring(0, schemeEnd + 3 + authorityEnd)
    val path = if (authorityEnd < 0) "" else afterScheme.substring(authorityEnd)
    return if (path.isEmpty() || path == "/") {
        listOf("$origin/.well-known/oauth-authorization-server", "$origin/.well-known/openid-configuration")
    } else {
        listOf(
            "$origin/.well-known/oauth-authorization-server$path",
            "$origin/.well-known/openid-configuration$path",
            "$origin$path/.well-known/openid-configuration",
        )
    }
}

/** The authorize URL, with PKCE S256, state and the resource indicator. */
internal fun buildAuthorizeUrl(
    endpoints: McpAuthEndpoints,
    clientId: String,
    redirectUri: String,
    state: String,
    codeChallenge: String,
    scope: String?,
): String {
    val params = buildList {
        add("response_type" to "code")
        add("client_id" to clientId)
        add("redirect_uri" to redirectUri)
        add("code_challenge" to codeChallenge)
        add("code_challenge_method" to "S256")
        add("state" to state)
        add("resource" to endpoints.resource)
        if (!scope.isNullOrBlank()) add("scope" to scope)
    }
    val separator = if (endpoints.authorizationEndpoint.contains('?')) "&" else "?"
    return endpoints.authorizationEndpoint + separator +
        params.joinToString("&") { (key, value) -> "${key.encodeURLParameter()}=${value.encodeURLParameter()}" }
}

/**
 * True for loopback, link-local and RFC 1918 hosts — IPv4 and IPv6 alike (ULA, link-local,
 * IPv4-mapped). The agent may not add a server pointing at one: that is how an injected prompt
 * reaches the owner's router or cloud metadata service.
 * A human adding the same URL by hand is a deliberate act and stays allowed.
 */
internal fun isPrivateAddress(url: String): Boolean {
    val authority = url.substringAfter("://", "").substringBefore('/')
    val hostPort = authority.substringAfterLast('@')
    // Bracketed IPv6 (`[::1]:8080`) keeps its colons inside the brackets, so the port split has
    // to happen after the closing bracket. A host with more than one colon and no brackets is a
    // bare IPv6 address (`fe80::1`), not a host:port pair.
    val host = when {
        hostPort.startsWith("[") -> hostPort.substringBefore(']').removePrefix("[")
        hostPort.count { it == ':' } > 1 -> hostPort
        else -> hostPort.substringBefore(':')
    }.lowercase()
    if (host.isEmpty()) return true
    if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return true
    if (host.contains(':')) return isPrivateIpv6(host)
    if (host == "0.0.0.0") return true
    return isPrivateIpv4(host)
}

private fun isPrivateIpv4(host: String): Boolean {
    val parts = host.split('.')
    if (parts.size == 4 && parts.all(::isCanonicalIpv4Octet)) return isPrivateQuad(parts.map { it.toInt() })
    // The JVM resolver behind ktor CIO also accepts the one-number (`2130706433`) and the
    // shortened (`127.1`) spelling of an address, so a host made only of digits and dots that
    // is not a canonical dotted quad is refused instead of trusted.
    if (host.isNotEmpty() && host.all { it in '0'..'9' || it == '.' }) return true
    // A DNS name is judged by its leading numeric quad, as before: `127.0.0.1.nip.io` resolves to
    // loopback and must not slip through just because the host also carries letters.
    val numeric = host.split('.').mapNotNull { it.toIntOrNull() }
    return numeric.size == 4 && isPrivateQuad(numeric)
}

/** Loopback, link-local, RFC 1918 and carrier-grade NAT quads. */
private fun isPrivateQuad(octets: List<Int>): Boolean = when {
    octets[0] == 127 -> true

    octets[0] == 10 -> true

    octets[0] == 192 && octets[1] == 168 -> true

    octets[0] == 172 && octets[1] in 16..31 -> true

    octets[0] == 169 && octets[1] == 254 -> true

    octets[0] == 100 && octets[1] in 64..127 -> true

    else -> false
}

/** One dotted-quad part as the canonical spelling writes it: 0..255, no leading zeros. */
private fun isCanonicalIpv4Octet(part: String): Boolean =
    part.length in 1..3 &&
        part.all { it in '0'..'9' } &&
        (part.length == 1 || part[0] != '0') &&
        part.toInt() <= 255

/**
 * Loopback (`::1`), unspecified (`::`), IPv4-mapped (`::ffff:127.0.0.1`, `::ffff:7f00:1`),
 * unique-local `fc00::/7` and link-local `fe80::/10` are all "inside the owner's network" for
 * this guard.
 */
private fun isPrivateIpv6(host: String): Boolean {
    val address = host.substringBefore('%') // a zone id (`fe80::1%eth0`) does not change the scope
    if (address.contains('.')) {
        // Dotted IPv4-mapped or IPv4-compatible form: judge the embedded IPv4 address.
        return isPrivateIpv4(address.substringAfterLast(':'))
    }
    val groups = expandIpv6(address) ?: return false
    if (groups.all { it == 0 }) return true
    if (groups.take(7).all { it == 0 } && groups[7] == 1) return true
    // The high 80 bits zero: IPv4-mapped (ffff) or the deprecated IPv4-compatible form.
    if (groups.take(5).all { it == 0 } && (groups[5] == 0 || groups[5] == 0xffff)) {
        return isPrivateIpv4("${groups[6] shr 8}.${groups[6] and 0xff}.${groups[7] shr 8}.${groups[7] and 0xff}")
    }
    val first = groups[0] shr 8
    val second = groups[0] and 0xff
    return when {
        first == 0xfe && (second and 0xc0) == 0x80 -> true // link-local fe80::/10
        (first and 0xfe) == 0xfc -> true // unique-local fc00::/7
        else -> false
    }
}

/** The eight 16-bit groups of an IPv6 literal, expanding a `::` run; null when it is not one. */
private fun expandIpv6(address: String): List<Int>? {
    val halves = address.split("::")
    if (halves.size > 2) return null
    fun groups(text: String): List<Int>? {
        val parts = text.split(':').filter { it.isNotEmpty() }
        return parts.map { it.toIntOrNull(16) ?: return null }
    }
    val head = groups(halves[0]) ?: return null
    val tail = if (halves.size == 2) groups(halves[1]) ?: return null else emptyList()
    val missing = 8 - head.size - tail.size
    if (missing < 0 || (halves.size == 1 && missing != 0)) return null
    return head + List(missing) { 0 } + tail
}
