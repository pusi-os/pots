package com.pusios.pots.data

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The heartbeat's evidence rules, without a scheduler: what counts as silence, what counts as
 * chatter, and what the model may not smuggle into a delivered report.
 */
class HeartbeatDecisionTest {

    private fun classify(
        content: String,
        toolCalls: List<String> = emptyList(),
        hasFreshMaterial: Boolean = false,
        budgetExhausted: Boolean = false,
    ) = classifyHeartbeatReply(content, toolCalls, hasFreshMaterial, budgetExhausted)

    @Test
    fun `the bare token is silence`() {
        assertEquals(HeartbeatDecision.Silent, classify("HEARTBEAT_OK"))
        assertEquals(HeartbeatDecision.Silent, classify("  HEARTBEAT_OK  "))
    }

    @Test
    fun `a short remainder next to the token is still silence`() {
        assertEquals(HeartbeatDecision.Silent, classify("HEARTBEAT_OK — nothing new"))
        assertEquals(HeartbeatDecision.Silent, classify("nothing new. HEARTBEAT_OK"))
    }

    @Test
    fun `a report that mentions the token keeps its text and loses the token`() {
        val body = "x".repeat(HEARTBEAT_ACK_REMAINDER_LIMIT + 1)
        val decision = classify("HEARTBEAT_OK $body", toolCalls = listOf("execute_shell_command"))
        val report = decision as? HeartbeatDecision.Report ?: error("expected a report, got $decision")
        assertFalse(HEARTBEAT_ACK in report.body)
        assertTrue(body in report.body)
    }

    @Test
    fun `a token in the middle of a sentence does not swallow the message`() {
        // The old substring check dropped whole reports that happened to quote the token.
        val decision = classify(
            "The log said HEARTBEAT_OK, but the disk is 96% full.",
            toolCalls = listOf("execute_shell_command"),
        )
        val report = decision as? HeartbeatDecision.Report ?: error("expected a report, got $decision")
        assertTrue("disk is 96% full" in report.body)
        assertFalse(HEARTBEAT_ACK in report.body)
    }

    @Test
    fun `a tool-less promise is chatter`() {
        assertEquals(
            HeartbeatDecision.Chatter,
            classify("I'll check the exchange rate next time."),
        )
    }

    @Test
    fun `fresh material makes a tool-less summary a report`() {
        val decision = classify("3 unread emails, one from the bank.", hasFreshMaterial = true)
        assertTrue(decision is HeartbeatDecision.Report)
    }

    @Test
    fun `a tool call makes even a short answer a report`() {
        val decision = classify("Rate is 92.4.", toolCalls = listOf("execute_shell_command"))
        assertTrue(decision is HeartbeatDecision.Report)
    }

    @Test
    fun `an empty answer is chatter, not a report`() {
        assertEquals(HeartbeatDecision.Chatter, classify("   "))
    }

    @Test
    fun `a budget stop is never delivered`() {
        assertEquals(
            HeartbeatDecision.Budget,
            classify("Rate is 92.", toolCalls = listOf("execute_shell_command"), budgetExhausted = true),
        )
    }
}

/** The anti-repeat ledger: content fingerprints, the suppression window and the repeat counter. */
class HeartbeatLedgerTest {

    private val settings = AppSettings(MapSettings())
    private val ledger = HeartbeatLedger(settings)

    @Test
    fun `the same content is a duplicate inside the window`() = runTest {
        val fingerprint = heartbeatFingerprint("Rate is 92.4")
        ledger.recordDelivered(fingerprint, 1_000_000L)
        assertTrue(ledger.isDuplicate(fingerprint, 1_000_000L + HeartbeatLedger.REPEAT_WINDOW_MS - 1))
    }

    @Test
    fun `the same content is new again after the window`() = runTest {
        val fingerprint = heartbeatFingerprint("Rate is 92.4")
        ledger.recordDelivered(fingerprint, 1_000_000L)
        assertFalse(ledger.isDuplicate(fingerprint, 1_000_000L + HeartbeatLedger.REPEAT_WINDOW_MS))
    }

    @Test
    fun `formatting drift does not read as new content`() = runTest {
        ledger.recordDelivered(heartbeatFingerprint("Rate is 92.4"), 1_000_000L)
        assertTrue(ledger.isDuplicate(heartbeatFingerprint("  rate   is 92.4 \n"), 1_000_000L + 1000))
    }

