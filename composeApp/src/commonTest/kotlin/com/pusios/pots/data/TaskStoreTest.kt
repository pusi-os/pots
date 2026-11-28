package com.pusios.pots.data

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks in the task-store behaviours that keep scheduled tasks from going zombie:
 * standing tasks deduplicate by description (a re-scheduled check replaces its twin
 * instead of accumulating), a description prefix can drop every task a mini-app owns,
 * and [TaskStore.tasksFlow] mirrors every write so the settings screen always shows
 * what the scheduler sees.
 */
class TaskStoreTest {

    private fun freshStore(): TaskStore = TaskStore(AppSettings(MapSettings()))

    @Test
    fun standingTaskWithSameDescriptionUpdatesInsteadOfDuplicating() = runTest {
        val store = freshStore()
        val first = store.addTask(
            description = "Mini-app app-1: Check the things",
            prompt = "Check v1",
            scheduledAtEpochMs = 0L,
            cron = "0 9 * * *",
        )
        val second = store.addTask(
            description = "Mini-app app-1: Check the things",
            prompt = "Check v2",
            scheduledAtEpochMs = 0L,
            cron = "0 10 * * *",
        )

        assertEquals(1, store.getAllTasks().size)
        assertEquals(first.id, second.id)
        assertEquals("Check v2", store.getAllTasks().single().prompt)
        assertEquals("0 10 * * *", store.getAllTasks().single().cron)
    }

    @Test
    fun heartbeatAndCronShareTheStandingDeduplication() = runTest {
        val store = freshStore()
        store.addTask(
            description = "Keep an eye on the server",
            prompt = "cron version",
            scheduledAtEpochMs = 0L,
            cron = "0 9 * * *",
        )
        store.addTask(
            description = "Keep an eye on the server",
            prompt = "heartbeat version",
            scheduledAtEpochMs = 0L,
            trigger = TaskTrigger.HEARTBEAT,
        )

        val tasks = store.getAllTasks()
        assertEquals(1, tasks.size)
        assertEquals(TaskTrigger.HEARTBEAT, tasks.single().trigger)
        assertEquals("heartbeat version", tasks.single().prompt)
    }

    @Test
    fun oneOffTasksWithSameDescriptionStayIndependent() = runTest {
        val store = freshStore()
        store.addTask(description = "Drink water", prompt = "Drink", scheduledAtEpochMs = 1700000000000)
        store.addTask(description = "Drink water", prompt = "Drink", scheduledAtEpochMs = 1700003600000)

        assertEquals(2, store.getAllTasks().size)
    }

    @Test
    fun removeTasksWithDescriptionPrefixDropsOnlyMatchingTasks() = runTest {
        val store = freshStore()
        store.addTask(
            description = "${miniAppTaskPrefix("app-1")} Check the things",
            prompt = "Check",
            scheduledAtEpochMs = 0L,
            cron = "0 9 * * *",
        )
        val keeper = store.addTask(description = "Water the flowers", prompt = "Water", scheduledAtEpochMs = 1700000000000)

        val removed = store.removeTasksWithDescriptionPrefix(miniAppTaskPrefix("app-1"))

        assertEquals(1, removed)
        assertEquals(listOf(keeper.id), store.getAllTasks().map { it.id })
    }

    @Test
    fun tasksFlowMirrorsAddsAndRemovals() = runTest {
        val store = freshStore()
        assertTrue(store.tasksFlow.value.isEmpty())

        val task = store.addTask(description = "Morning check", prompt = "Check", scheduledAtEpochMs = 1700000000000)
        assertEquals(listOf(task.id), store.tasksFlow.value.map { it.id })

        store.removeTask(task.id)
        assertTrue(store.tasksFlow.value.isEmpty())
    }

    /**
     * A paused task keeps its row in the list (the user still sees it) but must never be picked
     * up by the scheduler — [TaskStore.getDueTasks] is the only gate every executor goes through.
     */
    @Test
    fun pausedTaskIsNotDueAndResumeMakesItDueAgain() = runTest {
        val store = freshStore()
        val task = store.addTask(description = "Check the rate", prompt = "Check", scheduledAtEpochMs = 1L)
        assertEquals(listOf(task.id), store.getDueTasks().map { it.id })

        assertTrue(store.pause(task.id))
        assertEquals(TaskStatus.PAUSED, store.getAllTasks().single().status)
        assertTrue(store.getDueTasks().isEmpty(), "a paused task must not be due")
        // The paused task also stops appearing in the heartbeat's pending lists.
        assertTrue(store.getPendingTasksPartitioned().scheduled.isEmpty())

        assertTrue(store.resume(task.id))
        assertEquals(TaskStatus.PENDING, store.getAllTasks().single().status)
        assertEquals(listOf(task.id), store.getDueTasks().map { it.id })
    }

    @Test
    fun pauseKeepsTheScheduleAndOnlyTouchesPendingTasks() = runTest {
        val store = freshStore()
        val task = store.addTask(description = "Water the flowers", prompt = "Water", scheduledAtEpochMs = 1700000000000)

        assertTrue(store.pause(task.id))
        val paused = store.getAllTasks().single()
        assertEquals(task.scheduledAtEpochMs, paused.scheduledAtEpochMs)
        assertEquals(task.prompt, paused.prompt)

        // Pausing twice is a no-op, and an unknown id is refused rather than inventing a row.
        assertFalse(store.pause(task.id))
        assertFalse(store.pause("no-such-task"))
        assertFalse(store.resume("no-such-task"))
    }

    @Test
    fun pauseDoesNotResurrectACompletedTask() = runTest {
        val store = freshStore()
        val task = store.addTask(description = "One shot", prompt = "Run", scheduledAtEpochMs = 1L)
        store.updateTask(task.copy(status = TaskStatus.COMPLETED))

        assertFalse(store.pause(task.id))
        assertEquals(TaskStatus.COMPLETED, store.getAllTasks().single().status)
        assertFalse(store.resume(task.id))
    }
}
