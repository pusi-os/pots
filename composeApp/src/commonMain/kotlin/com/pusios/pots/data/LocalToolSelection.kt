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
         * Hard ceiling on the number of tools a single on-device request carries.
         *
         * Small Gemma/LFM bundles degrade sharply past a handful of tool schemas: every extra
         * schema is prompt bytes competing for a 4K window, and litert-lm's ANTLR function-call
         * parser starts rejecting the model's output (INVALID_ARGUMENT from JNI) once the model
         * loses track of the argument shapes. Eight is what the default chat set already needs,
         * so the ceiling trims opt-in extras (MCP servers) instead of the everyday tools.
         */
        const val MAX_TOOLS = 8

        /**
         * Built-in tools exposed to the on-device model when the user has not configured a
         * selection, in the order they are kept when [MAX_TOOLS] trims the set.
         *
         * A name list rather than a structural filter, because small Gemma models hit
         * litert-lm's strict ANTLR function-call parser hard on anything more complex than a
         * couple of string parameters. Excluded by default: `memory_learn` (4 params + enum),
         * the scheduling tools (datetime + cron), the email family and the heartbeat config
         * tools. The user can widen this in the settings.
         */
        val CHAT_PRIORITY_TOOL_IDS: List<String> = listOf(
            "get_local_time",
            "web_search",
            "open_url",
            "memory_store",
            "memory_forget",
            "memory_reinforce",
            "get_location_from_ip",
            "execute_shell_command",
        )

        /** The default set, kept as a set for membership tests against schema names. */
        val DEFAULT_TOOL_IDS: Set<String> = CHAT_PRIORITY_TOOL_IDS.toSet()

        /**
         * Model ids known to do template-level (XML) tool calling even though the LiteRT
         * bundle does not set `ModelInfo.supportsFunctionCalling`.
         *
         * LiquidAI's LFM bundles are the case this exists for. Everything else has to declare
         * the capability in its metadata: a model whose chat template has no tool section does
         * not ignore the tools it is handed — it invents the answer a tool would have produced,
         * which is how Qwen3 0.6B reports a fictional time instead of calling `get_local_time`.
         */
        val TEMPLATE_LEVEL_TOOL_CALLING_MODEL_IDS: Set<String> = setOf(
            "lfm2.5-1.2b-instruct",
            "lfm3-1.7b-int4",
            "lfm3-omni-2b-int8",
        )

        /**
         * Resolves a stored selection into the one that is actually in force.
         *
         * An unconfigured selection falls back to [DEFAULT_TOOL_IDS] and to *no* MCP servers:
         * MCP tools are opt-in per server, because a connected server can add dozens of
         * deeply-nested schemas that a 4K on-device window and the ANTLR parser cannot carry.
         */
        fun effective(selection: LocalToolSelection): LocalToolSelection = if (selection.configured) {
            selection
        } else {
            LocalToolSelection(
                configured = false,
                toolIds = CHAT_PRIORITY_TOOL_IDS,
                mcpServerIds = emptyList(),
            )
        }

        /**
         * Whether the on-device model should be handed tools at all.
         *
         * Metadata first: a bundle that declares [supportsFunctionCalling] gets tools, one that
         * declares the opposite gets none, and a bundle that says nothing (null — an old
         * `.litertlm`, an unreadable metadata section, or a platform without the probe) is
         * treated as incapable unless its id is in [TEMPLATE_LEVEL_TOOL_CALLING_MODEL_IDS].
         * The previous rule ("hand tools whenever any are selected") is what put tool schemas
         * in front of models that could only fabricate their output.
         */
        fun modelSupportsToolCalling(supportsFunctionCalling: Boolean?, modelId: String): Boolean = supportsFunctionCalling == true || modelId in TEMPLATE_LEVEL_TOOL_CALLING_MODEL_IDS

        /**
         * Trims [names] to at most [MAX_TOOLS] entries, keeping the chat-priority tools first.
         *
         * Selection order is preserved inside each group, so a user who picked six MCP tools
         * and then two built-ins keeps the built-ins. Names the priority list does not know
         * are kept in their original order after the priority ones.
         */
        fun capToolNames(names: List<String>): List<String> {
            if (names.size <= MAX_TOOLS) return names
            val priority = CHAT_PRIORITY_TOOL_IDS.mapNotNull { id -> names.firstOrNull { it == id } }
            val rest = names.filterNot { it in CHAT_PRIORITY_TOOL_IDS }
            return (priority + rest).take(MAX_TOOLS)
        }
    }
}
