package com.pusios.pots.ui.settings

import com.pusios.pots.NoOpPotsBuildController
import com.pusios.pots.PotsBuildController
import com.pusios.pots.build.AgentUpdate
import com.pusios.pots.build.BuildAgents
import com.pusios.pots.build.PotsBuildState
import com.pusios.pots.build.WebPanels
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
import kotlin.test.assertTrue

/**
 * The Settings card reads the agents out of Pots Build's state, so the mapping is
 * what has to hold: every agent in the catalog is a row, a version the agent never
 * reported stays null instead of becoming a made-up one, and nothing is ever
 * called up to date without a check that answered.
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

    private fun viewModel(build: PotsBuildController) =
        SandboxViewModel(FakeDataRepository(), FakeSandboxController(), build)

    @Test
    fun `every agent is a row, installed or not`() = runTest {
        val build = FakePotsBuildController()
        build.state.value = PotsBuildState(
            installedAgents = persistentSetOf(BuildAgents.PI, BuildAgents.HERMES),
            agentVersions = persistentMapOf(BuildAgents.PI to "1.5.2", BuildAgents.HERMES to "0.23.2"),
        )

        val rows = viewModel(build).also { testDispatcher.scheduler.advanceUntilIdle() }.state.value.agentRows

        assertEquals(
            listOf(BuildAgents.OPENCODE, BuildAgents.PI, BuildAgents.HERMES),
            rows.map { it.id },
        )
        // The two that are there carry what they printed and are only "unchecked".
        assertEquals(AgentUpdate.Unchecked("1.5.2"), rows[1].update)
        assertEquals("0.23.2", rows[2].version)
        // The one that is not is what offers to install it.
        assertEquals(AgentUpdate.NotInstalled, rows[0].update)
        assertNull(rows[0].version)
    }

    @Test
    fun `the OpenCode row shows the version its probe printed`() = runTest {
        val build = FakePotsBuildController()
        build.state.value = PotsBuildState(
            installedAgents = persistentSetOf(BuildAgents.OPENCODE),
            agentVersions = persistentMapOf(BuildAgents.OPENCODE to "2.3.2"),
        )

        val rows = viewModel(build).also { testDispatcher.scheduler.advanceUntilIdle() }.state.value.agentRows

        assertEquals("2.3.2", rows.first { it.id == BuildAgents.OPENCODE }.version)
    }

    @Test
    fun `an agent that reported no version keeps a null one`() = runTest {
        val build = FakePotsBuildController()
        build.state.value = PotsBuildState(installedAgents = persistentSetOf(BuildAgents.PI))

        val rows = viewModel(build).also { testDispatcher.scheduler.advanceUntilIdle() }.state.value.agentRows

        val pi = rows.single { it.id == BuildAgents.PI }
        assertNull(pi.version)
        assertEquals(AgentUpdate.Unchecked(null), pi.update)
    }

    @Test
    fun `pi-web is a row of its own once Pi is installed`() = runTest {
        val build = FakePotsBuildController()
        build.state.value = PotsBuildState(
            installedAgents = persistentSetOf(BuildAgents.PI),
            agentVersions = persistentMapOf(
                BuildAgents.PI to "1.5.2",
                WebPanels.PI_WEB_VERSION_KEY to "0.17.0",
            ),
        )

        val rows = viewModel(build).also { testDispatcher.scheduler.advanceUntilIdle() }.state.value.agentRows

        val piWeb = rows.single { it.id == WebPanels.PI_WEB_VERSION_KEY }
        assertEquals("0.17.0", piWeb.version)
        assertEquals(AgentUpdate.Unchecked("0.17.0"), piWeb.update)
    }

    @Test
    fun `a failed check reaches the card as a failure`() = runTest {
        val build = FakePotsBuildController()
        build.state.value = PotsBuildState(
            installedAgents = persistentSetOf(BuildAgents.PI),
            agentVersions = persistentMapOf(BuildAgents.PI to "1.5.2"),
            agentUpdates = persistentMapOf(BuildAgents.PI to AgentUpdate.CheckFailed("1.5.2", "14:32")),
        )

        val rows = viewModel(build).also { testDispatcher.scheduler.advanceUntilIdle() }.state.value.agentRows

        val pi = rows.single { it.id == BuildAgents.PI }
        assertEquals(AgentUpdate.CheckFailed("1.5.2", "14:32"), pi.update)
        assertTrue(pi.update !is AgentUpdate.UpToDate)
    }

    @Test
    fun `nothing installed still offers the whole catalog`() = runTest {
        val rows = viewModel(FakePotsBuildController())
            .also { testDispatcher.scheduler.advanceUntilIdle() }
            .state.value.agentRows

        assertEquals(
            listOf(BuildAgents.OPENCODE, BuildAgents.PI, BuildAgents.HERMES),
            rows.map { it.id },
        )
        assertTrue(rows.all { it.update == AgentUpdate.NotInstalled })
    }
}
