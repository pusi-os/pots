// Acceptance tests for MCP server state (3.21.1). The bug being pinned: a failed connect used to
// delete the only copy of a server's tools, so the server disappeared from the agent's prompt and
// the UI showed a red dot with no reason. These tests drive the real manager with a fake session
// and assert what the agent sees after 401 / 500 / a network failure.

package com.pusios.pots.mcp

import com.pusios.pots.data.AppSettings
import com.pusios.pots.data.HeadlessRunElement
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class McpServerStateTest {

    /** What the fake session answers with next. */
    private class FakeSession(
        private val mode: () -> FakeMode,
        private val closed: MutableList<String>,
        private val serverId: String,
    ) : McpSession {
        override suspend fun initialize() {
            when (val m = mode()) {
                is FakeMode.Ok -> Unit
                is FakeMode.Unauthorized -> throw McpHttpException(401, m.challenge, "unauthorized")
                is FakeMode.ServerError -> throw McpHttpException(500, null, "boom")
                is FakeMode.Network -> throw java.net.SocketTimeoutException("timed out")
            }
        }

        override suspend fun listTools(): List<McpToolDefinition> = listOf(
            McpToolDefinition(name = "search", description = "Search things"),
            McpToolDefinition(name = "create_issue", description = "Open an issue"),
        )

        override suspend fun callTool(name: String, arguments: JsonObject): String = "called $name"

        override fun close() {
            closed += serverId
        }
    }

    private sealed interface FakeMode {
        data object Ok : FakeMode

        data class Unauthorized(val challenge: String?) : FakeMode

        data object ServerError : FakeMode

        data object Network : FakeMode
    }

    private fun manager(
        settings: AppSettings,
        closed: MutableList<String> = mutableListOf(),
        mode: () -> FakeMode,
    ) = McpServerManager(
        appSettings = settings,
        sessionFactory = { server -> FakeSession(mode, closed, server.id) },
        openBrowser = { true },
        authListenerFactory = { null },
    )

    private suspend fun addServer(manager: McpServerManager) = manager.addServer(name = "Sentry", url = "https://mcp.sentry.io/mcp", headers = emptyMap())

    @Test
    fun successfulConnectCachesToolsAndAdvertisesThem() = runTest {
        val settings = AppSettings(MapSettings())
        val manager = manager(settings) { FakeMode.Ok }
        val server = addServer(manager)

        val result = manager.connect(server.id)

        assertTrue(result.isSuccess)
        val status = manager.status(server.id)
        assertEquals(McpServerStateKind.Connected, status.state)
        assertEquals(2, status.toolCount)
        assertEquals(listOf("search", "create_issue"), manager.getEnabledMcpTools().map { it.schema.name })
        assertEquals(2, manager.getToolsForServer(server.id).size)
    }

    @Test
    fun unauthorizedServerStaysVisibleWithItsCachedTools() = runTest {
        val settings = AppSettings(MapSettings())
        var mode: FakeMode = FakeMode.Ok
        val manager = manager(settings) { mode }
        val server = addServer(manager)
        assertTrue(manager.connect(server.id).isSuccess)

        // The token is revoked server-side: the next connect is a 401 with a challenge.
        mode = FakeMode.Unauthorized("Bearer resource_metadata=\"https://mcp.sentry.io/.well-known/oauth-protected-resource\"")
        val result = manager.connect(server.id)

        assertTrue(result.isFailure)
        val status = manager.status(server.id)
        assertEquals(McpServerStateKind.NeedsAuth, status.state)
        assertEquals(McpFailureKind.NeedsAuth, status.failure?.kind)
        assertEquals(401, status.failure?.httpStatus)
        assertEquals(2, status.toolCount, "the tool cache must survive a failed connect")

        val tools = manager.getEnabledMcpTools()
        assertEquals(listOf("search", "create_issue"), tools.map { it.schema.name }, "the agent still sees the server")

        // Calling it explains what to fix instead of failing with a bare HTTP error.
        val call = tools.first().execute(emptyMap()) as Map<*, *>
        assertEquals(false, call["success"])
        assertTrue((call["error"] as String).contains("requires authorization"))
    }

    @Test
    fun serverErrorIsFailedAndKeepsTheCache() = runTest {
        val settings = AppSettings(MapSettings())
        var mode: FakeMode = FakeMode.Ok
        val manager = manager(settings) { mode }
        val server = addServer(manager)
        assertTrue(manager.connect(server.id).isSuccess)

        mode = FakeMode.ServerError
        manager.connect(server.id)

        val status = manager.status(server.id)
        assertEquals(McpServerStateKind.Failed, status.state)
        assertEquals(McpFailureKind.ServerError, status.failure?.kind)
        assertEquals(500, status.failure?.httpStatus)
        assertEquals(2, status.toolCount)
        assertEquals(2, manager.getEnabledMcpTools().size)
    }

    @Test
    fun networkFailureIsUnreachableNotNeedsAuth() = runTest {
        val settings = AppSettings(MapSettings())
        val manager = manager(settings) { FakeMode.Network }
        val server = addServer(manager)

        manager.connect(server.id)

        val status = manager.status(server.id)
        assertEquals(McpServerStateKind.Failed, status.state)
        assertEquals(McpFailureKind.Unreachable, status.failure?.kind)
        assertNotNull(status.failure)
        assertTrue(status.failure!!.atEpochMs > 0, "the failure carries a timestamp for the UI")
    }

    @Test
    fun disablingClosesTheSessionAndHidesTheToolsButKeepsThemCached() = runTest {
        val settings = AppSettings(MapSettings())
        val closed = mutableListOf<String>()
        val manager = manager(settings, closed = closed) { FakeMode.Ok }
        val server = addServer(manager)
        assertTrue(manager.connect(server.id).isSuccess)

        manager.setServerEnabled(server.id, false)

        assertTrue(closed.contains(server.id), "the live session is closed")
        assertEquals(McpServerStateKind.Disabled, manager.status(server.id).state)
        assertTrue(manager.getEnabledMcpTools().isEmpty(), "a disabled server contributes no tools")
        assertEquals(2, manager.status(server.id).toolCount, "the cache is kept for when it is re-enabled")
    }

    @Test
    fun setEnabledToolChangesNothingWhenTheOwnerDoesNotConfirm() = runTest {
        val settings = AppSettings(MapSettings())
        val manager = manager(settings) { FakeMode.Ok }
        val server = addServer(manager)
        assertTrue(manager.connect(server.id).isSuccess)
        val setEnabled = manager.adminTools().first { it.schema.name == "mcp_set_enabled" }
        assertTrue(
            setEnabled.timeout > McpGate.DEFAULT_CONFIRM_TIMEOUT_SECONDS.seconds,
            "the tool timeout must outlive the confirmation window: ${setEnabled.timeout}",
        )

        // Headless runs have nobody to ask, so the gate refuses and the server keeps its state.
        val result = withContext(HeadlessRunElement()) {
            setEnabled.execute(mapOf("server" to server.id, "enabled" to false)) as Map<*, *>
        }

        assertEquals(false, result["success"])
        assertTrue((result["error"] as String).contains("did not confirm"))
        assertTrue(manager.getServers().single().isEnabled, "the server stays enabled")
        assertEquals(2, manager.getEnabledMcpTools().size, "its tools stay available to the agent")
    }

    @Test
    fun stateAndToolsSurviveARestart() = runTest {
        val settings = AppSettings(MapSettings())
        val first = manager(settings) { FakeMode.Ok }
        val server = addServer(first)
        assertTrue(first.connect(server.id).isSuccess)

        // A new process: the same settings, a fresh manager with no live sessions.
        val second = manager(settings) { FakeMode.Ok }

        val status = second.status(server.id)
        // The persisted "connected" is a snapshot taken by the previous process: it is shown as
        // unverified until this process reaches the server itself, which is what the reconnect at
        // startup does within seconds. Claiming "connected" here is what let the card disagree with
        // a live 401.
        assertEquals(McpServerStateKind.Unknown, status.state)
        assertEquals(2, status.toolCount, "the cached tools are still there")
        assertEquals(
            listOf("search", "create_issue"),
            second.getEnabledMcpTools().map { it.schema.name },
            "the agent sees the server on its first turn, before the reconnect finishes",
        )

        assertTrue(second.connect(server.id).isSuccess)
        assertEquals(McpServerStateKind.Connected, second.status(server.id).state)
    }

    @Test
    fun advertisedNamesAreStableAndAvoidReservedOnes() = runTest {
        val settings = AppSettings(MapSettings())
        val manager = manager(settings) { FakeMode.Ok }
        val server = addServer(manager)
        assertTrue(manager.connect(server.id).isSuccess)

        val first = manager.getEnabledMcpTools(reservedNames = setOf("search"))
        val second = manager.getEnabledMcpTools(reservedNames = setOf("search"))

        assertEquals(first.map { it.schema.name }, second.map { it.schema.name }, "names must not drift between turns")
        assertFalse(first.map { it.schema.name }.contains("search"), "a reserved built-in name is never reused")
        assertTrue(first.map { it.schema.name }.any { it.endsWith("search") })
        assertEquals(first.size, first.map { it.schema.name }.toSet().size, "names are unique")
    }

    @Test
    fun removingAServerForgetsItsStateAndToken() = runTest {
        val settings = AppSettings(MapSettings())
        val manager = manager(settings) { FakeMode.Ok }
        val server = addServer(manager)
        assertTrue(manager.connect(server.id).isSuccess)
        manager.setManualToken(server.id, "pat-123")
        assertTrue(manager.hasStoredToken(server.id))

        manager.removeServer(server.id)

        assertTrue(manager.getServers().isEmpty())
        assertEquals(McpServerStateKind.Unknown, manager.status(server.id).state)
        assertFalse(manager.hasStoredToken(server.id))
    }

    @Test
    fun gateRefusesHeadlessRuns() = runTest {
        val gate = McpGate()
        val allowed = withContext(HeadlessRunElement()) {
            gate.confirm("mcp_add_server", "Добавить сервер")
        }
        assertFalse(allowed, "an unattended run must not wire the agent to a new remote server")
    }

    @Test
    fun gateAnswersWhatTheDialogSays() = runTest {
        val gate = McpGate()
        val decision = backgroundScope.async {
            gate.confirm("mcp_add_server", "Добавить сервер")
        }
        // The UI reads the pending request from the same gate the tools call.
        runCurrent()
        val pending = gate.pending.value
        assertNotNull(pending)
        gate.respond(pending.id, true)
        assertTrue(decision.await())
    }

    @Test
    fun agentToolsExposeEveryServerIncludingBrokenOnes() = runTest {
        val settings = AppSettings(MapSettings())
        var mode: FakeMode = FakeMode.Unauthorized(null)
        val manager = manager(settings) { mode }
        val server = addServer(manager)
        manager.connect(server.id)

        val tools = manager.adminTools()
        assertEquals(McpTools.TOOL_NAMES, tools.map { it.schema.name })
        val listTool = tools.first { it.schema.name == "mcp_list_servers" }
        val result = listTool.execute(emptyMap()) as Map<*, *>
        assertEquals(true, result["success"])
        val text = result["result"] as String
        assertTrue(text.contains("id=${server.id}"))
        assertTrue(text.contains("state=needsauth"), "a server awaiting sign-in is listed, not hidden: $text")

        // A successful connect flips the same entry without the server being re-added.
        mode = FakeMode.Ok
        manager.connect(server.id)
        val refreshed = listTool.execute(emptyMap()) as Map<*, *>
        assertTrue((refreshed["result"] as String).contains("state=connected"))
    }

    @Test
    fun addServerToolRefusesPrivateAddresses() = runTest {
        val settings = AppSettings(MapSettings())
        val manager = manager(settings) { FakeMode.Ok }
        val add = manager.adminTools().first { it.schema.name == "mcp_add_server" }

        val result = add.execute(
            mapOf("name" to "Router", "url" to "http://192.168.1.1/mcp"),
        ) as Map<*, *>

        assertEquals(false, result["success"])
        assertTrue((result["error"] as String).contains("private"))
        assertTrue(manager.getServers().isEmpty())
    }

    @Test
    fun authorizeUrlIsKeptForTheUiWhenTheBrowserDoesNotOpen() = runTest {
        val settings = AppSettings(MapSettings())
        val manager = manager(settings) { FakeMode.Ok }
        val server = addServer(manager)
        // No loopback listener on this platform: discovery cannot even start, and the failure is
        // reported as needs-auth with a reason the owner can act on.
        val result = manager.beginAuthorization(server.id)
        assertTrue(result.isFailure)
        val status = manager.status(server.id)
        assertEquals(McpServerStateKind.NeedsAuth, status.state)
        assertNotNull(status.failure?.detail)
        assertTrue(status.failure!!.detail.isNotBlank())
    }

    @Test
    fun manualTokenIsStoredOutsideTheServerConfig() = runTest {
        val settings = AppSettings(MapSettings())
        val manager = manager(settings) { FakeMode.Ok }
        val server = addServer(manager)

        manager.setManualToken(server.id, "  pat-secret  ")

        assertTrue(manager.hasStoredToken(server.id))
        // The token lives in its own secure record, not in the server configuration that is
        // exported with the rest of the settings.
        assertFalse(settings.getMcpServersJson().contains("pat-secret"))
        assertTrue(settings.getMcpTokenJson(server.id).orEmpty().contains("pat-secret"))
    }
}
