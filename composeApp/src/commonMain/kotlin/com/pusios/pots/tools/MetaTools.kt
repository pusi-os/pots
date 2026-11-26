package com.pusios.pots.tools

import com.pusios.pots.data.currentConversationIdOrNull
import com.pusios.pots.data.toolErrorJson
import com.pusios.pots.network.tools.ParameterSchema
import com.pusios.pots.network.tools.Tool
import com.pusios.pots.network.tools.ToolSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The three tools that stand in for the full list once it is too big to send: browse
 * (`list_tools`), search (`find_tools`), open (`describe_tool`).
 *
 * Only `describe_tool` changes state — it enables the described tool for the rest of the
 * conversation ([ToolActivation]), which is what lets the model call it on the next step even
 * though the tool was not in the request that produced the call.
 *
 * They are built per call over whatever the enabled set holds, so they are never part of
 * `getAvailableTools()`. Every place that answers "can this name be called?" has to add them
 * explicitly — [META_TOOL_NAMES] and [callableTools] exist for that.
 */
internal fun metaTools(catalog: ToolCatalog): List<Tool> = listOf(
    ListToolsTool(catalog),
    FindToolsTool(catalog),
    DescribeToolTool(catalog),
)

/** Names of [metaTools], for the callers that need the set without building the tools. */
internal val META_TOOL_NAMES: Set<String> = setOf(
    ListToolsTool.NAME,
    FindToolsTool.NAME,
    DescribeToolTool.NAME,
)

/**
 * Everything a tool call may resolve against: the enabled tools plus the three meta-tools built
 * over them.
 *
 * The request and the executor must agree on this universe. They did not once: the request
 * carried `list_tools` / `find_tools` / `describe_tool` while the executor looked names up in
 * `getAvailableTools()` only, so the model was told to use three tools that answered
 * "Unknown tool" — the whole lazy path was unusable. Building both from this one function is
 * what keeps them from drifting again.
 */
internal fun callableTools(available: List<Tool>): List<Tool> = if (available.isEmpty()) available else available + metaTools(ToolCatalog(available))

internal class ListToolsTool(private val catalog: ToolCatalog) : Tool {

    override val schema: ToolSchema = ToolSchema(
        name = NAME,
        description = "List the tools enabled in this conversation, grouped by category. " +
            "Pass a category id to see its tools with their parameter signatures.",
        parameters = mapOf(
            "category" to ParameterSchema(
                type = "string",
                description = "Optional category id, e.g. \"email\". Omit for the full index.",
                required = false,
            ),
        ),
    )

    override suspend fun execute(args: Map<String, Any>): Any {
        val category = (args["category"] as? String)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        return catalog.renderList(category)
    }

    companion object {
        const val NAME = "list_tools"
    }
}

internal class FindToolsTool(private val catalog: ToolCatalog) : Tool {

    override val schema: ToolSchema = ToolSchema(
        name = NAME,
        description = "Search the enabled tools by name and description. Returns the best matches " +
            "with their parameter signatures; use describe_tool to load one in full.",
        parameters = mapOf(
            "query" to ParameterSchema(
                type = "string",
                description = "What you want to do, in a few words — e.g. \"send email\" or \"generate image\".",
                required = true,
            ),
            "limit" to ParameterSchema(
                type = "number",
                description = "How many matches to return, default 8.",
                required = false,
            ),
        ),
    )

    override suspend fun execute(args: Map<String, Any>): Any {
        val query = (args["query"] as? String)?.trim().orEmpty()
        if (query.isEmpty()) return toolErrorJson("find_tools needs a non-empty \"query\".")
        val limit = (args["limit"] as? Number)?.toInt()?.coerceIn(1, 20) ?: ToolCatalog.SEARCH_LIMIT
        return catalog.renderMatches(query, catalog.search(query, limit))
    }

    companion object {
        const val NAME = "find_tools"
    }
}

internal class DescribeToolTool(private val catalog: ToolCatalog) : Tool {

    override val schema: ToolSchema = ToolSchema(
        name = NAME,
        description = "Load one tool's full JSON schema and enable it for this conversation. " +
            "Call it before using a tool you have only seen in the index or in search results.",
        parameters = mapOf(
            "name" to ParameterSchema(
                type = "string",
                description = "Exact tool name, as returned by list_tools or find_tools.",
                required = true,
            ),
        ),
    )

    override suspend fun execute(args: Map<String, Any>): Any {
        val name = (args["name"] as? String)?.trim().orEmpty()
        if (name.isEmpty()) return toolErrorJson("describe_tool needs a non-empty \"name\".")
        val tool = catalog.find(name)
            ?: return toolErrorJson(
                "Tool \"$name\" is not enabled in this conversation. " +
                    "Call list_tools or find_tools to see what is available.",
            )
        ToolActivation.activate(currentConversationIdOrNull(), tool.schema.name)
        return describeJson(tool)
    }

    private fun describeJson(tool: Tool): String = buildJsonObject {
        put("category", categoryOf(tool).id)
        put("enabled", true)
        toolSchemaJsonObject(tool).forEach { (key, value) -> put(key, value) }
    }.toString()

    companion object {
        const val NAME = "describe_tool"
    }
}
