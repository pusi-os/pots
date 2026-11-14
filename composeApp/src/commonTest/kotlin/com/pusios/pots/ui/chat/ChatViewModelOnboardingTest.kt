package com.pusios.pots.ui.chat

import androidx.lifecycle.ViewModelStore
import app.cash.turbine.test
import com.pusios.pots.data.Service
import com.pusios.pots.data.TaskScheduler
import com.pusios.pots.network.OpenAICompatibleInvalidApiKeyException
import com.pusios.pots.network.OpenAICompatibleQuotaExhaustedException
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The first-launch wizard's state machine: trigger, steps, skip, interruption,
 * resume, and the add-provider result (spec §2, tests required by the task).
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

    // ---- steps ----

    @Test
    fun `opening the site advances the step and persists it`() = runTest {
        val viewModel = createViewModel()
        viewModel.state.test {
            awaitItem()
            viewModel.state.value.actions.onboardingOpenSite()
            assertEquals(2, awaitItem().onboarding.step)
            viewModel.state.value.actions.onboardingOpenSite()
            assertEquals(3, awaitItem().onboarding.step)
            assertEquals(3, fakeRepository.getOnboardingStep())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `saving a blank key rejects inline and adds nothing`() = runTest {
        val viewModel = createViewModel()
        viewModel.state.test {
            awaitItem()
            viewModel.state.value.actions.onboardingOpenSite()
            awaitItem()
            viewModel.state.value.actions.onboardingOpenSite()
            awaitItem()
            viewModel.state.value.actions.onboardingSaveKey("   ")
            val state = awaitItem()
            assertEquals(3, state.onboarding.step)
            assertEquals(Res.string.onboarding_error_empty_key, state.onboarding.error)
            assertTrue(fakeRepository.getConfiguredServiceInstances().isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `saving a key adds the PlusVibe instance and moves to step four`() = runTest {
        val viewModel = createViewModel()
        viewModel.state.test {
            awaitItem()
            viewModel.state.value.actions.onboardingOpenSite()
            awaitItem()
            viewModel.state.value.actions.onboardingOpenSite()
            awaitItem()
            viewModel.state.value.actions.onboardingSaveKey("  test-key-123  ")
            val state = awaitItem()
            assertEquals(4, state.onboarding.step)
            val instance = fakeRepository.getConfiguredServiceInstances().single()
            assertEquals(Service.PlusVibe.id, instance.serviceId)
            assertEquals("test-key-123", fakeRepository.getInstanceApiKey(instance.instanceId))
            assertEquals(4, fakeRepository.getOnboardingStep())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `saving a key reuses an existing PlusVibe instance`() = runTest {
        // An interrupted-after-step-3 restart: the instance already exists on device.
        val existing = fakeRepository.addConfiguredService(Service.PlusVibe.id)
        fakeRepository.setOnboardingFinishedStep(true)
        fakeRepository.setOnboardingStep(4)
        val viewModel = createViewModel()
        viewModel.state.test {
            val initial = awaitItem()
            assertEquals(OnboardingPhase.Aborted, initial.onboarding.phase)
            initial.actions.onboardingResume()
            awaitItem()
            initial.actions.onboardingSaveKey("second-key")
            val instances = fakeRepository.getConfiguredServiceInstances()
            assertEquals(1, instances.size)
            assertEquals(existing.instanceId, instances.single().instanceId)
            assertEquals("second-key", fakeRepository.getInstanceApiKey(existing.instanceId))
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---- check / finish ----

    @Test
    fun `successful check finishes onboarding and persists done`() = runTest {
        val viewModel = createViewModel()
        viewModel.state.test {
            awaitItem()
            viewModel.state.value.actions.onboardingOpenSite()
            awaitItem()
            viewModel.state.value.actions.onboardingOpenSite()
            awaitItem()
            viewModel.state.value.actions.onboardingSaveKey("valid-key")
            awaitItem()
            viewModel.state.value.actions.onboardingCheck()
            // The unconfined test dispatcher resolves the check synchronously, so the
            // intermediate "checking" emission may be conflated away by the StateFlow.
            val finished = awaitItem()
            assertFalse(finished.onboarding.isChecking)
            assertEquals(OnboardingPhase.Finished, finished.onboarding.phase)
            assertTrue(fakeRepository.isOnboardingDone())
            assertFalse(fakeRepository.isOnboardingFinishedStep())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `invalid key maps to the invalid-key error and keeps step four`() = runTest {
        fakeRepository.validateConnectionException = OpenAICompatibleInvalidApiKeyException()
        val viewModel = createViewModel()
        viewModel.state.test {
            awaitItem()
            viewModel.state.value.actions.onboardingOpenSite()
            awaitItem()
            viewModel.state.value.actions.onboardingOpenSite()
            awaitItem()
            viewModel.state.value.actions.onboardingSaveKey("bad-key")
            awaitItem()
            viewModel.state.value.actions.onboardingCheck()
            val failed = awaitItem()
            assertEquals(OnboardingPhase.Active, failed.onboarding.phase)
            assertEquals(4, failed.onboarding.step)
            assertFalse(failed.onboarding.isChecking)
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
            viewModel.state.value.actions.onboardingSaveKey("valid-key")
            awaitItem()
            viewModel.state.value.actions.onboardingCheck()
            val failed = awaitItem()
            assertEquals(Res.string.onboarding_error_no_balance, failed.onboarding.error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `other failures map to the network error`() = runTest {
        fakeRepository.validateConnectionException = IllegalStateException("boom")
        val viewModel = createViewModel()
        viewModel.state.test {
            awaitItem()
            viewModel.state.value.actions.onboardingSaveKey("valid-key")
            awaitItem()
            viewModel.state.value.actions.onboardingCheck()
            val failed = awaitItem()
            assertEquals(Res.string.onboarding_error_network, failed.onboarding.error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `check without a saved instance is a no-op`() = runTest {
        val viewModel = createViewModel()
        viewModel.state.test {
            val initial = awaitItem()
            viewModel.state.value.actions.onboardingCheck()
            expectNoEvents()
            assertEquals(OnboardingPhase.Active, initial.onboarding.phase)
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
            viewModel.state.value.actions.onboardingOpenSite()
            awaitItem()
            viewModel.state.value.actions.onboardingSkip()
            awaitItem()
            viewModel.state.value.actions.onboardingResume()
            val resumed = awaitItem()
            assertEquals(OnboardingPhase.Active, resumed.onboarding.phase)
            assertEquals(2, resumed.onboarding.step)
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
}
