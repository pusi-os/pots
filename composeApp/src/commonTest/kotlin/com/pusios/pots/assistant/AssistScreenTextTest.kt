package com.pusios.pots.assistant

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The rules that turn an assist snapshot into a prompt block.
 *
 * The snapshot itself is the platform's (`androidMain` walks the
 * `AssistStructure`); what is tested here is what survives that walk — and the
 * cases that matter are the ones where a screen has *no* readable text: a secure
 * window, a blocked node, an empty tree.
 */
class AssistScreenTextTest {

    @Test
    fun `an empty snapshot is no context at all`() {
        assertEquals("", assistScreenText(emptyList()))
    }

    @Test
    fun `text nodes are read in screen order`() {
        val roots = listOf(
            AssistNode(
                text = "Inbox",
                children = listOf(
                    AssistNode(text = "Alice: lunch?"),
                    AssistNode(text = "Bob: tomorrow"),
                ),
            ),
            AssistNode(text = "12:30"),
        )

        assertEquals("Inbox\nAlice: lunch?\nBob: tomorrow\n12:30", assistScreenText(roots))
    }

    @Test
    fun `an icon button is read by its content description`() {
        assertEquals("Search", assistScreenText(listOf(AssistNode(contentDescription = "Search"))))
    }

    @Test
    fun `an empty field is read by its hint`() {
        assertEquals("Message", assistScreenText(listOf(AssistNode(hint = "Message"))))
    }

    @Test
    fun `a repeated line is kept once`() {
        val roots = listOf(
            AssistNode(text = "Chats"),
            AssistNode(text = "Chats", contentDescription = "Chats"),
            AssistNode(text = "Settings", children = listOf(AssistNode(text = "Chats"))),
        )

        assertEquals("Chats\nSettings", assistScreenText(roots))
    }

    @Test
    fun `whitespace is collapsed and empty text is dropped`() {
        val roots = listOf(
            AssistNode(text = "  Hello \n  world "),
            AssistNode(text = "   "),
            AssistNode(text = null),
        )

        assertEquals("Hello world", assistScreenText(roots))
    }

    @Test
    fun `a secure window arrives as an empty stub, so it is no text`() {
        // FLAG_SECURE: the platform sends an empty structure instead of the view
        // tree — there is nothing to read, and nothing may be invented.
        assertEquals("", assistScreenText(listOf(AssistNode(text = ""), AssistNode())))
    }

    @Test
    fun `a blocked node hides its subtree`() {
        val roots = listOf(
            AssistNode(
                text = "Visible",
                children = listOf(
                    AssistNode(
                        text = "Secret",
                        blocked = true,
                        children = listOf(AssistNode(text = "Also secret")),
                    ),
                ),
            ),
            AssistNode(text = "After"),
        )

        assertEquals("Visible\nAfter", assistScreenText(roots))
    }

    @Test
    fun `the node limit cuts the walk and says so`() {
        val roots = (1..10).map { AssistNode(text = "line $it") }

        val text = assistScreenText(roots, nodeLimit = 3)

        assertEquals("line 1\nline 2\nline 3$ASSIST_TEXT_TRUNCATED_MARK", text)
    }

    @Test
    fun `the character limit cuts the text and says so`() {
        val roots = (1..50).map { AssistNode(text = "line $it " + "x".repeat(40)) }

        val text = assistScreenText(roots, limit = 100)

        assertTrue(text.endsWith(ASSIST_TEXT_TRUNCATED_MARK), "the cut has to be visible: $text")
        assertTrue(
            text.length <= 100 + ASSIST_TEXT_TRUNCATED_MARK.length,
            "the limit is what a prompt may cost, the mark is its price: ${text.length}",
        )
    }

    @Test
    fun `a snapshot under the limits is not marked as cut`() {
        val text = assistScreenText(listOf(AssistNode(text = "short"), AssistNode(text = "screen")))

        assertEquals("short\nscreen", text)
        assertTrue(!text.contains(ASSIST_TEXT_TRUNCATED_MARK))
    }

    @Test
    fun `the question keeps the user's words and the screen apart`() {
        val text = questionWithScreenContext(
            question = "  Что тут написано?  ",
            screenContext = "Hello",
            header = "Screen:",
        )

        assertEquals("Что тут написано?\n\nScreen:\nHello", text)
    }

    @Test
    fun `a question without a snapshot is just the question`() {
        assertEquals("Вопрос", questionWithScreenContext("Вопрос", "   ", "Screen:"))
    }
}
