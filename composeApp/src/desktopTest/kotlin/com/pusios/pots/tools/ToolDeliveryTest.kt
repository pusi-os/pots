package com.pusios.pots.tools

import com.pusios.pots.data.ConversationIdElement
import com.pusios.pots.data.ToolExecutor
import com.pusios.pots.data.providers.sanitizeToolMessages
import com.pusios.pots.network.dtos.openaicompatible.OpenAICompatibleChatRequestDto.FunctionCall
import com.pusios.pots.network.dtos.openaicompatible.OpenAICompatibleChatRequestDto.Message
import com.pusios.pots.network.dtos.openaicompatible.OpenAICompatibleChatRequestDto.ToolCall
import com.pusios.pots.network.tools.ParameterSchema
import com.pusios.pots.network.tools.Tool
import com.pusios.pots.network.tools.ToolSchema
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Lazy delivery has to buy real context without making a tool unreachable: a large set ships an
 * index instead of every schema, search still finds the right tool from a description, and the
 * executor refuses a call the model was never given — until `describe_tool` enables it.
 */
class ToolDeliveryTest {

    @Test
    fun `small set is delivered eagerly as before`() {
        val tools = FORTY_TOOLS.take(5).map { fakeTool(it) }

        val plan = ToolDelivery.plan(tools)

        assertEquals(ToolDeliveryMode.EAGER, plan.mode)
        assertEquals(tools.map { it.schema.name }, plan.delivered.map { it.schema.name })
        assertFalse(plan.delivered.any { it.schema.name in META_TOOL_NAMES }, "no meta-tools below the threshold")
    }

    @Test
    fun `forty tools cost less than forty percent of the full block`() {
        val tools = FORTY_TOOLS.map { fakeTool(it) }

        val plan = ToolDelivery.plan(tools)

        assertEquals(ToolDeliveryMode.LAZY, plan.mode)
        assertTrue(
            plan.deliveredTokens * 100 / plan.fullTokens < 40,
            "lazy delivery sent ${plan.deliveredTokens} of ${plan.fullTokens} tokens",
        )
        val names = plan.delivered.map { it.schema.name }
        assertTrue(names.containsAll(META_TOOL_NAMES), "meta-tools: $names")
        // The index the prompt carries must be cheaper than the schemas it replaces, otherwise
        // the whole exercise is a wash.
        assertTrue(
            estimateTokens(plan.catalog.promptBlock()) < plan.fullTokens / 4,
            "index ${estimateTokens(plan.catalog.promptBlock())} vs full ${plan.fullTokens}",
        )
    }

    @Test
    fun `find_tools locates a tool from its description`() = runBlocking {
        val finder = FindToolsTool(ToolCatalog(FORTY_TOOLS.map { fakeTool(it) }))

        val result = finder.execute(mapOf("query" to "photo library by filename")) as String

        val firstMatch = result.lines().firstOrNull { it.startsWith("- ") }.orEmpty()
        assertTrue(firstMatch.startsWith("- search_photos("), "first match should be search_photos:\n$result")
        assertTrue(result.contains("describe_tool"), "result must tell the model how to open the tool")
    }

    @Test
    fun `find_tools returns a readable miss`() = runBlocking {
        val finder = FindToolsTool(ToolCatalog(FORTY_TOOLS.map { fakeTool(it) }))

        val result = finder.execute(mapOf("query" to "zzzz")) as String

        assertTrue(result.contains("No tool matched"), result)
        assertTrue(result.contains("list_tools"), result)
    }

    @Test
    fun `describe_tool enables the tool for the conversation and it is re-delivered`() = runBlocking {
        val conversation = "describe-activation"
        val tools = FORTY_TOOLS.map { fakeTool(it) }
        val describer = DescribeToolTool(ToolCatalog(tools))
        ToolActivation.recordDelivered(conversation, setOf("web_search"))

        assertFalse(ToolActivation.canExecute(conversation, "vault_reveal"), "not delivered, not activated")

        val json = withContext(ConversationIdElement(conversation)) {
            describer.execute(mapOf("name" to "vault_reveal")) as String
        }

        assertTrue(json.contains("\"category\":\"vault\""), json)
        assertTrue(json.contains("\"required\":[\"item\"]"), json)
        assertTrue(ToolActivation.canExecute(conversation, "vault_reveal"))
        ToolActivation.recordDelivered("another-conversation", emptySet())
        assertFalse(ToolActivation.canExecute("another-conversation", "vault_reveal"), "activation is per conversation")

        val plan = ToolDelivery.plan(tools, ToolActivation.activatedNames(conversation))
        assertTrue(plan.delivered.any { it.schema.name == "vault_reveal" }, "activated tool comes back in full")
    }

