package com.pusios.pots.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pusios.pots.mcp.McpConfirmRequest
import com.pusios.pots.mcp.PopularMcpServer
import com.pusios.pots.mcp.authorizationHeaderValue
import com.pusios.pots.mcp.popularMcpServers
import com.pusios.pots.openUrl
import com.pusios.pots.ui.PotsOutlinedTextField
import com.pusios.pots.ui.components.VerticalScrollbarForScroll
import com.pusios.pots.ui.handCursor
import com.pusios.pots.ui.potsAdaptiveCardBorder
import com.pusios.pots.ui.potsAdaptiveCardColors
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.ic_arrow_drop_down
import pots.composeapp.generated.resources.settings_mcp_add
import pots.composeapp.generated.resources.settings_mcp_add_header
import pots.composeapp.generated.resources.settings_mcp_add_server
import pots.composeapp.generated.resources.settings_mcp_added_by_agent
import pots.composeapp.generated.resources.settings_mcp_allow
import pots.composeapp.generated.resources.settings_mcp_api_key
import pots.composeapp.generated.resources.settings_mcp_api_key_help
import pots.composeapp.generated.resources.settings_mcp_auth_required_help
import pots.composeapp.generated.resources.settings_mcp_authorize
import pots.composeapp.generated.resources.settings_mcp_copy_link
import pots.composeapp.generated.resources.settings_mcp_deny
import pots.composeapp.generated.resources.settings_mcp_header_key
import pots.composeapp.generated.resources.settings_mcp_header_value
import pots.composeapp.generated.resources.settings_mcp_link_copied
import pots.composeapp.generated.resources.settings_mcp_manual_token
import pots.composeapp.generated.resources.settings_mcp_manual_token_help
import pots.composeapp.generated.resources.settings_mcp_manual_token_label
import pots.composeapp.generated.resources.settings_mcp_no_tools
import pots.composeapp.generated.resources.settings_mcp_open_link
import pots.composeapp.generated.resources.settings_mcp_popular_servers
import pots.composeapp.generated.resources.settings_mcp_refresh
import pots.composeapp.generated.resources.settings_mcp_remove
import pots.composeapp.generated.resources.settings_mcp_retry
import pots.composeapp.generated.resources.settings_mcp_save
import pots.composeapp.generated.resources.settings_mcp_server_name
import pots.composeapp.generated.resources.settings_mcp_server_url
import pots.composeapp.generated.resources.settings_mcp_servers
import pots.composeapp.generated.resources.settings_mcp_servers_description
import pots.composeapp.generated.resources.settings_mcp_sign_out
import pots.composeapp.generated.resources.settings_mcp_status_connected
import pots.composeapp.generated.resources.settings_mcp_status_connecting
import pots.composeapp.generated.resources.settings_mcp_status_disabled
import pots.composeapp.generated.resources.settings_mcp_status_error
import pots.composeapp.generated.resources.settings_mcp_status_needs_auth
import pots.composeapp.generated.resources.settings_mcp_status_unknown
import pots.composeapp.generated.resources.settings_mcp_tool_count
import pots.composeapp.generated.resources.settings_mcp_tools_cached
import pots.composeapp.generated.resources.settings_mcp_waiting_browser

@Composable
internal fun McpServersSection(
    mcpServers: ImmutableList<McpServerUiState>,
    onAddMcpServer: (String, String, Map<String, String>) -> Unit,
    onRemoveMcpServer: (String) -> Unit,
    onToggleMcpServer: (String, Boolean) -> Unit,
    onRefreshMcpServer: (String) -> Unit,
    onAuthorizeMcpServer: (String) -> Unit,
    onSetMcpManualToken: (String, String) -> Unit,
    onSignOutMcpServer: (String) -> Unit,
    onToggleTool: (String, Boolean) -> Unit,
    showAddDialog: Boolean,
    onShowAddDialog: (Boolean) -> Unit,
    onAddPopularMcpServer: (PopularMcpServer) -> Unit,
    pendingConfirm: McpConfirmRequest? = null,
    onRespondConfirm: (Boolean) -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(Res.string.settings_mcp_servers),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(Res.string.settings_mcp_servers_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(12.dp))

        // The agent can only change MCP configuration with the owner's word, and the word is
        // given here because this is the screen that shows the result.
        if (pendingConfirm != null) {
            McpConfirmCard(request = pendingConfirm, onRespond = onRespondConfirm)
            Spacer(Modifier.height(8.dp))
        }

        for (server in mcpServers) {
            McpServerCard(
                server = server,
                onToggle = { enabled -> onToggleMcpServer(server.id, enabled) },
                onRemove = { onRemoveMcpServer(server.id) },
                onRefresh = { onRefreshMcpServer(server.id) },
                onAuthorize = { onAuthorizeMcpServer(server.id) },
                onSetManualToken = { token -> onSetMcpManualToken(server.id, token) },
                onSignOut = { onSignOutMcpServer(server.id) },
                onToggleTool = onToggleTool,
            )
            Spacer(Modifier.height(8.dp))
        }

        OutlinedButton(
            onClick = { onShowAddDialog(true) },
            modifier = Modifier.align(Alignment.CenterHorizontally).handCursor(),
        ) {
            Text(stringResource(Res.string.settings_mcp_add_server))
        }
    }

    if (showAddDialog) {
        AddMcpServerDialog(
            onDismiss = { onShowAddDialog(false) },
            onAdd = onAddMcpServer,
            onAddPopular = onAddPopularMcpServer,
        )
    }
}

