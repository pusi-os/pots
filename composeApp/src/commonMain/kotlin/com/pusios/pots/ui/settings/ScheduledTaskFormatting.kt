@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.pusios.pots.ui.settings

// Compose-free presentation logic for the scheduled-task list: what a row says and how a
// timestamp reads. Kept out of the composables so the labels can be unit-tested on the JVM
// (the settings screen itself needs a Compose runtime and a device).

import com.pusios.pots.data.CronExpression
import com.pusios.pots.data.ScheduledTask
import com.pusios.pots.data.TaskStatus
import com.pusios.pots.data.TaskTrigger
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** What a task does, in the three shapes the scheduler understands. */
enum class ScheduledTaskType { ONE_OFF, RECURRING, EVERY_HEARTBEAT }

/** Whether the scheduler will fire the task, the user paused it, or it already ran. */
enum class ScheduledTaskState { ACTIVE, PAUSED, DONE }

/** Everything a task row needs, already decided — the composable only picks strings. */
data class ScheduledTaskRow(
    val type: ScheduledTaskType,
    val state: ScheduledTaskState,
    /** When it fires next; null for paused, finished, and every-heartbeat tasks. */
    val nextRunEpochMs: Long?,
)

fun scheduledTaskRow(task: ScheduledTask): ScheduledTaskRow = ScheduledTaskRow(
    type = when (task.trigger) {
        TaskTrigger.TIME -> ScheduledTaskType.ONE_OFF
        TaskTrigger.CRON -> ScheduledTaskType.RECURRING
        TaskTrigger.HEARTBEAT -> ScheduledTaskType.EVERY_HEARTBEAT
    },
    state = when (task.status) {
        TaskStatus.PENDING -> ScheduledTaskState.ACTIVE
        TaskStatus.PAUSED -> ScheduledTaskState.PAUSED
        TaskStatus.COMPLETED -> ScheduledTaskState.DONE
    },
    // A heartbeat addition has no clock, and a paused or finished task will not fire, so a
    // "next run" line there would be a promise the scheduler never keeps.
    nextRunEpochMs = task.scheduledAtEpochMs.takeIf {
        task.status == TaskStatus.PENDING && task.trigger != TaskTrigger.HEARTBEAT && it > 0
    },
)

/** How many tasks the header's counter reports. */
data class ScheduledTaskCounts(val active: Int, val paused: Int)

fun scheduledTaskCounts(tasks: List<ScheduledTask>): ScheduledTaskCounts = ScheduledTaskCounts(
    active = tasks.count { it.status == TaskStatus.PENDING },
    paused = tasks.count { it.status == TaskStatus.PAUSED },
)

/** True when [text] is a cron expression the scheduler can actually advance. */
fun isValidCron(text: String): Boolean = try {
    CronExpression(text).nextAfter(Instant.fromEpochMilliseconds(0L)) != null
} catch (_: Exception) {
    false
}

/**
 * First fire of [cron] after [fromEpochMs]. The edit dialog needs it: a saved cron task whose
 * next run is left at 0 would be due on the very next poll, so "edit the schedule" would fire
 * the task instead of rescheduling it.
 */
fun nextCronRunEpochMs(
    cron: String,
    fromEpochMs: Long,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): Long = try {
    CronExpression(cron).nextAfter(Instant.fromEpochMilliseconds(fromEpochMs), zone)?.toEpochMilliseconds()
        ?: fromEpochMs
} catch (_: Exception) {
    fromEpochMs
}

/**
 * Parses the `YYYY-MM-DD HH:MM` form the edit dialog shows. Returns the epoch millisecond of
 * that local wall clock, or null when the text is not a real date-time — the dialog keeps the
 * save button disabled on null, so a typo can never reschedule a task to the epoch.
 */
fun parseTaskDateTime(text: String, zone: TimeZone = TimeZone.currentSystemDefault()): Long? {
    val normalized = text.trim().replace(' ', 'T')
    if (normalized.length < 16) return null
    return try {
        LocalDateTime.parse(normalized.take(16)).toInstant(zone).toEpochMilliseconds()
    } catch (_: Exception) {
        null
    }
}

/** The `YYYY-MM-DD HH:MM` form used to prefill the edit dialog for [epochMs]. */
fun formatTaskDateTimeInput(epochMs: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
    if (epochMs <= 0L) return ""
    val local = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(zone)
    val month = local.monthNumber.toString().padStart(2, '0')
    val day = local.day.toString().padStart(2, '0')
    val hour = local.hour.toString().padStart(2, '0')
    val minute = local.minute.toString().padStart(2, '0')
    return "${local.year}-$month-$day $hour:$minute"
}

/**
 * Human-readable timestamp for the list and history rows: `28 Nov 2026 9:05`. Returns an
 * em dash for the epoch, which is what "never ran" and "no schedule" both store.
 */
fun formatTaskTimestamp(epochMs: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
    if (epochMs <= 0L) return "—"
    val local = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(zone)
    val month = local.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
    val minute = local.minute.toString().padStart(2, '0')
    return "${local.day} $month ${local.year} ${local.hour}:$minute"
}
