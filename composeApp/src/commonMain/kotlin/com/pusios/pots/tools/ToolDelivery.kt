package com.pusios.pots.tools

import com.pusios.pots.isDebugBuild
import com.pusios.pots.network.tools.Tool

internal enum class ToolDeliveryMode {
    /** Every enabled tool goes on the wire with its full schema — the pre-lazy behaviour. */
    EAGER,

    /** Meta-tools plus a small hot set; everything else is pulled in on demand. */
    LAZY,
}

internal data class ToolDeliveryPlan(
    val mode: ToolDeliveryMode,
    val delivered: List<Tool>,
    val catalog: ToolCatalog,
    val fullTokens: Int,
    val deliveredTokens: Int,
)

/**
 * Decides what one request carries: the whole tool set, or three meta-tools plus the few tools
 * the model is most likely to call immediately.
 *
 * The threshold is about the prompt, not the count: below [EAGER_MAX_TOOLS] tools and
 * [EAGER_MAX_TOKENS] estimated tokens there is nothing to save, so the set stays eager and
 * behaves exactly as before. Above it, schemas are what cost the ~10K tokens measured on this
 * app's 60-tool set, and they are also the least useful part of the prompt for a request that
 * will not call a tool at all.
 *
 * Two things are always delivered eagerly:
 * - tools the model already pulled in with `describe_tool` ([activatedNames]) — it asked for
 *   them, and re-asking every turn would be pure overhead;
 * - [HOT_TOOL_NAMES] — the handful a chat reaches for without thinking first. Only the
 *   cloud path uses this plan: the on-device engine owns its own tool loop and cannot extend
 *   the tool list mid-turn, so it stays eager (its user-chosen selection is the filter there).
 *
 * Cost: [plan] rebuilds the catalogue and re-serialises every schema to estimate tokens —
 * ~1.4 ms on 40 tools, ~1.9 ms on 60 (measured on the desktop JVM on the RPi5). The two call
 * sites — the system-prompt index once per turn and `toolsForRequest` once per round — stay
 * far below one network round trip, so no cache is worth its invalidation: the plan depends
 * on the activated set, which changes inside a turn when the model calls `describe_tool`.
 */
internal object ToolDelivery {

    const val EAGER_MAX_TOOLS = 8
    const val EAGER_MAX_TOKENS = 8_000

    /** Ceiling on eagerly re-delivered activated tools, so a chatty turn cannot undo the saving. */
    private const val EAGER_ACTIVATED_MAX = 20

    /**
     * Tools sent with a full schema even in lazy mode: cheap, parameter-light and used in
     * ordinary conversation far more often than anything else.
     */
    val HOT_TOOL_NAMES = listOf("web_search", "fetch_url", "get_local_time", "memory_store", "open_url")

    /**
     * The six `mcp_*` administration tools, which are the only way to fix a server that needs
     * authorization — and exactly the tools a user asks for when a server is not working. They
     * were hidden by lazy delivery in 3.21.1: the model could see the MCP category in the index,
     * had no schema to call, and answered "Unknown tool". They ride along whenever the set
     * contains them at all, i.e. when MCP administration is compiled in for this platform.
     */
    val MCP_ADMIN_TOOL_NAMES = setOf(
        "mcp_list_servers",
        "mcp_add_server",
        "mcp_remove_server",
        "mcp_set_enabled",
        "mcp_refresh",
        "mcp_authorize",
    )

    fun plan(available: List<Tool>, activatedNames: Set<String> = emptySet()): ToolDeliveryPlan {
        val catalog = ToolCatalog(available)
        val fullTokens = estimateToolSetTokens(available)
        if (available.size <= EAGER_MAX_TOOLS && fullTokens <= EAGER_MAX_TOKENS) {
            return ToolDeliveryPlan(ToolDeliveryMode.EAGER, available, catalog, fullTokens, fullTokens)
        }

        val activated = available.filter { it.schema.name in activatedNames }.take(EAGER_ACTIVATED_MAX)
        val admin = available.filter { it.schema.name in MCP_ADMIN_TOOL_NAMES }
        val hot = available.filter { it.schema.name in HOT_TOOL_NAMES }
        val extras = (activated + admin + hot).distinctBy { it.schema.name }
        val delivered = metaTools(catalog) + extras
        return ToolDeliveryPlan(
            mode = ToolDeliveryMode.LAZY,
            delivered = delivered,
            catalog = catalog,
            fullTokens = fullTokens,
            deliveredTokens = estimateToolSetTokens(delivered),
        )
    }

    /** Debug-build breadcrumb: what the request carried and what the lazy index saved. */
    fun log(plan: ToolDeliveryPlan) {
        if (!isDebugBuild) return
        println(
            "[Tools] delivery=${plan.mode} enabled=${plan.catalog.tools.size} " +
                "sent=${plan.delivered.size} tokens≈${plan.deliveredTokens}/${plan.fullTokens}",
        )
    }
}
