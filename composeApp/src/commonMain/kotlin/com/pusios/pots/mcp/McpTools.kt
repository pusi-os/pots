// The agent's own MCP administration tools.
//
// They exist because the owner asked for "add and configure MCP servers yourself", but they are
// deliberately narrower than the settings screen: the model may name a server, give a URL and a
// transport, read statuses and start an authorization — it may never see or type a secret. A
// header or a token is entered by the human in Settings → Tools, never through the chat, so an
// injected prompt cannot exfiltrate one and the history never holds one.
//
// Every mutating call goes through [McpGate]: the owner sees what is about to be added, removed
// or authorized, and a headless run is refused outright.

package com.pusios.pots.mcp

import com.pusios.pots.network.tools.ParameterSchema
import com.pusios.pots.network.tools.Tool
import com.pusios.pots.network.tools.ToolInfo
import com.pusios.pots.network.tools.ToolSchema
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.tool_mcp_add_server_description
import pots.composeapp.generated.resources.tool_mcp_add_server_name
import pots.composeapp.generated.resources.tool_mcp_authorize_description
import pots.composeapp.generated.resources.tool_mcp_authorize_name
import pots.composeapp.generated.resources.tool_mcp_description
import pots.composeapp.generated.resources.tool_mcp_list_servers_description
import pots.composeapp.generated.resources.tool_mcp_list_servers_name
import pots.composeapp.generated.resources.tool_mcp_name
import pots.composeapp.generated.resources.tool_mcp_refresh_description
import pots.composeapp.generated.resources.tool_mcp_refresh_name
import pots.composeapp.generated.resources.tool_mcp_remove_server_description
import pots.composeapp.generated.resources.tool_mcp_remove_server_name
import pots.composeapp.generated.resources.tool_mcp_set_enabled_description
import pots.composeapp.generated.resources.tool_mcp_set_enabled_name
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Everything the MCP administration tools need. */
data class McpToolDependencies(
    val manager: McpServerManager,
    val gate: McpConfirmation,
)

object McpTools {

    /** Master id, mirroring AdbTools/VaultTools: the Tools tab shows the set under one label. */
    const val MASTER_ID = "mcp_admin_tools"

    val TOOL_NAMES = listOf(
        "mcp_list_servers",
        "mcp_add_server",
        "mcp_remove_server",
        "mcp_set_enabled",
        "mcp_refresh",
        "mcp_authorize",
    )

    /** Display definitions for chat. The set is always on; each mutation asks the owner anyway. */
    val toolDefinitions: List<ToolInfo> = listOf(
        ToolInfo(
            id = MASTER_ID,
            name = "MCP servers",
            description = "Let the agent list, add and authorize MCP servers",
            nameRes = Res.string.tool_mcp_name,
            descriptionRes = Res.string.tool_mcp_description,
            userToggleable = false,
        ),
        ToolInfo(
            "mcp_list_servers",
            "MCP: list servers",
            "Every configured server with its state and tool count",
            nameRes = Res.string.tool_mcp_list_servers_name,
            descriptionRes = Res.string.tool_mcp_list_servers_description,
            userToggleable = false,
        ),
        ToolInfo(
            "mcp_add_server",
            "MCP: add server",
            "Add an MCP server by name and URL (owner confirms; secrets stay out of chat)",
            nameRes = Res.string.tool_mcp_add_server_name,
            descriptionRes = Res.string.tool_mcp_add_server_description,
            userToggleable = false,
        ),
        ToolInfo(
            "mcp_remove_server",
            "MCP: remove server",
            "Remove a server the agent added (owner confirms)",
            nameRes = Res.string.tool_mcp_remove_server_name,
            descriptionRes = Res.string.tool_mcp_remove_server_description,
            userToggleable = false,
        ),
        ToolInfo(
            "mcp_set_enabled",
            "MCP: enable/disable server",
            "Turn a configured server on or off for the agent",
            nameRes = Res.string.tool_mcp_set_enabled_name,
            descriptionRes = Res.string.tool_mcp_set_enabled_description,
            userToggleable = false,
        ),
        ToolInfo(
            "mcp_refresh",
            "MCP: refresh",
            "Reconnect a server and reload its tool list",
            nameRes = Res.string.tool_mcp_refresh_name,
            descriptionRes = Res.string.tool_mcp_refresh_description,
            userToggleable = false,
        ),
        ToolInfo(
            "mcp_authorize",
            "MCP: authorize",
            "Start the browser sign-in flow for a server (owner confirms)",
            nameRes = Res.string.tool_mcp_authorize_name,
            descriptionRes = Res.string.tool_mcp_authorize_description,
            userToggleable = false,
        ),
    )

