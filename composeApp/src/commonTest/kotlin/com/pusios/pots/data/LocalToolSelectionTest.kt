package com.pusios.pots.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks in the on-device tool gate: which tools a local model is handed, how many, and whether
 * it is handed any at all.
 *
 * The defect these cover is "the local model answers with errors": a 4K window and a strict
 * function-call parser cannot carry an unbounded tool set, and a model whose chat template has
 * no tool section invents the tool's output instead of calling it.
 */
class LocalToolSelectionTest {

    @Test
    fun `unconfigured selection resolves to the built-in chat set`() {
        val effective = LocalToolSelection.effective(LocalToolSelection())

        assertEquals(LocalToolSelection.CHAT_PRIORITY_TOOL_IDS, effective.toolIds)
        assertEquals(LocalToolSelection.DEFAULT_TOOL_IDS.size, effective.toolIds.size)
    }

    @Test
    fun `unconfigured selection hands no MCP servers`() {
        // Every connected server used to be handed to the on-device model by default; a server
        // with a dozen nested schemas is what pushed small models past their parser.
        val effective = LocalToolSelection.effective(LocalToolSelection())

        assertTrue(effective.mcpServerIds.isEmpty())
    }

    @Test
    fun `configured selection is returned untouched`() {
        val stored = LocalToolSelection(
            configured = true,
            toolIds = listOf("get_local_time"),
            mcpServerIds = listOf("server-1"),
        )

        assertEquals(stored, LocalToolSelection.effective(stored))
    }

    @Test
    fun `explicit empty selection stays empty`() {
        // "Tools off" is a real setting: the owner must be able to run the local model bare.
        val effective = LocalToolSelection.effective(LocalToolSelection(configured = true))

        assertTrue(effective.toolIds.isEmpty())
        assertTrue(effective.mcpServerIds.isEmpty())
    }

    @Test
    fun `tool names are capped at the maximum`() {
        val many = (1..20).map { "tool_$it" }

        assertEquals(LocalToolSelection.MAX_TOOLS, LocalToolSelection.capToolNames(many).size)
    }

    @Test
    fun `cap keeps chat-priority tools ahead of the rest`() {
        val mcpTools = (1..6).map { "mcp_tool_$it" }
        val names = mcpTools + listOf("web_search", "get_local_time", "memory_store")

        val capped = LocalToolSelection.capToolNames(names)

        assertEquals(LocalToolSelection.MAX_TOOLS, capped.size)
        assertTrue("web_search" in capped)
        assertTrue("get_local_time" in capped)
        assertTrue("memory_store" in capped)
        assertTrue("mcp_tool_6" !in capped, "an MCP tool must lose its slot to a chat-priority tool")
    }

    @Test
    fun `cap preserves order inside each group`() {
        val names = listOf("mcp_a", "get_local_time", "mcp_b", "web_search", "mcp_c", "mcp_d", "mcp_e", "mcp_f", "mcp_g")

        val capped = LocalToolSelection.capToolNames(names)

        assertEquals(listOf("get_local_time", "web_search", "mcp_a", "mcp_b", "mcp_c", "mcp_d", "mcp_e", "mcp_f"), capped)
    }

    @Test
    fun `a set below the cap is returned as-is`() {
        val names = listOf("web_search", "open_url")

        assertEquals(names, LocalToolSelection.capToolNames(names))
    }

    @Test
    fun `model declaring function calling gets tools`() {
        assertTrue(LocalToolSelection.modelSupportsToolCalling(true, "gemma-4-e2b-it"))
    }

    @Test
    fun `model denying function calling gets none`() {
        assertFalse(LocalToolSelection.modelSupportsToolCalling(false, "qwen3-0-6b"))
    }

    @Test
    fun `model with unknown capabilities gets none`() {
        // null is "the bundle would not say". Handing tools on that guess is what made the
        // model fabricate tool output instead of calling the tool.
        assertFalse(LocalToolSelection.modelSupportsToolCalling(null, "some-imported-model"))
    }

    @Test
    fun `template-level tool calling ids are opted in by name`() {
        for (id in LocalToolSelection.TEMPLATE_LEVEL_TOOL_CALLING_MODEL_IDS) {
            assertTrue(LocalToolSelection.modelSupportsToolCalling(null, id), "$id should be opted in")
        }
    }
}