@Composable
private fun McpConfirmCard(request: McpConfirmRequest, onRespond: (Boolean) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = request.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onRespond(true) }, modifier = Modifier.handCursor()) {
                    Text(stringResource(Res.string.settings_mcp_allow))
                }
                TextButton(onClick = { onRespond(false) }, modifier = Modifier.handCursor()) {
                    Text(stringResource(Res.string.settings_mcp_deny))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun McpServerCard(
    server: McpServerUiState,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit,
    onRefresh: () -> Unit,
    onAuthorize: () -> Unit,
    onSetManualToken: (String) -> Unit,
    onSignOut: () -> Unit,
    onToggleTool: (String, Boolean) -> Unit,
) {
    // A server that refuses us opens itself: the sign-in button is the only thing that can fix it,
    // and the owner reported not finding it behind a collapsed card.
    var expanded by remember(server.id) { mutableStateOf(server.canAuthorize) }
    var showTokenDialog by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    Card(
        onClick = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth().handCursor(),
        colors = potsAdaptiveCardColors(),
        border = potsAdaptiveCardBorder(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Status dot
                val statusColor = when (server.connectionStatus) {
                    McpConnectionStatus.Connected -> StatusColorConnected
                    McpConnectionStatus.Connecting -> StatusColorChecking
                    McpConnectionStatus.NeedsAuth -> StatusColorChecking
                    McpConnectionStatus.Failed -> StatusColorError
                    McpConnectionStatus.Disabled -> StatusColorUnknown
                    McpConnectionStatus.Unknown -> StatusColorUnknown
                }
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(statusColor),
                )
                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = server.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = server.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (server.addedByAgent) {
                        Text(
                            text = stringResource(Res.string.settings_mcp_added_by_agent),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Switch(
                    checked = server.isEnabled,
                    onCheckedChange = onToggle,
                )

                Spacer(Modifier.width(8.dp))

                Icon(
                    imageVector = vectorResource(Res.drawable.ic_arrow_drop_down),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (expanded) {
                Spacer(Modifier.height(12.dp))

                // Status line: the reason, not just a colour. A server that needs a sign-in and a
                // server that is down used to look identical here.
                val statusText = if (server.isWaitingForBrowser) {
                    stringResource(Res.string.settings_mcp_waiting_browser)
                } else {
                    when (server.connectionStatus) {
                        McpConnectionStatus.Connected -> stringResource(Res.string.settings_mcp_status_connected)
                        McpConnectionStatus.Connecting -> stringResource(Res.string.settings_mcp_status_connecting)
                        McpConnectionStatus.NeedsAuth -> stringResource(Res.string.settings_mcp_status_needs_auth)
                        McpConnectionStatus.Failed -> stringResource(Res.string.settings_mcp_status_error)
                        McpConnectionStatus.Disabled -> stringResource(Res.string.settings_mcp_status_disabled)
                        McpConnectionStatus.Unknown -> stringResource(Res.string.settings_mcp_status_unknown)
                    }
                }
                Text(
                    text = if (server.toolCount > 0) {
                        "$statusText · ${stringResource(Res.string.settings_mcp_tool_count, server.toolCount)}"
                    } else {
                        statusText
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = when (server.connectionStatus) {
                        McpConnectionStatus.Failed -> MaterialTheme.colorScheme.error
                        McpConnectionStatus.NeedsAuth -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                if (server.failureDetail.isNotBlank()) {
                    // Two lines are enough to name the cause; the whole text (a server's own error
                    // body can be a paragraph) stays one tap away.
                    var showFullFailure by remember(server.failureDetail) { mutableStateOf(false) }
                    Text(
                        text = server.failureDetail,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (server.canAuthorize) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        maxLines = if (showFullFailure) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { showFullFailure = !showFullFailure },
                    )
                }
                if (server.connectionStatus == McpConnectionStatus.NeedsAuth) {
                    Text(
                        text = stringResource(Res.string.settings_mcp_auth_required_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // A flow that is waiting for the browser: the URL is shown so it can be opened by
                // hand when the system browser did not come up (or the server wants a specific one).
                val pendingAuthorizationUrl = server.pendingAuthorizationUrl
                if (pendingAuthorizationUrl != null) {
                    Spacer(Modifier.height(8.dp))
                    SelectionContainer {
                        Text(
                            text = pendingAuthorizationUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    TextButton(
                        onClick = { openUrl(pendingAuthorizationUrl) },
                        modifier = Modifier.handCursor(),
                    ) {
                        Text(stringResource(Res.string.settings_mcp_open_link))
                    }
                    // Opening the link is not always possible (a custom scheme the platform does
                    // not route, or a browser that never comes to the foreground), so the owner can
                    // also take the URL to another device or app.
                    var copied by remember(pendingAuthorizationUrl) { mutableStateOf(false) }
                    TextButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(pendingAuthorizationUrl))
                            copied = true
                        },
                        modifier = Modifier.handCursor(),
                    ) {
                        Text(
                            stringResource(
                                if (copied) Res.string.settings_mcp_link_copied else Res.string.settings_mcp_copy_link,
                            ),
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                // FlowRow, not Row: five actions do not fit one line on a phone, and a clipped
                // "Авторизоваться" is the same defect as a missing one.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (server.canAuthorize) {
                        // The sign-in is the point of this card while the server refuses us, so it
                        // gets the weight of a primary action instead of blending into the links.
                        Button(onClick = onAuthorize, modifier = Modifier.handCursor()) {
                            Text(stringResource(Res.string.settings_mcp_authorize))
                        }
                    }
                    if (server.canRetry) {
                        TextButton(onClick = onRefresh, modifier = Modifier.handCursor()) {
                            Text(stringResource(Res.string.settings_mcp_retry))
                        }
                    }
                    TextButton(onClick = onRefresh, modifier = Modifier.handCursor()) {
                        Text(stringResource(Res.string.settings_mcp_refresh))
                    }
                    if (server.hasStoredToken) {
                        TextButton(onClick = onSignOut, modifier = Modifier.handCursor()) {
                            Text(stringResource(Res.string.settings_mcp_sign_out))
                        }
                    }
                    TextButton(onClick = onRemove, modifier = Modifier.handCursor()) {
                        Text(
                            text = stringResource(Res.string.settings_mcp_remove),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                // Manual token: the escape hatch for servers whose discovery fails or that only
                // accept a personal access token. Entered here, never in the chat.
                TextButton(
                    onClick = { showTokenDialog = true },
                    modifier = Modifier.handCursor(),
                ) {
                    Text(stringResource(Res.string.settings_mcp_manual_token))
                }

                // Tools list
                if (server.tools.isNotEmpty()) {
                    if (server.toolsFromCache) {
                        Text(
                            text = stringResource(Res.string.settings_mcp_tools_cached),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    for (tool in server.tools) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = tool.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onBackground,
                                )
                                if (tool.description.isNotEmpty()) {
                                    Text(
                                        text = tool.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Switch(
                                checked = tool.isEnabled,
                                onCheckedChange = { enabled -> onToggleTool(tool.id, enabled) },
                            )
                        }
                    }
                } else if (server.connectionStatus == McpConnectionStatus.Connected) {
                    Text(
                        text = stringResource(Res.string.settings_mcp_no_tools),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (showTokenDialog) {
        McpManualTokenDialog(
            serverName = server.name,
            onDismiss = { showTokenDialog = false },
            onSave = { token ->
                showTokenDialog = false
                onSetManualToken(token)
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun McpManualTokenDialog(
    serverName: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var token by remember { mutableStateOf("") }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = serverName,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(12.dp))
            PotsOutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text(stringResource(Res.string.settings_mcp_manual_token_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(Res.string.settings_mcp_manual_token_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss, modifier = Modifier.handCursor()) {
                    Text(stringResource(Res.string.settings_mcp_deny))
                }
                TextButton(
                    onClick = { onSave(token) },
                    enabled = token.isNotBlank(),
                    modifier = Modifier.handCursor(),
                ) {
                    Text(stringResource(Res.string.settings_mcp_save))
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

private data class HeaderEntry(val key: String = "Authorization", val value: String = "")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddMcpServerDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String, Map<String, String>) -> Unit,
    onAddPopular: (PopularMcpServer) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    val headers = remember { mutableStateListOf(HeaderEntry()) }
    // When a popular server needs auth (e.g. Jina), prefill the form and show an API key field.
    var requiresAuth by remember { mutableStateOf(false) }
    var apiKey by remember { mutableStateOf("") }
    val mcpScrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    fun prefillPopularWithAuth(server: PopularMcpServer) {
        name = server.name
        url = server.url
        requiresAuth = true
        apiKey = ""
        headers.clear()
        headers.add(HeaderEntry(key = "Authorization", value = ""))
        scope.launch { mcpScrollState.animateScrollTo(0) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Box {
            Column(
                modifier = Modifier
                    .verticalScroll(mcpScrollState)
                    .padding(16.dp),
            ) {
                Text(
                    text = stringResource(Res.string.settings_mcp_add_server),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(16.dp))

                PotsOutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(Res.string.settings_mcp_server_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                PotsOutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(Res.string.settings_mcp_server_url)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))

                if (requiresAuth) {
                    PotsOutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text(stringResource(Res.string.settings_mcp_api_key)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(Res.string.settings_mcp_api_key_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                } else {
                    headers.forEachIndexed { index, entry ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // The floating labels must stay on one line: at large font
                            // scales a wrapped label grows upward out of the field and
                            // lands on top of the URL field above it.
                            PotsOutlinedTextField(
                                value = entry.key,
                                onValueChange = { headers[index] = entry.copy(key = it) },
                                label = {
                                    Text(
                                        text = stringResource(Res.string.settings_mcp_header_key),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                singleLine = true,
                                modifier = Modifier.weight(0.5f),
                            )
                            Spacer(Modifier.width(8.dp))
                            PotsOutlinedTextField(
                                value = entry.value,
                                onValueChange = { headers[index] = entry.copy(value = it) },
                                label = {
                                    Text(
                                        text = stringResource(Res.string.settings_mcp_header_value),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                singleLine = true,
                                modifier = Modifier.weight(0.5f),
                            )
                            IconButton(
                                onClick = { headers.removeAt(index) },
                                modifier = Modifier.handCursor(),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = stringResource(Res.string.settings_mcp_remove),
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }

                    TextButton(
                        onClick = { headers.add(HeaderEntry(key = "", value = "")) },
                        modifier = Modifier.handCursor(),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(Res.string.settings_mcp_add_header))
                    }
                }

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = {
                            val headerMap = buildMap {
                                headers
                                    .filter { it.key.isNotBlank() && it.value.isNotBlank() }
                                    .forEach { put(it.key.trim(), it.value.trim()) }
                                if (requiresAuth && apiKey.isNotBlank()) {
                                    // Replace any Authorization row with the dedicated API key field.
                                    keys.filter { it.equals("Authorization", ignoreCase = true) }
                                        .toList()
                                        .forEach { remove(it) }
                                    put("Authorization", authorizationHeaderValue(apiKey))
                                }
                            }
                            onAdd(name, url, headerMap)
                        },
                        // Auth-required popular servers (e.g. Jina) can still be added without a
                        // key — some tools work unauthenticated; search needs a key later.
                        enabled = name.isNotBlank() && url.isNotBlank(),
                        modifier = Modifier.handCursor(),
                    ) {
                        Text(stringResource(Res.string.settings_mcp_add))
                    }
                }

                if (popularMcpServers.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(Res.string.settings_mcp_popular_servers),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    for (server in popularMcpServers) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(CardDefaults.shape)
                                .clickable {
                                    if (server.requiresAuth) {
                                        prefillPopularWithAuth(server)
                                    } else {
                                        onAddPopular(server)
                                    }
                                }
                                .handCursor(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            ),
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = server.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = server.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }

                Spacer(Modifier.height(16.dp))
            }
            VerticalScrollbarForScroll(
                scrollState = mcpScrollState,
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }
    }
}
