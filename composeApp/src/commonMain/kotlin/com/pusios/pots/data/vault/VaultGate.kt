// Encrypted vault: per-use owner confirmation for the tools that spend a secret outside
// the app (`vault_http_request`, `vault_form_fill`). The master toggle lives in
// AppSettings (`VaultTools.MASTER_ID`); this gate asks the owner before every use.
// Headless runs (heartbeat, schedule) are refused immediately.

package com.pusios.pots.data.vault

import com.pusios.pots.data.HeadlessRunElement
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/** What the confirmation dialog shows: which tool, and a one-line summary of the action. */
data class VaultConfirmRequest(
    val id: String,
    val tool: String,
    val summary: String,
)

class VaultGate(private val confirmTimeout: Duration = 120.seconds) {

    private val _pending = MutableStateFlow<VaultConfirmRequest?>(null)

    /** The confirmation to render, or null. Collect in the chat UI. */
    val pending: StateFlow<VaultConfirmRequest?> = _pending.asStateFlow()

    private var deferred: CompletableDeferred<Boolean>? = null

    /** Called by the tool before it touches a secret. False = owner declined / timed out / headless. */
    suspend fun confirm(tool: String, summary: String): Boolean {
        if (coroutineContext[HeadlessRunElement] != null) return false
        val request = VaultConfirmRequest(newVaultFormId(), tool, summary.take(SUMMARY_LIMIT))
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