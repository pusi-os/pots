package com.pusios.pots.ui.potsik

import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * One question asked from the cloud, and the frame of the screen it may carry.
 *
 * Both entries end here — the field and the voice loop — and the frame of Flow 3
 * goes out with whichever asked first. That is the owner's flow: take a shot,
 * type or say the question about it, and get the answer in the cloud without
 * leaving it. The rules of the frame live in one place because a rule kept in
 * two call sites ends up true in one of them.
 */

/**
 * The pending frame, and the question that spends it.
 *
 * [toFile] is the platform's own conversion — the frame is written to the cache
 * and handed to the chat as the very `PlatformFile` the picker gives it — but
 * *when* it is called and what a null answer means are decided here, where they
 * can be tested without a device. It suspends because the frame is a full-screen
 * PNG and its write belongs off the thread that asked; [ask] waits for it, so
 * the question still goes out only once the file has landed.
 *
 * The frame is spent by the question it rides with: a second question asked a
 * minute later is about another app on another screen, and a picture of the
 * place the user has left is worse than no picture. A frame that cannot be
 * turned into an attachment stops the question instead of going out without the
 * picture that was taken for it.
 */
class PotsikCloudQuestion(
    private val asker: PotsikCloudAsker,
    private val toFile: suspend (PotsikShot) -> PlatformFile?,
) {

    /** What asking turned out to be. */
    sealed interface Outcome {
        /**
         * Nothing to ask: an empty field asks nothing, and a question that is
         * already being prepared refuses a second one rather than sending the
         * same thing twice.
         */
        data object Nothing : Outcome

        /** Sent — with the frame, when one was in hand. [question] is the text that went out. */
        data class Sent(val question: String) : Outcome

        /** A frame was in hand and could not be attached: nothing was sent. */
        data object ScreenFailed : Outcome
    }

    private val _shot = MutableStateFlow<PotsikShot?>(null)

    /** The frame in hand, if any: the pill draws it and the field previews it. */
    val shot: StateFlow<PotsikShot?> = _shot.asStateFlow()

    /**
     * A frame is in the middle of being written for the question that claimed it.
     *
     * The claim itself is [shot] going null, made before the write starts. This
     * only records that the write is still running: the write suspends, and a
     * question entering during it is the second half of a double tap on
     * [Отправить] — it would go out as a second request about a picture it no
     * longer has, and the provider bills for both.
     */
    private var claiming = false

    /** A frame was taken: it is now owed to the next question. */
    fun attach(shot: PotsikShot) {
        _shot.value = shot
    }

    /** The frame goes, without a question: [×] on the field, or the cloud itself going away. */
    fun dropShot() {
        _shot.value = null
    }

    /**
     * The state the field opens in — the frame in hand stays attached to it.
     *
     * Opening the field is not a decision about the picture: the user asked for
     * a keyboard, not for the frame to be thrown away. That is also what makes
     * «Текст» after a shot keep the shot.
     */
    fun textInputState(): PotsikCloudState = PotsikCloudState(
        phase = PotsikCloudPhase.TextInput,
        shotAttached = _shot.value != null,
    )

    /**
     * Asks [draft] in the current chat.
     *
     * [decorate] is the caller's last word on the text — the assist screen
     * context, which is spent by the same question.
     *
     * Suspends while the frame is written to the cache: the tap that asked
     * returns to the screen at once, and the question follows the file.
     */
    suspend fun ask(draft: String, decorate: (String) -> String = { it }): Outcome {
        if (draft.isBlank()) return Outcome.Nothing
        // A question is already preparing its frame: this one is the second half
        // of a double tap, and it has nothing to say that the first does not.
        if (claiming) return Outcome.Nothing
        // The frame is taken before it is written, not after. The write suspends,
        // and a question that enters during it would otherwise find the frame
        // still in hand and attach the same picture again — two questions, two
        // paid vision requests, one tap of [Отправить]. «Кадр занят этим
        // вопросом» has to be true from the moment the frame is claimed.
        val pending = _shot.value
        _shot.value = null
        val file = if (pending == null) {
            null
        } else {
            claiming = true
            try {
                // A frame that cannot be attached turns the question into a
                // question about nothing: stop instead of sending it bare.
                toFile(pending) ?: run {
                    // Nothing was written, so nothing was spent: the frame goes
                    // back to the user, who retries with the picture in hand.
                    // Unless a newer frame was taken while this one was being
                    // written («Экран» stays reachable in the field's phase):
                    // that one is the user's latest look at the screen, and the
                    // old picture must not overwrite it.
                    if (_shot.value == null) _shot.value = pending
                    return Outcome.ScreenFailed
                }
            } finally {
                claiming = false
            }
        }
        val question = decorate(draft).trim()
        asker.ask(question, listOfNotNull(file))
        return Outcome.Sent(question)
    }
}
