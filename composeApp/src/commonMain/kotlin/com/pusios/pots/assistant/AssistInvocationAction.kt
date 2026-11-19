package com.pusios.pots.assistant

import com.pusios.pots.handsfree.HandsFreeStatus

/** What the assist gesture does with the microphone, given what the loop is doing. */
internal enum class AssistInvocationAction {
    /** A dialogue owns the microphone: only attach the snapshot. */
    AttachOnly,

    /** Nothing is running: open the cloud and listen, as a double tap does. */
    StartListening,

    /** He is talking: stop the voice, then listen — the question was answered. */
    InterruptAndListen,
}

/**
 * The one-microphone rule, as a pure function.
 *
 * The owner's requirement is that an invocation opens Потсик already listening,
 * and there is exactly one owner of the microphone. The loop's own state says
 * which of the two applies:
 *
 * * `Idle` — nothing is running, open it;
 * * `Error` — the loop has already stopped listening (a denied permission, a
 *   recognizer that never answered). A fresh gesture is a fresh attempt, so it
 *   opens the microphone again; a `PermissionDenied` error simply comes back,
 *   which is the honest answer;
 * * `Speaking` — he is answering a *previous* question. The person invoked the
 *   assistant, so they want to be heard: the answer is cut short and the
 *   microphone opens, through `HandsFreeController.interruptAndListen()`, which
 *   silences the voice *before* the recognizer starts;
 * * `Listening`, `Thinking`, `NoMatch` — the loop is already on it. A second
 *   start would `cancel()` the live recognition session and open another on top
 *   of it, and the asynchronous `cancel()` is what produced
 *   `ERROR_RECOGNIZER_BUSY` before. `NoMatch` counts as busy because the loop
 *   reopens the microphone by itself two seconds later.
 *
 * `Thinking` stays [AssistInvocationAction.AttachOnly] on purpose: the question
 * has been asked and the answer is on its way — a new session would ask the
 * same thing twice.
 *
 * Pure, and in `commonMain`, because this is the rule that decides whether the
 * gesture works at all — it is tested rather than trusted to the window code.
 */
internal fun assistInvocationAction(status: HandsFreeStatus): AssistInvocationAction = when (status) {
    HandsFreeStatus.Listening,
    HandsFreeStatus.Thinking,
    HandsFreeStatus.NoMatch,
    -> AssistInvocationAction.AttachOnly

    HandsFreeStatus.Speaking -> AssistInvocationAction.InterruptAndListen

    HandsFreeStatus.Idle, HandsFreeStatus.Error -> AssistInvocationAction.StartListening
}
