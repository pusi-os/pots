// The redirect half of MCP authorization (RFC 8252 §7.3): a one-shot loopback listener the
// authorization server redirects back to.
//
// Loopback is chosen over a custom scheme because it needs no manifest intent-filter and no
// Activity plumbing, and every AS in the MCP ecosystem registers native clients with an
// `http://127.0.0.1:<port>/…` redirect. Platforms without a loopback HTTP server return null
// from [startMcpAuthListener]; there the app offers the manual token field instead.

package com.pusios.pots.mcp

import kotlin.time.Duration

/** The query parameters an OAuth redirect came back with. Exactly one of code/error is set. */
data class McpAuthCallback(
    val code: String? = null,
    val state: String? = null,
    val error: String? = null,
    val errorDescription: String? = null,
)

/** A listener bound to one authorize attempt. [close] is safe to call more than once. */
interface McpAuthListener {
    /** `http://127.0.0.1:<port>/<path>` — registered as `redirect_uri` and used in the URL. */
    val redirectUri: String

    /** Suspends until the browser comes back; null when the owner never finishes. */
    suspend fun await(timeout: Duration): McpAuthCallback?

    fun close()
}

/**
 * Starts a loopback listener on an ephemeral port, or returns null where the platform has no
 * HTTP server (iOS, web). Callers must [McpAuthListener.close] on every path.
 */
expect fun startMcpAuthListener(path: String = "callback"): McpAuthListener?
