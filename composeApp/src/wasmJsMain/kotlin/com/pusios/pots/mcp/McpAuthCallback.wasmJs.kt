package com.pusios.pots.mcp

/**
 * No loopback listener on the web build: a browser tab cannot open a server socket. The app
 * falls back to pasting a token by hand, and the UI says so.
 */
actual fun startMcpAuthListener(path: String): McpAuthListener? = null
