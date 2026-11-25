package com.pusios.pots.mcp

import kotlinx.serialization.Serializable

@Serializable
data class McpServerConfig(
    val id: String,
    val name: String,
    val url: String,
    /** Non-secret headers and static API keys the owner typed. OAuth tokens live in McpTokenStore. */
    val headers: Map<String, String> = emptyMap(),
    val isEnabled: Boolean = true,
    /** True when the agent added it through `mcp_add_server`; shown in the UI as an audit mark. */
    val addedByAgent: Boolean = false,
)
