package com.pusios.pots.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.pusios.pots.data.LocalToolSelection
import com.pusios.pots.network.tools.ToolInfo
import com.pusios.pots.ui.handCursor
import com.pusios.pots.ui.potsAdaptiveCardBorder
import com.pusios.pots.ui.potsAdaptiveCardColors
import kotlinx.collections.immutable.ImmutableList
import org.jetbrains.compose.resources.stringResource
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.settings_local_model_description
import pots.composeapp.generated.resources.settings_local_model_mcp_title
import pots.composeapp.generated.resources.settings_local_model_reset
import pots.composeapp.generated.resources.settings_local_model_tools_title
import pots.composeapp.generated.resources.settings_mcp_no_servers
import pots.composeapp.generated.resources.settings_tools_none_available

/**
 * Settings section for the tools the on-device (local) model may call.
 *
 * The on-device path used to run on a hardcoded allowlist that also excluded every MCP tool,
 * which made the reachable tool set impossible to change without a new release. Here the user
 * picks the built-in tools and the MCP servers the local model gets to see.
 */
@Composable
internal fun LocalModelToolsContent(
    tools: ImmutableList<ToolInfo>,
    mcpServers: ImmutableList<McpServerUiState>,
    selection: LocalToolSelection,
    isConfigured: Boolean,
    onToggleTool: (String, Boolean) -> Unit,
    onToggleMcpServer: (String, Boolean) -> Unit,
    onReset: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(Res.string.settings_local_model_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(24.dp))

        Text(
            text = stringResource(Res.string.settings_local_model_tools_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )

        Spacer(Modifier.height(8.dp))

        if (tools.isEmpty()) {
            Text(
                text = stringResource(Res.string.settings_tools_none_available),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val columns = when {
                    maxWidth >= 800.dp -> 3
                    maxWidth >= 500.dp -> 2
                    else -> 1
                }
                val rows = tools.chunked(columns)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    rows.forEach { rowTools ->
                        Row(
                            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            rowTools.forEach { tool ->
                                LocalToolItem(
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                    title = tool.nameRes?.let { stringResource(it) } ?: tool.name,
                                    subtitle = tool.descriptionRes?.let { stringResource(it) } ?: tool.description,
                                    isChecked = tool.id in selection.toolIds,
                                    onToggle = { checked -> onToggleTool(tool.id, checked) },
                                )
                            }
                            repeat(columns - rowTools.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        Text(
            text = stringResource(Res.string.settings_local_model_mcp_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )

        Spacer(Modifier.height(8.dp))

        if (mcpServers.isEmpty()) {
            Text(
                text = stringResource(Res.string.settings_mcp_no_servers),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                mcpServers.forEach { server ->
                    LocalToolItem(
                        modifier = Modifier.fillMaxWidth(),
                        title = server.name,
                        subtitle = server.url,
                        isChecked = server.id in selection.mcpServerIds,
                        onToggle = { checked -> onToggleMcpServer(server.id, checked) },
                    )
                }
            }
        }

        if (isConfigured) {
            Spacer(Modifier.height(16.dp))
            TextButton(
                onClick = onReset,
                modifier = Modifier.handCursor(),
            ) {
                Text(stringResource(Res.string.settings_local_model_reset))
            }
        }
    }
}

@Composable
private fun LocalToolItem(
    title: String,
    subtitle: String,
    isChecked: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .clip(CardDefaults.shape)
            .clickable { onToggle(!isChecked) }
            .handCursor(),
        colors = potsAdaptiveCardColors(),
        border = potsAdaptiveCardBorder(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.width(16.dp))

            Checkbox(
                checked = isChecked,
                onCheckedChange = onToggle,
            )
        }
    }
}
