package com.pusios.pots.ui.potsik

import com.pusios.pots.data.DataRepository
import com.pusios.pots.ui.chat.lastRenderedAssistant
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Sends a question from the cloud into the current chat and waits for the
 * answer.
 *
 * Both flows end here — the voice loop's transcript and the text field's
 * message — because they are the same request to the same chat; only the way
 * the question was typed differs. The repository is the chat's own send path
 * (`DataRepository.ask`), so a question asked from the cloud lands in the
 * conversation the user is in, with the tools, the profile and the history the
 * chat has, instead of a second, cloud-only pipeline that would drift from it.
 *
 * The failure rule from the spec lives here: a question that could not be sent
 * comes back as [Answer.Failed] *with the text*, so the pill can offer
 * [Повторить] and nothing the user said is thrown away.
 */
class PotsikCloudAsker(
    private val scope: CoroutineScope,
    private val repository: DataRepository?,
) {

    sealed interface Answer {
        /** Nothing asked, nothing awaited. */
        data object Idle : Answer

        /** Sent; the agent is working on it. */
        data class Thinking(val question: String) : Answer

        /** The chat has an answer for this question. */
        data class Answered(val question: String, val text: String, val messageId: String) : Answer

        /** The request never made it: [question] is kept so it can be sent again. */
        data class Failed(val question: String) : Answer
    }

    private val _answer = MutableStateFlow<Answer>(Answer.Idle)
    val answer: StateFlow<Answer> = _answer.asStateFlow()

    private var job: Job? = null

    /** The question currently in flight or last answered, for [retry]. */
    private var lastQuestion: String = ""

    /** What was attached to it — the screenshot of Flow 3, if there was one. */
    private var lastFiles: List<PlatformFile> = emptyList()

    /**
     * Sends [question] to the current chat.
     *
     * A second question replaces the first: the chat answers one turn at a
     * time, and a queued turn nobody asked for would speak out of order.
     */
    fun ask(question: String, files: List<PlatformFile> = emptyList()) {
        val text = question.trim()
        if (text.isEmpty()) return
        val repo = repository
        if (repo == null) {
            lastQuestion = text
            _answer.value = Answer.Failed(text)
            return
        }
        job?.cancel()
        lastQuestion = text
        lastFiles = files
        // What the chat had before the question: the answer is whatever the
        // assistant says next, not a reply left over from the previous turn.
        val previousReplyId = repo.chatHistory.value.lastRenderedAssistant()?.id
        _answer.value = Answer.Thinking(text)
        job = scope.launch {
            try {
                repo.ask(text, files)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // No network, no service, a refused permission — the chat's
                // own error path is the source of truth for *why*; the cloud
                // only needs to know that it must keep the question.
                _answer.value = Answer.Failed(text)
                return@launch
            }
            val reply = repo.chatHistory.value.lastRenderedAssistant()?.takeIf { it.id != previousReplyId }
            _answer.value = if (reply != null) {
                Answer.Answered(text, reply.content, reply.id)
            } else {
                // The turn ended without an answer to show: the same treatment
                // as a failed send, because the user is owed their words back.
                Answer.Failed(text)
            }
        }
    }

    /** Sends the last question again, after a failure. */
    fun retry() {
        val question = lastQuestion
        if (question.isEmpty()) return
        ask(question, lastFiles)
    }

    /** Drops the answer, and any request still in flight with it. */
    fun reset() {
        job?.cancel()
        job = null
        lastFiles = emptyList()
        _answer.value = Answer.Idle
    }
}
