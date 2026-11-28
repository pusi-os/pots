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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The hands-free loop's contract, in two blocks of history.
 *
 * Block 8 (owner): on a phone with a dead system recognition service the mode
 * never listened and never switched off. The device logs showed the service
 * accepting sessions without a single callback, and `SpeechRecognizer.cancel()`
 * throwing when the service died.
 *
 * 3.21.4 (owner): a phrase longer than fifteen seconds was cut in the middle.
 * The watchdog measured the length of the session, not inactivity, and a
 * cancelled session reports neither a result nor an error — so what was said
 * simply vanished. The tests below pin both halves of the fix: the watchdog is
 * re-armed by partials and by microphone levels, and no exit path drops the
 * text that was already recognised.
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
        var lastListener: SpeechToTextListener? = null

        override fun start(listener: SpeechToTextListener) {
            startCalls++
            lastListener = listener
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
            stt.lastListener?.onResult("привет")
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
            stt.lastListener?.onError(SpeechToTextError.Failed)
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
            stt.lastListener?.onResult("привет")
            controller.onAssistantReply("m1", "ответ")
            assertEquals(HandsFreeStatus.Speaking, controller.status.value)
            controller.stop()
            assertEquals(HandsFreeStatus.Idle, controller.status.value)
        }
    }

    @Test
    fun deadServiceWithoutCallbacksTimesOutOfListening() = runTest(testDispatcher) {
        val stt = FakeStt() // start() records the listener but nothing ever fires
        val controller = controller(stt)
        controller.start()
        runCurrent()
        assertEquals(HandsFreeStatus.Listening, controller.status.value)

        advanceTimeBy(HandsFreeController.LISTEN_TIMEOUT_MS + 100)
        runCurrent()

        assertEquals(HandsFreeStatus.Error, controller.status.value, "a hung session must fail visibly")
        assertEquals(SpeechToTextError.Timeout, controller.error.value, "«сервис завис» is its own reason")
        assertEquals(RecordingPhase.Error, controller.recording.value?.phase)
    }

    @Test
    fun resultBeforeTimeoutCancelsWatchdog() = runTest(testDispatcher) {
        val stt = FakeStt()
        val controller = controller(stt)
        controller.start()
        runCurrent()
        stt.lastListener?.onResult("привет")
        assertEquals(HandsFreeStatus.Thinking, controller.status.value)

        advanceTimeBy(HandsFreeController.LISTEN_TIMEOUT_MS + 100)
        runCurrent()

        assertEquals(HandsFreeStatus.Thinking, controller.status.value, "the watchdog must not override a live session")
    }

    @Test
    fun partialsKeepALongSessionAlive() = runTest(testDispatcher) {
        val stt = FakeStt()
        val controller = controller(stt)
        controller.start()
        runCurrent()

        // Forty seconds of speech, reported the way a real engine reports it: a
        // new partial every five seconds. Under the old fixed 15 s timeout the
        // session was cancelled after the third one and the phrase was lost.
        for (second in 1..8) {
            advanceTimeBy(5_000)
            stt.lastListener?.onPartial("слово $second")
        }
        runCurrent()

        assertEquals(HandsFreeStatus.Listening, controller.status.value, "a talking session is never timed out")
        assertNull(controller.error.value)
        assertEquals("слово 8", controller.transcript.value)
    }

    @Test
    fun microphoneLevelsAlsoKeepTheSessionAlive() = runTest(testDispatcher) {
        val stt = FakeStt()
        val controller = controller(stt)
        controller.start()
        runCurrent()

        // The system recognizer may deliver levels without partials (its partial
        // results are implementation-defined). Forty seconds of loud audio is
        // still a session in which the user is talking.
        for (tick in 1..40) {
            advanceTimeBy(1_000)
            stt.lastListener?.onLevel(0.8f)
        }
        runCurrent()

        assertEquals(HandsFreeStatus.Listening, controller.status.value)
        assertEquals(RecordingPhase.SpeechDetected, controller.recording.value?.phase)
        assertEquals(0.8f, controller.recording.value?.level)
    }

    @Test
    fun aTimeoutKeepsWhatWasAlreadyRecognised() = runTest(testDispatcher) {
        val stt = FakeStt()
        val submitted = mutableListOf<String>()
        val controller = controller(stt, onSubmit = { submitted += it })
        controller.start()
        runCurrent()
        stt.lastListener?.onPartial("привет как дела")

        // The partial re-arms the watchdog: the session was alive when it came in.
        advanceTimeBy(HandsFreeController.LISTEN_TIMEOUT_MS)
        runCurrent()
        assertEquals(
            HandsFreeStatus.Listening,
            controller.status.value,
            "a partial resets the inactivity clock",
        )

        // …and then the engine goes quiet for the full timeout.
        advanceTimeBy(HandsFreeController.WATCHDOG_TICK_MS * 2)
        runCurrent()

        assertEquals(HandsFreeStatus.Error, controller.status.value)
        assertEquals("привет как дела", controller.transcript.value, "the recognised text must not be dropped")
        assertTrue(submitted.isEmpty(), "a failed session does not send anything by itself")
        assertEquals(RecordingPhase.Error, controller.recording.value?.phase)
    }

    @Test
    fun anErrorKeepsTheTranscriptAndSaysWhy() = runTest(testDispatcher) {
        val stt = FakeStt()
        val controller = controller(stt)
        controller.start()
        runCurrent()
        stt.lastListener?.onPartial("проверка связи")

        stt.lastListener?.onError(SpeechToTextError.Unavailable)

        assertEquals(HandsFreeStatus.Error, controller.status.value)
        assertEquals(SpeechToTextError.Unavailable, controller.error.value)
        assertEquals("проверка связи", controller.transcript.value)
        assertEquals(SpeechToTextError.Unavailable, controller.recording.value?.error)
    }

    @Test
    fun noMatchIsAnnouncedAndThenListensAgain() = runTest(testDispatcher) {
        val stt = FakeStt()
        val controller = controller(stt)
        controller.start()
        runCurrent()
        assertEquals(1, stt.startCalls)

        stt.lastListener?.onError(SpeechToTextError.NoMatch)

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
        stt.lastListener?.onError(SpeechToTextError.NoMatch)
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

        stt.lastListener?.onResult("как дела")
        assertEquals(HandsFreeStatus.Thinking, controller.status.value)
        assertEquals(listOf("как дела"), submitted)

        controller.onAssistantReply("m1", "Хорошо!")
        runCurrent()

        assertEquals(HandsFreeStatus.Listening, controller.status.value, "after the reply the loop listens again")

        // The reopened session is hung as well (the fake never calls back) — it
        // too must fail visibly instead of pretending to listen.
        advanceTimeBy(HandsFreeController.LISTEN_TIMEOUT_MS + 100)
        runCurrent()
        assertEquals(HandsFreeStatus.Error, controller.status.value)
    }

    @Test
    fun aResultSubmitsTheDraftPlusWhatWasSaid() = runTest(testDispatcher) {
        val stt = FakeStt()
        val submitted = mutableListOf<String>()
        val controller = controller(stt, onSubmit = { submitted += it })
        controller.start()
        runCurrent()
        controller.dictationDraft = "Заметка:"
        stt.lastListener?.onPartial("купи хлеб")
        stt.lastListener?.onResult("купи хлеб и молоко")

        assertEquals(listOf("Заметка: купи хлеб и молоко"), submitted, "text typed before dictation is kept")
        assertEquals(HandsFreeStatus.Thinking, controller.status.value)
        assertEquals("", controller.transcript.value)
        assertNull(controller.recording.value, "the strip leaves once the transcript is sent")
    }

    @Test
    fun recordingPhasesFollowTheSession() = runTest(testDispatcher) {
        val stt = FakeStt()
        val phases = mutableListOf<RecordingPhase?>()
        var controllerRef: HandsFreeController? = null
        val controller = controller(
            stt,
            onSubmit = { phases += controllerRef?.recording?.value?.phase },
        )
        controllerRef = controller

        controller.start()
        runCurrent()
        assertEquals(RecordingPhase.Listening, controller.recording.value?.phase)

        stt.lastListener?.onSpeechStart()
        assertEquals(RecordingPhase.SpeechDetected, controller.recording.value?.phase)

        stt.lastListener?.onFinalising()
        assertEquals(RecordingPhase.Finalising, controller.recording.value?.phase)

        stt.lastListener?.onResult("готово")
        assertEquals(listOf<RecordingPhase?>(RecordingPhase.Sending), phases, "the strip says «Отправляю…» while the text is handed over")
        assertEquals(HandsFreeStatus.Thinking, controller.status.value)
        assertNull(controller.recording.value)
    }

    @Test
    fun stopAndKeepDoesNotSubmitAndKeepsTheText() = runTest(testDispatcher) {
        val stt = FakeStt()
        val submitted = mutableListOf<String>()
        val controller = controller(stt, onSubmit = { submitted += it })
        controller.start()
        runCurrent()
        stt.lastListener?.onPartial("оставить в поле")

        controller.finishListening()

        assertEquals(HandsFreeStatus.Idle, controller.status.value)
        assertNull(controller.recording.value)
        assertTrue(submitted.isEmpty(), "«Остановить и вставить» must not send")
        assertEquals("оставить в поле", controller.transcript.value)
    }

    @Test
    fun restartDropsTheDictationAndOpensANewSession() = runTest(testDispatcher) {
        val stt = FakeStt()
        val controller = controller(stt)
        controller.start()
        runCurrent()
        stt.lastListener?.onPartial("лишнее")

        controller.restartListening()

        assertEquals(HandsFreeStatus.Listening, controller.status.value)
        assertEquals(RecordingPhase.Listening, controller.recording.value?.phase)
        assertEquals("", controller.transcript.value, "only this session's text is dropped")
        assertEquals(2, stt.startCalls)
    }

    @Test
    fun sendNowSubmitsTheComposerTextAsIs() = runTest(testDispatcher) {
        val stt = FakeStt()
        val submitted = mutableListOf<String>()
        val controller = controller(stt, onSubmit = { submitted += it })
        controller.start()
        runCurrent()
        controller.dictationDraft = "черновик"
        stt.lastListener?.onPartial("текст")

        controller.sendNow("черновик текст")

        assertEquals(listOf("черновик текст"), submitted, "the composer text is already complete")
        assertEquals(HandsFreeStatus.Thinking, controller.status.value)
    }

    @Test
    fun theComposerSendButtonSendsOnceAndClosesTheSession() = runTest(testDispatcher) {
        val stt = FakeStt()
        val submitted = mutableListOf<String>()
        val controller = controller(stt, onSubmit = { submitted += it })
        controller.start()
        runCurrent()
        controller.dictationDraft = "Заметка:"
        stt.lastListener?.onPartial("купи хлеб")

        // The composer's own send button while the loop is recording. ChatScreen
        // routes it through sendComposerText, so the session is closed first.
        controller.sendComposerText("Заметка: купи хлеб") { submitted += it }

        // The recognizer of the session that was just sent reports its final
        // result anyway — it was not stopped in time. It must add nothing.
        stt.lastListener?.onResult("купи хлеб")

        assertEquals(
            listOf("Заметка: купи хлеб"),
            submitted,
            "the composer's send button must not let the live session send the phrase a second time",
        )
        assertEquals(HandsFreeStatus.Thinking, controller.status.value)
        assertNull(controller.recording.value, "the send ends the session")
    }

    @Test
    fun theComposerSendButtonLetsTheChatSendWhenTheLoopIsOff() = runTest(testDispatcher) {
        val stt = FakeStt()
        val submitted = mutableListOf<String>()
        val controller = controller(stt, onSubmit = { submitted += it })

        controller.sendComposerText("привет") { submitted += it }

        assertEquals(listOf("привет"), submitted, "with the loop off the chat sends the text itself")
        assertEquals(0, stt.startCalls, "no microphone is opened")
        assertEquals(HandsFreeStatus.Idle, controller.status.value)
    }

    @Test
    fun joinDictationAppendsWithoutGlueingWords() {
        assertEquals("привет", joinDictation("", " привет "))
        assertEquals("Заметка:", joinDictation("Заметка:", ""))
        assertEquals("Заметка: купи хлеб", joinDictation("Заметка:", "купи хлеб"))
        assertEquals("слово купи хлеб", joinDictation("слово ", "купи хлеб"))
    }

    @Test
    fun dictationReplacesItsOwnTailAndKeepsWhatWasTyped() {
        // First partial: the field had a draft, dictation appends to it.
        val first = applyDictation("Заметка:", "", "купи хлеб")
        assertEquals("Заметка: купи хлеб", first.text)
        assertEquals(" купи хлеб", first.tail)
        assertEquals("Заметка:", first.draft, "the draft is what the user typed, never the dictation")

        // The user types an exclamation mark at the end while still speaking.
        // The next partial replaces only the stretch dictation wrote.
        val second = applyDictation("${first.text}!", first.tail, "купи хлеб и молоко")
        assertEquals(
            "Заметка:! купи хлеб и молоко",
            second.text,
            "nothing typed is dropped, only the dictation tail is replaced",
        )
        assertEquals(" купи хлеб и молоко", second.tail)
        assertEquals("Заметка:!", second.draft)
    }

    @Test
    fun dictationDoesNotDuplicateTheTranscriptWhenTheTailIsGone() {
        // The field no longer contains the previous tail (the user cleared it):
        // the new transcript is appended to what is there, once.
        val update = applyDictation("привет", "", "как дела")
        assertEquals("привет как дела", update.text)
        assertEquals(" как дела", update.tail)

        val afterClear = applyDictation("привет", update.tail, "как дела")
        assertEquals("привет как дела", afterClear.text)
        assertEquals(" как дела", afterClear.tail)
    }

    @Test
    fun theComposerChainSendsTheDictationExactlyOnce() = runTest(testDispatcher) {
        val stt = FakeStt()
        val submitted = mutableListOf<String>()
        val controller = controller(stt, onSubmit = { submitted += it })
        controller.start()
        runCurrent()

        // The composer exactly as ChatScreen keeps it: the field text, the
        // stretch the dictation wrote, and the draft the controller joins the
        // final result to. A previous version recomputed the draft from the
        // field and sent the phrase twice when the field held typed text.
        var field = "Заметка:"
        var tail = ""
        controller.dictationDraft = field

        fun writeTranscript(spoken: String) {
            val update = applyDictation(field, tail, spoken)
            field = update.text
            tail = update.tail
            controller.dictationDraft = update.draft
        }

        writeTranscript("купи хлеб")
        assertEquals("Заметка: купи хлеб", field)

        // The user types a symbol while the phrase is still running.
        field += "!"
        writeTranscript("купи хлеб и молоко")
        assertEquals("Заметка:! купи хлеб и молоко", field)

        stt.lastListener?.onResult("купи хлеб и молоко")

        assertEquals(
            listOf("Заметка:! купи хлеб и молоко"),
            submitted,
            "the message must be exactly what the field showed, with the phrase once",
        )
    }

    @Test
    fun restartRestoresTheDraftWithoutTheDictation() = runTest(testDispatcher) {
        val stt = FakeStt()
        val controller = controller(stt)
        controller.start()
        runCurrent()
        controller.dictationDraft = "Заметка:"

        val update = applyDictation("Заметка:", "", "купи хлеб")
        controller.dictationDraft = update.draft

        // «Отменить и начать заново» puts dictationDraft back into the composer.
        controller.restartListening()

        assertEquals("Заметка:", controller.dictationDraft, "restart must not keep the dropped phrase in the field")
        assertEquals(2, stt.startCalls)
    }
}
