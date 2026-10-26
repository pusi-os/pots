package com.pusios.pots.ui.settings

import app.cash.turbine.test
import com.pusios.pots.DaemonController
import com.pusios.pots.data.Service
import com.pusios.pots.splinterlands.SplinterlandsApi
import com.pusios.pots.splinterlands.SplinterlandsBattleRunner
import com.pusios.pots.splinterlands.SplinterlandsStore
import com.pusios.pots.testutil.FakeDataRepository
import com.russhwolf.settings.MapSettings
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
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SplinterlandsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeRepository: FakeDataRepository
    private lateinit var fakeSplinterlandsStore: SplinterlandsStore
    private lateinit var fakeSplinterlandsBattleRunner: SplinterlandsBattleRunner

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeRepository = FakeDataRepository()
        val testSettings = com.pusios.pots.data.AppSettings(MapSettings())
        fakeSplinterlandsStore = SplinterlandsStore(testSettings)
        fakeSplinterlandsBattleRunner = SplinterlandsBattleRunner(
            fakeSplinterlandsStore,
            SplinterlandsApi(),
            fakeRepository,
            object : DaemonController {
                override fun start() {}
                override fun stop() {}
            },
        )
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial splinterlands instanceIds is empty`() = runTest {
        val viewModel = SplinterlandsViewModel(fakeRepository, fakeSplinterlandsStore, fakeSplinterlandsBattleRunner, SplinterlandsApi())

        viewModel.state.test {
            val state = awaitItem()
            assertTrue(state.splinterlandsInstanceIds.isEmpty())
        }
    }

    @Test
    fun `onAddSplinterlandsService adds service to list`() = runTest {
        fakeRepository.setConfiguredServices(Service.Polza)
        val viewModel = SplinterlandsViewModel(fakeRepository, fakeSplinterlandsStore, fakeSplinterlandsBattleRunner, SplinterlandsApi())

        viewModel.state.test {
            val initialState = awaitItem()
            assertTrue(initialState.splinterlandsInstanceIds.isEmpty())

            initialState.onAddSplinterlandsService("polza")

            val updatedState = awaitItem()
            assertEquals(listOf("polza"), updatedState.splinterlandsInstanceIds)
        }
    }

    @Test
    fun `onRemoveSplinterlandsService removes service from list`() = runTest {
        fakeRepository.setConfiguredServices(Service.Polza, Service.VseGpt)
        fakeSplinterlandsStore.setInstanceIds(listOf("polza", "vsegpt"))
        val viewModel = SplinterlandsViewModel(fakeRepository, fakeSplinterlandsStore, fakeSplinterlandsBattleRunner, SplinterlandsApi())

        viewModel.state.test {
            val initialState = awaitItem()
            assertEquals(listOf("polza", "vsegpt"), initialState.splinterlandsInstanceIds)

            initialState.onRemoveSplinterlandsService("polza")

            val updatedState = awaitItem()
            assertEquals(listOf("vsegpt"), updatedState.splinterlandsInstanceIds)
        }
    }

    @Test
    fun `onReorderSplinterlandsServices changes priority order`() = runTest {
        fakeRepository.setConfiguredServices(Service.Polza, Service.VseGpt)
        fakeSplinterlandsStore.setInstanceIds(listOf("polza", "vsegpt"))
        val viewModel = SplinterlandsViewModel(fakeRepository, fakeSplinterlandsStore, fakeSplinterlandsBattleRunner, SplinterlandsApi())

        viewModel.state.test {
            val initialState = awaitItem()
            assertEquals(listOf("polza", "vsegpt"), initialState.splinterlandsInstanceIds)

            initialState.onReorderSplinterlandsServices(listOf("vsegpt", "polza"))

            val updatedState = awaitItem()
            assertEquals(listOf("vsegpt", "polza"), updatedState.splinterlandsInstanceIds)
            // Verify persisted
            assertEquals(listOf("vsegpt", "polza"), fakeSplinterlandsStore.getInstanceIds())
        }
    }
}
