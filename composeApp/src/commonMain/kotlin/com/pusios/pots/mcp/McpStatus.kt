// What the app remembers about an MCP server between connect attempts.
//
// This is persisted on purpose. The bug behind 3.21.1 was that a failed connect deleted the
// only copy of the server's tool list, so the server vanished from the agent's prompt and the
// UI showed a red dot with no reason. Configuration, tool cache and live connection are three
// different things: the first two survive a failure, the third is rebuilt on demand.

package com.pusios.pots.mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Why a connect attempt failed, derived from HTTP semantics only — that is what separates
 * "log in again" from "the server is down".
 */
enum class McpFailureKind {
    /** 401, or a stored token that expired and could not be refreshed. */
    NeedsAuth,

    /** 403 with `error="insufficient_scope"`: the token is valid but lacks a scope. */
    NeedsScope,

    /** 403 without a scope challenge. */
    Forbidden,

    /** Timeout, DNS, TLS, no route. */
    Unreachable,

    /** 5xx from the server. */
    ServerError,

    /** 404/405 — usually the SSE/Streamable-HTTP endpoint mismatch. */
    WrongEndpoint,

    /** A 2xx whose body is not a JSON-RPC response, or a JSON-RPC error. */
    ProtocolError,

    Unknown,
}

/** One failed attempt: kind, HTTP status when there was one, and a redacted one-line detail. */
@Serializable
data class McpFailure(
    val kind: McpFailureKind = McpFailureKind.Unknown,
    val httpStatus: Int? = null,
    val detail: String = "",
    val atEpochMs: Long = 0,
)

/** A tool as `tools/list` returned it, kept so the agent sees the server while it is down. */
@Serializable
data class McpCachedTool(
    val name: String,
    val description: String = "",
    val inputSchema: JsonObject? = null,
)

/** Last known state of one server plus its tool cache. */
@Serializable
data class McpServerStatus(
    val state: McpServerStateKind = McpServerStateKind.Unknown,
    val failure: McpFailure? = null,
    /** From the last successful `tools/list`; deliberately kept across failures. */
    val tools: List<McpCachedTool> = emptyList(),
    val toolsFetchedAtEpochMs: Long = 0,
    val updatedAtEpochMs: Long = 0,
) {
    val toolCount: Int get() = tools.size
}

@Serializable
enum class McpServerStateKind {
    /** Never connected and nothing persisted yet. */
    Unknown,
    Disabled,
    Connecting,
    /** Reachable but refuses us until the owner authorizes. */
    NeedsAuth,
    Connected,
    Failed,
}

/** Turns an HTTP failure into the kind the UI shows. */
internal fun classifyHttpFailure(status: Int, wwwAuthenticate: String?): McpFailureKind = when {
    status == 401 -> McpFailureKind.NeedsAuth
    status == 403 && wwwAuthenticate?.contains("insufficient_scope", ignoreCase = true) == true ->
        McpFailureKind.NeedsScope
    status == 403 -> McpFailureKind.Forbidden
    status == 404 || status == 405 -> McpFailureKind.WrongEndpoint
    status in 500..599 -> McpFailureKind.ServerError
    else -> McpFailureKind.Unknown
}
