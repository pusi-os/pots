package com.pusios.pots.data

import com.pusios.pots.network.TokenUsage

/**
 * What one headless run (heartbeat / scheduled task) produced, together with the evidence the
 * caller needs to judge it.
 *
 * The reply alone is not enough: a heartbeat has to tell "the model did something" from "the
 * model only talked", and it cannot do that by reading prose. [toolCalls] is the trace the tool
 * loop already had in hand and used to throw away.
 */
data class HeadlessRunResult(
    val content: String,
    /** Names of the tools the run actually executed, in order. Empty means the model only talked. */
    val toolCalls: List<String> = emptyList(),
    /** Token accounting for the whole run, summed over every provider round. */
    val usage: TokenUsage? = null,
    /** Set when the run stopped early because it hit [HeadlessBudget] — the answer is partial. */
    val budgetExhausted: Boolean = false,
)

/**
 * Hard ceilings for one autonomous run.
 *
 * A background check that loops on tools or burns the context window is worse than a check that
 * did not happen: nobody is watching to press stop, and the bill arrives later.
 */
data class HeadlessBudget(
    val maxToolCalls: Int = 12,
    val maxTokens: Int = 60_000,
)
