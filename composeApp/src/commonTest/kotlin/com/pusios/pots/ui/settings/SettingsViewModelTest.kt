package com.pusios.pots.ui.settings

import app.cash.turbine.test
import com.pusios.pots.DaemonController
import com.pusios.pots.data.Service
import com.pusios.pots.data.ServiceEntry
import com.pusios.pots.data.TaskScheduler
import com.pusios.pots.testutil.FakeDataRepository
import com.pusios.pots.tools.AppPermission
import com.pusios.pots.tools.PermissionController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeRepository: FakeDataRepository
    private lateinit var noOpScheduler: TaskScheduler
    private val fakeDaemonController = object : DaemonController {
        override fun start() {}
        override fun stop() {}
    }
    private val fakeNotificationPermissionController = PermissionController(AppPermission.POST_NOTIFICATIONS)

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

    @Test
    fun `initial state has empty configured services when none configured`() = runTest {
        val viewModel = SettingsViewModel(fakeRepository, fakeDaemonController, fakeNotificationPermissionController, noOpScheduler, testDispatcher)

        viewModel.state.test {
            val state = awaitItem()
            assertTrue(state.configuredServices.isEmpty())
        }
    }

    @Test
    fun `initial state reflects configured services`() = runTest {
        fakeRepository.setConfiguredServices(Service.Polza, Service.VseGpt)

        val viewModel = SettingsViewModel(fakeRepository, fakeDaemonController, fakeNotificationPermissionController, noOpScheduler, testDispatcher)

        viewModel.state.test {
            val state = awaitItem()
            assertEquals(2, state.configuredServices.size)
            assertEquals(Service.Polza, state.configuredServices[0].service)
            assertEquals("polza", state.configuredServices[0].instanceId)
            assertEquals(Service.VseGpt, state.configuredServices[1].service)
            assertEquals("vsegpt", state.configuredServices[1].instanceId)
        }
    }

    @Test
    fun `onAddService adds a new configured service`() = runTest {
        val viewModel = SettingsViewModel(fakeRepository, fakeDaemonController, fakeNotificationPermissionController, noOpScheduler, testDispatcher)

        viewModel.state.test {
            val initialState = awaitItem()
            assertTrue(initialState.configuredServices.isEmpty())

            viewModel.actions.onAddService(Service.AiTunnel)
            testDispatcher.scheduler.advanceUntilIdle()

            val updatedState = awaitItem()
            assertEquals(1, updatedState.configuredServices.size)
            assertEquals(Service.AiTunnel, updatedState.configuredServices[0].service)
            assertEquals("aitunnel", updatedState.expandedServiceId)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onRemoveService removes a configured service by instanceId`() = runTest {
        fakeRepository.setConfiguredServices(Service.Polza, Service.VseGpt)
        val viewModel = SettingsViewModel(fakeRepository, fakeDaemonController, fakeNotificationPermissionController, noOpScheduler, testDispatcher)

        viewModel.state.test {
            val initialState = awaitItem()
            assertEquals(2, initialState.configuredServices.size)

            // Remove by instanceId (which equals serviceId for first instances)
            viewModel.actions.onRemoveService("polza")
            testDispatcher.scheduler.advanceUntilIdle()

            // Deletion is deferred (undo snackbar), collect until actually removed
            val updates = cancelAndConsumeRemainingEvents()
            val lastState = updates.filterIsInstance<app.cash.turbine.Event.Item<SettingsUiState>>().lastOrNull()?.value
            requireNotNull(lastState)
            assertEquals(1, lastState.configuredServices.size)
            assertEquals(Service.VseGpt, lastState.configuredServices[0].service)
        }
    }

    @Test
    fun `rapid onRemoveService keeps the new pendingDeletion after the previous commits`() = runTest {
        fakeRepository.setConfiguredServices(Service.Polza, Service.VseGpt, Service.AiTunnel)
        val viewModel = SettingsViewModel(fakeRepository, fakeDaemonController, fakeNotificationPermissionController, noOpScheduler, testDispatcher)

        viewModel.state.test {
            assertEquals(3, awaitItem().configuredServices.size)

            // First remove: Gemini becomes pending.
            viewModel.actions.onRemoveService("polza")
            testDispatcher.scheduler.runCurrent()

            // Second remove fires before the 4s undo timeout: commits Gemini immediately and queues OpenAI.
            viewModel.actions.onRemoveService("vsegpt")
            testDispatcher.scheduler.runCurrent()

            // Let the committed (now-stale) executeDeletion for Gemini finish without advancing the 4s delay for OpenAI.
            testDispatcher.scheduler.runCurrent()

            val afterCommit = cancelAndConsumeRemainingEvents()
                .filterIsInstance<app.cash.turbine.Event.Item<SettingsUiState>>()
                .last().value

            // Gemini is gone, OpenAI is still pending so its snackbar stays visible.
            assertEquals(2, afterCommit.configuredServices.size)
            assertEquals(Service.VseGpt, afterCommit.configuredServices[0].service)
            assertEquals(Service.AiTunnel, afterCommit.configuredServices[1].service)
            assertEquals(PendingDeletion.Service("vsegpt"), afterCommit.pendingDeletion)
        }
    }

    @Test
    fun `availableServicesToAdd contains all non-Free services`() = runTest {
        fakeRepository.setConfiguredServices(Service.Polza)

        val viewModel = SettingsViewModel(fakeRepository, fakeDaemonController, fakeNotificationPermissionController, noOpScheduler, testDispatcher)

        viewModel.state.test {
            val state = awaitItem()
            // Should not contain Free
            assertTrue(state.availableServicesToAdd.none { it == Service.Free })
            // Should contain all other services including already-configured ones (multi-instance)
            assertTrue(state.availableServicesToAdd.contains(Service.Polza))
            assertTrue(state.availableServicesToAdd.contains(Service.VseGpt))
            assertTrue(state.availableServicesToAdd.contains(Service.ProxyApi))
        }
    }

    @Test
    fun `Pulse model picker includes a configured local model`() = runTest {
        fakeRepository.fakeServiceEntries = listOf(
            ServiceEntry(
                instanceId = "litert",
                serviceId = Service.LiteRT.id,
                serviceName = Service.LiteRT.displayName,
                modelId = "gemma-local",
                icon = Service.LiteRT.icon,
            ),
        )

        val viewModel = SettingsViewModel(fakeRepository, fakeDaemonController, fakeNotificationPermissionController, noOpScheduler, testDispatcher)

        viewModel.state.test {
            val state = awaitItem()
            assertTrue(state.heartbeatServiceEntries.any { it.serviceId == Service.LiteRT.id })
        }
    }

    @Test
    fun `adding same service type twice creates unique instanceIds`() = runTest {
        val viewModel = SettingsViewModel(fakeRepository, fakeDaemonController, fakeNotificationPermissionController, noOpScheduler, testDispatcher)

        viewModel.state.test {
            val initialState = awaitItem()

            viewModel.actions.onAddService(Service.VseGpt)
            testDispatcher.scheduler.advanceUntilIdle()
            val afterFirst = awaitItem()
            assertEquals(1, afterFirst.configuredServices.size)
            assertEquals("vsegpt", afterFirst.configuredServices[0].instanceId)

            viewModel.actions.onAddService(Service.VseGpt)
            testDispatcher.scheduler.advanceUntilIdle()
            val afterSecond = awaitItem()
            assertEquals(2, afterSecond.configuredServices.size)
            assertEquals("vsegpt", afterSecond.configuredServices[0].instanceId)
            assertEquals("vsegpt_2", afterSecond.configuredServices[1].instanceId)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- MCP Server Tests ---

    @Test
    fun `initial state has empty mcp servers when none configured`() = runTest {
        val viewModel = SettingsViewModel(fakeRepository, fakeDaemonController, fakeNotificationPermissionController, noOpScheduler, testDispatcher)

        viewModel.state.test {
            val state = awaitItem()
            assertTrue(state.mcpServers.isEmpty())
        }
    }

    @Test
    fun `onAddMcpServer adds server and closes dialog`() = runTest {
        val viewModel = SettingsViewModel(fakeRepository, fakeDaemonController, fakeNotificationPermissionController, noOpScheduler, testDispatcher)

        viewModel.state.test {
            val initialState = awaitItem()
            assertTrue(initialState.mcpServers.isEmpty())

            viewModel.actions.onAddMcpServer("Test Server", "https://example.com/mcp", emptyMap())
            testDispatcher.scheduler.advanceUntilIdle()

            // Dialog should close and server should appear
            val updates = cancelAndConsumeRemainingEvents()
            val lastState = (updates.filterIsInstance<app.cash.turbine.Event.Item<SettingsUiState>>().lastOrNull()?.value)
            if (lastState != null) {
                assertFalse(lastState.showAddMcpServerDialog)
                assertEquals(1, lastState.mcpServers.size)
                assertEquals("Test Server", lastState.mcpServers[0].name)
            }
        }
    }

    @Test
    fun `onRemoveMcpServer removes server`() = runTest {
        // Pre-add a server
        fakeRepository.addMcpServer("Test", "https://example.com/mcp", emptyMap())
        val viewModel = SettingsViewModel(fakeRepository, fakeDaemonController, fakeNotificationPermissionController, noOpScheduler, testDispatcher)

        viewModel.state.test {
            val initialState = awaitItem()
            assertEquals(1, initialState.mcpServers.size)

            viewModel.actions.onRemoveMcpServer(initialState.mcpServers[0].id)
            testDispatcher.scheduler.advanceUntilIdle()

            // Deletion is deferred (undo snackbar), collect until actually removed
            val updates = cancelAndConsumeRemainingEvents()
            val lastState = updates.filterIsInstance<app.cash.turbine.Event.Item<SettingsUiState>>().lastOrNull()?.value
            requireNotNull(lastState)
            assertTrue(lastState.mcpServers.isEmpty())
        }
    }

    @Test
    fun `onToggleMcpServer disables server and updates status`() = runTest {
        val config = fakeRepository.addMcpServer("Test", "https://example.com/mcp", emptyMap())
        val viewModel = SettingsViewModel(fakeRepository, fakeDaemonController, fakeNotificationPermissionController, noOpScheduler, testDispatcher)

        viewModel.state.test {
            val initialState = awaitItem()
            assertTrue(initialState.mcpServers[0].isEnabled)

            viewModel.actions.onToggleMcpServer(config.id, false)
            testDispatcher.scheduler.advanceUntilIdle()

            val updates = cancelAndConsumeRemainingEvents()
            val lastState = updates.filterIsInstance<app.cash.turbine.Event.Item<SettingsUiState>>().lastOrNull()?.value
            if (lastState != null) {
                assertFalse(lastState.mcpServers[0].isEnabled)
            }
        }
    }

    @Test
    fun `showAddMcpServerDialog toggles correctly`() = runTest {
        val viewModel = SettingsViewModel(fakeRepository, fakeDaemonController, fakeNotificationPermissionController, noOpScheduler, testDispatcher)

        viewModel.state.test {
            val initialState = awaitItem()
            assertFalse(initialState.showAddMcpServerDialog)

            viewModel.actions.onShowAddMcpServerDialog(true)
            val showState = awaitItem()
            assertTrue(showState.showAddMcpServerDialog)

            viewModel.actions.onShowAddMcpServerDialog(false)
            val hideState = awaitItem()
            assertFalse(hideState.showAddMcpServerDialog)
        }
    }

    @Test
    fun `onToggleTool does not crash with no mcp servers`() = runTest {
        val viewModel = SettingsViewModel(fakeRepository, fakeDaemonController, fakeNotificationPermissionController, noOpScheduler, testDispatcher)

        viewModel.state.test {
            awaitItem()
            // Toggle a tool - should not crash even with no MCP servers
            viewModel.actions.onToggleTool("some_tool", false)
            // No exception means success
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Service Tests (continued) ---

    @Test
    fun `onChangeApiKey updates API key for specific instance`() = runTest {
        fakeRepository.setConfiguredServices(Service.AiTunnel)
        val viewModel = SettingsViewModel(fakeRepository, fakeDaemonController, fakeNotificationPermissionController, noOpScheduler, testDispatcher)

        viewModel.state.test {
            val initialState = awaitItem()

            // Use instanceId instead of Service
            viewModel.actions.onChangeApiKey("aitunnel", "new-api-key")

            val updatedState = awaitItem()
            assertEquals("new-api-key", updatedState.configuredServices[0].apiKey)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `adding Anthropic service shows no models before validation`() = runTest {
        val viewModel = SettingsViewModel(fakeRepository, fakeDaemonController, fakeNotificationPermissionController, noOpScheduler, testDispatcher)

        viewModel.state.test {
            val initialState = awaitItem()
            assertTrue(initialState.configuredServices.isEmpty())

            viewModel.actions.onAddService(Service.Gptunnel)
            testDispatcher.scheduler.advanceUntilIdle()

            val updatedState = awaitItem()
            assertEquals(1, updatedState.configuredServices.size)
            assertEquals(Service.Gptunnel, updatedState.configuredServices[0].service)
            assertEquals("gptunnel", updatedState.configuredServices[0].instanceId)

            // Models should be empty until API key is validated and models are fetched
            val models = fakeRepository.getInstanceModels("gptunnel", Service.Gptunnel).value
            assertTrue(models.isEmpty())

            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Per-instance settings persistence ---

    @Test
    fun `onChangeApiKey persists to repository`() = runTest {
        fakeRepository.setConfiguredServices(Service.Gptunnel)
        val viewModel = SettingsViewModel(fakeRepository, fakeDaemonController, fakeNotificationPermissionController, noOpScheduler, testDispatcher)

        viewModel.state.test {
            val initialState = awaitItem()
            viewModel.actions.onChangeApiKey("gptunnel", "sk-test-123")
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("sk-test-123", fakeRepository.getInstanceApiKey("gptunnel"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onChangeApiKey clears models for that instance`() = runTest {
        fakeRepository.setConfiguredServices(Service.Gptunnel)
        // Pre-populate models
        fakeRepository.setInstanceModels("gptunnel", listOf(SettingsModel(id = "claude-old", subtitle = "")))
        val viewModel = SettingsViewModel(fakeRepository, fakeDaemonController, fakeNotificationPermissionController, noOpScheduler, testDispatcher)

        viewModel.state.test {
            val initialState = awaitItem()
            viewModel.actions.onChangeApiKey("gptunnel", "new-key")
            testDispatcher.scheduler.advanceUntilIdle()

            // Models should have been cleared
            val models = fakeRepository.getInstanceModels("gptunnel", Service.Gptunnel).value
            assertTrue(models.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onChangeBaseUrl persists to repository`() = runTest {
        fakeRepository.setConfiguredServices(Service.OpenAICompatible)
        val viewModel = SettingsViewModel(fakeRepository, fakeDaemonController, fakeNotificationPermissionController, noOpScheduler, testDispatcher)

        viewModel.state.test {
            val initialState = awaitItem()
            val instanceId = initialState.configuredServices.first().instanceId
            viewModel.actions.onChangeBaseUrl(instanceId, "https://custom.example.com/v1/")
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(
                "https://custom.example.com/v1/",
                fakeRepository.getInstanceBaseUrl(instanceId, Service.OpenAICompatible),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onChangeBaseUrl resets connectionStatus to Unknown`() = runTest {
        fakeRepository.setConfiguredServices(Service.OpenAICompatible)
        val viewModel = SettingsViewModel(fakeRepository, fakeDaemonController, fakeNotificationPermissionController, noOpScheduler, testDispatcher)

        viewModel.state.test {
            val initialState = awaitItem()
            val instanceId = initialState.configuredServices.first().instanceId
            viewModel.actions.onChangeBaseUrl(instanceId, "https://x.example/v1")

            val updatedState = awaitItem()
            assertEquals(ConnectionStatus.Unknown, updatedState.configuredServices.first().connectionStatus)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onSelectModel marks the chosen model as selected on the repository`() = runTest {
        fakeRepository.setConfiguredServices(Service.Polza)
        fakeRepository.setInstanceModels(
            "polza",
            listOf(
                SettingsModel(id = "gemini-flash", subtitle = ""),
                SettingsModel(id = "gemini-pro", subtitle = ""),
            ),
        )
        val viewModel = SettingsViewModel(fakeRepository, fakeDaemonController, fakeNotificationPermissionController, noOpScheduler, testDispatcher)

        viewModel.state.test {
            val initialState = awaitItem()
            viewModel.actions.onSelectModel("polza", "gemini-pro")
            testDispatcher.scheduler.advanceUntilIdle()

            val models = fakeRepository.getInstanceModels("polza", Service.Polza).value
            assertTrue(models.first { it.id == "gemini-pro" }.isSelected)
            assertFalse(models.first { it.id == "gemini-flash" }.isSelected)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Expand state ---

    @Test
    fun `onExpandService updates expandedServiceId`() = runTest {
        fakeRepository.setConfiguredServices(Service.Polza, Service.VseGpt)
        val viewModel = SettingsViewModel(fakeRepository, fakeDaemonController, fakeNotificationPermissionController, noOpScheduler, testDispatcher)

        viewModel.state.test {
            val initialState = awaitItem()
            assertEquals(null, initialState.expandedServiceId)

            viewModel.actions.onExpandService("vsegpt")
            testDispatcher.scheduler.advanceUntilIdle()

            val expandedState = awaitItem()
            assertEquals("vsegpt", expandedState.expandedServiceId)

            // Collapse
            viewModel.actions.onExpandService(null)
            val collapsedState = awaitItem()
            assertEquals(null, collapsedState.expandedServiceId)
        }
    }

    // --- Tab selection ---

    @Test
    fun `onSelectTab updates currentTab`() = runTest {
        val viewModel = SettingsViewModel(fakeRepository, fakeDaemonController, fakeNotificationPermissionController, noOpScheduler, testDispatcher)

        viewModel.state.test {
            val initialState = awaitItem()
            viewModel.actions.onSelectTab(SettingsTab.Tools)
            val updated = awaitItem()
            assertEquals(SettingsTab.Tools, updated.currentTab)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Soul / dynamic UI / memory toggles ---

    @Test
    fun `onSaveSoul persists soul text`() = runTest {
        val viewModel = SettingsViewModel(fakeRepository, fakeDaemonController, fakeNotificationPermissionController, noOpScheduler, testDispatcher)

        viewModel.state.test {
            val initialState = awaitItem()
            viewModel.actions.onSaveSoul("You are a helpful assistant.")

            val updated = awaitItem()
            assertEquals("You are a helpful assistant.", updated.soulText)
            assertEquals("You are a helpful assistant.", fakeRepository.getSoulText())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onToggleDynamicUi persists and reflects in state`() = runTest {
        val viewModel = SettingsViewModel(fakeRepository, fakeDaemonController, fakeNotificationPermissionController, noOpScheduler, testDispatcher)

        viewModel.state.test {
            val initialState = awaitItem()
            assertTrue(initialState.isDynamicUiEnabled)
            viewModel.actions.onToggleDynamicUi(false)
            val updated = awaitItem()
            assertFalse(updated.isDynamicUiEnabled)
            assertFalse(fakeRepository.isDynamicUiEnabled())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onToggleMemory persists and reflects in state`() = runTest {
        val viewModel = SettingsViewModel(fakeRepository, fakeDaemonController, fakeNotificationPermissionController, noOpScheduler, testDispatcher)

        viewModel.state.test {
            val initialState = awaitItem()
            assertTrue(initialState.isMemoryEnabled)
            viewModel.actions.onToggleMemory(false)
            val updated = awaitItem()
            assertFalse(updated.isMemoryEnabled)
            assertFalse(fakeRepository.isMemoryEnabled())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onToggleScheduling persists and reflects in state`() = runTest {
        val viewModel = SettingsViewModel(fakeRepository, fakeDaemonController, fakeNotificationPermissionController, noOpScheduler, testDispatcher)

        viewModel.state.test {
            val initialState = awaitItem()
            assertTrue(initialState.isSchedulingEnabled)
            viewModel.actions.onToggleScheduling(false)
            val updated = awaitItem()
            assertFalse(updated.isSchedulingEnabled)
            assertFalse(fakeRepository.isSchedulingEnabled())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onToggleFreeFallback persists and reflects in state`() = runTest {
        val viewModel = SettingsViewModel(fakeRepository, fakeDaemonController, fakeNotificationPermissionController, noOpScheduler, testDispatcher)

        viewModel.state.test {
            val initialState = awaitItem()
            assertTrue(initialState.isFreeFallbackEnabled)
            viewModel.actions.onToggleFreeFallback(false)
            val updated = awaitItem()
            assertFalse(updated.isFreeFallbackEnabled)
            assertFalse(fakeRepository.isFreeFallbackEnabled())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onChangeUiScale persists and reflects in state`() = runTest {
        val viewModel = SettingsViewModel(fakeRepository, fakeDaemonController, fakeNotificationPermissionController, noOpScheduler, testDispatcher)

        viewModel.state.test {
            val initialState = awaitItem()
            viewModel.actions.onChangeUiScale(1.5f)
            val updated = awaitItem()
            assertEquals(1.5f, updated.uiScale)
            assertEquals(1.5f, fakeRepository.getUiScale())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onChangeEmailPollInterval persists and reflects in state`() = runTest {
        val viewModel = SettingsViewModel(fakeRepository, fakeDaemonController, fakeNotificationPermissionController, noOpScheduler, testDispatcher)

        viewModel.state.test {
            val initialState = awaitItem()
            viewModel.actions.onChangeEmailPollInterval(30)
            val updated = awaitItem()
            assertEquals(30, updated.emailPollIntervalMinutes)
            assertEquals(30, fakeRepository.getEmailPollIntervalMinutes())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onChangeHeartbeatInterval persists and reflects in state`() = runTest {
        val viewModel = SettingsViewModel(fakeRepository, fakeDaemonController, fakeNotificationPermissionController, noOpScheduler, testDispatcher)

        viewModel.state.test {
            val initialState = awaitItem()
            viewModel.actions.onChangeHeartbeatInterval(45)
            val updated = awaitItem()
            assertEquals(45, updated.heartbeatIntervalMinutes)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
