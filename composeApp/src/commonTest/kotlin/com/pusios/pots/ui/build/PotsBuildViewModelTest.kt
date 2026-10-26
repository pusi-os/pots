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
                installedAgents = persistentSetOf("claude-code", "grok"),
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

            viewModel.setLaunchAgent("grok")
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("grok", awaitItem().launchAgentId)
            assertEquals("grok", fakeRepository.getPotsBuildLaunchAgent())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `launch agent is restored on the next app start`() = runTest {
        fakeRepository.storedPotsBuildLaunchAgent = "claude-code"

        val viewModel = PotsBuildViewModel(fakeController, fakeRepository)

        viewModel.uiState.test {
            assertEquals("claude-code", awaitItem().launchAgentId)
            cancelAndIgnoreRemainingEvents()
        }

        viewModel.openProject("demo")
        assertEquals(listOf<String?>("claude-code"), fakeController.startedWith)
    }

    @Test
    fun `picking the shell again clears the stored agent`() = runTest {
        fakeRepository.storedPotsBuildLaunchAgent = "grok"

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
        fakeRepository.storedPotsBuildLaunchAgent = "opencode"

        val viewModel = PotsBuildViewModel(fakeController, fakeRepository)

        viewModel.uiState.test {
            assertNull(awaitItem().launchAgentId)
            cancelAndIgnoreRemainingEvents()
        }

        viewModel.openProject("demo")
        assertEquals(listOf<String?>(null), fakeController.startedWith)
    }
}
