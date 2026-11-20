package com.pusios.pots.ui.potsik

import com.pusios.pots.testutil.FakeDataRepository
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The frame's rules: a shot rides with the question asked about it, the frame is
 * spent by that question, and the next question goes out bare.
 *
 * These live in desktopTest and not in commonTest for one reason — `PlatformFile`
 * has no common constructor (the same note as `ChatViewModelFileAttachmentTest`),
 * while the rules under test have no Android in them: the conversion is a lambda,
 * and the chat is the project's own [FakeDataRepository].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PotsikCloudQuestionTest {

    private val shot = PotsikShot(width = 6, height = 4, png = byteArrayOf(1, 2, 3))

    private fun platformFile(): PlatformFile {
        val file = File.createTempFile("pots-shot-", ".png")
        file.deleteOnExit()
        return PlatformFile(file)
    }

    private fun question(
        repository: FakeDataRepository,
        scheduler: TestCoroutineScheduler,
        toFile: (PotsikShot) -> PlatformFile? = { platformFile() },
    ): PotsikCloudQuestion = PotsikCloudQuestion(
        // The asker's own dispatcher has to share the test's scheduler, or its
        // coroutines never run inside advanceUntilIdle().
        asker = PotsikCloudAsker(CoroutineScope(StandardTestDispatcher(scheduler)), repository),
        toFile = toFile,
    )

    @Test
    fun `a shot rides with the typed question and is spent by it`() = runTest {
        val repository = FakeDataRepository()
        val question = question(repository, testScheduler)
        question.attach(shot)

        val outcome = question.ask("что тут на экране")

        assertIs<PotsikCloudQuestion.Outcome.Sent>(outcome)
        assertEquals("что тут на экране", outcome.question)
        advanceUntilIdle()
        val call = repository.askCalls.single()
        assertEquals("что тут на экране", call.first)
        assertEquals(1, call.second.size, "the frame goes to the chat as one attachment")
        assertNull(question.shot.value, "the shot is spent by the question it rode with")
    }

    @Test
    fun `a shot rides with the spoken question too`() = runTest {
        // The voice loop submits through this same call; the transcript is what
        // the recognizer returned, and the frame is whatever is in hand.
        val repository = FakeDataRepository()
        val question = question(repository, testScheduler)
        question.attach(shot)

        question.ask("а это что за кнопка")

        advanceUntilIdle()
        val call = repository.askCalls.single()
        assertEquals("а это что за кнопка", call.first)
        assertEquals(1, call.second.size, "the frame goes with the spoken question as well")
        assertNull(question.shot.value)
    }

    @Test
    fun `the next question after the answer goes out without the frame`() = runTest {
        val repository = FakeDataRepository()
        val question = question(repository, testScheduler)
        question.attach(shot)

        question.ask("что тут на экране")
        // The first turn is answered before the next question is asked — that is
        // the dialogue: the answer lands, then the user asks again. (Asking twice
        // in a row without waiting is the asker's own "a second question replaces
        // the first" rule, and not what this test is about.)
        advanceUntilIdle()
        question.ask("а теперь что")

        advanceUntilIdle()
        assertEquals(2, repository.askCalls.size)
        assertEquals(1, repository.askCalls[0].second.size)
        // The user has left the screen by now: the old frame is a picture of
        // another app, so the second question carries nothing.
        assertTrue(repository.askCalls[1].second.isEmpty())
    }

    @Test
    fun `a frame that cannot be attached stops the question`() = runTest {
        val repository = FakeDataRepository()
        val question = question(repository, testScheduler, toFile = { null })
        question.attach(shot)

        val outcome = question.ask("что тут на экране")

        assertIs<PotsikCloudQuestion.Outcome.ScreenFailed>(outcome)
        advanceUntilIdle()
        // Nothing is sent bare, and nothing is lost: the frame is still there
        // for the retry, and so is the draft the overlay keeps.
        assertTrue(repository.askCalls.isEmpty())
        assertNotNull(question.shot.value)
    }

    @Test
    fun `an empty field asks nothing, frame or not`() = runTest {
        val repository = FakeDataRepository()
        val question = question(repository, testScheduler)
        question.attach(shot)

        assertIs<PotsikCloudQuestion.Outcome.Nothing>(question.ask("   "))

        advanceUntilIdle()
        assertTrue(repository.askCalls.isEmpty())
        assertNotNull(question.shot.value, "an empty field must not spend the frame")
    }

    @Test
    fun `the assist context decorates the question and the field keeps only what was sent`() = runTest {
        val repository = FakeDataRepository()
        val question = question(repository, testScheduler)
        var decorated = false

        val outcome = question.ask("что тут") { text ->
            decorated = true
            "$text\n\nscreen: 2 lines"
        }

        assertEquals("что тут\n\nscreen: 2 lines", (outcome as PotsikCloudQuestion.Outcome.Sent).question)
        assertTrue(decorated)
        advanceUntilIdle()
        assertEquals("что тут\n\nscreen: 2 lines", repository.askCalls.single().first)
    }
}
