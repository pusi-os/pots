package com.pusios.pots.ui.chat

import app.cash.turbine.test
import com.pusios.pots.data.TaskScheduler
import com.pusios.pots.testutil.FakeDataRepository
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ACTION_ASSIST must open a fresh chat and turn the voice loop on. Restoring
 * the previous conversation would hide the assist session; leaving speech
 * off would make the gesture silent.
 *
 * Production change that would fail these tests:
 * - restore the last conversation instead of startNewChat
 * - consume the pulse without starting hands-free
 * - leave isSpeechOutputEnabled false after assist
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelAssistTest {

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

    @Test
    fun `an assist pulse starts a fresh chat and asks for hands-free`() = runTest {
        fakeRepository.chatHistory.value = listOf(
            History(role = History.Role.USER, content = "old turn"),
            History(role = History.Role.ASSISTANT, content = "old reply"),
        )
        val viewModel = createViewModel()

        viewModel.state.test {
            var withHistory: ChatUiState
            do {
                withHistory = awaitItem()
            } while (withHistory.history.isEmpty())
            assertTrue(withHistory.history.isNotEmpty())
            assertFalse(withHistory.startHandsFreeRequested)
            assertFalse(withHistory.isSpeechOutputEnabled)

            fakeRepository.requestOpenAssist()
            testDispatcher.scheduler.advanceUntilIdle()

            var after: ChatUiState
            do {
                after = awaitItem()
            } while (after.history.isNotEmpty() || !after.startHandsFreeRequested)

            assertTrue(after.history.isEmpty())
            assertNull(after.error)
            assertFalse(after.isLoading)
            assertTrue(after.startHandsFreeRequested)
            assertTrue(after.isSpeechOutputEnabled)
            assertFalse(fakeRepository.openAssistRequested.value)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `consuming the hands-free request does not turn the loop off`() = runTest {
        val viewModel = createViewModel()
        fakeRepository.requestOpenAssist()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.state.test {
            var requested: ChatUiState
            do {
                requested = awaitItem()
            } while (!requested.startHandsFreeRequested)

            requested.actions.consumeStartHandsFree()
            testDispatcher.scheduler.advanceUntilIdle()

            var consumed: ChatUiState
            do {
                consumed = awaitItem()
            } while (consumed.startHandsFreeRequested)

            assertFalse(consumed.startHandsFreeRequested)
            assertTrue(consumed.isSpeechOutputEnabled)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
