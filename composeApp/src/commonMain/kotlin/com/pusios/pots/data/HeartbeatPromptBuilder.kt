@file:OptIn(kotlin.time.ExperimentalTime::class)

// Pure builder for the heartbeat USER-message prompt. Like `buildChatSystemPrompt`,
// every input is explicit so tests can call it directly with hand-crafted inputs.
// The heartbeat prompt is a single shape — always sent as a user message.
//
// There is deliberately no input for previous heartbeat answers: a run that receives its own
// output as context talks to itself, and that is exactly the loop 3.21.8 removed. Keeping the
// parameter out of the signature makes feeding the model its own output a compile error.

package com.pusios.pots.data

import kotlin.time.Instant

/** A pending (polled-but-not-yet-heartbeat-picked-up) email rendered into the `## New Emails` section. */
internal data class HeartbeatPendingEmail(
    val accountEmail: String,
    val from: String,
    val subject: String,
    val preview: String,
)

/** A pending (polled-but-not-yet-heartbeat-picked-up) SMS rendered into the `## New SMS` section. */
internal data class HeartbeatPendingSms(
    val id: Long,
    val from: String,
    val preview: String,
)

/** A pending (captured-but-not-yet-heartbeat-picked-up) notification rendered into the `## New Notifications` section. */
internal data class HeartbeatPendingNotification(
    val id: String,
    val appLabel: String,
    val title: String,
    val preview: String,
)

/** Memory promotion candidate rendered into the `## Promotion Candidates` section. */
internal data class HeartbeatPromotionCandidate(
    val key: String,
    val hitCount: Int,
    val category: MemoryCategory,
    val content: String,
)

/**
 * Composes the heartbeat prompt.
 *
 * @param customOrDefaultPrompt leading free text — custom user prompt or [HeartbeatManager.DEFAULT_HEARTBEAT_PROMPT]
 * @param heartbeatAdditions tasks with trigger=HEARTBEAT; rendered as `## Heartbeat Additions` so their prompts run on every heartbeat. Empty list = section omitted
 * @param pendingTasks time/cron tasks to include in the `## Pending Tasks` section (heartbeat tasks belong to [heartbeatAdditions] instead); empty list = section omitted
 * @param emailAccounts email account statuses; empty list = section omitted
 * @param pendingEmails new emails polled since the last heartbeat pickup; empty list = section omitted
 * @param pendingSms new SMS polled since the last heartbeat pickup; empty list = section omitted
 * @param pendingNotifications new notifications captured since the last heartbeat pickup; empty list = section omitted
 * @param promotionCandidates memory promotion candidates; empty list = section omitted
 * @param budget the ceilings this run is held to, echoed to the model so it can plan
 */
internal fun buildHeartbeatPrompt(
    customOrDefaultPrompt: String,
    heartbeatAdditions: List<ScheduledTask>,
    pendingTasks: List<ScheduledTask>,
    emailAccounts: List<EmailAccountSummary>,
    pendingEmails: List<HeartbeatPendingEmail>,
    pendingSms: List<HeartbeatPendingSms>,
    pendingNotifications: List<HeartbeatPendingNotification>,
    promotionCandidates: List<HeartbeatPromotionCandidate>,
    budget: HeadlessBudget,
): String = buildString {
    append(customOrDefaultPrompt)
    append("\n")

    if (heartbeatAdditions.isNotEmpty()) {
        append("\n## Heartbeat Additions\n")
        append("Standing instructions the user asked to run on every heartbeat. Address each one in this run: do it with a tool now, or file it with schedule_task, or say plainly that it is not needed. A promise to do it later is not an action.\n")
        for (addition in heartbeatAdditions) {
            append("- **")
            append(addition.description)
            append("** (id: ")
            append(addition.id)
            append("): ")
            append(addition.prompt)
            append('\n')
        }
    }

    if (pendingTasks.isNotEmpty()) {
        append("\n## Pending Tasks\n")
        for (t in pendingTasks) {
            append("- **")
            append(t.description)
            append("** (id: ")
            append(t.id)
            append(", scheduled: ")
            append(t.scheduledAt)
            append(")")
            if (t.cron != null) {
                append(" [cron: ")
                append(t.cron)
                append("]")
            }
            append('\n')
        }
    }

    if (emailAccounts.isNotEmpty()) {
        append("\n## Email Status\n")
        for (account in emailAccounts) {
            append("- **")
            append(account.email)
            append("**: ")
            append(account.unreadCount)
            append(" unread")
            if (account.lastSyncEpochMs > 0) {
                append(" (last sync: ")
                append(Instant.fromEpochMilliseconds(account.lastSyncEpochMs))
                append(")")
            }
            append('\n')
        }
    }

    if (pendingEmails.isNotEmpty()) {
        append("\n## New Emails\n")
        append("These arrived since the last heartbeat. Summarise briefly; only flag items that genuinely need attention.\n")
        append("<untrusted-data source=\"email\">\n")
        for (msg in pendingEmails) {
            append("- **")
            append(msg.subject.ifBlank { "(no subject)" })
            append("** — ")
            append(msg.from)
            append(" [")
            append(msg.accountEmail)
            append("]")
            if (msg.preview.isNotBlank()) {
                append(": ")
                append(msg.preview)
            }
            append('\n')
        }
        append("</untrusted-data>\n")
    }

    if (pendingSms.isNotEmpty()) {
        append("\n## New SMS\n")
        append("These SMS arrived since the last heartbeat. Summarise briefly; only flag items that genuinely need attention.\n")
        append("<untrusted-data source=\"sms\">\n")
        for (msg in pendingSms) {
            append("- **")
            append(msg.from.ifBlank { "(unknown sender)" })
            append("** (id: ")
            append(msg.id)
            append(")")
            if (msg.preview.isNotBlank()) {
                append(": ")
                append(msg.preview)
            }
            append('\n')
        }
        append("</untrusted-data>\n")
    }

    if (pendingNotifications.isNotEmpty()) {
        append("\n## New Notifications\n")
        append("These notifications arrived since the last heartbeat. Summarise briefly; only flag items that genuinely need attention.\n")
        append("<untrusted-data source=\"notification\">\n")
        for (notif in pendingNotifications) {
            append("- **")
            append(notif.appLabel.ifBlank { "(unknown app)" })
            append("**")
            if (notif.title.isNotBlank()) {
                append(" — ")
                append(notif.title)
            }
            append(" (id: ")
            append(notif.id)
            append(")")
            if (notif.preview.isNotBlank()) {
                append(": ")
                append(notif.preview)
            }
            append('\n')
        }
        append("</untrusted-data>\n")
    }

    if (promotionCandidates.isNotEmpty()) {
        append("\n## Promotion Candidates\n")
        append("These memories have been reinforced ")
        append(promotionCandidates.first().hitCount)
        append("+ times. Mention them in your report if they matter; promotion into the soul happens in a normal chat, never here.\n")
        for (entry in promotionCandidates) {
            append("- **")
            append(entry.key)
            append("** (hits: ")
            append(entry.hitCount)
            append(", category: ")
            append(entry.category)
            append("): ")
            append(entry.content)
            append('\n')
        }
    }

    append("\n## Pulse limits\n")
    append("This is a background check, not a conversation. You may run at most ")
    append(budget.maxToolCalls)
    append(" tool calls and spend about ")
    append(budget.maxTokens)
    append(" tokens; past that the run stops and its answer is not shown. Answer with the result, not with a plan.\n")
}
