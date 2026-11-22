package com.pusios.pots.ui.build

import app.cash.turbine.test
import com.pusios.pots.FileBrowserSource
import com.pusios.pots.PotsBuildController
import com.pusios.pots.NoOpFileBrowserSource
import com.pusios.pots.build.BuildEnvironmentState
import com.pusios.pots.build.PotsBuildState
import com.pusios.pots.testutil.FakeDataRepository
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class PotsBuildViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeRepository: FakeDataRepository
    private lateinit var fakeController: FakePotsBuildController

    private class FakePotsBuildController : PotsBuildController {
        override val state = MutableStateFlow(
            PotsBuildState(
                environment = BuildEnvironmentState.Ready,
                installedAgents = persistentSetOf("pi", "hermes"),
            ),
        )
        override val files: FileBrowserSource = NoOpFileBrowserSource

        /** Agent ids passed to [startSession], oldest first. */
        val startedWith = mutableListOf<String?>()

        /** Project names passed to [deleteProject], oldest first. */
        val deleted = mutableListOf<String>()

        /** Old-to-new pairs passed to [renameProject], oldest first. */
        val renamed = mutableListOf<Pair<String, String>>()

        override fun install(agentIds: Set<String>) {}
        override fun cancel() {}
        override fun uninstall() {}
        override fun refresh() {}
        override fun createProject(name: String): String? = name
        override fun deleteProject(name: String) {
            deleted += name
        }

        override fun renameProject(name: String, newName: String): String {
            renamed += name to newName
            return newName
        }

        override fun startSession(project: String, agentId: String?) {
            startedWith += agentId
        }

        override fun selectSession(id: String) {}
        override fun closeSession(id: String) {}
        override fun resumeProject(project: String): Boolean = false
        override fun leaveProject(project: String) {}
        override fun writeToTerminal(text: String) {}
        override fun resizeTerminal(columns: Int, rows: Int) {}
        override fun startWebPanel(panelId: String) {}
        override fun stopWebPanel(panelId: String) {}
        override fun openWebPanel(panelId: String) {}
        override fun setWebPanelAutostart(panelId: String, enabled: Boolean) {}
        override suspend fun webPanelPassword(panelId: String): String? = null
        override fun webPanelLog(panelId: String): String {
            logRequests += panelId
            return "starting\nlistening on 4096"
        }

        /** Panel ids passed to [webPanelLog], oldest first — the log must be read on demand only. */
        val logRequests = mutableListOf<String>()

        /** Agent id sets passed to [checkAgents], oldest first. */
        val checked = mutableListOf<Set<String>>()

        /** Agent ids passed to [updateAgent], oldest first. */
        val updated = mutableListOf<String>()

        /** Id-and-flag pairs passed to [removeAgent], oldest first. */
        val removed = mutableListOf<Pair<String, Boolean>>()

        override fun checkAgents(agentIds: Set<String>) {
            checked += agentIds
        }

        override fun updateAgent(agentId: String) {
            updated += agentId
        }

        override fun removeAgent(agentId: String, includeBulk: Boolean) {
            removed += agentId to includeBulk
        }
    }

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeRepository = FakeDataRepository()
        fakeController = FakePotsBuildController()
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `setLaunchAgent persists the choice`() = runTest {
        val viewModel = PotsBuildViewModel(fakeController, fakeRepository)

        viewModel.uiState.test {
            assertNull(awaitItem().launchAgentId)

            viewModel.setLaunchAgent("hermes")
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("hermes", awaitItem().launchAgentId)
            assertEquals("hermes", fakeRepository.getPotsBuildLaunchAgent())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `launch agent is restored on the next app start`() = runTest {
        fakeRepository.storedPotsBuildLaunchAgent = "pi"

        val viewModel = PotsBuildViewModel(fakeController, fakeRepository)

        viewModel.uiState.test {
            assertEquals("pi", awaitItem().launchAgentId)
            cancelAndIgnoreRemainingEvents()
        }

        viewModel.openProject("demo")
        assertEquals(listOf<String?>("pi"), fakeController.startedWith)
    }

    @Test
    fun `picking the shell again clears the stored agent`() = runTest {
        fakeRepository.storedPotsBuildLaunchAgent = "hermes"

        val viewModel = PotsBuildViewModel(fakeController, fakeRepository)
        viewModel.setLaunchAgent(null)

        assertNull(fakeRepository.getPotsBuildLaunchAgent())
        viewModel.openProject("demo")
        assertEquals(listOf<String?>(null), fakeController.startedWith)
    }

    @Test
    fun `deleting and renaming reach the environment`() = runTest {
        val viewModel = PotsBuildViewModel(fakeController, fakeRepository)

        viewModel.deleteProject("old-demo")
        viewModel.renameProject("demo", "demo-2")

        assertEquals(listOf("old-demo"), fakeController.deleted)
        assertEquals(listOf("demo" to "demo-2"), fakeController.renamed)
    }

    @Test
    fun `a remembered agent that is no longer installed falls back to a shell`() = runTest {
        // A choice made when Pots still shipped Claude Code: the id is gone from the
        // catalog now, so the environment can never report it as installed.
        fakeRepository.storedPotsBuildLaunchAgent = "claude-code"

        val viewModel = PotsBuildViewModel(fakeController, fakeRepository)

        viewModel.uiState.test {
            assertNull(awaitItem().launchAgentId)
            cancelAndIgnoreRemainingEvents()
        }

        viewModel.openProject("demo")
        assertEquals(listOf<String?>(null), fakeController.startedWith)
    }

    @Test
    fun `per-agent check, update and remove reach the environment`() = runTest {
        val viewModel = PotsBuildViewModel(fakeController, fakeRepository)

        viewModel.checkAgents(setOf("pi", "opencode"))
        viewModel.updateAgent("opencode")
        viewModel.removeAgent("pi", includeBulk = false)

        assertEquals(listOf(setOf("pi", "opencode")), fakeController.checked)
        assertEquals(listOf("opencode"), fakeController.updated)
        // The bulk flag reaches the controller as asked: Hermes' 5.2 GB checkout
        // is only dropped when the user says so.
        assertEquals(listOf("pi" to false), fakeController.removed)
    }

    @Test
    fun `the panel log is read only when asked for, and reaches the caller`() = runTest {
        val viewModel = PotsBuildViewModel(fakeController, fakeRepository)

        assertEquals(emptyList(), fakeController.logRequests, "nothing is read on construction")

        assertEquals("starting\nlistening on 4096", viewModel.panelLog("opencode"))
        assertEquals(listOf("opencode"), fakeController.logRequests)
    }
}