    /**
     * The gate window plus a connect/authorization deadline, so `ToolExecutor`'s
     * `withTimeout(tool.timeout)` never kills a call while the owner is still deciding.
     */
    private val GATED_TIMEOUT: Duration = (McpGate.DEFAULT_CONFIRM_TIMEOUT_SECONDS + 120).seconds
    private val PLAIN_TIMEOUT: Duration = 90.seconds

    fun create(deps: McpToolDependencies): List<Tool> = listOf(
        listServersTool(deps),
        addServerTool(deps),
        removeServerTool(deps),
        setEnabledTool(deps),
        refreshTool(deps),
        authorizeTool(deps),
    )

    // ------------------------------------------------------------------ tools

    private fun listServersTool(deps: McpToolDependencies): Tool = object : Tool {
        override val schema = ToolSchema(
            name = "mcp_list_servers",
            description = "List every configured MCP server with its state (connected, needs " +
                "authorization, failed, disabled), how many tools it has, and the last error. " +
                "Use the returned id or name with the other mcp_* tools.",
            parameters = emptyMap(),
        )
        override val timeout: Duration = PLAIN_TIMEOUT

        override suspend fun execute(args: Map<String, Any>): Any {
            val servers = deps.manager.getServers()
            if (servers.isEmpty()) return ok("No MCP servers are configured.")
            val text = buildString {
                append("MCP servers (${servers.size}):")
                for (server in servers) {
                    val status = deps.manager.status(server.id)
                    append("\n- id=").append(server.id)
                    append(" name=").append(server.name)
                    append(" enabled=").append(server.isEnabled)
                    append(" state=").append(status.state.name.lowercase())
                    append(" tools=").append(status.toolCount)
                    if (server.addedByAgent) append(" addedByAgent=true")
                    status.failure?.let { failure ->
                        append(" lastError=").append(failure.kind.name.lowercase())
                        if (failure.detail.isNotBlank()) append(" (").append(failure.detail).append(')')
                    }
                }
            }
            return ok(text)
        }
    }

