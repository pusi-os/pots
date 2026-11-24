package com.pusios.pots.ui.chat

import app.cash.turbine.test
import com.pusios.pots.data.ProviderBalance
import com.pusios.pots.data.Service
import com.pusios.pots.data.TaskScheduler
import com.pusios.pots.network.ProviderBalanceClient
import com.pusios.pots.testutil.FakeDataRepository
import com.pusios.pots.ui.settings.SettingsModel
import com.pusios.pots.ui.settings.formatProviderBalance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The 3.20.0 onboarding rework (board task t_3223b2ba): the pasted key saves immediately
 * (no separate «Проверить и добавить» button), the model dropdown lands on the DeepSeek
 * V4.1 Flash default from the provider's own catalog, the balance appears only for
 * providers that expose one, and a provider switch clears everything the previous key loaded.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelOnboardingFlowTest {

    private val testDispatcher = StandardTestDispatcher()

    // Shares the scheduler with runTest, so virtual time (timeouts) advances in tests.
    private val unconfinedDispatcher = UnconfinedTestDispatcher(testDispatcher.scheduler)
    private lateinit var fakeRepository: FakeDataRepository

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeRepository = FakeDataRepository()
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class StubBalanceClient(private val balance: ProviderBalance?) : ProviderBalanceClient() {
        override suspend fun fetch(service: Service, apiKey: String): ProviderBalance? = balance
    }

    private fun createViewModel(balance: ProviderBalance? = null): ChatViewModel {
        val noOpScheduler = TaskScheduler(fakeRepository, enabled = false)
        return ChatViewModel(fakeRepository, noOpScheduler, unconfinedDispatcher, balanceClient = StubBalanceClient(balance))
    }

    @Test
    fun `pasted key saves immediately and marks the wizard key-added`() = runTest {
        val viewModel = createViewModel()
        viewModel.state.test {
            val initial = awaitItem()
            assertEquals(OnboardingPhase.Active, initial.onboarding.phase)

            // The unconfined test dispatcher resolves the add synchronously, so the
            // intermediate "checking" emission may be conflated away by the StateFlow.
            viewModel.state.value.actions.onboardingAddKey(Service.PlusVibe.id, "sk-pv-test")
            val state = awaitItem()

            // Same mechanism as Settings → Services: key on the instance, connection validated.
            assertEquals("sk-pv-test", fakeRepository.getInstanceApiKey(Service.PlusVibe.id))
            assertTrue(state.onboarding.isKeyAdded)
            assertEquals(4, state.onboarding.step)
            assertEquals(4, fakeRepository.getOnboardingStep())
            assertEquals(Service.PlusVibe.id, state.onboarding.serviceId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `after the save the catalog loads and the DeepSeek default is selected`() = runTest {
        val viewModel = createViewModel(balance = ProviderBalance(amount = 123.45, currency = "RUB"))
        fakeRepository.modelsFetchedOnValidate = listOf(
            SettingsModel(id = "glm-5.3-flash", subtitle = ""),
            SettingsModel(id = "deepseek-v4.1-flash-1017", subtitle = ""),
            SettingsModel(id = "gemini-3.8-flash", subtitle = ""),
        )
        viewModel.state.test {
            awaitItem()
            viewModel.state.value.actions.onboardingAddKey(Service.PlusVibe.id, "sk-pv-test")
            val state = awaitItem()

            assertTrue(state.onboarding.isKeyAdded)
            assertEquals("deepseek-v4.1-flash-1017", state.onboarding.selectedModelId)
            assertEquals(3, state.onboarding.modelOptions.size)
            assertTrue(state.onboarding.modelOptions.first { it.id == "deepseek-v4.1-flash-1017" }.isSelected)
            // The balance row shows only what the provider reports, formatted like in Settings.
            assertEquals("123.45 ₽", state.onboarding.balance?.let { formatProviderBalance(it) })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `provider switch clears the verdict and everything the previous key loaded`() = runTest {
        val viewModel = createViewModel(balance = ProviderBalance(amount = 50.0, currency = "RUB"))
        fakeRepository.modelsFetchedOnValidate = listOf(
            SettingsModel(id = "deepseek-v4.1-flash-1017", subtitle = ""),
        )
        viewModel.state.test {
            awaitItem()
            viewModel.state.value.actions.onboardingAddKey(Service.PlusVibe.id, "sk-pv-test")
            val added = awaitItem()
            assertTrue(added.onboarding.isKeyAdded)
            assertTrue(added.onboarding.modelOptions.isNotEmpty())

            // Rule 03 (03-key-states §3): a switch to another provider resets the verdict
            // and everything the previous key loaded — catalog, picked model, balance.
            viewModel.state.value.actions.onboardingClearKeyState()
            val reset = awaitItem()
            assertFalse(reset.onboarding.isKeyAdded)
            assertNull(reset.onboarding.error)
            assertFalse(reset.onboarding.isChecking)
            assertTrue(reset.onboarding.modelOptions.isEmpty())
            assertNull(reset.onboarding.selectedModelId)
            assertNull(reset.onboarding.balance)
            assertFalse(reset.onboarding.isLoadingDetails)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a failing check keeps the wizard open with the mapped error`() = runTest {
        fakeRepository.validateConnectionException = RuntimeException("boom")
        val viewModel = createViewModel()
        viewModel.state.test {
            awaitItem()
            viewModel.state.value.actions.onboardingAddKey(Service.PlusVibe.id, "sk-pv-bad")
            val failed = awaitItem()

            assertFalse(failed.onboarding.isKeyAdded)
            assertTrue(failed.onboarding.error != null)
            // The key still landed on the instance — the field keeps it for the next attempt.
            assertEquals("sk-pv-bad", fakeRepository.getInstanceApiKey(Service.PlusVibe.id))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onboardingGoToStep persists the step and carries the picked provider`() = runTest {
        val viewModel = createViewModel()
        viewModel.state.test {
            val initial = awaitItem()
            initial.actions.onboardingGoToStep(2, Service.Polza.id)
            val moved = awaitItem()

            assertEquals(2, moved.onboarding.step)
            assertEquals(Service.Polza.id, moved.onboarding.serviceId)
            assertEquals(2, fakeRepository.getOnboardingStep())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onboardingSelectModel persists the picked model on the instance`() = runTest {
        val viewModel = createViewModel()
        fakeRepository.modelsFetchedOnValidate = listOf(
            SettingsModel(id = "deepseek-v4.1-flash-1017", subtitle = ""),
            SettingsModel(id = "gemini-3.8-flash", subtitle = ""),
        )
        viewModel.state.test {
            awaitItem()
            viewModel.state.value.actions.onboardingAddKey(Service.PlusVibe.id, "sk-pv-test")
            val added = awaitItem()
            assertEquals("deepseek-v4.1-flash-1017", added.onboarding.selectedModelId)

            viewModel.state.value.actions.onboardingSelectModel("gemini-3.8-flash")
            val picked = awaitItem()

            assertEquals("gemini-3.8-flash", picked.onboarding.selectedModelId)
            assertTrue(picked.onboarding.modelOptions.first { it.id == "gemini-3.8-flash" }.isSelected)
            assertFalse(picked.onboarding.modelOptions.first { it.id == "deepseek-v4.1-flash-1017" }.isSelected)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a provider without a balance endpoint shows no balance at all`() = runTest {
        val viewModel = createViewModel(balance = null)
        fakeRepository.modelsFetchedOnValidate = listOf(
            SettingsModel(id = "deepseek-v4.1-flash-1017", subtitle = ""),
        )
        viewModel.state.test {
            awaitItem()
            viewModel.state.value.actions.onboardingAddKey(Service.PlusVibe.id, "sk-pv-test")
            val state = awaitItem()

            assertNull(state.onboarding.balance)
            assertTrue(state.onboarding.modelOptions.isNotEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
