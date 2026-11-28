package com.pusios.pots.data

import com.pusios.pots.testutil.FakeDataRepository
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end behaviour of one Pulse run as the owner experiences it: what the model is given,
 * what reaches the conversation, and what the journal says afterwards.
 *
 * Every test here fails on the pre-3.21.8 code: the old scheduler fed the previous answers back
 * in, keyed the shell on the Pulse conversation, and delivered anything that merely lacked the
 * `HEARTBEAT_OK` substring.
 */
class HeartbeatStatelessRunTest {

    private class Harness {
        val repository = FakeDataRepository().apply {
            fakeServiceEntries = listOf(
                ServiceEntry(
                    instanceId = "pulse-primary",
                    serviceId = Service.Polza.id,
                    serviceName = Service.Polza.displayName,
                    modelId = "gpt-4o",
                    icon = Service.Polza.icon,
                ),
            )
            askWithToolsResponse = "HEARTBEAT_OK"
        }
        private val settings = AppSettings(MapSettings())
        private val taskStore = TaskStore(settings)
        val manager = HeartbeatManager(settings, MemoryStore(settings), taskStore)
        val scheduler = TaskScheduler(
            dataRepository = repository,
            taskStore = taskStore,
            appSettings = settings,
            heartbeatManager = manager,
            backgroundDispatcher = UnconfinedTestDispatcher(),
        )

        init {
            manager.saveConfig(
                HeartbeatConfig(
                    enabled = true,
                    intervalMinutes = 30,
                    activeHoursStart = 0,
                    activeHoursEnd = 24,
                    heartbeatInstanceId = "pulse-primary",
                ),
            )
        }

        fun journal() = manager.getHeartbeatLog()
        fun latest() = manager.getHeartbeatLog().first()
    }

    @Test
    fun `a second run does not receive the first run's answer`() = runTest {
        val harness = Harness()
        harness.repository.askWithToolsResponse = "Rate is 92.4 rubles."
        harness.repository.askWithToolsTools = listOf("execute_shell_command")
        harness.scheduler.triggerHeartbeatNow()

        harness.repository.askWithToolsResponse = "HEARTBEAT_OK"
        harness.repository.askWithToolsTools = emptyList()
        harness.scheduler.triggerHeartbeatNow()

        val prompts = harness.repository.askWithToolsPrompts
        assertEquals(2, prompts.size)
        assertFalse("92.4" in prompts[1], "the second run saw the first run's answer:\n${prompts[1]}")
        assertFalse("Previous Heartbeat Results" in prompts[1])
    }

    @Test
    fun `each run gets its own ephemeral session and closes it`() = runTest {
        val harness = Harness()
        harness.repository.askWithToolsResponse = "HEARTBEAT_OK"
        harness.scheduler.triggerHeartbeatNow()
        harness.scheduler.triggerHeartbeatNow()

        val ids = harness.repository.askWithToolsConversationIds
        assertEquals(2, ids.size)
        for (id in ids) {
            assertNotNull(id)
            assertTrue(id.startsWith(HEARTBEAT_SESSION_PREFIX), "session id is not ephemeral: $id")
        }
        assertNotEquals(ids[0], ids[1], "two runs shared one session id")
        assertEquals(ids.filterNotNull(), harness.repository.closedHeadlessSessions, "run sessions outlived their runs")
    }

    @Test
    fun `a run without a tool call and without the token is journal-only`() = runTest {
        val harness = Harness()
        harness.repository.askWithToolsResponse = "I'll check the exchange rate next time."

        harness.scheduler.triggerHeartbeatNow()

        assertTrue(harness.repository.assistantMessages.isEmpty(), "a promise reached the conversation")
        val entry = harness.latest()
        assertEquals(HeartbeatOutcome.CHATTER, entry.outcome)
        assertFalse(entry.notified)
    }

    @Test
    fun `the token is never written to the conversation`() = runTest {
        val harness = Harness()
        harness.repository.askWithToolsResponse = "HEARTBEAT_OK"

        harness.scheduler.triggerHeartbeatNow()

        assertTrue(harness.repository.assistantMessages.isEmpty())
        assertEquals(HeartbeatOutcome.OK_SILENT, harness.latest().outcome)
    }

