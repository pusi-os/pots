package com.pusios.pots.ui.chat

import com.pusios.pots.data.ProviderBalance
import com.pusios.pots.data.Service
import com.pusios.pots.data.TaskScheduler
import com.pusios.pots.network.ProviderBalanceClient
import com.pusios.pots.testutil.FakeDataRepository
import com.pusios.pots.ui.settings.SettingsModel
import com.pusios.pots.ui.settings.formatProviderBalance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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

    private class StubBalanceClient(private val balance: ProviderBalance?) : ProviderBalanceClient() {
        override suspend fun fetch(service: Service, apiKey: String): ProviderBalance? = balance
    }

    private fun createViewModel(balance: ProviderBalance? = null): ChatViewModel {
        val noOpScheduler = TaskScheduler(fakeRepository, enabled = false)
        return ChatViewModel(fakeRepository, noOpScheduler, unconfinedDispatcher, balanceClient = StubBalanceClient(balance))
    }

    /** Seeds the fake instance's catalog the way validateConnection populates the real one. */
    private fun seedCatalog(instanceId: String, service: Service, ids: List<String>) {
        val flow = fakeRepository.getInstanceModels(instanceId, service) as MutableStateFlow
        flow.value = ids.map { SettingsModel(id = it, subtitle = "") }
    }

    @Test
    fun `pasted key saves immediately and marks the wizard key-added`() = runTest {
        val viewModel = createViewModel()
        assertTrue(viewModel.state.value.onboarding.phase == OnboardingPhase.Active)

        viewModel.state.value.actions.onboardingAddKey(Service.PlusVibe.id, "sk-pv-test")

        // Same mechanism as Settings → Services: key on the instance, connection validated.
        assertEquals("sk-pv-test", fakeRepository.getInstanceApiKey(Service.PlusVibe.id))
        val onboarding = viewModel.state.value.onboarding
        assertTrue(onboarding.isKeyAdded)
        assertEquals(4, onboarding.step)
        assertEquals(4, fakeRepository.getOnboardingStep())
        assertEquals(Service.PlusVibe.id, onboarding.serviceId)
    }

    @Test
    fun `after the save the catalog loads and the DeepSeek default is selected`() = runTest {
        val instance = fakeRepository.addConfiguredService(Service.PlusVibe.id)
        seedCatalog(
            instance.instanceId,
            Service.PlusVibe,
            listOf("glm-5.3-flash", "deepseek-v4.1-flash-1017", "gemini-3.8-flash"),
        )
        val viewModel = createViewModel(balance = ProviderBalance(amount = 123.45, currency = "RUB"))

        viewModel.state.value.actions.onboardingAddKey(Service.PlusVibe.id, "sk-pv-test")

        val onboarding = viewModel.state.value.onboarding
        assertTrue(onboarding.isKeyAdded)
        assertEquals("deepseek-v4.1-flash-1017", onboarding.selectedModelId)
        assertEquals(3, onboarding.modelOptions.size)
        assertTrue(onboarding.modelOptions.first { it.id == "deepseek-v4.1-flash-1017" }.isSelected)
        // The balance row shows only what the provider reports, formatted like in Settings.
        assertEquals("123.45 ₽", onboarding.balance?.let { formatProviderBalance(it) })
    }

    @Test
    fun `provider switch clears the verdict and everything the previous key loaded`() = runTest {
        val instance = fakeRepository.addConfiguredService(Service.PlusVibe.id)
        seedCatalog(
            instance.instanceId,
            Service.PlusVibe,
            listOf("deepseek-v4.1-flash-1017", "glm-5.3-flash"),
        )
        val viewModel = createViewModel(balance = ProviderBalance(amount = 50.0, currency = "RUB"))
        viewModel.state.value.actions.onboardingAddKey(Service.PlusVibe.id, "sk-pv-test")

        viewModel.state.value.actions.onboardingClearKeyState()

        val onboarding = viewModel.state.value.onboarding
        assertFalse(onboarding.isKeyAdded)
        assertNull(onboarding.error)
        assertFalse(onboarding.isChecking)
        assertTrue(onboarding.modelOptions.isEmpty())
        assertNull(onboarding.selectedModelId)
        assertNull(onboarding.balance)
        assertFalse(onboarding.isLoadingDetails)
    }

    @Test
    fun `a failing check keeps the wizard open with the mapped error`() = runTest {
        fakeRepository.validateConnectionException = RuntimeException("boom")
        val viewModel = createViewModel()

        viewModel.state.value.actions.onboardingAddKey(Service.PlusVibe.id, "sk-pv-bad")

        val onboarding = viewModel.state.value.onboarding
        assertFalse(onboarding.isKeyAdded)
        assertTrue(onboarding.error != null)
        // The key still landed on the instance — the field keeps it for the next attempt.
        assertEquals("sk-pv-bad", fakeRepository.getInstanceApiKey(Service.PlusVibe.id))
    }

    @Test
    fun `onboardingGoToStep persists the step and carries the picked provider`() = runTest {
        val viewModel = createViewModel()

        viewModel.state.value.actions.onboardingGoToStep(2, Service.Polza.id)

        assertEquals(2, viewModel.state.value.onboarding.step)
        assertEquals(Service.Polza.id, viewModel.state.value.onboarding.serviceId)
        assertEquals(2, fakeRepository.getOnboardingStep())
    }

    @Test
    fun `onboardingSelectModel persists the picked model on the instance`() = runTest {
        val instance = fakeRepository.addConfiguredService(Service.PlusVibe.id)
        seedCatalog(
            instance.instanceId,
            Service.PlusVibe,
            listOf("deepseek-v4.1-flash-1017", "gemini-3.8-flash"),
        )
        val viewModel = createViewModel()
        viewModel.state.value.actions.onboardingAddKey(Service.PlusVibe.id, "sk-pv-test")
        assertEquals("deepseek-v4.1-flash-1017", viewModel.state.value.onboarding.selectedModelId)

        viewModel.state.value.actions.onboardingSelectModel("gemini-3.8-flash")

        val onboarding = viewModel.state.value.onboarding
        assertEquals("gemini-3.8-flash", onboarding.selectedModelId)
        assertTrue(onboarding.modelOptions.first { it.id == "gemini-3.8-flash" }.isSelected)
        assertFalse(onboarding.modelOptions.first { it.id == "deepseek-v4.1-flash-1017" }.isSelected)
    }

    @Test
    fun `a provider without a balance endpoint shows no balance at all`() = runTest {
        val instance = fakeRepository.addConfiguredService(Service.PlusVibe.id)
        seedCatalog(instance.instanceId, Service.PlusVibe, listOf("deepseek-v4.1-flash-1017"))
        val viewModel = createViewModel(balance = null)

        viewModel.state.value.actions.onboardingAddKey(Service.PlusVibe.id, "sk-pv-test")

        assertNull(viewModel.state.value.onboarding.balance)
        assertTrue(viewModel.state.value.onboarding.modelOptions.isNotEmpty())
    }
}