    @Test
    fun `describe_tool reports a tool that is not enabled`() = runBlocking {
        val describer = DescribeToolTool(ToolCatalog(listOf(fakeTool(FORTY_TOOLS.first()))))

        val json = describer.execute(mapOf("name" to "adb_shell")) as String

        assertTrue(json.contains("not enabled"), json)
        assertTrue(json.contains("list_tools"), json)
    }

    @Test
    fun `executor refuses a tool the request did not carry until it is described`() = runBlocking {
        val conversation = "executor-gate"
        val tools = listOf(
            fakeTool(ToolSpec("vault_reveal", "Reveal one vault field to the owner after confirmation", "item")),
            fakeTool(ToolSpec("web_search", "Search the web", "query")),
        )
        val executor = ToolExecutor { tools }
        ToolActivation.recordDelivered(conversation, setOf("web_search"))

        val refused = executor.executeTool("vault_reveal", "{}", conversation)
        assertTrue(refused.contains("not loaded"), refused)
        assertTrue(refused.contains("describe_tool"), refused)

        ToolActivation.activate(conversation, "vault_reveal")
        assertEquals("done", executor.executeTool("vault_reveal", "{}", conversation))
    }

    @Test
    fun `gate refusal reaches the model on the next round instead of being sanitized away`() = runBlocking {
        val conversation = "refusal-round-trip"
        val tools = FORTY_TOOLS.map { fakeTool(it) }
        val executor = ToolExecutor { tools }

        // Round 1: the request carries the lazy set, and the model calls a tool it only saw in
        // the prompt index — its schema was never on the wire.
        ToolActivation.recordDelivered(
            conversation,
            ToolDelivery.plan(tools).delivered.mapTo(mutableSetOf()) { it.schema.name },
        )
        val refusal = executor.executeTool("vault_reveal", "{}", conversation)
        assertTrue(refusal.contains("not loaded"), refusal)

        // Round 2: RemoteDataRepository re-plans the declared set for every round, and the
        // sanitizer drops any call whose tool is not in it — together with its tool response.
        val nextDeclared = ToolDelivery.plan(tools, ToolActivation.activatedNames(conversation))
            .delivered.mapTo(mutableSetOf()) { it.schema.name }
        val history = listOf(
            Message(role = "user", content = JsonPrimitive("reveal my wifi password")),
            Message(
                role = "assistant",
                // `toGroqMessageDto` nulls the empty content of a tool-calling assistant turn.
                content = null,
                tool_calls = listOf(
                    ToolCall(id = "call_1", function = FunctionCall(name = "vault_reveal", arguments = "{}")),
                ),
            ),
            Message(role = "tool", content = JsonPrimitive(refusal), tool_call_id = "call_1"),
        )

        val sent = sanitizeToolMessages(history, nextDeclared)

        assertEquals(3, sent.size, "the refusal must survive the next request, got: $sent")
        assertEquals(refusal, (sent.last().content as JsonPrimitive).content)
        // The refusal also activated the tool, so the schema travels with the next request and
        // the model can retry straight away instead of looping into the REPEATING bailout.
        assertEquals("done", executor.executeTool("vault_reveal", "{}", conversation))
    }

    @Test
    fun `executor stays unrestricted when no delivery was planned`() = runBlocking {
        val executor = ToolExecutor { listOf(fakeTool(FORTY_TOOLS.first())) }

        assertEquals("done", executor.executeTool("web_search", "{}", "never-planned"))
    }

