package com.pusios.pots.ui.markdown

import com.pusios.pots.ui.dynamicui.AlertNode
import com.pusios.pots.ui.dynamicui.ColumnNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PotsUiBlockIntegrationTest {

    @Test
    fun `pots-ui fence produces PotsUiBlock`() {
        val md = """
            ```pots-ui
            {"type":"alert","title":"Heads up","message":"Hello"}
            ```
        """.trimIndent()
        val block = parseMarkdown(md).blocks.single()
        assertTrue(block is PotsUiBlock)
        val alert = block.node as AlertNode
        assertEquals("Heads up", alert.title)
        assertEquals("Hello", alert.message)
    }

    @Test
    fun `malformed pots-ui fence produces PotsUiError`() {
        val md = """
            ```pots-ui
            not json at all
            ```
        """.trimIndent()
        val block = parseMarkdown(md).blocks.single()
        assertTrue(block is PotsUiError)
    }

    @Test
    fun `ndjson multi-line pots-ui wraps children in a column`() {
        val md = """
            ```pots-ui
            {"type":"text","value":"a"}
            {"type":"text","value":"b"}
            ```
        """.trimIndent()
        val block = parseMarkdown(md).blocks.single()
        assertTrue(block is PotsUiBlock)
        val col = block.node as ColumnNode
        assertEquals(2, col.children.size)
    }

    @Test
    fun `pots-ui block surrounded by markdown produces three blocks`() {
        val md = """
            Before

            ```pots-ui
            {"type":"alert","message":"hi"}
            ```

            After
        """.trimIndent()
        val blocks = parseMarkdown(md).blocks
        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is Paragraph)
        assertTrue(blocks[1] is PotsUiBlock)
        assertTrue(blocks[2] is Paragraph)
    }

    @Test
    fun `split-block pattern with json fence is treated as pots-ui`() {
        val md = """
            pots-ui
            ```json
            {"type":"alert","message":"hi"}
            ```
        """.trimIndent()
        val block = parseMarkdown(md).blocks.single()
        assertTrue(block is PotsUiBlock)
    }

    @Test
    fun `pots-ui block speakable text walks the node tree`() {
        val md = """
            Intro.

            ```pots-ui
            {"type":"alert","title":"Heads up","message":"Take care"}
            ```

            Outro.
        """.trimIndent()
        val spoken = parseMarkdown(md).toSpeakableText()
        assertTrue(spoken.contains("Intro"))
        assertTrue(spoken.contains("Heads up"))
        assertTrue(spoken.contains("Take care"))
        assertTrue(spoken.contains("Outro"))
    }
}
