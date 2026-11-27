package com.pusios.pots.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpRequestHeadersTest {

    @Test
    fun `a token replaces a configured Authorization header`() {
        val headers = mcpRequestHeaders(
            sessionId = "session-1",
            staticHeaders = mapOf("Authorization" to "Bearer stale-api-key", "X-Env" to "prod"),
            token = "fresh-oauth-token",
        )

        val authorization = headers.filter { it.first.equals("Authorization", ignoreCase = true) }
        assertEquals(1, authorization.size, "exactly one Authorization header: $headers")
        assertEquals("Bearer fresh-oauth-token", authorization.single().second)
        assertEquals("session-1", headers.first { it.first == "Mcp-Session-Id" }.second)
        assertEquals("prod", headers.first { it.first == "X-Env" }.second, "other headers survive")
    }

    @Test
    fun `a configured Authorization survives when no token is stored`() {
        val headers = mcpRequestHeaders(
            sessionId = null,
            staticHeaders = mapOf("Authorization" to "Bearer api-key"),
            token = null,
        )

        assertEquals(listOf("Authorization" to "Bearer api-key"), headers.filter { it.first == "Authorization" })
        assertTrue(headers.none { it.first == "Mcp-Session-Id" }, "no session header before the server issues one")
    }

    @Test
    fun `the token is the last header and there is exactly one`() {
        val headers = mcpRequestHeaders(null, mapOf("Authorization" to "old", "authorization" to "older"), "t")

        assertEquals(1, headers.count { it.first.equals("Authorization", ignoreCase = true) }, headers.toString())
        assertEquals("Authorization" to "Bearer t", headers.last())
    }
}
