package com.pusios.pots.data

import kotlinx.serialization.Serializable

/**
 * Which tools and MCP servers the on-device (local) model is allowed to call.
 *
 * The on-device path used to work from a hardcoded allowlist, which made the set of tools a
 * local model could reach impossible to change without a new release — and excluded MCP tools
 * entirely. This selection makes it user-configurable.
 *
 * [configured] distinguishes "the user has not touched the setting" from "the user selected
 * nothing". Without it an empty selection would be indistinguishable from an unset one, and
 * there would be no way to switch the local model off from tools altogether.
 */
@Serializable
data class LocalToolSelection(
    /** True once the user has made an explicit choice. */
    val configured: Boolean = false,
    /** Ids of the built-in tools the local model may call. */
    val toolIds: List<String> = emptyList(),
    /** Ids of the MCP servers whose tools the local model may call. */
    val mcpServerIds: List<String> = emptyList(),
) {
    companion object {
        /**
         * Built-in tools exposed to the on-device model when the user has not configured a
         * selection.
         *
         * A name list rather than a structural filter, because small Gemma models hit
         * litert-lm's strict ANTLR function-call parser hard on anything more complex than a
         * couple of string parameters. Excluded by default: `memory_learn` (4 params + enum),
         * the scheduling tools (datetime + cron), the email family and the heartbeat config
         * tools. The user can widen this in the settings.
         */
        val DEFAULT_TOOL_IDS: Set<String> = setOf(
            "get_local_time",
            "get_location_from_ip",
            "web_search",
            "open_url",
            "memory_store",
            "memory_forget",
            "memory_reinforce",
            "execute_shell_command",
        )

        /**
         * Resolves a stored selection into the one that is actually in force.
         *
         * An unconfigured selection falls back to [DEFAULT_TOOL_IDS] and to every enabled MCP
         * server; the caller supplies the enabled server ids because they live in the MCP
         * manager, not in the settings blob.
         */
        fun effective(
            selection: LocalToolSelection,
            enabledMcpServerIds: Set<String> = emptySet(),
        ): LocalToolSelection = if (selection.configured) {
            selection
        } else {
            LocalToolSelection(
                configured = false,
                toolIds = DEFAULT_TOOL_IDS.toList(),
                mcpServerIds = enabledMcpServerIds.toList(),
            )
        }
    }
}