    private fun addServerTool(deps: McpToolDependencies): Tool = object : Tool {
        override val schema = ToolSchema(
            name = "mcp_add_server",
            description = "Add an MCP server the owner asked for. Pass its display name and its " +
                "Streamable-HTTP endpoint URL. Never ask the owner for an API key or a token in " +
                "chat: keys and sign-in are entered by the owner in Settings → Tools. The owner " +
                "confirms the addition in chat first.",
            parameters = mapOf(
                "name" to ParameterSchema("string", "Short display name, e.g. \"Sentry\"", true),
                "url" to ParameterSchema("string", "HTTPS endpoint of the MCP server", true),
                "transport" to ParameterSchema(
                    "string",
                    "Transport: http (Streamable HTTP). SSE is not supported by this client.",
                    false,
                ),
            ),
        )
        override val timeout: Duration = GATED_TIMEOUT

        override suspend fun execute(args: Map<String, Any>): Any {
            val name = (args["name"] as? String)?.trim().orEmpty()
            val url = (args["url"] as? String)?.trim().orEmpty()
            if (name.isEmpty() || url.isEmpty()) return err("Both name and url are required.")
            if (!url.startsWith("https://") && !url.startsWith("http://")) {
                return err("The URL must start with https:// (or http:// for a server on your network).")
            }
            val transport = (args["transport"] as? String)?.trim()?.lowercase()
            if (!transport.isNullOrEmpty() && transport != "http" && transport != "streamable-http") {
                return err("Only the Streamable HTTP transport is supported.")
            }
            // The agent may not point the app at the router, the metadata service or a loopback
            // port: that is the shortest path from a poisoned tool description to a private API.
            if (isPrivateAddress(url)) {
                return err(
                    "Refusing a private or loopback address. If you really mean it, the owner can " +
                        "add this server by hand in Settings → Tools.",
                )
            }
            val existing = deps.manager.getServers().firstOrNull {
                it.url.trimEnd('/').equals(url.trimEnd('/'), ignoreCase = true)
            }
            if (existing != null) {
                return ok("A server with this URL already exists: id=${existing.id} name=${existing.name}.")
            }
            if (!deps.gate.confirm("mcp_add_server", "Добавить MCP-сервер «$name» — $url")) {
                return err("The owner did not confirm adding this server.")
            }
            val config = deps.manager.addServer(name = name, url = url, headers = emptyMap(), addedByAgent = true)
            val result = deps.manager.connect(config.id)
            return ok(
                buildString {
                    append("Added MCP server id=").append(config.id).append(" name=").append(config.name).append(". ")
                    append(describeOutcome(config, result.isSuccess, deps.manager))
                },
            )
        }
    }

    private fun removeServerTool(deps: McpToolDependencies): Tool = object : Tool {
        override val schema = ToolSchema(
            name = "mcp_remove_server",
            description = "Remove an MCP server that the agent added earlier. Servers the owner " +
                "added by hand cannot be removed by the agent — say so instead. The owner confirms.",
            parameters = mapOf(
                "server" to ParameterSchema("string", "Server id or name from mcp_list_servers", true),
            ),
        )
        override val timeout: Duration = GATED_TIMEOUT

        override suspend fun execute(args: Map<String, Any>): Any {
            val server = resolveServer(deps.manager, args["server"] as? String)
                ?: return err("No MCP server matches that id or name. Call mcp_list_servers first.")
            if (!server.addedByAgent) {
                return err(
                    "Server \"${server.name}\" was added by the owner, so only the owner can remove it " +
                        "(Settings → Tools).",
                )
            }
            if (!deps.gate.confirm("mcp_remove_server", "Удалить MCP-сервер «${server.name}» — ${server.url}")) {
                return err("The owner did not confirm the removal.")
            }
            deps.manager.removeServer(server.id)
            return ok("Removed MCP server \"${server.name}\".")
        }
    }

    private fun setEnabledTool(deps: McpToolDependencies): Tool = object : Tool {
        override val schema = ToolSchema(
            name = "mcp_set_enabled",
            description = "Enable or disable a configured MCP server for the agent. Disabling " +
                "closes its connection and hides its tools; the configuration is kept.",
            parameters = mapOf(
                "server" to ParameterSchema("string", "Server id or name from mcp_list_servers", true),
                "enabled" to ParameterSchema("boolean", "true to enable, false to disable", true),
            ),
        )
        override val timeout: Duration = PLAIN_TIMEOUT

        override suspend fun execute(args: Map<String, Any>): Any {
            val server = resolveServer(deps.manager, args["server"] as? String)
                ?: return err("No MCP server matches that id or name. Call mcp_list_servers first.")
            val enabled = when (val raw = args["enabled"]) {
                is Boolean -> raw
                is String -> raw.equals("true", ignoreCase = true)
                else -> return err("The `enabled` argument must be true or false.")
            }
            deps.manager.setServerEnabled(server.id, enabled)
            if (enabled) deps.manager.connect(server.id)
            val status = deps.manager.status(server.id)
            return ok(
                "MCP server \"${server.name}\" is now ${if (enabled) "enabled" else "disabled"} " +
                    "(state=${status.state.name.lowercase()}, tools=${status.toolCount}).",
            )
        }
    }

