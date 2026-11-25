package com.pusios.pots.mcp

/**
 * No loopback HTTP server on iOS: the system browser session there is ASWebAuthenticationSession
 * with a custom scheme, which is a separate piece of work. The app falls back to pasting a token
 * by hand, and the UI says so.
 */
actual fun startMcpAuthListener(path: String): McpAuthListener? = null
