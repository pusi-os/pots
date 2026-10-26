package com.pusios.pots.network

import com.pusios.pots.data.Service
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * OpenCode Zen rejects requests that arrive without `x-opencode-session`. The id has to be
 * stable for the whole conversation, and no other provider may see the header.
 */
class SessionHeadersTest {

    private val header = "x-opencode-session"

    private fun expected(sessionId: String): Map<String, String> = mapOf(
        header to sessionId,
        "x-opencode-client" to "cli",
        "x-opencode-project" to "global",
        "HTTP-Referer" to "https://opencode.ai/",
        "X-Title" to "opencode",
    )

    @Test
    fun openCodeRequestCarriesTheConversationIdAsSessionId() {
        assertEquals(expected("conv-42"), sessionHeadersFor(Service.OpenCodeFree, "conv-42"))
    }

    @Test
    fun sameConversationKeepsTheSameSessionId() {
        assertEquals(
            sessionHeadersFor(Service.OpenCodeFree, "conv-42"),
            sessionHeadersFor(Service.OpenCodeFree, "conv-42"),
        )
    }

    @Test
    fun requestsOutsideAnyConversationStillCarryAStableSessionId() {
        val first = sessionHeadersFor(Service.OpenCodeFree, null)[header]
        val blank = sessionHeadersFor(Service.OpenCodeFree, "  ")[header]
        assertNotNull(first)
        assertTrue(first.isNotBlank())
        assertEquals(first, blank)
    }

    @Test
    fun otherProvidersGetNoSessionHeader() {
        for (service in Service.all.filter { it != Service.OpenCodeFree }) {
            assertEquals(emptyMap(), sessionHeadersFor(service, "conv-42"), "unexpected header for ${service.id}")
        }
    }

    @Test
    fun openAICompatibleInstancePointedAtOpenCodeCarriesTheSessionId() {
        assertEquals(
            expected("conv-42"),
            sessionHeadersFor(Service.OpenAICompatible, "conv-42", "https://opencode.ai/zen/go/v1"),
        )
        assertEquals(emptyMap(), sessionHeadersFor(Service.OpenAICompatible, "conv-42", "http://localhost:11434/v1"))
    }
}
