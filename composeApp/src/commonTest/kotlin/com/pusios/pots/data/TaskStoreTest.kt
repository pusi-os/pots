package com.pusios.pots.data

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
