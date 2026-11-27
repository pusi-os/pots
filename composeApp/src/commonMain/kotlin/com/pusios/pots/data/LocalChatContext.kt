package com.pusios.pots.data

import com.pusios.pots.inference.LocalContextOverflowException
import com.pusios.pots.ui.chat.History

/*
 * Sizing rules for an on-device request.
 *
 * A local model has a real, small window (4 096 tokens is the catalog default) and litert-lm
 * does not trim anything for us: it fails the whole request with INVALID_ARGUMENT when the
 * system prompt plus the history do not fit. The remote path can rely on the provider's error
 * to fall back to the next service; the on-device path cannot — it has nowhere to fall back to,
 * so the budget has to be enforced before the call.
 */

/** Same 4-chars-per-token estimate the remote context checks use. */
internal const val LOCAL_CHARS_PER_TOKEN = 4

/**
 * Tokens left for the model's own answer. Decoding is what the user waits for, and a window
 * filled to the last token produces a truncated reply, so a fixed reserve beats a percentage.
 */
internal const val LOCAL_RESPONSE_RESERVE_TOKENS = 512

/**
 * Share of the window the system prompt may take when the caller does not pass a budget.
 *
 * A third leaves room for the reserve above plus a few turns of history. It is a share rather
 * than a constant so a 32K-context model gets a proportionally larger prompt instead of the
 * same 6K ceiling.
 */
internal const val LOCAL_PROMPT_WINDOW_SHARE_PERCENT = 35

/** Budget used when the caller does not know the model's window. */
internal const val LOCAL_PROMPT_BUDGET_CHARS = 6_000

/**
 * Character budget for the `CHAT_LOCAL` system prompt.
 *
 * Zero or negative [contextTokens] means "unknown" (no model downloaded yet, or a platform
 * that does not report the window) and falls back to [LOCAL_PROMPT_BUDGET_CHARS].
 */
fun localPromptBudgetCharsFor(contextTokens: Int): Int = if (contextTokens <= 0) {
    LOCAL_PROMPT_BUDGET_CHARS
} else {
    (contextTokens * LOCAL_CHARS_PER_TOKEN * LOCAL_PROMPT_WINDOW_SHARE_PERCENT / 100)
        .coerceIn(2_000, 12_000)
}

/**
 * Keeps the newest part of [messages] that fits the on-device window.
 *
 * The last user message is never dropped or rewritten — a silently truncated question produces
 * a confidently wrong answer, which is worse than an error that says the message is too long.
 * When it does not fit on its own, [LocalContextOverflowException] is thrown and the UI turns it
 * into "shorten the message or raise the context".
 *
 * Tool rows are filtered out rather than trimmed: the local engine sends only user and assistant
 * turns anyway, so a partially kept tool round could never be replayed.
 *
 * The budget is `(contextTokens - LOCAL_RESPONSE_RESERVE_TOKENS) * LOCAL_CHARS_PER_TOKEN` minus
 * everything else that rides in the same window: [systemPromptChars] and [toolSchemaChars]. The
 * tool descriptions are the ones the engine sends in `ConversationConfig.tools`; on a 4K model a
 * prompt at its ceiling plus the reserve fills the window exactly, so a history budget that
 * ignored them sent a long conversation back over the limit and into the retry without tools.
 */
internal fun trimLocalChatHistory(
    messages: List<History>,
    contextTokens: Int,
    systemPromptChars: Int,
    toolSchemaChars: Int = 0,
): List<History> {
    val conversational = messages.filter { it.role == History.Role.USER || it.role == History.Role.ASSISTANT }
    if (contextTokens <= 0) return conversational

    val lastUserIndex = conversational.indexOfLast { it.role == History.Role.USER }
    if (lastUserIndex < 0) return conversational

    val budgetChars =
        (contextTokens - LOCAL_RESPONSE_RESERVE_TOKENS) * LOCAL_CHARS_PER_TOKEN - systemPromptChars - toolSchemaChars
    val lastUser = conversational[lastUserIndex]
    if (lastUser.content.length > budgetChars) throw LocalContextOverflowException(contextTokens)

    var used = lastUser.content.length
    val kept = mutableListOf(lastUser)
    for (index in lastUserIndex - 1 downTo 0) {
        val message = conversational[index]
        if (used + message.content.length > budgetChars) break
        kept.add(0, message)
        used += message.content.length
    }
    return kept
}
