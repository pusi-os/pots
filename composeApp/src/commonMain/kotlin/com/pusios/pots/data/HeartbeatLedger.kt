package com.pusios.pots.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer

/**
 * One delivered notification, remembered so the same content is not delivered twice.
 *
 * The fingerprint is derived from the delivered text, not from the run: a model that re-words
 * nothing but repeats the same finding every thirty minutes must be caught, and only content can
 * tell that apart from a genuinely new finding.
 */
@Serializable
data class HeartbeatFingerprint(
    val fingerprint: String,
    val firstSeenEpochMs: Long,
    val lastSeenEpochMs: Long,
    /** How many runs produced this same content — the repeat counter shown in the UI. */
    val seenCount: Int = 1,
    /** How many of those runs were suppressed instead of delivered. */
    val suppressedCount: Int = 0,
)

/** A standing repeat worth telling the owner about, with the text that keeps coming back. */
data class HeartbeatRepeatWarning(
    val fingerprint: String,
    val suppressedCount: Int,
    val firstSeenEpochMs: Long,
    val lastSeenEpochMs: Long,
)

/**
 * The anti-repeat ledger: content fingerprints of delivered reports, and how often each came back.
 *
 * Kept in app settings next to the run journal because it has to survive a process restart —
 * otherwise every app launch would re-deliver the same finding.
 */
class HeartbeatLedger(private val appSettings: AppSettings) {

    private val entries = SettingsJsonList(
        read = appSettings::getHeartbeatLedgerJson,
        write = appSettings::setHeartbeatLedgerJson,
        itemSerializer = serializer<HeartbeatFingerprint>(),
        label = "HeartbeatLedger",
    )

    fun getEntries(): List<HeartbeatFingerprint> = entries.get()

    /** True when [fingerprint] was delivered inside the suppression window. */
    fun isDuplicate(fingerprint: String, nowMs: Long): Boolean {
        val entry = entries.get().find { it.fingerprint == fingerprint } ?: return false
        return nowMs - entry.lastSeenEpochMs < REPEAT_WINDOW_MS
    }

    /** Records a delivery: a new fingerprint starts a window, a known one extends it. */
    suspend fun recordDelivered(fingerprint: String, nowMs: Long) {
        entries.update { list ->
            val existing = list.find { it.fingerprint == fingerprint }
            val updated = if (existing == null) {
                HeartbeatFingerprint(fingerprint, firstSeenEpochMs = nowMs, lastSeenEpochMs = nowMs)
            } else {
                existing.copy(lastSeenEpochMs = nowMs, seenCount = existing.seenCount + 1)
            }
            (listOf(updated) + list.filter { it.fingerprint != fingerprint }).take(MAX_LEDGER_ENTRIES)
        }
    }

    /** Records a suppression. Returns how many times this content has been held back in 24 h. */
    suspend fun recordSuppressed(fingerprint: String, nowMs: Long): Int {
        var suppressed = 0
        entries.update { list ->
            val existing = list.find { it.fingerprint == fingerprint } ?: return@update list
            // A counter that only ever grows would keep the warning up for weeks after the model
            // stopped repeating itself; the window is what makes the number mean "lately".
            val inWindow = nowMs - existing.firstSeenEpochMs < REPEAT_WARNING_WINDOW_MS
            val nextCount = if (inWindow) existing.suppressedCount + 1 else 1
            suppressed = nextCount
            val updated = existing.copy(
                lastSeenEpochMs = nowMs,
                seenCount = existing.seenCount + 1,
                suppressedCount = nextCount,
            )
            (listOf(updated) + list.filter { it.fingerprint != fingerprint }).take(MAX_LEDGER_ENTRIES)
        }
        return suppressed
    }

    /** The most-repeated content still inside the warning window, or null when nothing repeats. */
    fun repeatWarning(nowMs: Long): HeartbeatRepeatWarning? = entries.get()
        .filter { it.suppressedCount >= REPEAT_WARNING_THRESHOLD }
        .filter { nowMs - it.lastSeenEpochMs < REPEAT_WARNING_WINDOW_MS }
        .maxByOrNull { it.suppressedCount }
        ?.let {
            HeartbeatRepeatWarning(
                fingerprint = it.fingerprint,
                suppressedCount = it.suppressedCount,
                firstSeenEpochMs = it.firstSeenEpochMs,
                lastSeenEpochMs = it.lastSeenEpochMs,
            )
        }

    fun clear() = entries.set(emptyList())

    companion object {
        /** How long the same content is held back after being delivered once. */
        const val REPEAT_WINDOW_MS = 6 * 60 * 60 * 1000L

        /** Suppressions inside this window are what the UI warning counts. */
        const val REPEAT_WARNING_WINDOW_MS = 24 * 60 * 60 * 1000L

        /** Suppressions before the UI says the pulse is repeating itself. */
        const val REPEAT_WARNING_THRESHOLD = 3

        /** Bounded so a long-running install cannot grow the settings blob without limit. */
        private const val MAX_LEDGER_ENTRIES = 40
    }
}

/**
 * Content fingerprint for the anti-repeat ledger.
 *
 * Whitespace and case are normalised so formatting drift does not read as new content; the hash
 * is the JVM string hash, which is specified and identical on every Kotlin target, so the same
 * text fingerprints the same after a restart.
 */
internal fun heartbeatFingerprint(body: String): String {
    val normalized = body.trim().lowercase().replace(Regex("\\s+"), " ")
    return "${normalized.length}:${normalized.hashCode()}"
}
