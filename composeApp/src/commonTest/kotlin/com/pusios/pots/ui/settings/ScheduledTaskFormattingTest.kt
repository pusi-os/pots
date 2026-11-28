@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.pusios.pots.ui.settings

// Row labels and the edit-dialog's date parsing, exercised without Compose: these are the bits
// that used to print "PENDING" and a raw instant at the owner, and that must refuse a typo
// before it reschedules a task.

import com.pusios.pots.data.ScheduledTask
import com.pusios.pots.data.TaskStatus
import com.pusios.pots.data.TaskTrigger
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScheduledTaskFormattingTest {

    private val utc = TimeZone.UTC

    private fun task(
        trigger: TaskTrigger = TaskTrigger.TIME,
        status: TaskStatus = TaskStatus.PENDING,
        scheduledAtEpochMs: Long = 1_800_000_000_000L,
        cron: String? = null,
    ) = ScheduledTask(
        id = "task-1",
        description = "Check the rate",
        prompt = "Check",
        scheduledAtEpochMs = scheduledAtEpochMs,
        createdAtEpochMs = 0L,
        cron = cron,
        trigger = trigger,
        status = status,
    )

    @Test
    fun `type follows the trigger`() {
        assertEquals(ScheduledTaskType.ONE_OFF, scheduledTaskRow(task(TaskTrigger.TIME)).type)
        assertEquals(ScheduledTaskType.RECURRING, scheduledTaskRow(task(TaskTrigger.CRON, cron = "0 9 * * *")).type)
        assertEquals(ScheduledTaskType.EVERY_HEARTBEAT, scheduledTaskRow(task(TaskTrigger.HEARTBEAT)).type)
    }

    @Test
    fun `state follows the status instead of leaking the enum name`() {
        assertEquals(ScheduledTaskState.ACTIVE, scheduledTaskRow(task(status = TaskStatus.PENDING)).state)
        assertEquals(ScheduledTaskState.PAUSED, scheduledTaskRow(task(status = TaskStatus.PAUSED)).state)
        assertEquals(ScheduledTaskState.DONE, scheduledTaskRow(task(status = TaskStatus.COMPLETED)).state)
    }

    @Test
    fun `only a pending clocked task advertises a next run`() {
        val epoch = 1_800_000_000_000L
        assertEquals(epoch, scheduledTaskRow(task(scheduledAtEpochMs = epoch)).nextRunEpochMs)
        assertNull(scheduledTaskRow(task(status = TaskStatus.PAUSED, scheduledAtEpochMs = epoch)).nextRunEpochMs)
        assertNull(scheduledTaskRow(task(status = TaskStatus.COMPLETED, scheduledAtEpochMs = epoch)).nextRunEpochMs)
        assertNull(scheduledTaskRow(task(TaskTrigger.HEARTBEAT, scheduledAtEpochMs = 0L)).nextRunEpochMs)
    }

    @Test
    fun `counts separate active from paused and ignore finished tasks`() {
        val counts = scheduledTaskCounts(
            listOf(
                task(status = TaskStatus.PENDING),
                task(status = TaskStatus.PENDING),
                task(status = TaskStatus.PAUSED),
                task(status = TaskStatus.COMPLETED),
            ),
        )
        assertEquals(ScheduledTaskCounts(active = 2, paused = 1), counts)
    }

    @Test
    fun `cron validation accepts real expressions and rejects the rest`() {
        assertTrue(isValidCron("0 9 * * *"))
        assertTrue(isValidCron("*/15 * * * 1-5"))
        assertFalse(isValidCron("every day"))
        assertFalse(isValidCron("0 9 * *"))
        assertFalse(isValidCron(""))
    }

    @Test
    fun `a rescheduled cron keeps a future next run instead of being due now`() {
        // 1_800_000_000_000 ms = 2027-01-15T08:00:00Z; the next "0 9 * * *" is one hour later.
        assertEquals(1_800_003_600_000L, nextCronRunEpochMs("0 9 * * *", 1_800_000_000_000L, utc))
        // An unparsable expression falls back to the anchor instead of throwing.
        assertEquals(42L, nextCronRunEpochMs("every day", 42L, utc))
    }

    @Test
    fun `edit dialog round-trips a local wall clock`() {
        val text = "2026-11-29 09:00"
        val epochMs = parseTaskDateTime(text, utc)
        // 2026-11-29T09:00:00Z — pinned, so a parser that quietly shifts the zone shows up here.
        assertEquals(1795942800000L, epochMs)
        assertEquals(text, formatTaskDateTimeInput(epochMs!!, utc))
    }

    @Test
    fun `a typo in the date field is refused instead of becoming the epoch`() {
        assertNull(parseTaskDateTime("29.11.2026 9:00", utc))
        assertNull(parseTaskDateTime("2026-13-45 09:00", utc))
        assertNull(parseTaskDateTime("", utc))
        assertNull(parseTaskDateTime("tomorrow", utc))
    }

    @Test
    fun `timestamps are readable and the epoch is not a date`() {
        assertEquals("—", formatTaskTimestamp(0L, utc))
        // 1_800_000_000_000 ms = 2027-01-15T08:00:00Z
        assertEquals("15 Jan 2027 8:00", formatTaskTimestamp(1_800_000_000_000L, utc))
    }

    /** Guards the offset used by the formatter: the zone, not the machine, decides the hour. */
    @Test
    fun `the zone shifts the rendered hour`() {
        val plusThree = TimeZone.of("UTC+03:00")
        assertEquals("15 Jan 2027 11:00", formatTaskTimestamp(1_800_000_000_000L, plusThree))
    }
}
