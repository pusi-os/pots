package com.pusios.pots.data

import com.pusios.pots.network.ConfiguredInstanceNotFoundException
import com.pusios.pots.network.NoConfiguredServiceException
import com.pusios.pots.network.OpenAICompatibleConnectionException
import com.pusios.pots.network.OpenAICompatibleInvalidApiKeyException
import com.pusios.pots.network.ProviderFailureReason
import com.pusios.pots.testutil.FakeDataRepository
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end behaviour of one Pulse run: which instance is tried, how often, what the journal
 * says afterwards. These are the owner's three acceptance scenarios, plus the "nothing is
 * configured" case that used to report success with an empty answer.
 */
class HeartbeatRunDiagnosticsTest {

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

    private class Harness(entries: List<ServiceEntry>, dispatcher: kotlin.coroutines.CoroutineContext) {
        val repository = FakeDataRepository().apply {
            fakeServiceEntries = entries
            askWithToolsResponse = "HEARTBEAT_OK"
        }
        private val settings = AppSettings(MapSettings())
        private val taskStore = TaskStore(settings)
        val manager = HeartbeatManager(settings, MemoryStore(settings), taskStore)
        private val scheduler = TaskScheduler(
            dataRepository = repository,
            taskStore = taskStore,
            appSettings = settings,
            heartbeatManager = manager,
            backgroundDispatcher = dispatcher,
        )

        suspend fun runHeartbeat(primaryId: String?, fallbackId: String? = null): HeartbeatLogEntry {
            manager.saveConfig(
                HeartbeatConfig(
                    enabled = true,
                    heartbeatInstanceId = primaryId,
                    heartbeatFallbackInstanceId = fallbackId,
                ),
            )
            scheduler.triggerHeartbeatNow()
            return manager.getHeartbeatLog().firstOrNull() ?: error("heartbeat recorded no log entry")
        }
    }

    @Test
    fun `a rejected key goes straight to the reserve and says so`() = runTest {
        val harness = Harness(listOf(primary, reserve), UnconfinedTestDispatcher(testScheduler))
        harness.repository.askWithToolsFailures = mapOf("pulse-primary" to OpenAICompatibleInvalidApiKeyException())

        val entry = harness.runHeartbeat(primaryId = "pulse-primary", fallbackId = "pulse-reserve")

        // No retry: a bad key fails identically a second later.
        assertEquals(listOf<String?>("pulse-primary", "pulse-reserve"), harness.repository.askWithToolsCalls)
        assertTrue(entry.success)
        val note = assertNotNull(entry.note)
        assertTrue(note.contains("fell back to"), "note does not say the reserve took over: $note")
        assertTrue(note.contains("Unauthorized"), "note does not name the reason: $note")
    }

    @Test
    fun `an unreachable primary is retried before the reserve takes over`() = runTest {
        val harness = Harness(listOf(primary, reserve), UnconfinedTestDispatcher(testScheduler))
        harness.repository.askWithToolsFailures = mapOf(
            "pulse-primary" to OpenAICompatibleConnectionException(ProviderFailureReason.Unreachable, "connection refused"),
        )

        val entry = harness.runHeartbeat(primaryId = "pulse-primary", fallbackId = "pulse-reserve")

        assertEquals(3, harness.repository.askWithToolsCalls.count { it == "pulse-primary" })
        assertEquals("pulse-reserve", harness.repository.askWithToolsCalls.last())
        assertTrue(entry.success)
        // The whole note, not a substring: an extra word here is a defect, not a nuance.
        assertEquals(
            "fell back to OpenAI-Compatible API / llama3 after Unreachable: connection refused (Polza.ai / gpt-4o)",
            entry.note,
        )
    }

