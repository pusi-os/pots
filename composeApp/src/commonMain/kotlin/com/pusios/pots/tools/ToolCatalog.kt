package com.pusios.pots.tools

import com.pusios.pots.mcp.McpTool
import com.pusios.pots.network.tools.Tool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Buckets the agent's tools are grouped into for lazy delivery.
 *
 * A category is what the model sees instead of a schema: an id, one line of prose and the
 * names inside. Deliberately coarse — the goal is to make 60 tools skimmable in ~15 lines,
 * not to classify perfectly. A tool matching nothing lands in [OTHER] and stays reachable
 * through `find_tools`.
 */
internal enum class ToolCategory(val id: String, val summary: String) {
    MEMORY("memory", "remember, recall and reinforce what you learned"),
    WEB("web", "search the web, fetch pages, open links, locate the user"),
    FILES("files", "notes, downloads and files on the device"),
    MEDIA("media", "generate images and videos, find and import photos"),
    EMAIL("email", "read, search, compose and send mail"),
    SMS("sms", "read and send text messages"),
    NOTIFICATIONS("notifications", "read and post device notifications"),
    TASKS("tasks", "scheduled tasks and heartbeat instructions"),
    CALENDAR("calendar", "calendar events and alarms"),
    CONTACTS("contacts", "search, create and call contacts"),
    MINI_APPS("mini-apps", "design and fill the user's mini-apps"),
    VAULT("vault", "password vault entries and secret placeholders"),
    ADB("adb", "Android devices over the local network"),
    SYSTEM("system", "shell, sandbox and SSH access"),
    MCP("mcp", "tools exposed by connected MCP servers"),
    OTHER("other", "everything else"),
}

private val TASK_NAMES = setOf("schedule_task", "list_tasks", "cancel_task")
private val MEDIA_NAMES = setOf(
    "generate_image",
    "generate_video",
    "list_media_models",
    "enhance_prompt",
    "search_photos",
    "import_photo",
)
private val FILE_NAMES = setOf("open_file", "save_to_downloads", "list_downloads", "vector_search_storage")
private val CALENDAR_NAMES = setOf(
    "create_calendar_event",
    "list_calendar_events",
    "update_calendar_event",
    "delete_calendar_event",
    "set_alarm",
)
private val WEB_NAMES = setOf("web_search", "fetch_url", "open_url", "get_location_from_ip")
private val SYSTEM_NAMES = setOf("execute_shell_command", "ask_sandbox_agent", "process_manager", "ssh_configure_host")

/**
 * Category of [tool]. MCP tools are recognised by type, built-ins by their schema name, so a
 * platform tool (Android-only calendar, desktop-only shell) needs no extra registration as
 * long as its name follows the family prefix.
 */
internal fun categoryOf(tool: Tool): ToolCategory {
    if (tool is McpTool) return ToolCategory.MCP
    val name = tool.schema.name
    return when {
        name.startsWith("vault_") -> ToolCategory.VAULT
        name.startsWith("adb_") -> ToolCategory.ADB
        name.startsWith("memory_") || name == "promote_learning" -> ToolCategory.MEMORY
        name.endsWith("_email") -> ToolCategory.EMAIL
        name.endsWith("_sms") -> ToolCategory.SMS
        name.contains("notification") -> ToolCategory.NOTIFICATIONS
        name in TASK_NAMES || name.startsWith("heartbeat") -> ToolCategory.TASKS
        name in MEDIA_NAMES -> ToolCategory.MEDIA
        name in FILE_NAMES || name.startsWith("obsidian_") -> ToolCategory.FILES
        name in CALENDAR_NAMES -> ToolCategory.CALENDAR
        name.startsWith("contact") -> ToolCategory.CONTACTS
        name in WEB_NAMES -> ToolCategory.WEB
        name == MiniAppDbTool.ID -> ToolCategory.MINI_APPS
        name in SYSTEM_NAMES -> ToolCategory.SYSTEM
        else -> ToolCategory.OTHER
    }
}

/** `name(required: string, optional?: number)` — what `find_tools` shows before a full schema. */
internal fun toolSignature(tool: Tool): String {
    val params = tool.schema.parameters.map { (name, param) ->
        if (param.required) "$name: ${param.type}" else "$name?: ${param.type}"
    }
    return "${tool.schema.name}(${params.joinToString(", ")})"
}

/**
 * Rough token estimate for a tool block. Providers tokenize JSON at roughly four characters
 * per token; the estimate only has to be good enough to compare eager and lazy delivery and
 * to log the saving, not to bill anything.
 */
internal fun estimateTokens(text: String): Int = (text.length + 3) / 4

/**
 * One schema in the shape the request sends (and `describe_tool` returns): name, description,
 * `type: object` parameters with `required`. Built by hand rather than with the serializer so
 * the estimate measures exactly what goes on the wire.
 */
internal fun toolSchemaJsonObject(tool: Tool): JsonObject = buildJsonObject {
    put("name", tool.schema.name)
    put("description", tool.schema.description)
    putJsonObject("parameters") {
        put("type", "object")
        putJsonObject("properties") {
            for ((paramName, param) in tool.schema.parameters) {
                val raw = param.rawSchema
                if (raw != null) {
                    put(paramName, raw)
                } else {
                    putJsonObject(paramName) {
                        put("type", param.type)
                        put("description", param.description)
                    }
                }
            }
        }
        putJsonArray("required") {
            tool.schema.parameters.filter { it.value.required }.keys.forEach { add(JsonPrimitive(it)) }
        }
    }
}

