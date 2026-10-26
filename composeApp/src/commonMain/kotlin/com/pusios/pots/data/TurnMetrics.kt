package com.pusios.pots.data

import kotlinx.serialization.Serializable

/**
 * Per-reply metrics shown in the footer of an assistant bubble.
 *
 * [sessionTokens] is the running total of tokens spent in the current session at the moment
 * this reply arrived — prompt *and* completion tokens of every request made so far. It is
 * accumulated by the repository, because only the repository sees every provider response.
 *
 * [contextWindowTokens] is the size of the model's context window. Context usage is
 * measured from the prompt of this request — not from the session total, which bills
 * the whole history again on every request and would quickly exceed 100 %.
 *
 * [durationMs] is how long this single request took, measured around the provider call.
 */
@Serializable
data class TurnMetrics(
    val sessionTokens: Int = 0,
    val sessionPromptTokens: Int = 0,
    val sessionCompletionTokens: Int = 0,
    val contextWindowTokens: Int = 0,
    val durationMs: Long = 0,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
) {
    /**
     * Share of the context window this request's prompt occupied, in percent.
     *
     * Rounded down, and zero when the window size is unknown — an unknown window must not
     * render as a misleading "100 %".
     */
    val contextUsagePercent: Int
        get() = if (contextWindowTokens <= 0) {
            0
        } else {
            ((promptTokens.toLong() * 100) / contextWindowTokens).toInt().coerceIn(0, 100)
        }
}