    @Test
    fun `repeated suppressions raise the warning`() = runTest {
        val fingerprint = heartbeatFingerprint("Rate is 92.4")
        ledger.recordDelivered(fingerprint, 1_000_000L)
        assertNull(ledger.repeatWarning(1_000_000L))
        repeat(HeartbeatLedger.REPEAT_WARNING_THRESHOLD) { i ->
            ledger.recordSuppressed(fingerprint, 1_000_000L + (i + 1) * 60_000L)
        }
        val warning = assertNotNull(ledger.repeatWarning(1_000_000L + 10 * 60_000L))
        assertEquals(HeartbeatLedger.REPEAT_WARNING_THRESHOLD, warning.suppressedCount)
    }

    @Test
    fun `a stale warning disappears`() = runTest {
        val fingerprint = heartbeatFingerprint("Rate is 92.4")
        val deliveredAt = 1_000_000L
        ledger.recordDelivered(fingerprint, deliveredAt)
        repeat(HeartbeatLedger.REPEAT_WARNING_THRESHOLD) { i ->
            ledger.recordSuppressed(fingerprint, deliveredAt + (i + 1) * 60_000L)
        }
        val lastSuppression = deliveredAt + HeartbeatLedger.REPEAT_WARNING_THRESHOLD * 60_000L
        assertNotNull(ledger.repeatWarning(lastSuppression))
        // The window is measured from the last repeat, so a day of quiet clears the warning.
        assertNull(ledger.repeatWarning(lastSuppression + HeartbeatLedger.REPEAT_WARNING_WINDOW_MS + 1))
    }

    @Test
    fun `clearing the ledger forgets everything`() = runTest {
        val fingerprint = heartbeatFingerprint("Rate is 92.4")
        ledger.recordDelivered(fingerprint, 1_000_000L)
        ledger.clear()
        assertFalse(ledger.isDuplicate(fingerprint, 1_000_001L))
        assertTrue(ledger.getEntries().isEmpty())
    }
}

/** Scheduler arithmetic: backoff, jitter and the due check — pure, so they are cheap to pin. */
class HeartbeatScheduleTest {

    private val settings = AppSettings(MapSettings())
    private val taskStore = TaskStore(settings)
    private val manager = HeartbeatManager(settings, MemoryStore(settings), taskStore)

    @Test
    fun `backoff doubles with each failure and is capped at an hour`() {
        val interval = 30
        assertEquals(0L, heartbeatBackoffMs(interval, 0))
        assertEquals(30 * 60_000L, heartbeatBackoffMs(interval, 1))
        assertEquals(60 * 60_000L, heartbeatBackoffMs(interval, 2))
        assertEquals(60 * 60_000L, heartbeatBackoffMs(interval, 7))
    }

    @Test
    fun `jitter stays inside plus or minus ten percent of the interval`() {
        val interval = 30
        val bound = 30 * 60_000L / 10
        for (attempt in 0L..48L) {
            val jitter = heartbeatJitterMs(interval, attempt * 60_000L)
            assertTrue(jitter in -bound..bound, "jitter $jitter out of bounds")
        }
    }

    @Test
    fun `a failed run is not due on the next tick`() {
        manager.saveConfig(
            HeartbeatConfig(
                enabled = true,
                intervalMinutes = 30,
                activeHoursStart = 0,
                activeHoursEnd = 24,
            ),
        )
        val now = 1_700_000_000_000L
        manager.markAttempt(now, failed = true)

        assertFalse(manager.isHeartbeatDue(now + 60_000L))
        assertTrue(manager.getConfig().nextAllowedAtEpochMs > now)
        assertEquals(1, manager.getConfig().consecutiveFailures)
    }

    @Test
    fun `a success clears the backoff`() {
        manager.saveConfig(
            HeartbeatConfig(
                enabled = true,
                intervalMinutes = 30,
                activeHoursStart = 0,
                activeHoursEnd = 24,
            ),
        )
        val now = 1_700_000_000_000L
        manager.markAttempt(now, failed = true)
        manager.markAttempt(now + 60_000L, failed = false)

        assertEquals(0, manager.getConfig().consecutiveFailures)
        assertEquals(0L, manager.getConfig().nextAllowedAtEpochMs)
    }

    @Test
    fun `a missed interval is not replayed`() {
        manager.saveConfig(
            HeartbeatConfig(
                enabled = true,
                intervalMinutes = 30,
                activeHoursStart = 0,
                activeHoursEnd = 24,
            ),
        )
        val now = 1_700_000_000_000L
        manager.markAttempt(now, failed = false)
        // Six hours later the run is due exactly once: the check is a threshold, not a queue.
        assertTrue(manager.isHeartbeatDue(now + 6 * 60 * 60_000L))
    }

    @Test
    fun `the history offer is asked once`() {
        assertTrue(manager.shouldOfferHistoryCleanup())
        manager.markHistoryCleanupOffered(1_700_000_000_000L)
        assertFalse(manager.shouldOfferHistoryCleanup())
    }
}
