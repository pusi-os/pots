package com.pusios.pots.data

import com.pusios.pots.network.OpenAICompatibleInvalidApiKeyException
import com.pusios.pots.testutil.FakeDataRepository
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A scheduled task that quietly switched models used to look like any other run: the Pulse
 * journal recorded the fallback, the task's own log did not. These are the two halves of that
 * — the note lands in the task log when a reserve took over, and a plain run gains no line.
 */
class ScheduledTaskFallbackLogTest {

    private val primary = ServiceEntry(
        instanceId = "pulse-primary",
        serviceId = Service.Polza.id,
        serviceName = Service.Polza.displayName,
        modelId = "gpt-4o",
        icon = Service.Polza.icon,
    )

    private val reserve = ServiceEntry(
        instanceId = "pulse-reserve",
        serviceId = Service.OpenAICompatible.id,
        serviceName = Service.OpenAICompatible.displayName,
        modelId = "llama3",
        icon = Service.OpenAICompatible.icon,
    )

    private class Harness(entries: List<ServiceEntry>, fallbackId: String? = null) {
        val repository = FakeDataRepository().apply {
            fakeServiceEntries = entries
            askWithToolsResponse = "all quiet"
        }
        private val settings = AppSettings(MapSettings())
        private val taskStore = TaskStore(settings)
        private val manager = HeartbeatManager(settings, MemoryStore(settings), taskStore).apply {
            // Scheduled tasks take their reserve from the Pulse config — that is the only
            // place a fallback model is chosen.
            saveConfig(HeartbeatConfig(heartbeatInstanceId = "pulse-primary", heartbeatFallbackInstanceId = fallbackId))
        }
        private val scheduler = TaskScheduler(
            dataRepository = repository,
            taskStore = taskStore,
            appSettings = settings,
            heartbeatManager = manager,
            backgroundDispatcher = UnconfinedTestDispatcher(),
        )

        /** Runs one due task the way the scheduler loop does, then returns it as stored. */
        suspend fun runOneTask(): ScheduledTask {
            val task = taskStore.addTask(
                description = "Evening check",
                prompt = "check the news",
                scheduledAtEpochMs = 0L,
                originInstanceId = "pulse-primary",
                originModelId = "gpt-4o",
            )
            scheduler.runScheduledTask(task)
            return taskStore.getAllTasks().single { it.id == task.id }
        }
    }

    @Test
    fun `a scheduled task that fell back says so in its own log`() = runTest {
        val harness = Harness(listOf(primary, reserve), fallbackId = "pulse-reserve")
        // A rejected key is not retried, so the reserve answers on the second call.
        harness.repository.askWithToolsFailures = mapOf("pulse-primary" to OpenAICompatibleInvalidApiKeyException())

        val task = harness.runOneTask()

        assertEquals(listOf<String?>("pulse-primary", "pulse-reserve"), harness.repository.askWithToolsCalls)
        val entry = task.recentExecutions.single()
        assertTrue(entry.success)
        // The whole line, not a substring: the task log has to name the reserve and the reason.
        assertEquals(
            "fell back to OpenAI-Compatible API / llama3 after Unauthorized (Polza.ai / gpt-4o)",
            entry.message,
        )
    }

    @Test
    fun `a scheduled task that did not fall back leaves its log line empty`() = runTest {
        val harness = Harness(listOf(primary))

        val task = harness.runOneTask()

        assertEquals(listOf<String?>("pulse-primary"), harness.repository.askWithToolsCalls)
        val entry = task.recentExecutions.single()
        assertTrue(entry.success)
        // No note, no line: a normal run must not grow text about models it never switched.
        assertNull(entry.message)
    }
}
