// The step log every ADB attempt writes to: one line per decision (endpoint, TCP, CNXN, reply
// kind, waiting for the owner, verdict), kept in a ring buffer so the settings screen and the
// agent's adb_diagnose tool can show what happened without a logcat session.
//
// The buffer is a singleton because the backend (androidMain), the view model and the agent tools
// all need the same lines, and it is a StateFlow so the screen can redraw without polling.

@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.pusios.pots.adb

import com.pusios.pots.media.MediaLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Clock

/** One ADB step: which part of the handshake spoke, and what it said. */
data class AdbLogLine(val epochMs: Long, val step: String, val message: String) {
    override fun toString(): String = "$step: $message"
}

/**
 * The last [CAPACITY] ADB steps, mirrored to the platform log under the `PotsAdb` tag.
 *
 * A ring buffer is enough: the question this answers is "what did the last connection attempt do",
 * and the useful window is one attempt, not a day of history. `record` is safe to call from any
 * thread — the state update is a compare-and-set loop — because the backend logs from the socket
 * thread while the screen reads on the main thread.
 */
object PotsAdbLog {
    /** The logcat tag; `adb logcat -s PotsAdb` is the whole debugging recipe. */
    const val TAG = "PotsAdb"

    /** How many lines the buffer keeps. One connect attempt writes about ten. */
    const val CAPACITY = 200

    private val _lines = MutableStateFlow<List<AdbLogLine>>(emptyList())

    /** The buffer as UI state, newest last. */
    val lines: StateFlow<List<AdbLogLine>> = _lines.asStateFlow()

    /** Appends one step and writes it to the platform log. */
    fun step(step: String, message: String) {
        val line = AdbLogLine(Clock.System.now().toEpochMilliseconds(), step, message)
        _lines.update { current -> (current + line).takeLast(CAPACITY) }
        MediaLog.d("$step: $message", TAG)
    }

    /** The current buffer, oldest first. */
    fun snapshot(): List<AdbLogLine> = _lines.value

    /** The last [count] lines, for a tool result that must stay small. */
    fun tail(count: Int = 20): List<AdbLogLine> = _lines.value.takeLast(count.coerceAtLeast(0))

    /** Empties the buffer; the settings screen calls it when the owner asks for a clean log. */
    fun clear() {
        _lines.value = emptyList()
    }
}