    private fun refreshTool(deps: McpToolDependencies): Tool = object : Tool {
        override val schema = ToolSchema(
            name = "mcp_refresh",
            description = "Reconnect an MCP server and reload its tool list. Call it after the " +
                "owner says they signed in, or when a server looks stale.",
            parameters = mapOf(
                "server" to ParameterSchema("string", "Server id or name; omit to refresh all enabled servers", false),
            ),
        )
        override val timeout: Duration = PLAIN_TIMEOUT

        override suspend fun execute(args: Map<String, Any>): Any {
            val query = (args["server"] as? String)?.trim().orEmpty()
            if (query.isEmpty()) {
                deps.manager.connectEnabledServers()
                return ok("Refreshed all enabled MCP servers. Call mcp_list_servers for the new states.")
            }
            val server = resolveServer(deps.manager, query)
                ?: return err("No MCP server matches that id or name. Call mcp_list_servers first.")
            val result = deps.manager.connect(server.id)
            return ok(describeOutcome(server, result.isSuccess, deps.manager))
        }
    }

    private fun authorizeTool(deps: McpToolDependencies): Tool = object : Tool {
        override val schema = ToolSchema(
            name = "mcp_authorize",
            description = "Start the browser sign-in flow for an MCP server that reports " +
                "\"needs authorization\". Opens the browser on the owner's device and returns " +
                "immediately; the owner finishes the sign-in there. The owner confirms first.",
            parameters = mapOf(
                "server" to ParameterSchema("string", "Server id or name from mcp_list_servers", true),
            ),
        )
        override val timeout: Duration = GATED_TIMEOUT

        override suspend fun execute(args: Map<String, Any>): Any {
            val server = resolveServer(deps.manager, args["server"] as? String)
                ?: return err("No MCP server matches that id or name. Call mcp_list_servers first.")
            if (!deps.gate.confirm("mcp_authorize", "Открыть браузер для входа в «${server.name}»")) {
                return err("The owner did not confirm the sign-in.")
            }
            val result = deps.manager.beginAuthorization(server.id)
            val url = result.getOrElse { error ->
                return err(
                    "Could not start sign-in for \"${server.name}\": ${error.message}. " +
                        "The owner can paste a token by hand in Settings → Tools.",
                )
            }
            return ok(
                "Opened the browser for \"${server.name}\" and waiting for the owner to finish. " +
                    "If the browser did not open, the link is: $url",
            )
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun ok(text: String): Any = mapOf("success" to true, "result" to text)

    private fun err(text: String): Any = mapOf("success" to false, "error" to text)

    private fun describeOutcome(server: McpServerConfig, connected: Boolean, manager: McpServerManager): String {
        val status = manager.status(server.id)
        if (connected) return "Connected: ${status.toolCount} tools available."
        return when (status.failure?.kind) {
            McpFailureKind.NeedsAuth, McpFailureKind.NeedsScope ->
                "The server needs authorization. Ask the owner to press Authorize for \"${server.name}\" " +
                    "in Settings → Tools (or call mcp_authorize), then call mcp_refresh."

            McpFailureKind.Unreachable ->
                "The server is unreachable right now; it stays configured " +
                    "and its cached tools remain visible."

            McpFailureKind.WrongEndpoint ->
                "The server answered on the wrong endpoint — check whether " +
                    "the URL is its Streamable HTTP endpoint."

            else -> "Could not connect: ${status.failure?.detail?.takeIf { it.isNotBlank() } ?: "unknown error"}."
        }
    }

    /** Resolves a server by id, then by exact name, then by case-insensitive name. */
    internal fun resolveServer(manager: McpServerManager, query: String?): McpServerConfig? {
        val needle = query?.trim().orEmpty()
        if (needle.isEmpty()) return null
        val servers = manager.getServers()
        return servers.firstOrNull { it.id == needle }
            ?: servers.firstOrNull { it.name == needle }
            ?: servers.firstOrNull { it.name.equals(needle, ignoreCase = true) }
    }
}
