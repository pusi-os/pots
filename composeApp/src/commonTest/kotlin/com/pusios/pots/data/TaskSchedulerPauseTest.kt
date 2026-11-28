@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.pusios.pots.data

// A scheduled run holds the task snapshot the poll picked up, and the model call can take
// minutes. These tests pin what has to survive that window: a pause pressed while the run is in
// flight is not overwritten by the result write.

import com.pusios.pots.testutil.FakeDataRepository
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

class TaskSchedulerPauseTest {

    private class Harness {
        val repository = FakeDataRepository().apply { askWithToolsResponse = "checked" }
        val store = TaskStore(AppSettings(MapSettings()))
        val scheduler = TaskScheduler(
            dataRepository = repository,
            taskStore = store,
            appSettings = AppSettings(MapSettings()),
            backgroundDispatcher = UnconfinedTestDispatcher(),
        )
    }

    @Test
    fun `a pause pressed during a run survives the completion write`() = runTest {
        val harness = Harness()
        val task = harness.store.addTask(
            description = "Check the rate",
            prompt = "check",
            scheduledAtEpochMs = 0L,
            cron = "0 9 * * *",
        )
        val gate = CompletableDeferred<Unit>()
        harness.repository.askWithToolsGate = gate

        val run = launch { harness.scheduler.runScheduledTask(task) }
        while (harness.repository.askWithToolsCalls.isEmpty()) yield()
        assertTrue(harness.store.pause(task.id))
        gate.complete(Unit)
        run.join()

        val stored = harness.store.getAllTasks().single()
        assertEquals(TaskStatus.PAUSED, stored.status)
        // The run still did its job: the log and the counters record it, and the cron schedule
        // moved on so resuming does not immediately fire the occurrence that just ran.
        assertEquals(1, stored.runCount)
        assertTrue(stored.recentExecutions.single().success)
        assertTrue(stored.scheduledAtEpochMs > Clock.System.now().toEpochMilliseconds())
    }

    @Test
    fun `a pause pressed during a failing run survives the failure write`() = runTest {
        val harness = Harness()
        val task = harness.store.addTask(description = "Check the rate", prompt = "check", scheduledAtEpochMs = 0L)
        val gate = CompletableDeferred<Unit>()
        harness.repository.askWithToolsGate = gate
        harness.repository.askWithToolsFailures = mapOf<String?, Exception>(null to IllegalStateException("boom"))

        val run = launch { harness.scheduler.runScheduledTaskTracked(task) }
        while (harness.repository.askWithToolsCalls.isEmpty()) yield()
        harness.store.pause(task.id)
        gate.complete(Unit)
        run.join()

        val stored = harness.store.getAllTasks().single()
        assertEquals(TaskStatus.PAUSED, stored.status)
        assertEquals(1, stored.consecutiveFailures)
        assertEquals(1, stored.runCount)
        assertFalse(stored.recentExecutions.single().success)
    }
}
