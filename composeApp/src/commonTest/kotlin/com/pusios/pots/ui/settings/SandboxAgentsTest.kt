package com.pusios.pots.ui.settings

import com.pusios.pots.NoOpPotsBuildController
import com.pusios.pots.PotsBuildController
import com.pusios.pots.build.BuildAgents
import com.pusios.pots.build.PotsBuildState
import com.pusios.pots.testutil.FakeDataRepository
import com.pusios.pots.testutil.FakeSandboxController
import kotlinx.collections.immutable.persistentMapOf
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

/**
 * The Settings card reads agent versions out of Pots Build's state, so the
 * mapping is what has to hold: only installed agents are listed, and a version
 * the agent never reported stays null instead of becoming a made-up one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SandboxAgentsTest {

    private val testDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakePotsBuildController : PotsBuildController by NoOpPotsBuildController() {
        override val state = MutableStateFlow(PotsBuildState())
    }

    @Test
    fun `lists installed agents with the version each reported`() = runTest {
        val build = FakePotsBuildController()
        build.state.value = PotsBuildState(
            installedAgents = persistentSetOf(BuildAgents.PI, BuildAgents.HERMES),
            agentVersions = persistentMapOf(BuildAgents.PI to "1.5.2", BuildAgents.HERMES to "0.23.2"),
        )

        val viewModel = SandboxViewModel(FakeDataRepository(), FakeSandboxController(), build)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            listOf(SandboxAgentUi(BuildAgents.PI, "Pi", "1.5.2"), SandboxAgentUi(BuildAgents.HERMES, "Hermes", "0.23.2")),
            viewModel.state.value.agents,
        )
    }

    @Test
    fun `an agent that reported no version keeps a null one`() = runTest {
        val build = FakePotsBuildController()
        build.state.value = PotsBuildState(installedAgents = persistentSetOf(BuildAgents.PI))

        val viewModel = SandboxViewModel(FakeDataRepository(), FakeSandboxController(), build)
        testDispatcher.scheduler.advanceUntilIdle()

        val pi = viewModel.state.value.agents.single()
        assertEquals(BuildAgents.PI, pi.id)
        assertNull(pi.version)
    }

    @Test
    fun `nothing installed means no agent rows`() = runTest {
        val viewModel = SandboxViewModel(FakeDataRepository(), FakeSandboxController(), FakePotsBuildController())
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(emptyList(), viewModel.state.value.agents)
    }
}
