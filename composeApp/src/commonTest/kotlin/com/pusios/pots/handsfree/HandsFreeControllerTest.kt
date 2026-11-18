package com.pusios.pots.handsfree

import com.pusios.pots.ui.tts.PotsTextToSpeech
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Block 8 (owner): on a phone with a dead system recognition service the mode
 * never listened and never switched off. The device logs showed the service
 * accepting sessions without a single callback, and `SpeechRecognizer.cancel()`
 * throwing when the service died. The controller's contract against that:
 *
 * * [HandsFreeController.stop] reaches Idle from ANY status, even when the
 *   recognizer's cancel throws — the mode always switches off;
 * * a session with no callback fails visibly after a timeout instead of
 *   hanging in Listening forever with a closed microphone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HandsFreeControllerTest {

    private val testDispatcher = StandardTestDispatcher()

    private class FakeStt(
        private val throwOnCancel: Boolean = false,
    ) : SpeechToText {
        override val isAvailable: Boolean = true
        var cancelCalls = 0
        var startCalls = 0
        var lastOnResult: ((String) -> Unit)? = null
        var lastOnError: ((SpeechToTextError) -> Unit)? = null

        override fun start(
            onPartial: (String) -> Unit,
            onResult: (String) -> Unit,
            onError: (SpeechToTextError) -> Unit,
        ) {
            startCalls++
            lastOnResult = onResult
            lastOnError = onError
        }

        override fun stop() = Unit

        override fun cancel() {
            cancelCalls++
            if (throwOnCancel) throw IllegalStateException("DeadObjectException: recognition service died")
        }
    }

    private class FakeTts : PotsTextToSpeech {
        override val isAvailable: Boolean = true
        override suspend fun say(text: String, flush: Boolean, interrupt: Boolean) = Unit
        override fun stop() = Unit
        override fun close() = Unit
    }

    private fun controller(
        stt: SpeechToText,
        onSubmit: (String) -> Unit = {},
    ): HandsFreeController = HandsFreeController(
        scope = CoroutineScope(SupervisorJob() + testDispatcher),
        textToSpeech = FakeTts(),
        speechToText = stt,
        onSubmit = onSubmit,
    )

    @Test
    fun stopReachesIdleEvenWhenCancelThrows() = runTest(testDispatcher) {
        val stt = FakeStt(throwOnCancel = true)
        val controller = controller(stt)
        controller.start()
        runCurrent()
        assertEquals(HandsFreeStatus.Listening, controller.status.value)

        // The exact field failure: cancel() on a dead service throws — the
        // mode must still switch off.
        controller.stop()

        assertEquals(HandsFreeStatus.Idle, controller.status.value)
        assertTrue(controller.error.value == null)
    }

    @Test
    fun stopFromEveryStatusReachesIdle() = runTest(testDispatcher) {
        // Listening
        run {
            val stt = FakeStt()
            val controller = controller(stt)
            controller.start()
            runCurrent()
            controller.stop()
            assertEquals(HandsFreeStatus.Idle, controller.status.value)
        }
        // Thinking (transcript submitted, agent working)
        run {
            val stt = FakeStt()
            val submitted = mutableListOf<String>()
            val controller = controller(stt, onSubmit = { submitted += it })
            controller.start()
            runCurrent()
            stt.lastOnResult?.invoke("привет")
            assertEquals(HandsFreeStatus.Thinking, controller.status.value)
            assertEquals(listOf("привет"), submitted)
            controller.stop()
            assertEquals(HandsFreeStatus.Idle, controller.status.value)
        }
        // Error
        run {
            val stt = FakeStt()
            val controller = controller(stt)
            controller.start()
            runCurrent()
            stt.lastOnError?.invoke(SpeechToTextError.Failed)
            assertEquals(HandsFreeStatus.Error, controller.status.value)
            controller.stop()
            assertEquals(HandsFreeStatus.Idle, controller.status.value)
        }
        // Speaking
        run {
            val stt = FakeStt()
            val controller = controller(stt)
            controller.start()
            runCurrent()
            stt.lastOnResult?.invoke("привет")
            controller.onAssistantReply("m1", "ответ")
            assertEquals(HandsFreeStatus.Speaking, controller.status.value)
            controller.stop()
            assertEquals(HandsFreeStatus.Idle, controller.status.value)
        }
    }

    @Test
    fun deadServiceWithoutCallbacksTimesOutOfListening() = runTest(testDispatcher) {
        val stt = FakeStt() // start() records callbacks but nothing ever fires
        val controller = controller(stt)
        controller.start()
        runCurrent()
        assertEquals(HandsFreeStatus.Listening, controller.status.value)

        advanceTimeBy(HandsFreeController.LISTEN_TIMEOUT_MS + 100)
        runCurrent()

        assertEquals(HandsFreeStatus.Error, controller.status.value, "a hung session must fail visibly")
        assertEquals(SpeechToTextError.Failed, controller.error.value)
    }

    @Test
    fun resultBeforeTimeoutCancelsWatchdog() = runTest(testDispatcher) {
        val stt = FakeStt()
        val controller = controller(stt)
        controller.start()
        runCurrent()
        stt.lastOnResult?.invoke("привет")
        assertEquals(HandsFreeStatus.Thinking, controller.status.value)

        advanceTimeBy(HandsFreeController.LISTEN_TIMEOUT_MS + 100)
        runCurrent()

        assertEquals(HandsFreeStatus.Thinking, controller.status.value, "the watchdog must not override a live session")
    }

    @Test
    fun noMatchIsAnnouncedAndThenListensAgain() = runTest(testDispatcher) {
        val stt = FakeStt()
        val controller = controller(stt)
        controller.start()
        runCurrent()
        assertEquals(1, stt.startCalls)

        stt.lastOnError?.invoke(SpeechToTextError.NoMatch)

        // «Не расслышал, повторите» has a status of its own: the chat pauses the
        // loop on Error, and a missed phrase must not pause anything.
        assertEquals(HandsFreeStatus.NoMatch, controller.status.value)
        assertEquals(SpeechToTextError.NoMatch, controller.error.value)

        advanceTimeBy(HandsFreeController.NO_MATCH_NOTICE_MS + 100)
        runCurrent()

        assertEquals(HandsFreeStatus.Listening, controller.status.value, "the loop reopens the microphone by itself")
        assertEquals(2, stt.startCalls, "exactly one new session after the notice")
    }

    @Test
    fun aTapDuringTheNoMatchNoticeDoesNotOpenTwoSessions() = runTest(testDispatcher) {
        val stt = FakeStt()
        val controller = controller(stt)
        controller.start()
        runCurrent()
        stt.lastOnError?.invoke(SpeechToTextError.NoMatch)
        assertEquals(HandsFreeStatus.NoMatch, controller.status.value)

        // The chat's button during the notice: skip the wait and listen now.
        controller.resume()
        runCurrent()
        assertEquals(HandsFreeStatus.Listening, controller.status.value)
        assertEquals(2, stt.startCalls)

        advanceTimeBy(HandsFreeController.NO_MATCH_NOTICE_MS + 100)
        runCurrent()
        assertEquals(2, stt.startCalls, "the pending notice must not add a session on top of the tap's")
    }

    @Test
    fun fullCycleReturnsToListeningAfterReply() = runTest(testDispatcher) {
        val stt = FakeStt()
        val submitted = mutableListOf<String>()
        val controller = controller(stt, onSubmit = { submitted += it })

        controller.start()
        runCurrent()
        assertEquals(HandsFreeStatus.Listening, controller.status.value)

        stt.lastOnResult?.invoke("как дела")
        assertEquals(HandsFreeStatus.Thinking, controller.status.value)
        assertEquals(listOf("как дела"), submitted)

        controller.onAssistantReply("m1", "Хорошо!")
        runCurrent()

        assertEquals(HandsFreeStatus.Listening, controller.status.value, "after the reply the loop listens again")

        // The reopened session is hung as well (the fake never calls back) —
        // it too must fail visibly instead of pretending to listen.
        advanceTimeBy(HandsFreeController.LISTEN_TIMEOUT_MS + 100)
        runCurrent()
        assertEquals(HandsFreeStatus.Error, controller.status.value)
    }
}
