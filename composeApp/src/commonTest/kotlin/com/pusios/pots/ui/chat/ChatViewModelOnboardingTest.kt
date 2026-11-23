package com.pusios.pots.ui.chat

import androidx.lifecycle.ViewModelStore
import app.cash.turbine.test
import com.pusios.pots.data.Service
import com.pusios.pots.data.TaskScheduler
import com.pusios.pots.network.NoConfiguredServiceException
import com.pusios.pots.network.OpenAICompatibleInvalidApiKeyException
import com.pusios.pots.network.OpenAICompatibleQuotaExhaustedException
import com.pusios.pots.network.UiError
import com.pusios.pots.testutil.FakeDataRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.onboarding_error_empty_key
import pots.composeapp.generated.resources.onboarding_error_invalid_key
import pots.composeapp.generated.resources.onboarding_error_network
import pots.composeapp.generated.resources.onboarding_error_no_balance
import pots.composeapp.generated.resources.onboarding_skipped
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The first-launch wizard's state machine: trigger, provider add, skip, interruption,
 * resume, and the add-service result (docs/design/onboarding, tests required by the task).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelOnboardingTest {

    private val testDispatcher = StandardTestDispatcher()
    private val unconfinedDispatcher = UnconfinedTestDispatcher()
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

    private fun createViewModel(): ChatViewModel {
        val noOpScheduler = TaskScheduler(fakeRepository, enabled = false)
        return ChatViewModel(fakeRepository, noOpScheduler, unconfinedDispatcher)
    }

    // ---- trigger ----

    @Test
    fun `wizard is active on first launch without configured services`() = runTest {
        val viewModel = createViewModel()
        viewModel.state.test {
            val state = awaitItem()
            assertEquals(OnboardingPhase.Active, state.onboarding.phase)
            assertEquals(1, state.onboarding.step)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `wizard is hidden when a service is already configured`() = runTest {
        fakeRepository.setConfiguredServices(Service.Polza)
        val viewModel = createViewModel()
        viewModel.state.test {
            val state = awaitItem()
            assertEquals(OnboardingPhase.Hidden, state.onboarding.phase)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `wizard is hidden when onboarding already finished with success`() = runTest {
        fakeRepository.setOnboardingDone(true)
        fakeRepository.setConfiguredServices(Service.PlusVibe)
        val viewModel = createViewModel()
        viewModel.state.test {
            val state = awaitItem()
            assertEquals(OnboardingPhase.Hidden, state.onboarding.phase)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---- add key (provider picker's «Проверить и добавить») ----

    @Test
    fun `a blank key rejects inline and adds nothing`() = runTest {
        val viewModel = createViewModel()
        viewModel.state.test {
            awaitItem()
            viewModel.state.value.actions.onboardingAddKey(Service.Polza.id, "   ")
            val state = awaitItem()
            assertEquals(Res.string.onboarding_error_empty_key, state.onboarding.error)
            assertTrue(fakeRepository.getConfiguredServiceInstances().isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `adding a key stores it on a new instance of the picked service and shows the added state`() = runTest {
        val viewModel = createViewModel()
        viewModel.state.test {
            awaitItem()
            // The unconfined test dispatcher resolves the add synchronously, so the
            // intermediate "checking" emission may be conflated away by the StateFlow.
            viewModel.state.value.actions.onboardingAddKey(Service.Polza.id, "  polza-key  ")
            val state = awaitItem()
            assertTrue(state.onboarding.isKeyAdded)
            assertEquals(OnboardingPhase.Active, state.onboarding.phase)
            assertEquals(4, state.onboarding.step)
            val instance = fakeRepository.getConfiguredServiceInstances().single()
            assertEquals(Service.Polza.id, instance.serviceId)
            assertEquals("polza-key", fakeRepository.getInstanceApiKey(instance.instanceId))
            assertEquals(4, fakeRepository.getOnboardingStep())
            // The wizard stays open until «Готово»; persistence keeps the resume point.
            assertFalse(fakeRepository.isOnboardingDone())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `adding a key reuses an existing instance of the same service and clears its models`() = runTest {
        // An interrupted-after-add restart: the instance already exists on device.
        val existing = fakeRepository.addConfiguredService(Service.Polza.id)
        fakeRepository.setOnboardingFinishedStep(true)
        fakeRepository.setOnboardingStep(4)
        val viewModel = createViewModel()
        viewModel.state.test {
            val initial = awaitItem()
            assertEquals(OnboardingPhase.Aborted, initial.onboarding.phase)
            initial.actions.onboardingResume()
            awaitItem()
            initial.actions.onboardingAddKey(Service.Polza.id, "second-key")
            val state = awaitItem()
            assertTrue(state.onboarding.isKeyAdded)
            val instances = fakeRepository.getConfiguredServiceInstances()
            assertEquals(1, instances.size)
            assertEquals(existing.instanceId, instances.single().instanceId)
            assertEquals("second-key", fakeRepository.getInstanceApiKey(existing.instanceId))
            assertEquals(1, fakeRepository.clearInstanceModelsCalls)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `confirm after an add finishes onboarding and persists done`() = runTest {
        val viewModel = createViewModel()
        viewModel.state.test {
            awaitItem()
            viewModel.state.value.actions.onboardingAddKey(Service.PlusVibe.id, "valid-key")
            awaitItem()
            viewModel.state.value.actions.onboardingConfirmKey()
            val finished = awaitItem()
            assertEquals(OnboardingPhase.Finished, finished.onboarding.phase)
            assertTrue(fakeRepository.isOnboardingDone())
            assertFalse(fakeRepository.isOnboardingFinishedStep())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `confirm without a prior add is a no-op`() = runTest {
        val viewModel = createViewModel()
        viewModel.state.test {
            val initial = awaitItem()
            viewModel.state.value.actions.onboardingConfirmKey()
            expectNoEvents()
            assertEquals(OnboardingPhase.Active, initial.onboarding.phase)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---- mapped errors ----

    @Test
    fun `an invalid key maps to the invalid-key error and keeps the wizard open`() = runTest {
        fakeRepository.validateConnectionException = OpenAICompatibleInvalidApiKeyException()
        val viewModel = createViewModel()
        viewModel.state.test {
            awaitItem()
            viewModel.state.value.actions.onboardingAddKey(Service.PlusVibe.id, "bad-key")
            val failed = awaitItem()
            assertEquals(OnboardingPhase.Active, failed.onboarding.phase)
            assertFalse(failed.onboarding.isChecking)
            assertFalse(failed.onboarding.isKeyAdded)
            assertEquals(Res.string.onboarding_error_invalid_key, failed.onboarding.error)
            assertFalse(fakeRepository.isOnboardingDone())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `exhausted quota maps to the balance error`() = runTest {
        fakeRepository.validateConnectionException = OpenAICompatibleQuotaExhaustedException()
        val viewModel = createViewModel()
        viewModel.state.test {
            awaitItem()
            viewModel.state.value.actions.onboardingAddKey(Service.PlusVibe.id, "valid-key")
            val failed = awaitItem()
            assertEquals(Res.string.onboarding_error_no_balance, failed.onboarding.error)
            assertFalse(failed.onboarding.isKeyAdded)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `other failures map to the network error`() = runTest {
        fakeRepository.validateConnectionException = IllegalStateException("boom")
        val viewModel = createViewModel()
        viewModel.state.test {
            awaitItem()
            viewModel.state.value.actions.onboardingAddKey(Service.PlusVibe.id, "valid-key")
            val failed = awaitItem()
            assertEquals(Res.string.onboarding_error_network, failed.onboarding.error)
            assertFalse(failed.onboarding.isKeyAdded)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---- skip / abort / resume ----

    @Test
    fun `skip parks the wizard and persists both flags`() = runTest {
        val viewModel = createViewModel()
        viewModel.state.test {
            awaitItem()
            viewModel.state.value.actions.onboardingSkip()
            val skipped = awaitItem()
            assertEquals(OnboardingPhase.Skipped, skipped.onboarding.phase)
            assertTrue(fakeRepository.isOnboardingDone())
            assertTrue(fakeRepository.isOnboardingFinishedStep())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `resume from the skipped card reopens the wizard and resets flags`() = runTest {
        val viewModel = createViewModel()
        viewModel.state.test {
            awaitItem()
            viewModel.state.value.actions.onboardingSkip()
            awaitItem()
            viewModel.state.value.actions.onboardingResume()
            val resumed = awaitItem()
            assertEquals(OnboardingPhase.Active, resumed.onboarding.phase)
            assertFalse(fakeRepository.isOnboardingDone())
            assertFalse(fakeRepository.isOnboardingFinishedStep())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `dismiss hides the final card for the session`() = runTest {
        val viewModel = createViewModel()
        viewModel.state.test {
            awaitItem()
            viewModel.state.value.actions.onboardingSkip()
            awaitItem()
            viewModel.state.value.actions.onboardingDismiss()
            val dismissed = awaitItem()
            assertEquals(OnboardingPhase.Hidden, dismissed.onboarding.phase)
            // Persistence is untouched — the next launch still shows the final card.
            assertTrue(fakeRepository.isOnboardingFinishedStep())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `aborted wizard is restored from the finished-step flag at the saved step`() = runTest {
        fakeRepository.setOnboardingStep(2)
        fakeRepository.setOnboardingFinishedStep(true)
        val viewModel = createViewModel()
        viewModel.state.test {
            val state = awaitItem()
            assertEquals(OnboardingPhase.Aborted, state.onboarding.phase)
            assertEquals(2, state.onboarding.step)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `leaving the chat while active marks the wizard interrupted`() = runTest {
        val viewModel = createViewModel()
        assertFalse(fakeRepository.isOnboardingFinishedStep())
        // The real lifecycle exit: ViewModelStore.clear() invokes onCleared().
        val store = ViewModelStore()
        store.put("chat", viewModel)
        store.clear()
        assertTrue(fakeRepository.isOnboardingFinishedStep())
        // A fresh ViewModel now shows the interrupted final card instead of step 1.
        val restored = ChatViewModel(fakeRepository, TaskScheduler(fakeRepository, enabled = false), unconfinedDispatcher)
        restored.state.test {
            val state = awaitItem()
            assertEquals(OnboardingPhase.Aborted, state.onboarding.phase)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---- local branch ----

    @Test
    fun `local branch reveals limits first and confirms on the second press`() = runTest {
        val viewModel = createViewModel()
        viewModel.state.test {
            awaitItem()
            viewModel.state.value.actions.onboardingChooseLocal()
            val revealed = awaitItem()
            assertTrue(revealed.onboarding.showLocalLimits)
            assertEquals(OnboardingPhase.Active, revealed.onboarding.phase)
            assertTrue(fakeRepository.getConfiguredServiceInstances().isEmpty())
            viewModel.state.value.actions.onboardingChooseLocal()
            val finished = awaitItem()
            assertEquals(OnboardingPhase.Finished, finished.onboarding.phase)
            val instances = fakeRepository.getConfiguredServiceInstances()
            assertEquals(Service.LiteRT.id, instances.single().serviceId)
            assertTrue(fakeRepository.isOnboardingDone())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---- second chance after skip (spec §5.4) ----

    @Test
    fun `first failed ask after skip opens provider panel with honest error`() = runTest {
        val viewModel = createViewModel()
        viewModel.state.test {
            val initial = awaitItem()
            assertEquals(OnboardingPhase.Active, initial.onboarding.phase)
            initial.actions.onboardingSkip()
            awaitItem()

            // With no configured services the real repository's fallback chain is
            // empty and it throws NoConfiguredServiceException before any network call.
            fakeRepository.askException = NoConfiguredServiceException()
            viewModel.state.value.actions.ask("Привет")
            testDispatcher.scheduler.advanceUntilIdle()

            var errorState: ChatUiState
            do {
                errorState = awaitItem()
            } while (errorState.error == null)
            assertTrue(errorState.showFreeProviderSuggestions)
            assertEquals(UiError.Resource(Res.string.onboarding_skipped), errorState.error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---- settings → «Онбординг» ----

    @Test
    fun `restarting from settings reopens a finished wizard at step one`() = runTest {
        // A user who completed the wizard: done + finished-step are set and the saved step is 4.
        fakeRepository.setOnboardingDone(true)
        fakeRepository.setOnboardingFinishedStep(true)
        fakeRepository.setOnboardingStep(4)
        val viewModel = createViewModel()
        viewModel.state.test {
            awaitItem()
            viewModel.restartOnboarding()
            testDispatcher.scheduler.advanceUntilIdle()
            val restarted = viewModel.state.value
            assertEquals(OnboardingPhase.Active, restarted.onboarding.phase)
            assertEquals(1, restarted.onboarding.step)
            assertFalse(restarted.onboarding.showLocalLimits)
            // Persistence is rewound too, so a relaunch does not jump back to step 4.
            assertEquals(1, fakeRepository.getOnboardingStep())
            assertFalse(fakeRepository.isOnboardingDone())
            assertFalse(fakeRepository.isOnboardingFinishedStep())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `restarting after a skip reopens the wizard instead of the final card`() = runTest {
        // «Пропустить» leaves done + finished-step set, which renders the resume card on restore.
        fakeRepository.setOnboardingDone(true)
        fakeRepository.setOnboardingFinishedStep(true)
        val viewModel = createViewModel()
        viewModel.state.test {
            awaitItem()
            viewModel.restartOnboarding()
            testDispatcher.scheduler.advanceUntilIdle()
            val restarted = viewModel.state.value
            assertEquals(OnboardingPhase.Active, restarted.onboarding.phase)
            assertEquals(1, restarted.onboarding.step)
            cancelAndIgnoreRemainingEvents()
        }
        // A fresh ViewModel (next launch) starts the wizard rather than the skipped card.
        val relaunched = createViewModel()
        relaunched.state.test {
            assertEquals(OnboardingPhase.Active, awaitItem().onboarding.phase)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
