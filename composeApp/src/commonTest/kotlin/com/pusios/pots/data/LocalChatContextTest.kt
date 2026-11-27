package com.pusios.pots.data

import com.pusios.pots.inference.LocalContextOverflowException
import com.pusios.pots.ui.chat.History
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Locks in the on-device context budget: what the system prompt may spend, and which part of
 * the history survives when the conversation no longer fits the model's window.
 *
 * litert-lm does not trim anything — an over-long request fails the whole turn with an opaque
 * INVALID_ARGUMENT — so both budgets have to hold before the native call.
 */
class LocalChatContextTest {

    private fun user(content: String) = History(role = History.Role.USER, content = content)

    private fun assistant(content: String) = History(role = History.Role.ASSISTANT, content = content)

    @Test
    fun `prompt budget follows the model window`() {
        // 4096-token window, 35% share, 4 chars per token.
        assertEquals(5_734, localPromptBudgetCharsFor(4_096))
    }

    @Test
    fun `prompt budget has a floor and a ceiling`() {
        assertEquals(2_000, localPromptBudgetCharsFor(512))
        assertEquals(12_000, localPromptBudgetCharsFor(131_072))
    }

    @Test
    fun `unknown window falls back to the default budget`() {
        assertEquals(LOCAL_PROMPT_BUDGET_CHARS, localPromptBudgetCharsFor(0))
        assertEquals(LOCAL_PROMPT_BUDGET_CHARS, localPromptBudgetCharsFor(-1))
    }

    @Test
    fun `history that fits is returned whole`() {
        val messages = listOf(user("привет"), assistant("здравствуй"), user("как дела?"))

        val trimmed = trimLocalChatHistory(messages, contextTokens = 4_096, systemPromptChars = 3_000)

        assertEquals(messages, trimmed)
    }

    @Test
    fun `oldest turns are dropped first`() {
        val long = "x".repeat(4_000)
        val messages = listOf(
            user(long),
            assistant(long),
            user("short question"),
        )

        // 4096 window - 512 reserve = 3 584 tokens ≈ 14 336 chars; a 9 000-char prompt leaves
        // 5 336 for history, which holds one long turn plus the question but not two.
        val trimmed = trimLocalChatHistory(messages, contextTokens = 4_096, systemPromptChars = 9_000)

        assertEquals(2, trimmed.size)
        assertEquals(History.Role.ASSISTANT, trimmed.first().role)
        assertEquals("short question", trimmed.last().content)
    }

    @Test
    fun `the last user message is never dropped`() {
        val messages = listOf(user("a"), assistant("b"), user("the question"))

        val trimmed = trimLocalChatHistory(messages, contextTokens = 4_096, systemPromptChars = 100)

        assertEquals("the question", trimmed.last().content)
        assertEquals(History.Role.USER, trimmed.last().role)
    }

    @Test
    fun `a message longer than the whole window is reported, not silently cut`() {
        val huge = "x".repeat(20_000)

        assertFailsWith<LocalContextOverflowException> {
            trimLocalChatHistory(listOf(user(huge)), contextTokens = 4_096, systemPromptChars = 0)
        }
    }

    @Test
    fun `tool rows are not replayed`() {
        val messages = listOf(
            user("q"),
            History(role = History.Role.TOOL, content = "tool result", toolName = "web_search"),
            assistant("a"),
            user("q2"),
        )

        val trimmed = trimLocalChatHistory(messages, contextTokens = 4_096, systemPromptChars = 0)

        assertTrue(trimmed.none { it.role == History.Role.TOOL })
    }

    @Test
    fun `unknown window keeps the conversational turns untouched`() {
        val messages = listOf(user("q1"), assistant("a1"), user("q2"))

        val trimmed = trimLocalChatHistory(messages, contextTokens = 0, systemPromptChars = 100_000)

        assertEquals(messages, trimmed)
    }
}