    @Test
    fun `disabled tools are not searchable`() {
        // `getAvailableTools()` never returns tools behind an off master toggle, so the catalogue
        // is built from what is enabled — a disabled tool must not be found or described.
        val enabledOnly = FORTY_TOOLS.filterNot { it.name.startsWith("vault_") }.map { fakeTool(it) }
        val catalog = ToolCatalog(enabledOnly)

        val hits = catalog.search("vault").map { it.schema.name }
        assertFalse(hits.contains("vault_reveal"), "disabled vault tool surfaced: $hits")
        assertNull(catalog.find("vault_reveal"))
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

    companion object {
        private val META_TOOL_NAMES = setOf(ListToolsTool.NAME, FindToolsTool.NAME, DescribeToolTool.NAME)

        /**
         * A realistic 40-tool set: the hot five lazy delivery always sends, the meta-tool
         * targets, and enough families for search and categories to be exercised.
         */
        private val FORTY_TOOLS = listOf(
            ToolSpec("web_search", "Search the web and return the most relevant pages with snippets", "query"),
            ToolSpec("fetch_url", "Fetch a web page or file by URL and return its readable text", "url"),
            ToolSpec("get_local_time", "Return the current local time, date and time zone", "format"),
            ToolSpec("memory_store", "Remember a fact about the user for later turns", "content"),
            ToolSpec("open_url", "Open a URL in the system browser", "url"),
            ToolSpec("memory_forget", "Forget a stored memory by key", "key"),
            ToolSpec("memory_learn", "Record a categorised learning", "content"),
            ToolSpec("memory_reinforce", "Reinforce a stored learning that worked", "key"),
            ToolSpec("promote_learning", "Promote a learning into long-term memory", "key"),
            ToolSpec("check_email", "Check the connected mailboxes for new messages", "account"),
            ToolSpec("read_email", "Read one email by id", "id"),
            ToolSpec("search_email", "Search the mailbox by sender, subject or body", "query"),
            ToolSpec("compose_email", "Draft a new email", "body"),
            ToolSpec("reply_email", "Draft a reply to an email", "body"),
            ToolSpec("setup_email", "Connect a new mailbox", "address"),
            ToolSpec("check_sms", "Check for new text messages", "limit"),
            ToolSpec("read_sms", "Read one text message by id", "id"),
            ToolSpec("send_sms", "Send a text message", "body"),
            ToolSpec("search_notifications", "Search recent device notifications", "query"),
            ToolSpec("check_notifications", "Check recent device notifications", "limit"),
            ToolSpec("schedule_task", "Schedule a prompt to run later or on a cron", "prompt"),
            ToolSpec("list_tasks", "List scheduled tasks", "filter"),
            ToolSpec("cancel_task", "Cancel a scheduled task", "id"),
            ToolSpec("generate_image", "Generate or edit an image from a prompt", "prompt"),
            ToolSpec("generate_video", "Generate a short video from a prompt", "prompt"),
            ToolSpec("list_media_models", "List media models with prices and capabilities", "kind"),
            ToolSpec("search_photos", "Search the device photo library by filename", "query"),
            ToolSpec("import_photo", "Copy a photo from the library into the sandbox", "id"),
            ToolSpec("open_file", "Open a file with the system viewer", "path"),
            ToolSpec("list_downloads", "List files Pots saved to Downloads", "filter"),
            ToolSpec("obsidian_read_note", "Read a note from the Obsidian vault", "path"),
            ToolSpec("obsidian_search_notes", "Search the Obsidian vault by text", "query"),
            ToolSpec("create_calendar_event", "Create a calendar event", "title"),
            ToolSpec("list_calendar_events", "List calendar events in a range", "from"),
            ToolSpec("set_alarm", "Set an alarm on the device", "time"),
            ToolSpec("contacts_search", "Search the device contacts", "query"),
            ToolSpec("contact_dial", "Call a contact", "id"),
            ToolSpec("mini_app_data", "Design and fill the user's mini-app collections", "action"),
            ToolSpec("vault_list", "List password vault entries by name and URL", "filter"),
            ToolSpec("vault_reveal", "Reveal one vault field to the owner after confirmation", "item"),
        )
    }
}
