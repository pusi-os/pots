package com.pusios.pots.data

/**
 * What one heartbeat run amounts to, decided from the run's *evidence* rather than from its prose.
 *
 * A model that writes "I'll check the rate next time" has done nothing — nothing reads that
 * promise back. Only a tool call, fresh material handed to the run, or an explicit
 * acknowledgement are observable facts.
 */
internal sealed interface HeartbeatDecision {
    /** The run answered `HEARTBEAT_OK`: nothing to show the user. */
    data object Silent : HeartbeatDecision

    /** No tool call and nothing fresh to report: journal only, never a notification. */
    data object Chatter : HeartbeatDecision

    /** The run hit its tool/token ceiling: the answer is partial, so it is not delivered. */
    data object Budget : HeartbeatDecision

    /** Something worth the user's attention. [body] never contains the acknowledgement token. */
    data class Report(val body: String) : HeartbeatDecision
}

internal const val HEARTBEAT_ACK = "HEARTBEAT_OK"

/**
 * Prefix of the ephemeral sandbox session a heartbeat run binds its tool calls to. Unique per run:
 * the run's shell must not be the chat's shell, and it must not outlive the run.
 */
internal const val HEARTBEAT_SESSION_PREFIX = "hb-run-"

/**
 * How much text may follow (or precede) the token and still count as a bare acknowledgement.
 * Beyond this the reply is a report that happens to mention the token, and the token is stripped
 * from the delivered body instead of swallowing the message.
 */
internal const val HEARTBEAT_ACK_REMAINDER_LIMIT = 300

/**
 * Splits an acknowledgement off a reply.
 *
 * The token only counts at the start or the end: a report that quotes `HEARTBEAT_OK` in the
 * middle of a sentence is a report, not a silence — matching it as a substring used to drop
 * whole answers.
 */
internal fun parseHeartbeatAck(content: String): Pair<Boolean, String> {
    val trimmed = content.trim()
    val remainder = when {
        trimmed.startsWith(HEARTBEAT_ACK) -> trimmed.removePrefix(HEARTBEAT_ACK)
        trimmed.endsWith(HEARTBEAT_ACK) -> trimmed.removeSuffix(HEARTBEAT_ACK)
        else -> return false to trimmed
    }.trim().trimStart(':', '—', '–', '-', '.', ',').trim()
    return (remainder.length <= HEARTBEAT_ACK_REMAINDER_LIMIT) to remainder
}

/**
 * Classifies one finished run.
 *
 * @param toolCalls tools the run actually executed — the only proof that an intention was acted on
 * @param hasFreshMaterial the prompt carried something that arrived since the last run (unread
 *   mail/SMS/notifications); a summary of it is a result even without a tool call. Standing
 *   instructions are not fresh material: they are always in the prompt, so a promise to act on
 *   one must not pass as an answer.
 */
internal fun classifyHeartbeatReply(
    content: String,
    toolCalls: List<String>,
    hasFreshMaterial: Boolean,
    budgetExhausted: Boolean,
): HeartbeatDecision {
    if (budgetExhausted) return HeartbeatDecision.Budget
    val (acknowledged, _) = parseHeartbeatAck(content)
    if (acknowledged) return HeartbeatDecision.Silent
    // The token is a machine marker, not a word: the journal and the conversation never show it.
    val body = content.replace(HEARTBEAT_ACK, "").trim()
    if (body.isEmpty()) return HeartbeatDecision.Chatter
    if (toolCalls.isEmpty() && !hasFreshMaterial) return HeartbeatDecision.Chatter
    return HeartbeatDecision.Report(body)
}
