// What the MCP card in Settings shows (3.21.3). The owner's report: a server that needed a sign-in
// sat in the list with only "Refresh" and "Remove" — no way to open the authorization from the card.
// The cause was a local "connecting" flag in the view model that outlived the manager's answer, so
// the card never reached needs-auth. These tests pin what the card derives from a status record and
// that the sign-in button actually reaches the manager.

package com.pusios.pots.ui.settings

import com.pusios.pots.DaemonController
import com.pusios.pots.data.TaskScheduler
import com.pusios.pots.mcp.McpFailure
import com.pusios.pots.mcp.McpFailureKind
import com.pusios.pots.mcp.McpServerConfig
import com.pusios.pots.mcp.McpServerStateKind
import com.pusios.pots.mcp.McpServerStatus
import com.pusios.pots.network.tools.ToolInfo
import com.pusios.pots.testutil.FakeDataRepository
import com.pusios.pots.tools.AppPermission
import com.pusios.pots.tools.PermissionController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class McpServerCardStateTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeRepository: FakeDataRepository
    private lateinit var noOpScheduler: TaskScheduler
    private val fakeDaemonController = object : DaemonController {
        override fun start() {}

        override fun stop() {}
    }
    private val fakeNotificationPermissionController = PermissionController(AppPermission.POST_NOTIFICATIONS)

    private val server = McpServerConfig(id = "walt", name = "Walt", url = "https://walt.example/mcp")
    private val cachedTools = listOf(
        ToolInfo(id = "mcp_walt_search", name = "search", description = "Search"),
        ToolInfo(id = "mcp_walt_fetch", name = "fetch", description = "Fetch"),
    )

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeRepository = FakeDataRepository()
        noOpScheduler = TaskScheduler(fakeRepository, enabled = false)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = SettingsViewModel(
        fakeRepository,
        fakeDaemonController,
        fakeNotificationPermissionController,
        noOpScheduler,
        testDispatcher,
    )

    private fun card(
        state: McpServerStateKind,
        failure: McpFailure? = null,
        tools: List<ToolInfo> = emptyList(),
        pendingAuthorizationUrl: String? = null,
    ) = mcpServerUiState(
        config = server,
        status = McpServerStatus(state = state, failure = failure),
        tools = tools,
        hasStoredToken = false,
        pendingAuthorizationUrl = pendingAuthorizationUrl,
    )

    @Test
    fun `a server that needs a sign-in offers the authorize action`() {
        val card = card(McpServerStateKind.NeedsAuth, McpFailure(McpFailureKind.NeedsAuth, 401, "HTTP 401"))

        assertEquals(McpConnectionStatus.NeedsAuth, card.connectionStatus)
        assertTrue(card.canAuthorize, "the sign-in button is the whole point of this state")
        assertFalse(card.canRetry, "a 401 is not something a plain reconnect fixes")
        assertEquals("HTTP 401", card.failureDetail, "the reason reaches the card, not just the dot colour")
    }

    @Test
    fun `a connected server hides the authorize action`() {
        val card = card(McpServerStateKind.Connected, tools = cachedTools)

        assertEquals(McpConnectionStatus.Connected, card.connectionStatus)
        assertFalse(card.canAuthorize)
        assertFalse(card.canRetry)
        assertEquals(2, card.toolCount)
        assertFalse(card.toolsFromCache, "a live connection is not a cache")
    }

    @Test
    fun `a failing server offers retry instead of a browser flow`() {
        val card = card(McpServerStateKind.Failed, McpFailure(McpFailureKind.ServerError, 500, "boom"))

        assertTrue(card.canRetry)
        assertFalse(card.canAuthorize, "a 500 is not a sign-in problem")
    }

    @Test
    fun `a disconnected server still shows its cached tools`() {
        val card = card(McpServerStateKind.NeedsAuth, tools = cachedTools)

        assertEquals(2, card.toolCount)
        assertTrue(card.toolsFromCache, "the last known list is marked as cached")
    }

    @Test
    fun `pressing authorize calls the manager and shows the browser wait`() = runTest {
        val config = fakeRepository.addMcpServer("Walt", "https://walt.example/mcp", emptyMap())
        fakeRepository.setMcpServerStatus(config.id, McpServerStatus(state = McpServerStateKind.NeedsAuth))
        val viewModel = viewModel()
        val states = collectStates(viewModel)

        assertEquals(McpConnectionStatus.NeedsAuth, states.last().mcpServers.single().connectionStatus)

        viewModel.actions.onAuthorizeMcpServer(config.id)
        advanceUntilIdle()

        assertEquals(listOf(config.id), fakeRepository.authorizeMcpCalls, "the button must reach beginAuthorization")
        val card = states.last().mcpServers.single()
        assertEquals(
            McpConnectionStatus.NeedsAuth,
            card.connectionStatus,
            "the manager's answer must not be swallowed by a local 'connecting' flag",
        )
        assertTrue(card.isWaitingForBrowser)
        assertNotNull(card.pendingAuthorizationUrl, "the URL is shown when the browser did not come up")
        assertTrue(card.canAuthorize, "a second attempt stays possible while the browser is pending")
    }

    @Test
    fun `a reconnect answer replaces the local connecting state`() = runTest {
        val config = fakeRepository.addMcpServer("Walt", "https://walt.example/mcp", emptyMap())
        fakeRepository.setMcpServerStatus(config.id, McpServerStatus(state = McpServerStateKind.Connected))
        val viewModel = viewModel()
        val states = collectStates(viewModel)

        assertEquals(McpConnectionStatus.Connected, states.last().mcpServers.single().connectionStatus)

        viewModel.actions.onRefreshMcpServer(config.id)
        advanceUntilIdle()

        assertEquals(
            McpConnectionStatus.Connected,
            states.last().mcpServers.single().connectionStatus,
            "a finished reconnect must not leave the card on 'connecting' forever",
        )
    }

    /**
     * Every state the screen would have shown, in order. Unconfined, so an emission is recorded
     * the moment the view model writes it — a collector on the standard dispatcher can start after
     * the action and then only ever see the final value, which hides the bug this file pins.
     */
    private fun TestScope.collectStates(viewModel: SettingsViewModel): List<SettingsUiState> {
        val states = mutableListOf<SettingsUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.state.collect { states += it }
        }
        advanceUntilIdle()
        return states
    }
}