internal fun toolSchemaJson(tool: Tool): String = toolSchemaJsonObject(tool).toString()

internal fun estimateToolSetTokens(tools: List<Tool>): Int =
    estimateTokens(tools.joinToString(",") { toolSchemaJson(it) })

internal data class ToolGroup(val category: ToolCategory, val tools: List<Tool>)

/**
 * The whole enabled tool set plus the lookups the meta-tools need.
 *
 * Built per request from `getAvailableTools()`, so a master toggle switched off in settings
 * removes its tools here too: the model cannot find, describe or call what the user disabled.
 */
internal class ToolCatalog(val tools: List<Tool>) {

    private val byName: Map<String, Tool> = tools.associateBy { it.schema.name }

    fun find(name: String): Tool? = byName[name.trim()]

    fun groups(): List<ToolGroup> = tools
        .groupBy { categoryOf(it) }
        .map { (category, groupTools) -> ToolGroup(category, groupTools.sortedBy { it.schema.name }) }
        .sortedBy { it.category.ordinal }

    /**
     * Ranked by where a query word hits: an exact name beats a name prefix, which beats a
     * description word. Ties keep name order so results are stable between calls.
     */
    fun search(query: String, limit: Int = SEARCH_LIMIT): List<Tool> {
        val phrase = query.trim().lowercase()
        // ASCII classes on purpose: Kotlin/JS has no Unicode property escapes, and tool names
        // and descriptions are English, so a non-ASCII query simply yields no terms.
        val terms = phrase.split(Regex("[^a-z0-9_]+")).filter { it.length >= 2 }
        if (phrase.isEmpty() || terms.isEmpty()) return emptyList()
        return tools
            .mapNotNull { tool ->
                val name = tool.schema.name.lowercase()
                val description = tool.schema.description.lowercase()
                var score = 0
                if (name == phrase) score += 20
                if (name.contains(phrase)) score += 6
                for (term in terms) {
                    if (name.contains(term)) score += 4
                    // Two-letter words in a description ("by", "id") match half the catalogue;
                    // only a name hit is informative for those.
                    if (term.length >= 3 && description.contains(term)) score += 2
                }
                if (score == 0) null else tool to score
            }
            .sortedWith(compareByDescending<Pair<Tool, Int>> { it.second }.thenBy { it.first.schema.name })
            .take(limit)
            .map { it.first }
    }

    /** One line per category: id, count, summary and the tool names inside. */
    fun catalogue(): String = buildString {
        for (group in groups()) {
            append("- ")
            append(group.category.id)
            append(" (")
            append(group.tools.size)
            append(") — ")
            append(group.category.summary)
            append(": ")
            append(group.tools.joinToString(", ") { it.schema.name })
            append('\n')
        }
    }

    /** `list_tools`: the whole catalogue, or one category expanded with signatures. */
    fun renderList(categoryId: String?): String {
        if (categoryId == null) {
            return "${tools.size} tools in ${groups().size} categories:\n" + catalogue()
        }
        val group = groups().firstOrNull { it.category.id == categoryId.lowercase() }
        if (group == null) {
            return "Unknown category \"$categoryId\". Categories: " + groups().joinToString(", ") { it.category.id }
        }
        return buildString {
            append(group.category.id).append(" — ").append(group.category.summary).append(":\n")
            for (tool in group.tools) {
                append("- ").append(toolSignature(tool)).append(" — ").append(shortDescription(tool)).append('\n')
            }
            append("Call describe_tool with a name to load the full schema and enable the tool.")
        }
    }

    /** `find_tools` output: top matches with signature and a one-line description. */
    fun renderMatches(query: String, matches: List<Tool>): String {
        if (matches.isEmpty()) {
            return "No tool matched \"$query\". Call list_tools to see the categories, or try a broader word."
        }
        return buildString {
            append(matches.size).append(if (matches.size == 1) " match for \"" else " matches for \"")
            append(query).append("\":\n")
            for (tool in matches) {
                append("- ").append(toolSignature(tool)).append(" — ").append(shortDescription(tool)).append('\n')
            }
            append("Call describe_tool with a name to load the full schema and enable the tool.")
        }
    }

    /**
     * Compact catalogue rendered into the system prompt instead of full schemas. Names are
     * cheap (~6 tokens a tool) and save the model a `list_tools` round-trip; schemas are not.
     */
    fun promptBlock(): String = buildString {
        append(tools.size)
        append(" tools are enabled — too many to send with full schemas, so the context carries this index instead.\n")
        append(catalogue())
        append('\n')
        append("Call `find_tools` with a short query to find tools by name or description, then `describe_tool` ")
        append("to load one tool's full schema; describing also enables that tool for the rest of this conversation. ")
        append("`list_tools` prints this index again, optionally for a single category.")
    }

    private fun shortDescription(tool: Tool): String {
        val description = tool.schema.description.trim()
        val stop = description.indexOf(". ")
        val sentence = if (stop > 0) description.substring(0, stop) else description
        return if (sentence.length <= DESCRIPTION_LIMIT) sentence else sentence.take(DESCRIPTION_LIMIT - 1) + "…"
    }

    companion object {
        /** Enough to show a real choice without turning `find_tools` into a second catalogue. */
        const val SEARCH_LIMIT = 8
        private const val DESCRIPTION_LIMIT = 140
    }
}
