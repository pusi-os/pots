// Acceptance tests for MCP credential durability (3.21.4). The bug being pinned: the owner signed in
// to Walt, the token was written with `apply()` and/or wiped by a read-all/write-all save, and after
// a restart the agent answered "not authorized" while the settings card still said "connected".
//
// Everything here runs without a device: the storage is a MapSettings (the same interface the
// Keystore-backed store implements), the network is a fake session, and a "restart" is a second
// AppSettings over the same bytes — which is exactly what the app does after a process kill.

package com.pusios.pots.mcp

import com.pusios.pots.data.AppSettings
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class McpTokenPersistenceTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private class OkSession : McpSession {
        override suspend fun initialize() = Unit

        override suspend fun listTools(): List<McpToolDefinition> = listOf(
            McpToolDefinition(name = "search", description = "Search things"),
            McpToolDefinition(name = "create_issue", description = "Open an issue"),
        )

        override suspend fun callTool(name: String, arguments: JsonObject): String = "called $name"

        override fun close() = Unit
    }

    private fun manager(
        settings: AppSettings,
        authClient: McpAuthClient = McpAuthClient(),
    ) = McpServerManager(
        appSettings = settings,
        authClient = authClient,
        sessionFactory = { OkSession() },
        openBrowser = { true },
        authListenerFactory = { null },
    )

    /** A settings store that cannot decrypt one key — the Keystore-invalidated case. */
    private class BrokenKeySettings(
        private val delegate: Settings,
        private val brokenKey: String,
    ) : Settings by delegate {
        override fun getString(key: String, defaultValue: String): String = if (key == brokenKey) throw IllegalStateException("AEADBadTagException: cannot decrypt") else delegate.getString(key, defaultValue)

        override fun getStringOrNull(key: String): String? = if (key == brokenKey) throw IllegalStateException("AEADBadTagException: cannot decrypt") else delegate.getStringOrNull(key)
    }

    private fun token(
        access: String = "access-1",
        refresh: String? = "refresh-1",
        expiresAt: Long = 0,
        tokenEndpoint: String = "https://auth.example/token",
    ) = McpStoredToken(
        issuer = "https://auth.example",
        tokenEndpoint = tokenEndpoint,
        clientId = "client-1",
        accessToken = access,
        refreshToken = refresh,
        resource = "https://mcp.example/mcp",
        expiresAtEpochMs = expiresAt,
    )

    private fun storedToken(settings: AppSettings, serverId: String): McpStoredToken {
        val raw = settings.getMcpTokenJson(serverId)
        assertNotNull(raw, "the token record must still be there")
        return json.decodeFromString(McpStoredToken.serializer(), raw)
    }

    @Test
    fun manualTokenSurvivesARestartAndTheNewProcessUsesIt() = runTest {
        val raw = MapSettings()
        val first = manager(AppSettings(raw))
        val server = first.addServer(name = "Walt", url = "https://mcp.example/mcp", headers = emptyMap())
        first.setManualToken(server.id, "pat-walt")

        // A process kill: new AppSettings over the same bytes, new manager, no live sessions.
        val second = manager(AppSettings(raw))

        assertTrue(second.hasStoredToken(server.id), "the token is still there after the restart")
        assertTrue(second.connect(server.id).isSuccess, "the first session after the restart must not need a sign-in")
        assertEquals(McpServerStateKind.Connected, second.status(server.id).state)
        assertEquals(
            listOf("search", "create_issue"),
            second.getEnabledMcpTools().map { it.schema.name },
            "the agent sees the tools in its first session",
        )
        assertEquals("pat-walt", storedToken(AppSettings(raw), server.id).accessToken)
    }

    @Test
    fun legacySingleMapIsMigratedWithoutASignIn() = runTest {
        val raw = MapSettings()
        val settings = AppSettings(raw)
        val first = manager(settings)
        val server = first.addServer(name = "Walt", url = "https://mcp.example/mcp", headers = emptyMap())
        // The pre-3.21.4 layout: one JSON map under `mcp_tokens`, nothing per server.
        val legacy = token(access = "pat-walt", refresh = null, tokenEndpoint = "")
        settings.setMcpTokensJson("""{"${server.id}":${json.encodeToString(McpStoredToken.serializer(), legacy)}}""")
        raw.remove(AppSettings.KEY_MCP_TOKEN_PREFIX + server.id)

        val restarted = manager(AppSettings(raw))

        assertTrue(restarted.hasStoredToken(server.id), "the migrated record is visible to the card")
        assertEquals("", AppSettings(raw).getMcpTokensJson(), "the old blob is dropped once every entry is safe")
        assertEquals("pat-walt", storedToken(AppSettings(raw), server.id).accessToken)
        assertTrue(restarted.connect(server.id).isSuccess)
    }

    @Test
    fun oneBrokenRecordDoesNotEraseTheOthers() = runTest {
        val settings = AppSettings(MapSettings())
        settings.setMcpTokenJson("alpha", json.encodeToString(McpStoredToken.serializer(), token(access = "pat-alpha")))
        settings.setMcpTokenJson("beta", "{this is not a token")
        val store = McpTokenStore(settings)

        assertTrue(store.read("alpha") is McpTokenRead.Loaded)
        assertTrue(store.read("beta") is McpTokenRead.Unreadable, "a corrupt record is reported, not treated as absent")

        assertTrue(store.save("gamma", token(access = "pat-gamma")), "saving a third record must still work")
        assertEquals("pat-alpha", store.load("alpha")?.accessToken, "one unreadable record must not cost the others")
        assertEquals("{this is not a token", settings.getMcpTokenJson("beta"), "the unreadable bytes are left in place")
    }

    @Test
    fun unreadableStoreIsExplainedInsteadOfAskingForANewLogin() = runTest {
        val raw = MapSettings()
        val setup = AppSettings(raw)
        val first = manager(setup)
        val server = first.addServer(name = "Walt", url = "https://mcp.example/mcp", headers = emptyMap())
        first.setManualToken(server.id, "pat-walt")
        val before = setup.getMcpTokenJson(server.id)

        val broken = AppSettings(BrokenKeySettings(raw, AppSettings.KEY_MCP_TOKEN_PREFIX + server.id))
        val restarted = manager(broken)
        restarted.connect(server.id)

        val status = restarted.status(server.id)
        assertEquals(McpServerStateKind.NeedsAuth, status.state)
        assertTrue(
            status.failure?.detail.orEmpty().contains("Хранилище токенов"),
            "the owner is told the store failed, not that he was logged out: ${status.failure?.detail}",
        )
        assertEquals(before, setup.getMcpTokenJson(server.id), "the undecryptable record is never overwritten")
    }

    @Test
    fun rotatedRefreshTokenIsPersistedBeforeTheCallReturns() = runTest {
        class RotatingAuth : McpAuthClient() {
            var calls = 0
            override suspend fun refresh(token: McpStoredToken): Result<McpTokenResponse> {
                calls++
                return Result.success(
                    McpTokenResponse(accessToken = "access-2", refreshToken = "refresh-2", expiresInSeconds = 3600),
                )
            }
        }

        val raw = MapSettings()
        val settings = AppSettings(raw)
        val auth = RotatingAuth()
        val manager = manager(settings, authClient = auth)
        val server = manager.addServer(name = "Walt", url = "https://mcp.example/mcp", headers = emptyMap())
        settings.setMcpTokenJson(
            server.id,
            json.encodeToString(McpStoredToken.serializer(), token(access = "access-1", refresh = "refresh-1", expiresAt = 1)),
        )

        assertTrue(manager.connect(server.id).isSuccess)
        assertEquals(1, auth.calls)

        // The old refresh token is dead on the server the moment it rotated: only the stored copy counts.
        val after = storedToken(AppSettings(raw), server.id)
        assertEquals("refresh-2", after.refreshToken)
        assertEquals("access-2", after.accessToken)
    }

    @Test
    fun networkFailureDuringRefreshIsNotAnAuthorizationFailure() = runTest {
        class OfflineAuth : McpAuthClient() {
            override suspend fun refresh(token: McpStoredToken): Result<McpTokenResponse> = Result.failure(McpTokenEndpointException(null, "token request failed: timeout"))
        }

        val settings = AppSettings(MapSettings())
        val manager = manager(settings, authClient = OfflineAuth())
        val server = manager.addServer(name = "Walt", url = "https://mcp.example/mcp", headers = emptyMap())
        settings.setMcpTokenJson(
            server.id,
            json.encodeToString(McpStoredToken.serializer(), token(expiresAt = 1)),
        )

        manager.connect(server.id)

        val status = manager.status(server.id)
        assertEquals(McpServerStateKind.Failed, status.state, "a lost packet is not a revoked grant")
        assertEquals(McpFailureKind.Unreachable, status.failure?.kind)
        assertTrue(status.failure?.detail.orEmpty().contains("Токен не обновлён"))
        assertEquals("refresh-1", storedToken(settings, server.id).refreshToken, "a failed refresh keeps the old record")
    }

    @Test
    fun refusedRefreshAsksForASignIn() = runTest {
        class RevokedAuth : McpAuthClient() {
            override suspend fun refresh(token: McpStoredToken): Result<McpTokenResponse> = Result.failure(
                McpTokenEndpointException(400, "token endpoint refused the request: HTTP 400 (invalid_grant)"),
            )
        }

        val settings = AppSettings(MapSettings())
        val manager = manager(settings, authClient = RevokedAuth())
        val server = manager.addServer(name = "Walt", url = "https://mcp.example/mcp", headers = emptyMap())
        settings.setMcpTokenJson(server.id, json.encodeToString(McpStoredToken.serializer(), token(expiresAt = 1)))

        manager.connect(server.id)

        val status = manager.status(server.id)
        assertEquals(McpServerStateKind.NeedsAuth, status.state)
        assertTrue(
            status.failure?.detail.orEmpty().contains("invalid_grant"),
            "the reason names the OAuth error so the owner can tell revoke from outage: ${status.failure?.detail}",
        )
    }

    @Test
    fun expiredManualTokenAsksForAReplacementNotForANewLogin() = runTest {
        val settings = AppSettings(MapSettings())
        val manager = manager(settings)
        val server = manager.addServer(name = "Walt", url = "https://mcp.example/mcp", headers = emptyMap())
        settings.setMcpTokenJson(
            server.id,
            json.encodeToString(
                McpStoredToken.serializer(),
                token(access = "pat-old", refresh = null, expiresAt = 1, tokenEndpoint = ""),
            ),
        )

        manager.connect(server.id)

        val status = manager.status(server.id)
        assertEquals(McpServerStateKind.NeedsAuth, status.state)
        assertTrue(status.failure?.detail.orEmpty().contains("ручного токена"))
    }

    @Test
    fun authorizedServerAddedBeforeProfilesStaysEnabledAfterTheUpgrade() = runTest {
        val raw = MapSettings()
        val settings = AppSettings(raw)
        val first = manager(settings)
        val server = first.addServer(name = "Walt", url = "https://mcp.example/mcp", headers = emptyMap())
        first.setManualToken(server.id, "pat-walt")
        // An old profile: an explicit selection that predates the server (and does not list it).
        settings.setProfileEnabledMcpServerIds(emptySet())
        raw.remove(AppSettings.KEY_MCP_PROFILE_SELECTION_MIGRATED)

        val upgraded = manager(AppSettings(raw))

        assertTrue(
            AppSettings(raw).getProfileEnabledMcpServerIds().orEmpty().contains(server.id),
            "a server with a token is added to the explicit selection, not switched off by it",
        )
        assertTrue(upgraded.getServers().single().isEnabled)
        assertEquals(2, upgraded.getEnabledMcpTools().size, "its tools reach the agent on the first turn")

        // One-shot: a later manual switch off must survive the next start.
        settings.setProfileEnabledMcpServerIds(emptySet())
        val later = manager(AppSettings(raw))
        assertFalse(later.getServers().single().isEnabled, "the migration does not re-enable what the owner turned off")
    }

    @Test
    fun tokenIsNeverWrittenIntoTheServerConfig() = runTest {
        val settings = AppSettings(MapSettings())
        val manager = manager(settings)
        val server = manager.addServer(name = "Walt", url = "https://mcp.example/mcp", headers = emptyMap())
        manager.setManualToken(server.id, "pat-secret")

        assertFalse(settings.getMcpServersJson().contains("pat-secret"))
        assertTrue(settings.getMcpTokenJson(server.id).orEmpty().contains("pat-secret"))
    }
}
