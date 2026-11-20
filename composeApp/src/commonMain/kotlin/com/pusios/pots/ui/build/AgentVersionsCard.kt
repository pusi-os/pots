package com.pusios.pots.ui.build

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pusios.pots.build.AgentUpdate
import com.pusios.pots.build.AgentVersionRow
import com.pusios.pots.build.AgentVersions
import com.pusios.pots.build.UpdateCost
import com.pusios.pots.ui.handCursor
import org.jetbrains.compose.resources.stringResource
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.pots_build_agents_available
import pots.composeapp.generated.resources.pots_build_agents_check
import pots.composeapp.generated.resources.pots_build_agents_check_all
import pots.composeapp.generated.resources.pots_build_agents_check_failed
import pots.composeapp.generated.resources.pots_build_agents_checking
import pots.composeapp.generated.resources.pots_build_agents_install
import pots.composeapp.generated.resources.pots_build_agents_not_checked
import pots.composeapp.generated.resources.pots_build_agents_not_installed
import pots.composeapp.generated.resources.pots_build_agents_remove
import pots.composeapp.generated.resources.pots_build_agents_remove_body
import pots.composeapp.generated.resources.pots_build_agents_remove_bulk
import pots.composeapp.generated.resources.pots_build_agents_remove_bulk_action
import pots.composeapp.generated.resources.pots_build_agents_remove_title
import pots.composeapp.generated.resources.pots_build_agents_retry
import pots.composeapp.generated.resources.pots_build_agents_update
import pots.composeapp.generated.resources.pots_build_agents_update_failed
import pots.composeapp.generated.resources.pots_build_agents_update_gateway
import pots.composeapp.generated.resources.pots_build_agents_update_heavy
import pots.composeapp.generated.resources.pots_build_agents_update_light
import pots.composeapp.generated.resources.pots_build_agents_update_title
import pots.composeapp.generated.resources.pots_build_agents_up_to_date
import pots.composeapp.generated.resources.pots_build_agents_up_to_date_at
import pots.composeapp.generated.resources.pots_build_agents_updating_until
import pots.composeapp.generated.resources.pots_build_setup_agents_title
import pots.composeapp.generated.resources.settings_sandbox_agent_version_unknown
import pots.composeapp.generated.resources.settings_sandbox_cancel

/**
 * The coding agents with their whole flow, one row each: what is installed, what
 * the registry offers, and the four things you can do about it.
 *
 * Every action is per agent, because the four are not interchangeable — a Pi
 * update is 7 MiB and a Hermes update restarts the gateway — and the dialog each
 * one opens says what it costs before anything runs.
 */
@Composable
internal fun AgentVersionsSection(
    rows: List<AgentVersionRow>,
    busy: Boolean,
    onCheck: (Set<String>) -> Unit,
    onInstall: (String) -> Unit,
    onUpdate: (String) -> Unit,
    onRemove: (agentId: String, includeBulk: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The row each dialog is about; null while it is closed.
    var updating by remember { mutableStateOf<AgentVersionRow?>(null) }
    var removing by remember { mutableStateOf<AgentVersionRow?>(null) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.pots_build_setup_agents_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(end = 8.dp),
            )
            TextButton(
                onClick = { onCheck(rows.map { it.id }.toSet()) },
                enabled = !busy && rows.none { it.update is AgentUpdate.Checking },
                modifier = Modifier.handCursor(),
            ) {
                Text(stringResource(Res.string.pots_build_agents_check_all))
            }
        }
        rows.forEachIndexed { index, row ->
            if (index > 0) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            AgentVersionRowContent(
                row = row,
                busy = busy,
                onCheck = { onCheck(setOf(row.id)) },
                onInstall = { onInstall(row.id) },
                onUpdate = { updating = row },
                onRemove = { removing = row },
            )
        }
    }

    updating?.let { row ->
        UpdateAgentDialog(
            row = row,
            onDismiss = { updating = null },
            onConfirm = {
                updating = null
                onUpdate(row.id)
            },
        )
    }

    removing?.let { row ->
        RemoveAgentDialog(
            row = row,
            onDismiss = { removing = null },
            onConfirm = { includeBulk ->
                removing = null
                onRemove(row.id, includeBulk)
            },
        )
    }
}