    @Test
    fun `failing primary with no reserve names the missing setting`() = runTest {
        val harness = Harness(listOf(primary), UnconfinedTestDispatcher(testScheduler))
        harness.repository.askWithToolsFailures = mapOf("pulse-primary" to OpenAICompatibleInvalidApiKeyException())

        val entry = harness.runHeartbeat(primaryId = "pulse-primary")

        assertFalse(entry.success)
        val error = assertNotNull(entry.error)
        assertTrue(error.contains("Unauthorized"), "journal lost the reason: $error")
        assertTrue(error.contains("no fallback model selected"), "journal does not name the missing setting: $error")
        assertTrue(error.contains(Service.Polza.displayName), "journal does not name the provider: $error")
    }

    @Test
    fun `a deleted instance is reported instead of silently using another provider`() = runTest {
        // Only the reserve is still configured; the saved Pulse model is gone, so the
        // repository answers with ConfiguredInstanceNotFoundException (see resolveAskInstance).
        val harness = Harness(listOf(reserve), UnconfinedTestDispatcher(testScheduler))
        harness.repository.askWithToolsFailures = mapOf(
            "pulse-primary" to ConfiguredInstanceNotFoundException("pulse-primary"),
        )

        val entry = harness.runHeartbeat(primaryId = "pulse-primary")

        assertFalse(entry.success)
        // One attempt against the pin; with no reserve selected nothing else may stand in for it.
        assertEquals(listOf<String?>("pulse-primary"), harness.repository.askWithToolsCalls)
        val error = assertNotNull(entry.error)
        assertTrue(error.contains("configured instance not found: pulse-primary"), "journal does not report the missing instance: $error")
    }

    @Test
    fun `a deleted instance with a reserve falls back and still names the missing pin`() = runTest {
        val harness = Harness(listOf(primary, reserve), UnconfinedTestDispatcher(testScheduler))
        harness.repository.askWithToolsFailures = mapOf(
            "pulse-primary" to ConfiguredInstanceNotFoundException("pulse-primary"),
        )

        val entry = harness.runHeartbeat(primaryId = "pulse-primary", fallbackId = "pulse-reserve")

        // The reserve is the owner's explicit choice, so it may take over — but the journal
        // still has to say that the chosen instance was the one that vanished.
        assertTrue(entry.success)
        assertEquals(
            "fell back to OpenAI-Compatible API / llama3 after configured instance not found: pulse-primary",
            entry.note,
        )
    }

    @Test
    fun `no configured instance is a failure, not an empty success`() = runTest {
        val harness = Harness(emptyList(), UnconfinedTestDispatcher(testScheduler))
        harness.repository.askWithToolsFailures = mapOf(
            null to NoConfiguredServiceException("no configured service instances"),
        )

        val entry = harness.runHeartbeat(primaryId = null)

        assertFalse(entry.success)
        assertEquals("no configured service instances", entry.error)
    }

    @Test
    fun `the provider detail reaches the journal exactly once`() = runTest {
        val harness = Harness(listOf(primary), UnconfinedTestDispatcher(testScheduler))
        harness.repository.askWithToolsFailures = mapOf(
            "pulse-primary" to OpenAICompatibleConnectionException(ProviderFailureReason.Unreachable, "connection refused"),
        )

        val entry = harness.runHeartbeat(primaryId = "pulse-primary")

        // The reason is already inside the exception's own message; the journal must not add it
        // a second time, and it must name the model that failed and the setting that is missing.
        assertEquals(
            "Unreachable: connection refused (Polza.ai / gpt-4o); no fallback model selected in Settings → Agent → Heartbeat",
            entry.error,
        )
    }

    @Test
    fun `a reserve equal to the primary is named honestly`() = runTest {
        val harness = Harness(listOf(primary), UnconfinedTestDispatcher(testScheduler))
        harness.repository.askWithToolsFailures = mapOf(
            "pulse-primary" to OpenAICompatibleInvalidApiKeyException(),
        )

        val entry = harness.runHeartbeat(primaryId = "pulse-primary", fallbackId = "pulse-primary")

        // A reserve *is* selected — it just cannot take over, so "no fallback model selected" lies.
        assertEquals(
            "Unauthorized (Polza.ai / gpt-4o); reserve equals the primary instance",
            entry.error,
        )
    }
}
