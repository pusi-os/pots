// MCP server lifecycle: configuration, persisted state, tool cache, connections and OAuth.
//
// Three things are deliberately separate here, because merging them is what made a failed
// connect hide a server from the agent in 3.21.0:
//   * configuration — a server exists until the owner deletes it;
//   * [McpServerStatus] — last known state plus the last tool list, persisted;
//   * live sessions — in memory only, rebuilt on demand, never required to list a server.
//
// Mutators are suspend and take [mutex]: the old read-modify-write on the settings list raced
// with itself whenever the UI and a background connect touched the same server.

package com.pusios.pots.mcp

import com.pusios.pots.data.AppSettings
import com.pusios.pots.data.SettingsJsonList
import com.pusios.pots.network.tools.Tool
import com.pusios.pots.network.tools.ToolInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

private val serverIdRegex = Regex("[^a-z0-9]")

/** Log tag for the whole MCP subsystem. Never pass a token or an Authorization header here. */
internal fun mcpLog(step: String, server: String, detail: String = "") {
    println("PotsMcp: [$step] $server${if (detail.isBlank()) "" else " — $detail"}")
}

class McpServerManager(
    private val appSettings: AppSettings,
    private val tokenStore: McpTokenStore = McpTokenStore(appSettings),
    private val authClient: McpAuthClient = McpAuthClient(),
    /** One session per server; tests inject a fake. */
    private val sessionFactory: (McpServerConfig) -> McpSession = { server ->
        McpClient(
            url = server.url,
            headers = server.headers,
            tokenProvider = { tokenStore.load(server.id)?.accessToken },
        )
    },
    private val openBrowser: (String) -> Boolean = { url -> com.pusios.pots.openUrl(url) },
    private val authListenerFactory: () -> McpAuthListener? = { startMcpAuthListener() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /** Owner confirmation for the agent's mcp_* tools; the chat UI renders [McpGate.pending]. */
    val gate: McpGate = McpGate(),
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val mutex = Mutex()
    private val clients = mutableMapOf<String, McpSession>()
    private val connectLocks = mutableMapOf<String, Mutex>()
    private val pendingAuth = mutableMapOf<String, PendingAuthorization>()

    private val _statuses = MutableStateFlow(loadStatuses())

    /** Last known state of every server we have ever touched, keyed by server id. */
    val statuses: StateFlow<Map<String, McpServerStatus>> = _statuses.asStateFlow()

    private val servers = SettingsJsonList(
        read = appSettings::getMcpServersJson,
        write = appSettings::setMcpServersJson,
        itemSerializer = McpServerConfig.serializer(),
        label = "McpServerManager",
        onCorrupt = { appSettings.backupCorruptJson("McpServerManager", it) },
        json = json,
    )

    init {
        // One-shot: fill missing popular default headers (e.g. Jina Authorization) without
        // overwriting headers the user already configured.
        migratePopularDefaultHeaders()
    }

    // ------------------------------------------------------------------ configuration

    fun getServers(): List<McpServerConfig> {
        val profileSelection = appSettings.getProfileEnabledMcpServerIds()
        return servers.get().map { server ->
            server.copy(isEnabled = profileSelection?.contains(server.id) ?: server.isEnabled)
        }
    }

    fun getServer(serverId: String): McpServerConfig? = getServers().find { it.id == serverId }

    /**
     * Adds a server and enables it for the active agent profile. [addedByAgent] is an audit mark:
     * the UI shows it, and only such servers may be removed by the agent.
     */
    suspend fun addServer(
        name: String,
        url: String,
        headers: Map<String, String>,
        addedByAgent: Boolean = false,
    ): McpServerConfig = mutex.withLock {
        val current = servers.get()
        val id = generateServerId(name, current)
        // If the user adds a popular endpoint manually without headers, still apply defaults
        // for missing keys only (never clobber explicit headers they typed).
        val popularDefaults = popularMcpServers
            .firstOrNull { matchesPopularMcpUrl(url, it.url) }
            ?.headers
            .orEmpty()
        val config = McpServerConfig(
            id = id,
            name = name,
            url = url,
            headers = mergeMissingHeaders(headers, popularDefaults),
            addedByAgent = addedByAgent,
        )
        servers.set(current + config)
        appSettings.getProfileEnabledMcpServerIds()?.let { enabled ->
            appSettings.setProfileEnabledMcpServerIds(enabled + id)
        }
        updateStatusLocked(id) { McpServerStatus(state = McpServerStateKind.Unknown) }
        config
    }

    suspend fun removeServer(serverId: String) {
        mutex.withLock {
            servers.set(servers.get().filterNot { it.id == serverId })
            appSettings.removeMcpServerFromAgentProfiles(serverId)
            clients.remove(serverId)?.close()
            tokenStore.clear(serverId)
            cancelPendingAuthLocked(serverId)
            _statuses.update { it - serverId }
            persistStatuses()
        }
    }

    suspend fun setServerEnabled(serverId: String, enabled: Boolean) {
        mutex.withLock {
            val ids = getServers().filter { it.isEnabled }.map { it.id }.toMutableSet()
            if (enabled) ids += serverId else ids -= serverId
            appSettings.setProfileEnabledMcpServerIds(ids)
            if (!enabled) {
                clients.remove(serverId)?.close()
                updateStatusLocked(serverId) {
                    it.copy(state = McpServerStateKind.Disabled, failure = null, updatedAtEpochMs = nowMs())
                }
            } else {
                updateStatusLocked(serverId) {
                    it.copy(state = McpServerStateKind.Unknown, failure = null, updatedAtEpochMs = nowMs())
                }
            }
        }
    }

    /** Drop sessions of servers the newly active agent profile no longer selects. */
    suspend fun closeDisabledProfileServers() {
        val enabled = getServers().filter { it.isEnabled }.map { it.id }.toSet()
        mutex.withLock {
            clients.keys.filterNot { it in enabled }.forEach { serverId ->
                clients.remove(serverId)?.close()
            }
        }
    }

    // ------------------------------------------------------------------ state

    fun status(serverId: String): McpServerStatus = _statuses.value[serverId] ?: McpServerStatus()

    fun isConnected(serverId: String): Boolean = status(serverId).state == McpServerStateKind.Connected

    fun hasStoredToken(serverId: String): Boolean = tokenStore.has(serverId)

    /** The authorize URL of a flow that is currently waiting for the browser, if any. */
    fun pendingAuthorizationUrl(serverId: String): String? = pendingAuth[serverId]?.authorizeUrl

    suspend fun cancelAuthorization(serverId: String) {
        mutex.withLock { cancelPendingAuthLocked(serverId) }
        updateStatus(serverId) {
            it.copy(state = McpServerStateKind.NeedsAuth, failure = null, updatedAtEpochMs = nowMs())
        }
    }

    // ------------------------------------------------------------------ connect

    /**
     * Connects once and refreshes the tool cache. A failure never clears the cache: the agent
     * keeps seeing the server's tools, and a call explains what is wrong.
     */
    suspend fun connect(serverId: String): Result<List<McpToolMetadata>> {
        val server = getServer(serverId)
            ?: return Result.failure(McpException("Server not found: $serverId"))
        return lockFor(serverId).withLock { attemptConnect(server) }
    }

    private suspend fun attemptConnect(server: McpServerConfig): Result<List<McpToolMetadata>> {
        val serverId = server.id
        updateStatus(serverId) {
            it.copy(state = McpServerStateKind.Connecting, failure = null, updatedAtEpochMs = nowMs())
        }
        mcpLog("connect", serverId, "start")

        val stored = tokenStore.load(serverId)
        if (stored != null && stored.isExpired()) {
            mcpLog("refresh", serverId, "stored token expired")
            if (refreshToken(serverId, stored) == null) {
                return fail(serverId, McpFailureKind.NeedsAuth, null, "Токен истёк, нужна повторная авторизация")
            }
        }

        var refreshedOnce = false
        while (true) {
            when (val attempt = openAndList(server)) {
                is Attempt.Ok -> {
                    mutex.withLock {
                        clients.remove(serverId)?.close()
                        clients[serverId] = attempt.session
                    }
                    updateStatus(serverId) {
                        it.copy(
                            state = McpServerStateKind.Connected,
                            failure = null,
                            tools = attempt.tools.map { def ->
                                McpCachedTool(def.name, def.description ?: "", def.inputSchema)
                            },
                            toolsFetchedAtEpochMs = nowMs(),
                            updatedAtEpochMs = nowMs(),
                        )
                    }
                    mcpLog("listTools", serverId, "${attempt.tools.size} tools")
                    return Result.success(
                        attempt.tools.map { def ->
                            McpToolMetadata(
                                serverId = serverId,
                                name = def.name,
                                description = def.description ?: "",
                                inputSchema = def.inputSchema,
                            )
                        },
                    )
                }

                is Attempt.Failed -> {
                    // A 401 with a refresh token is worth one silent retry; anything else is final.
                    val token = tokenStore.load(serverId)
                    if (attempt.kind == McpFailureKind.NeedsAuth && !refreshedOnce &&
                        token?.refreshToken != null && refreshToken(serverId, token) != null
                    ) {
                        refreshedOnce = true
                        continue
                    }
                    return fail(serverId, attempt.kind, attempt.status, attempt.detail)
                }
            }
        }
    }

    private sealed interface Attempt {
        data class Ok(val session: McpSession, val tools: List<McpToolDefinition>) : Attempt

        data class Failed(val kind: McpFailureKind, val status: Int?, val detail: String) : Attempt
    }

    /** Opens a session and lists tools. The session is closed on every failure path. */
    private suspend fun openAndList(server: McpServerConfig): Attempt {
        val session = sessionFactory(server)
        return try {
            val handshake = withTimeoutOrNull(CONNECT_TIMEOUT) {
                session.initialize()
                true
            }
            if (handshake == null) {
                session.close()
                return Attempt.Failed(McpFailureKind.Unreachable, null, "Таймаут подключения")
            }
            val tools = withTimeoutOrNull(LIST_TOOLS_TIMEOUT) { session.listTools() }
            if (tools == null) {
                session.close()
                return Attempt.Failed(McpFailureKind.Unreachable, null, "Таймаут запроса списка инструментов")
            }
            Attempt.Ok(session, tools)
        } catch (e: McpHttpException) {
            session.close()
            mcpLog("connect", server.id, "HTTP ${e.status}")
            Attempt.Failed(
                kind = classifyHttpFailure(e.status, e.wwwAuthenticate),
                status = e.status,
                detail = e.message?.take(200) ?: "",
            )
        } catch (e: McpProtocolException) {
            session.close()
            mcpLog("connect", server.id, "protocol error: ${e.message}")
            Attempt.Failed(McpFailureKind.ProtocolError, null, e.message?.take(200) ?: "")
        } catch (e: CancellationException) {
            session.close()
            throw e
        } catch (e: Exception) {
            session.close()
            mcpLog("connect", server.id, "unreachable: ${e.message}")
            Attempt.Failed(McpFailureKind.Unreachable, null, e.message?.take(200) ?: "Нет связи")
        }
    }

    private suspend fun refreshToken(serverId: String, token: McpStoredToken): McpStoredToken? {
        if (token.refreshToken == null || token.tokenEndpoint.isBlank()) return null
        mcpLog("refresh", serverId, "requesting a new access token")
        val response = authClient.refresh(token).getOrElse {
            mcpLog("refresh", serverId, "refresh failed: ${it.message}")
            return null
        }
        val updated = token.copy(
            accessToken = response.accessToken,
            refreshToken = response.refreshToken ?: token.refreshToken,
            scope = response.scope ?: token.scope,
            expiresAtEpochMs = expiresAt(response),
        )
        tokenStore.save(serverId, updated)
        return updated
    }

    private fun expiresAt(response: McpTokenResponse): Long =
        if (response.expiresInSeconds > 0) nowMs() + response.expiresInSeconds * 1000 else 0

    private suspend fun fail(
        serverId: String,
        kind: McpFailureKind,
        status: Int?,
        detail: String,
    ): Result<List<McpToolMetadata>> {
        val failure = McpFailure(kind, status, detail, nowMs())
        updateStatus(serverId) {
            it.copy(
                state = if (kind == McpFailureKind.NeedsAuth || kind == McpFailureKind.NeedsScope) {
                    McpServerStateKind.NeedsAuth
                } else {
                    McpServerStateKind.Failed
                },
                failure = failure,
                updatedAtEpochMs = nowMs(),
            )
        }
        return Result.failure(
            McpException("$serverId: $kind${status?.let { " (HTTP $it)" } ?: ""} $detail".trim()),
        )
    }

    /** Reconnects every enabled server in parallel; failures become statuses, not exceptions. */
    suspend fun connectEnabledServers() {
        val enabled = getServers().filter { it.isEnabled }
        closeDisabledProfileServers()
        supervisorScope {
            enabled.map { server ->
                async {
                    withTimeoutOrNull(TOTAL_BUDGET) { connectWithBackoff(server.id) }
                }
            }.awaitAll()
        }
    }

    /**
     * Backoff only for failures that can heal (network, 5xx). A 401 or a 404 will not change by
     * being asked again, and retrying an unauthenticated server just spams the log.
     */
    private suspend fun connectWithBackoff(serverId: String) {
        var backoff = INITIAL_BACKOFF
        var attempt = 0
        while (attempt < MAX_ATTEMPTS) {
            attempt++
            if (connect(serverId).isSuccess) return
            val kind = status(serverId).failure?.kind
            if (kind == McpFailureKind.NeedsAuth || kind == McpFailureKind.NeedsScope ||
                kind == McpFailureKind.Forbidden || kind == McpFailureKind.WrongEndpoint
            ) {
                return
            }
            if (attempt >= MAX_ATTEMPTS) return
            delay(backoff)
            backoff *= 2
        }
    }

    suspend fun refresh(serverId: String): Result<List<McpToolMetadata>> = connect(serverId)

    // ------------------------------------------------------------------ OAuth

    /**
     * Runs discovery and registration, opens the browser and returns the authorize URL at once —
     * the redirect is awaited in the background and the outcome lands in [statuses]. The URL is
     * returned so the UI can offer "open the link" for clients that will not launch a browser.
     */
    suspend fun beginAuthorization(serverId: String): Result<String> {
        val server = getServer(serverId)
            ?: return Result.failure(McpException("Server not found: $serverId"))
        mutex.withLock { cancelPendingAuthLocked(serverId) }

        val challenge = authClient.probeChallenge(server.url, server.headers)
        mcpLog("discovery", serverId, challenge?.resourceMetadataUrl ?: "no WWW-Authenticate challenge")
        val endpoints = authClient.discover(server.url, challenge).getOrElse { error ->
            val reason = error.message ?: "discovery failed"
            mcpLog("discovery", serverId, "failed: $reason")
            fail(serverId, McpFailureKind.NeedsAuth, null, reason)
            return Result.failure(error)
        }

        val listener = authListenerFactory()
        if (listener == null) {
            val reason = "На этой платформе нет loopback-сервера: введите токен вручную"
            mcpLog("discovery", serverId, "no loopback listener")
            fail(serverId, McpFailureKind.NeedsAuth, null, reason)
            return Result.failure(McpAuthException(reason))
        }

        val requestedScope = challenge?.scope?.takeIf { it.isNotBlank() }
            ?: endpoints.scopesSupported.joinToString(" ").takeIf { it.isNotBlank() }
        val registration = authClient.register(endpoints, listener.redirectUri, requestedScope)
            .getOrElse { error ->
                listener.close()
                val reason = error.message ?: "client registration failed"
                mcpLog("dcr", serverId, "failed: $reason")
                fail(serverId, McpFailureKind.NeedsAuth, null, reason)
                return Result.failure(error)
            }

        val verifier = Pkce.newVerifier()
        val state = base64UrlNoPad(randomBytes(16))
        val authorizeUrl = buildAuthorizeUrl(
            endpoints = endpoints,
            clientId = registration.clientId,
            redirectUri = listener.redirectUri,
            state = state,
            codeChallenge = Pkce.challenge(verifier),
            scope = requestedScope,
        )
        val pending = PendingAuthorization(
            authorizeUrl = authorizeUrl,
            listener = listener,
            state = state,
            verifier = verifier,
            endpoints = endpoints,
            registration = registration,
        )
        mutex.withLock { pendingAuth[serverId] = pending }
        updateStatus(serverId) {
            it.copy(
                state = McpServerStateKind.NeedsAuth,
                failure = McpFailure(McpFailureKind.NeedsAuth, null, "Ожидаем подтверждение в браузере", nowMs()),
                updatedAtEpochMs = nowMs(),
            )
        }
        mcpLog("authorize", serverId, "opening browser at ${endpoints.authorizationEndpoint}")
        if (!openBrowser(authorizeUrl)) {
            mcpLog("authorize", serverId, "browser did not open; the URL is shown in the UI")
        }
        scope.launch { finishAuthorization(serverId, pending) }
        return Result.success(authorizeUrl)
    }

    private suspend fun finishAuthorization(serverId: String, pending: PendingAuthorization) {
        try {
            val callback = pending.listener.await(AUTH_TIMEOUT)
            if (callback == null) {
                mcpLog("token", serverId, "no redirect within ${AUTH_TIMEOUT.inWholeSeconds}s")
                fail(serverId, McpFailureKind.NeedsAuth, null, "Авторизация не завершена")
                return
            }
            if (callback.error != null) {
                mcpLog("token", serverId, "authorization error: ${callback.error}")
                fail(serverId, McpFailureKind.NeedsAuth, null, "Отказано: ${callback.error}")
                return
            }
            if (callback.state != pending.state) {
                mcpLog("token", serverId, "state mismatch")
                fail(serverId, McpFailureKind.NeedsAuth, null, "Ответ авторизации не совпал по state")
                return
            }
            val code = callback.code
            if (code == null) {
                fail(serverId, McpFailureKind.NeedsAuth, null, "В ответе нет кода авторизации")
                return
            }

            val response = authClient.exchangeCode(
                tokenEndpoint = pending.endpoints.tokenEndpoint,
                code = code,
                codeVerifier = pending.verifier,
                redirectUri = pending.listener.redirectUri,
                clientId = pending.registration.clientId,
                clientSecret = pending.registration.clientSecret,
                resource = pending.endpoints.resource,
            ).getOrElse { error ->
                mcpLog("token", serverId, "exchange failed: ${error.message}")
                fail(serverId, McpFailureKind.NeedsAuth, null, error.message ?: "Обмен кода на токен не удался")
                return
            }

            tokenStore.save(
                serverId,
                McpStoredToken(
                    issuer = pending.endpoints.issuer,
                    tokenEndpoint = pending.endpoints.tokenEndpoint,
                    clientId = pending.registration.clientId,
                    clientSecret = pending.registration.clientSecret,
                    accessToken = response.accessToken,
                    refreshToken = response.refreshToken,
                    scope = response.scope,
                    resource = pending.endpoints.resource,
                    expiresAtEpochMs = expiresAt(response),
                ),
            )
            mcpLog("token", serverId, "token stored, reconnecting")
            connect(serverId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mcpLog("token", serverId, "authorization failed: ${e.message}")
            fail(serverId, McpFailureKind.NeedsAuth, null, e.message ?: "Авторизация не удалась")
        } finally {
            pending.listener.close()
            mutex.withLock { if (pendingAuth[serverId] === pending) pendingAuth.remove(serverId) }
        }
    }

    /** Manual token entry for servers whose discovery fails or that only accept a PAT. */
    suspend fun setManualToken(serverId: String, token: String) {
        val server = getServer(serverId) ?: return
        val trimmed = token.trim()
        if (trimmed.isEmpty()) return
        tokenStore.save(
            serverId,
            McpStoredToken(
                issuer = server.url,
                tokenEndpoint = "",
                clientId = "manual",
                accessToken = trimmed,
                resource = canonicalResource(server.url),
            ),
        )
        mcpLog("token", serverId, "manual token stored")
        connect(serverId)
    }

    /** Forgets the token. The server stays configured and switches to "needs authorization". */
    suspend fun clearToken(serverId: String) {
        tokenStore.clear(serverId)
        mutex.withLock {
            clients.remove(serverId)?.close()
            cancelPendingAuthLocked(serverId)
        }
        updateStatus(serverId) {
            it.copy(state = McpServerStateKind.NeedsAuth, failure = null, updatedAtEpochMs = nowMs())
        }
    }

    // ------------------------------------------------------------------ tools

    /**
     * Enabled MCP tools, named so none collides with [reservedNames] (the built-in tools) or with
     * another server's tool. Duplicate function names make providers reject the request, and the
     * executor would always run the first match.
     *
     * Served from the persisted cache, so a server that is down or unauthenticated still appears
     * in the prompt — its calls then explain what to fix instead of vanishing.
     */
    fun getEnabledMcpTools(reservedNames: Set<String> = emptySet()): List<Tool> {
        val enabledServers = getServers().filter { it.isEnabled }
        val cachedStatuses = _statuses.value
        val taken = reservedNames.toMutableSet()
        return buildList {
            for (server in enabledServers) {
                val cached = cachedStatuses[server.id]?.tools.orEmpty()
                for (tool in cached) {
                    val toolId = McpTool.toolId(server.id, tool.name)
                    if (!appSettings.isToolEnabled(toolId)) continue
                    val name = uniqueMcpToolName(server.id, tool.name, taken)
                    taken += name
                    add(
                        McpTool(
                            metadata = McpToolMetadata(
                                serverId = server.id,
                                name = tool.name,
                                description = tool.description,
                                inputSchema = tool.inputSchema,
                            ),
                            advertisedName = name,
                            call = { args -> callTool(server.id, tool.name, args) },
                        ),
                    )
                }
            }
        }
    }

    /** Last known tools of one server, for Settings — available without a live connection. */
    fun getToolsForServer(serverId: String): List<ToolInfo> = status(serverId).tools.map { tool ->
        val toolId = McpTool.toolId(serverId, tool.name)
        ToolInfo(
            id = toolId,
            name = tool.name,
            description = tool.description,
            isEnabled = appSettings.isToolEnabled(toolId),
        )
    }

    /**
     * Calls one tool, reconnecting first if the session was dropped. A 401 becomes an
     * instruction the model can relay ("needs authorization"), not a bare HTTP error.
     */
    private suspend fun callTool(serverId: String, toolName: String, args: JsonObject): String {
        val server = getServer(serverId)
            ?: throw McpException("MCP server \"$serverId\" is no longer configured")
        val session = sessionFor(server)
        return try {
            session.callTool(toolName, args)
        } catch (e: McpHttpException) {
            if (e.status != 401) throw e
            mutex.withLock { clients.remove(serverId)?.close() }
            fail(serverId, McpFailureKind.NeedsAuth, 401, e.message?.take(200) ?: "")
            throw McpException(
                "MCP server \"${server.name}\" requires authorization. " +
                    "Ask the owner to open Settings → Tools → ${server.name} and press Authorize.",
            )
        } catch (e: McpProtocolException) {
            throw McpException("MCP server \"${server.name}\" answered with an error: ${e.message}")
        }
    }

    private suspend fun sessionFor(server: McpServerConfig): McpSession {
        mutex.withLock { clients[server.id] }?.let { return it }
        val result = connect(server.id)
        mutex.withLock { clients[server.id] }?.let { return it }
        val reason = when (status(server.id).failure?.kind) {
            McpFailureKind.NeedsAuth, McpFailureKind.NeedsScope ->
                "requires authorization — open Settings → Tools → ${server.name} and press Authorize"

            McpFailureKind.Unreachable -> "is unreachable"
            McpFailureKind.WrongEndpoint -> "answered on the wrong endpoint (SSE vs Streamable HTTP)"
            McpFailureKind.ServerError -> "is failing on its side"
            else -> result.exceptionOrNull()?.message ?: "is not connected"
        }
        throw McpException("MCP server \"${server.name}\" $reason")
    }

    /**
     * The agent's own MCP administration tools (list/add/remove/enable/refresh/authorize).
     * Built here rather than in the platform tool sets so every platform gets them from the one
     * place that owns the manager and its gate.
     */
    fun adminTools(): List<Tool> = McpTools.create(McpToolDependencies(this, gate))

    /** Closes every session; the app is going away. */
    fun shutdown() {
        clients.values.forEach { runCatching { it.close() } }
        clients.clear()
        pendingAuth.values.forEach { runCatching { it.listener.close() } }
        pendingAuth.clear()
    }

    // ------------------------------------------------------------------ internals

    private class PendingAuthorization(
        val authorizeUrl: String,
        val listener: McpAuthListener,
        val state: String,
        val verifier: String,
        val endpoints: McpAuthEndpoints,
        val registration: McpClientRegistration,
    )

    /** One connect at a time per server; different servers connect in parallel. */
    private suspend fun lockFor(serverId: String): Mutex =
        mutex.withLock { connectLocks.getOrPut(serverId) { Mutex() } }

    private fun cancelPendingAuthLocked(serverId: String) {
        pendingAuth.remove(serverId)?.listener?.close()
    }

    private suspend fun updateStatus(serverId: String, transform: (McpServerStatus) -> McpServerStatus) {
        mutex.withLock { updateStatusLocked(serverId, transform) }
    }

    private fun updateStatusLocked(serverId: String, transform: (McpServerStatus) -> McpServerStatus) {
        _statuses.update { current ->
            val existing = current[serverId] ?: McpServerStatus()
            current + (serverId to transform(existing))
        }
        persistStatuses()
    }

    private fun persistStatuses() {
        runCatching { appSettings.setMcpStatusJson(statusesSerializer.encodeToString(_statuses.value)) }
    }

    private fun loadStatuses(): Map<String, McpServerStatus> = try {
        val raw = appSettings.getMcpStatusJson()
        if (raw.isBlank()) emptyMap() else statusesSerializer.decodeFromString(raw)
    } catch (_: Exception) {
        emptyMap()
    }

    private fun migratePopularDefaultHeaders() {
        val current = servers.get()
        val updated = applyPopularDefaultHeaders(current)
        if (updated !== current) {
            servers.set(updated)
        }
    }

    private fun generateServerId(name: String, existing: List<McpServerConfig>): String {
        val base = name.lowercase().replace(serverIdRegex, "_").take(30)
        val existingIds = existing.map { it.id }.toSet()
        if (base !in existingIds) return base
        var counter = 2
        while ("${base}_$counter" in existingIds) counter++
        return "${base}_$counter"
    }

    private companion object {
        val statusesSerializer = MapSerializer(String.serializer(), McpServerStatus.serializer())

        val CONNECT_TIMEOUT = 20.seconds
        val LIST_TOOLS_TIMEOUT = 30.seconds
        val AUTH_TIMEOUT = 5 * 60.seconds
        val TOTAL_BUDGET = 90.seconds
        const val MAX_ATTEMPTS = 3
        val INITIAL_BACKOFF = 1.seconds

        fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()
    }
}

private val toolNameInvalidChars = Regex("[^A-Za-z0-9_-]")

/**
 * [toolName] itself when free; otherwise `<serverId>_<toolName>` (then `_2`, `_3`, …), restricted
 * to the characters and 64-char length providers accept for function names.
 */
internal fun uniqueMcpToolName(serverId: String, toolName: String, taken: Set<String>): String {
    if (toolName !in taken) return toolName
    val base = "${serverId}_$toolName".replace(toolNameInvalidChars, "_").take(64)
    if (base !in taken) return base
    var n = 2
    while (true) {
        val suffix = "_$n"
        val candidate = base.take(64 - suffix.length) + suffix
        if (candidate !in taken) return candidate
        n++
    }
}
