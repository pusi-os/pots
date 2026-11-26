package com.pusios.pots.tools

import com.pusios.pots.data.ToolExecutor
import com.pusios.pots.network.tools.ParameterSchema
import com.pusios.pots.network.tools.Tool
import com.pusios.pots.network.tools.ToolSchema
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The invariant lazy delivery rests on: **every name the request carries must be callable**.
 *
 * 3.21.1 shipped the opposite — the request carried `list_tools`, `find_tools` and
 * `describe_tool` while the executor resolved names against `getAvailableTools()`, which never
 * contains them. Every call to those three answered "Unknown tool", so the model could not look
 * up an MCP server or start an authorization: the whole feature was unusable and the tests did
 * not notice, because each of them injected its own `toolsProvider` instead of building one the
 * way the app does.
 *
 * These tests build the executor exactly the way the app does — through [callableTools] — so the
 * wiring, not just the pieces, is what is being checked.
 */
class ToolCallabilityTest {

    @Test
    fun `every tool a request carries can be executed`() = runBlocking {
        val available = SAMPLE_TOOLS.map { fakeTool(it) }
        val callable = callableTools(available).mapTo(mutableSetOf()) { it.schema.name }

        val plan = ToolDelivery.plan(available)
        assertEquals(ToolDeliveryMode.LAZY, plan.mode, "the sample set must exercise the lazy path")

        val carried = plan.delivered.map { it.schema.name }
        val missing = carried.filterNot { it in callable }
        assertTrue(missing.isEmpty(), "the request carries tools nothing can run: $missing")
    }

    @Test
    fun `executor runs list_tools instead of answering Unknown tool`() = runBlocking {
        val executor = ToolExecutor { callableTools(SAMPLE_TOOLS.map { fakeTool(it) }) }

        val result = executor.executeTool(ListToolsTool.NAME, "{}", "callability-list")

        assertTrue(!result.contains("Unknown tool"), result)
        assertTrue(result.contains("web"), "the index should name a category: $result")
    }

    @Test
    fun `executor runs find_tools and describe_tool`() = runBlocking {
        val conversation = "callability-find"
        val executor = ToolExecutor { callableTools(SAMPLE_TOOLS.map { fakeTool(it) }) }
        ToolActivation.recordDelivered(conversation, setOf("web_search"))

        val found = executor.executeTool(FindToolsTool.NAME, """{"query":"search the web"}""", conversation)
        assertTrue(found.contains("web_search"), found)

        val described = executor.executeTool(DescribeToolTool.NAME, """{"name":"vault_reveal"}""", conversation)
        assertTrue(!described.contains("Unknown tool"), described)
        assertTrue(described.contains("vault"), described)
        assertTrue(ToolActivation.canExecute(conversation, "vault_reveal"), "describe_tool must enable the tool")
    }

    @Test
    fun `meta-tools are exempt from the delivery gate`() = runBlocking {
        val conversation = "callability-gate"
        val executor = ToolExecutor { callableTools(SAMPLE_TOOLS.map { fakeTool(it) }) }
        // A lazy request that carried neither meta-tools nor vault_reveal (eager plan of a small set).
        ToolActivation.recordDelivered(conversation, setOf("web_search"))

        val result = executor.executeTool(ListToolsTool.NAME, "{}", conversation)

        assertTrue(!result.contains("was not loaded"), "meta-tools must always run: $result")
    }

    @Test
    fun `unknown tool points the model at find_tools`() = runBlocking {
        val executor = ToolExecutor { callableTools(SAMPLE_TOOLS.map { fakeTool(it) }) }

        val result = executor.executeTool("walt_search", "{}", "callability-unknown")

        assertTrue(result.contains("Unknown tool"), result)
        assertTrue(result.contains("find_tools"), "the miss must say how to recover: $result")
    }

    @Test
    fun `MCP administration tools ride along in lazy mode`() {
        val tools = (SAMPLE_TOOLS + MCP_ADMIN_TOOLS).map { fakeTool(it) }

        val plan = ToolDelivery.plan(tools)

        assertEquals(ToolDeliveryMode.LAZY, plan.mode)
        val names = plan.delivered.map { it.schema.name }.toSet()
        val missing = MCP_ADMIN_TOOLS.map { it.name }.filterNot { it in names }
        assertTrue(missing.isEmpty(), "the only way to fix an MCP server was not delivered: $missing")
    }

    private fun fakeTool(spec: ToolSpec): Tool = object : Tool {
        override val schema = ToolSchema(
            name = spec.name,
            description = spec.description,
            parameters = mapOf(
                spec.param to ParameterSchema(type = "string", description = "Input for ${spec.name}", required = true),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any = "done"
    }

    private data class ToolSpec(val name: String, val description: String, val param: String = "input")

    private companion object {
        val MCP_ADMIN_TOOLS = listOf(
            ToolSpec("mcp_list_servers", "List every configured MCP server with its state", "filter"),
            ToolSpec("mcp_add_server", "Add an MCP server by name and URL", "url"),
            ToolSpec("mcp_remove_server", "Remove a configured MCP server", "server_id"),
            ToolSpec("mcp_set_enabled", "Enable or disable a configured MCP server", "server_id"),
            ToolSpec("mcp_refresh", "Reconnect a server and refresh its tool list", "server_id"),
            ToolSpec("mcp_authorize", "Start the OAuth sign-in for a server that needs it", "server_id"),
        )

        /** Big enough to cross the lazy threshold, small enough to read. */
        val SAMPLE_TOOLS = listOf(
            ToolSpec("web_search", "Search the web and return the most relevant pages with snippets", "query"),
            ToolSpec("fetch_url", "Fetch a web page or file by URL and return its readable text", "url"),
            ToolSpec("get_local_time", "Return the current local time, date and time zone", "format"),
            ToolSpec("memory_store", "Remember a fact about the user for later turns", "content"),
            ToolSpec("open_url", "Open a URL in the system browser", "url"),
            ToolSpec("search_email", "Search the mailbox by sender, subject or body", "query"),
            ToolSpec("generate_image", "Generate or edit an image from a prompt", "prompt"),
            ToolSpec("schedule_task", "Schedule a prompt to run later or on a cron", "prompt"),
            ToolSpec("search_photos", "Search the device photo library by filename", "query"),
            ToolSpec("adb_shell", "Run a read-only shell command on a paired Android device", "command"),
            ToolSpec("vault_list", "List password vault entries by name and URL", "filter"),
            ToolSpec("vault_reveal", "Reveal one vault field to the owner after confirmation", "item"),
        )
    }
}