@Composable
private fun AgentVersionRowContent(
    row: AgentVersionRow,
    busy: Boolean,
    onCheck: () -> Unit,
    onInstall: () -> Unit,
    onUpdate: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(end = 8.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (row.update is AgentUpdate.Checking || row.update is AgentUpdate.Updating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.size(6.dp))
                }
                Text(
                    text = rowStatus(row),
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor(row.update),
                )
            }
        }
        // An installed agent keeps the same three actions in every state. The
        // check is what says whether an update is worth taking; removal never
        // depends on that answer, and a check that failed must not take the
        // remove button with it — that is exactly the state a sandbox with no
        // network is in.
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            when (val update = row.update) {
                AgentUpdate.NotInstalled -> {
                    AgentAction(
                        label = stringResource(Res.string.pots_build_agents_install),
                        enabled = !busy,
                        onClick = onInstall,
                    )
                }

                is AgentUpdate.Unchecked, is AgentUpdate.CheckFailed, is AgentUpdate.UpToDate,
                is AgentUpdate.Available, is AgentUpdate.UpdateFailed -> {
                    AgentAction(
                        label = stringResource(
                            if (update is AgentUpdate.CheckFailed) {
                                Res.string.pots_build_agents_retry
                            } else {
                                Res.string.pots_build_agents_check
                            },
                        ),
                        enabled = !busy,
                        onClick = onCheck,
                    )
                    AgentAction(
                        label = stringResource(Res.string.pots_build_agents_update),
                        enabled = !busy,
                        onClick = onUpdate,
                    )
                    AgentAction(
                        label = stringResource(Res.string.pots_build_agents_remove),
                        enabled = !busy,
                        onClick = onRemove,
                        error = true,
                    )
                }

                // Both are in flight: the buttons would race the job that owns them.
                AgentUpdate.Checking, AgentUpdate.Updating -> Unit
            }
        }
    }
}

/** One line saying where this agent stands — never a guess, see [AgentUpdate]. */
@Composable
private fun rowStatus(row: AgentVersionRow): String = when (val update = row.update) {
    AgentUpdate.NotInstalled -> stringResource(Res.string.pots_build_agents_not_installed)
    is AgentUpdate.Unchecked -> update.installed
        ?.let { stringResource(Res.string.pots_build_agents_not_checked, it) }
        ?: stringResource(Res.string.settings_sandbox_agent_version_unknown)
    AgentUpdate.Checking -> stringResource(Res.string.pots_build_agents_checking)
    is AgentUpdate.UpToDate -> update.checkedAt
        ?.let { stringResource(Res.string.pots_build_agents_up_to_date_at, update.installed, it) }
        ?: stringResource(Res.string.pots_build_agents_up_to_date, update.installed)
    is AgentUpdate.Available -> stringResource(
        Res.string.pots_build_agents_available,
        update.installed,
        update.latest,
    )
    is AgentUpdate.CheckFailed -> stringResource(Res.string.pots_build_agents_check_failed)
    AgentUpdate.Updating -> {
        // The cap is the agent's own (900 s / 1800 s / 5400 s): a spinner with no
        // bound reads as "stuck" the moment the download is slow.
        val minutes = (AgentVersions.get(row.id)?.updateTimeoutSeconds ?: 1800L) / 60
        stringResource(Res.string.pots_build_agents_updating_until, minutes.toInt())
    }
    is AgentUpdate.UpdateFailed -> stringResource(Res.string.pots_build_agents_update_failed)
}

@Composable
private fun statusColor(update: AgentUpdate) = when (update) {
    is AgentUpdate.Available -> MaterialTheme.colorScheme.primary
    is AgentUpdate.CheckFailed, is AgentUpdate.UpdateFailed -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun AgentAction(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    error: Boolean = false,
) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.handCursor()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
    }
}

/** What the update costs, said before it runs — the four are not equally expensive. */
@Composable
private fun UpdateAgentDialog(
    row: AgentVersionRow,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val cost = AgentVersions.get(row.id)?.updateCost ?: UpdateCost.Light
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.pots_build_agents_update_title, row.title)) },
        text = {
            Text(
                stringResource(
                    when (cost) {
                        UpdateCost.Light -> Res.string.pots_build_agents_update_light
                        UpdateCost.Heavy -> Res.string.pots_build_agents_update_heavy
                        UpdateCost.Gateway -> Res.string.pots_build_agents_update_gateway
                    },
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.handCursor()) {
                Text(stringResource(Res.string.pots_build_agents_update))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.handCursor()) {
                Text(stringResource(Res.string.settings_sandbox_cancel))
            }
        },
    )
}

/**
 * Removal is not the same as uninstalling a product: the configuration and the
 * session history beside the binary are the user's, and they stay. Only Hermes
 * asks a second question, because its checkout is the thing that takes 5 GiB —
 * and the safe answer is the one the confirm button gives.
 */
@Composable
private fun RemoveAgentDialog(
    row: AgentVersionRow,
    onDismiss: () -> Unit,
    onConfirm: (includeBulk: Boolean) -> Unit,
) {
    val bulk = AgentVersions.get(row.id)?.bulkPath != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.pots_build_agents_remove_title, row.title)) },
        text = {
            Column {
                Text(stringResource(Res.string.pots_build_agents_remove_body))
                if (bulk) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(Res.string.pots_build_agents_remove_bulk))
                    TextButton(
                        onClick = { onConfirm(true) },
                        modifier = Modifier.handCursor(),
                    ) {
                        Text(
                            text = stringResource(Res.string.pots_build_agents_remove_bulk_action),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(false) }, modifier = Modifier.handCursor()) {
                Text(
                    text = stringResource(Res.string.pots_build_agents_remove),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.handCursor()) {
                Text(stringResource(Res.string.settings_sandbox_cancel))
            }
        },
    )
}
