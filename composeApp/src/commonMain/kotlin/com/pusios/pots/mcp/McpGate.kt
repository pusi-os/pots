// Owner confirmation for the MCP tools that change configuration: adding a server, removing one,
// and starting an authorization (which opens a browser and registers a client with a third-party
// authorization server). Modelled on VaultGate/AdbGate.
//
// Headless runs (heartbeat, scheduled task) have no chat surface to confirm in and are refused
// immediately: an unattended run must not silently wire the agent to a new remote server.

package com.pusios.pots.mcp

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
data class McpConfirmRequest(
    val id: String,
    val tool: String,
    val summary: String,
)

/** The confirmation half of the gate as the tools see it — a test confirms without a dialog. */
fun interface McpConfirmation {
    /** True when the owner allowed the action; false when they declined, timed out or ran headless. */
    suspend fun confirm(tool: String, summary: String): Boolean
}

@OptIn(ExperimentalUuidApi::class)
class McpGate(val confirmTimeout: Duration = DEFAULT_CONFIRM_TIMEOUT_SECONDS.seconds) : McpConfirmation {

    private val _pending = MutableStateFlow<McpConfirmRequest?>(null)

    /** The confirmation to render, or null. Collect in the chat UI. */
    val pending: StateFlow<McpConfirmRequest?> = _pending.asStateFlow()

    private var deferred: CompletableDeferred<Boolean>? = null

    override suspend fun confirm(tool: String, summary: String): Boolean {
        if (coroutineContext[HeadlessRunElement] != null) return false
        val request = McpConfirmRequest(Uuid.random().toString(), tool, summary.take(SUMMARY_LIMIT))
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

    companion object {
        /**
         * How long the owner has to answer. A gated MCP tool must set a
         * [com.pusios.pots.network.tools.Tool.timeout] longer than this window plus its own
         * network deadline: `ToolExecutor` wraps the call in `withTimeout(tool.timeout)`.
         */
        const val DEFAULT_CONFIRM_TIMEOUT_SECONDS = 120L
        private const val SUMMARY_LIMIT = 200
    }
}
