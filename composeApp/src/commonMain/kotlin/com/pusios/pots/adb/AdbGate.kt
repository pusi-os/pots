// Owner confirmation for the ADB tools that change a device: any non-read-only shell command,
// every install, every uninstall. The master toggle is AppSettings' `adb_tools`; this gate is the
// per-action half of the same policy, modelled on VaultGate.
// Headless runs (heartbeat, scheduled task) have no chat surface to confirm in and are refused
// immediately.

package com.pusios.pots.adb

import com.pusios.pots.data.HeadlessRunElement
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** What the confirmation dialog shows: which tool, and a one-line summary of the action. */
data class AdbConfirmRequest(
    val id: String,
    val tool: String,
    val summary: String,
)

/** The confirmation half of the gate, as the tools see it — a test confirms without a dialog. */
fun interface AdbConfirmation {
    /** True when the owner allowed the action; false when they declined, timed out or ran headless. */
    suspend fun confirm(tool: String, summary: String): Boolean
}

@OptIn(ExperimentalUuidApi::class)
class AdbGate(private val confirmTimeout: Duration = 120.seconds) : AdbConfirmation {

    private val _pending = MutableStateFlow<AdbConfirmRequest?>(null)

    /** The confirmation to render, or null. Collect in the chat UI. */
    val pending: StateFlow<AdbConfirmRequest?> = _pending.asStateFlow()

    private var deferred: CompletableDeferred<Boolean>? = null

    override suspend fun confirm(tool: String, summary: String): Boolean {
        if (coroutineContext[HeadlessRunElement] != null) return false
        val request = AdbConfirmRequest(Uuid.random().toString(), tool, summary.take(SUMMARY_LIMIT))
        _pending.value = request
        val waiting = CompletableDeferred<Boolean>()
        deferred = waiting
        try {
            return withTimeoutOrNull(confirmTimeout) { waiting.await() } ?: false
        } finally {
            if (_pending.value?.id == request.id) _pending.value = null
            deferred = null
        }
    }

    /** Called from the confirmation dialog. */
    fun respond(requestId: String, allowed: Boolean) {
        if (_pending.value?.id == requestId) deferred?.complete(allowed)
    }

    private companion object {
        const val SUMMARY_LIMIT = 200
    }
}