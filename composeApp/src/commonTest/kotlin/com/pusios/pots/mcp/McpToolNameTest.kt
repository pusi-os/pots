package com.pusios.pots.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpToolNameTest {

    @Test
    fun `free names are kept as-is`() {
        assertEquals("search", uniqueMcpToolName("github", "search", setOf("web_search")))
    }

    @Test
    fun `clashes are prefixed with the server id`() {
        assertEquals("github_search", uniqueMcpToolName("github", "search", setOf("search")))
        assertEquals("github_search_2", uniqueMcpToolName("github", "search", setOf("search", "github_search")))
    }

    @Test
    fun `renamed tools stay within provider name rules`() {
        val name = uniqueMcpToolName("my server.v2", "x".repeat(80), setOf("x".repeat(80)))
        assertTrue(name.length <= 64, name)
        assertTrue(name.all { it.isLetterOrDigit() || it == '_' || it == '-' }, name)
    }

    @Test
    fun `dots are sanitized even when the name is free`() {
        val name = uniqueMcpToolName("filesystem", "read.file", emptySet())
        assertEquals("read_file", name)
        assertTrue(Regex("^[A-Za-z0-9_-]{1,64}$").matches(name), name)
    }

    @Test
    fun `long free names are truncated to 64 characters`() {
        val name = uniqueMcpToolName("filesystem", "x".repeat(128), emptySet())
        assertEquals(64, name.length, name)
        assertTrue(Regex("^[A-Za-z0-9_-]{1,64}$").matches(name), name)
    }

    @Test
    fun `a name made only of invalid characters still yields a legal one`() {
        val name = uniqueMcpToolName("server", "☃☃", emptySet())
        assertTrue(Regex("^[A-Za-z0-9_-]{1,64}$").matches(name), name)
    }
}