    @Test
    fun `a report with a tool call is delivered once and journaled with its metrics`() = runTest {
        val harness = Harness()
        harness.repository.askWithToolsResponse = "Rate is 92.4 rubles."
        harness.repository.askWithToolsTools = listOf("execute_shell_command")

        harness.scheduler.triggerHeartbeatNow()

        assertEquals(listOf("Rate is 92.4 rubles."), harness.repository.assistantMessages)
        val entry = harness.latest()
        assertEquals(HeartbeatOutcome.REPORTED, entry.outcome)
        assertTrue(entry.notified)
        assertEquals(1, entry.toolCount)
        assertEquals("Polza.ai / gpt-4o", entry.modelLabel)
        assertTrue(entry.durationMs >= 0)
    }

    @Test
    fun `the same report inside the window is suppressed`() = runTest {
        val harness = Harness()
        harness.repository.askWithToolsResponse = "Disk is 96% full."
        harness.repository.askWithToolsTools = listOf("execute_shell_command")

        harness.scheduler.triggerHeartbeatNow()
        harness.scheduler.triggerHeartbeatNow()

        assertEquals(listOf("Disk is 96% full."), harness.repository.assistantMessages)
        assertEquals(HeartbeatOutcome.DUPLICATE, harness.latest().outcome)
        assertEquals(HeartbeatOutcome.REPORTED, harness.journal()[1].outcome)
    }

    @Test
    fun `a budget stop is not delivered`() = runTest {
        val harness = Harness()
        harness.repository.askWithToolsResponse = "Rate is 92."
        harness.repository.askWithToolsTools = listOf("execute_shell_command")
        harness.repository.askWithToolsBudgetExhausted = true

        harness.scheduler.triggerHeartbeatNow()

        assertTrue(harness.repository.assistantMessages.isEmpty())
        assertEquals(HeartbeatOutcome.BUDGET, harness.latest().outcome)
        assertTrue(harness.repository.closedHeadlessSessions.isNotEmpty())
    }

    @Test
    fun `single flight refuses a second run while one is in flight`() = runTest {
        val harness = Harness()
        harness.repository.askWithToolsResponse = "HEARTBEAT_OK"
        harness.repository.askWithToolsDelayMs = 1_000

        val first = launch { harness.scheduler.triggerHeartbeatNow() }
        // The first run is now suspended inside the provider call, holding the single-flight lock.
        val second = launch { harness.scheduler.triggerHeartbeatNow() }
        advanceUntilIdle()

        first.join()
        second.join()
        assertEquals(1, harness.repository.askWithToolsCalls.size, "two runs hit the provider at once")
        // The button press that found a run in flight says so in the journal; the run itself
        // still finished normally and is the newer of the two entries.
        assertTrue(
            harness.journal().any { it.outcome == HeartbeatOutcome.BUSY },
            "the refused run left no trace in the journal: ${harness.journal().map { it.outcome }}",
        )
        assertEquals(HeartbeatOutcome.OK_SILENT, harness.latest().outcome)
    }

    @Test
    fun `switching the pulse off cancels the run in flight without leaving a report`() = runTest {
        val harness = Harness()
        harness.repository.askWithToolsResponse = "Rate is 92.4 rubles."
        harness.repository.askWithToolsTools = listOf("execute_shell_command")
        harness.repository.askWithToolsDelayMs = 1_000

        val run = launch { harness.scheduler.triggerHeartbeatNow() }
        // Start the run and let it suspend inside the provider call; without this the job is still
        // queued, the scheduler has nothing to cancel, and the run would finish normally.
        runCurrent()
        harness.scheduler.cancelHeartbeatRun()
        advanceUntilIdle()
        run.join()

        assertTrue(harness.repository.assistantMessages.isEmpty(), "a cancelled run wrote into the Pulse")
        assertEquals(HeartbeatOutcome.CANCELLED, harness.latest().outcome)
        assertEquals(1, harness.repository.closedHeadlessSessions.size, "the run's shell was left open")
    }

    @Test
    fun `a failed run arms the backoff so the next tick does nothing`() = runTest {
        val harness = Harness()
        harness.repository.askWithToolsFailures = mapOf(
            "pulse-primary" to com.pusios.pots.network.OpenAICompatibleInvalidApiKeyException(),
        )

        harness.scheduler.triggerHeartbeatNow()

        val entry = harness.latest()
        assertEquals(HeartbeatOutcome.ERROR, entry.outcome)
        assertNotNull(entry.error)
        assertTrue(harness.manager.getConfig().consecutiveFailures > 0)
        assertFalse(harness.manager.isHeartbeatDue(), "a failed run stayed due on the next tick")
        assertTrue(harness.repository.closedHeadlessSessions.isNotEmpty())
    }
}
