package com.pusios.pots.ui.build

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import com.pusios.pots.build.AgentRemovalProgress
import com.pusios.pots.build.AgentUpdate
import com.pusios.pots.build.AgentVersionRow
import com.pusios.pots.build.AgentVersions
import com.pusios.pots.build.BuildAgents
import com.pusios.pots.build.UpdateCost
import com.pusios.pots.openUrl
import com.pusios.pots.ui.handCursor
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.github_mark
import pots.composeapp.generated.resources.ic_agent_check
import pots.composeapp.generated.resources.ic_agent_docs
import pots.composeapp.generated.resources.ic_agent_install
import pots.composeapp.generated.resources.ic_agent_update
import pots.composeapp.generated.resources.ic_arrow_drop_down
import pots.composeapp.generated.resources.ic_delete_forever
import pots.composeapp.generated.resources.pots_build_agents_about
import pots.composeapp.generated.resources.pots_build_agents_available
import pots.composeapp.generated.resources.pots_build_agents_check
import pots.composeapp.generated.resources.pots_build_agents_check_all
import pots.composeapp.generated.resources.pots_build_agents_check_failed
import pots.composeapp.generated.resources.pots_build_agents_checking
import pots.composeapp.generated.resources.pots_build_agents_docs
import pots.composeapp.generated.resources.pots_build_agents_github
import pots.composeapp.generated.resources.pots_build_agents_hermes_description
import pots.composeapp.generated.resources.pots_build_agents_install
import pots.composeapp.generated.resources.pots_build_agents_installing
import pots.composeapp.generated.resources.pots_build_agents_log
import pots.composeapp.generated.resources.pots_build_agents_not_checked
import pots.composeapp.generated.resources.pots_build_agents_not_installed
import pots.composeapp.generated.resources.pots_build_agents_opencode_description
import pots.composeapp.generated.resources.pots_build_agents_pi_description
import pots.composeapp.generated.resources.pots_build_agents_pi_web
import pots.composeapp.generated.resources.pots_build_agents_remove
import pots.composeapp.generated.resources.pots_build_agents_remove_body
import pots.composeapp.generated.resources.pots_build_agents_remove_bulk
import pots.composeapp.generated.resources.pots_build_agents_remove_bulk_action
import pots.composeapp.generated.resources.pots_build_agents_remove_title
import pots.composeapp.generated.resources.pots_build_agents_removing
import pots.composeapp.generated.resources.pots_build_agents_removing_progress
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
 * one opens says what it costs before anything runs. While one of them runs, the
 * row's buttons step aside for its progress and its log: a removal that shows
 * nothing for five minutes reads as a removal that did not start.
 */
@Composable
internal fun AgentVersionsSection(
    rows: List<AgentVersionRow>,
    busy: Boolean,
    onCheck: (Set<String>) -> Unit,
    onInstall: (String) -> Unit,
    onUpdate: (String) -> Unit,
    onRemove: (agentId: String, includeBulk: Boolean) -> Unit,
    log: String = "",
    logDropped: Boolean = false,
    activeAgentId: String? = null,
    removalProgress: AgentRemovalProgress? = null,
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
                // A check would overwrite the state of a task that is running:
                // its row would drop the progress block and the log mid-flight.
                enabled = !busy && rows.none { inFlight(it.update) },
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
                log = log,
                logDropped = logDropped,
                activeAgentId = activeAgentId,
                removalProgress = removalProgress,
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
    log: String,
    logDropped: Boolean,
    activeAgentId: String?,
    removalProgress: AgentRemovalProgress?,
    onCheck: () -> Unit,
    onInstall: () -> Unit,
    onUpdate: () -> Unit,
    onRemove: () -> Unit,
) {
    val update = row.update
    // An install, an update or a removal has a bar and a log to show; a check is
    // over in seconds and keeps the row as it is, only without the buttons.
    val withProgress = showsProgress(update)
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
                if (inFlight(update)) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.size(6.dp))
                }
                // While a task runs, its line sits next to the bar below instead —
                // saying it twice on one row reads as two different things.
                if (!withProgress) {
                    Text(
                        text = rowStatus(row, removalProgress),
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColor(update),
                    )
                }
            }
        }
        // The panel that rides with this agent has no row of its own: its version
        // is a second line inside this one, quieter than the agent's own, and it
        // has no buttons — updating the agent updates the panel with it.
        row.childVersion?.let { panel ->
            Text(
                text = stringResource(Res.string.pots_build_agents_pi_web, panel),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
        if (withProgress) {
            AgentTaskProgress(
                status = rowStatus(row, removalProgress),
                fraction = if (update is AgentUpdate.Removing) {
                    removalFraction(row.id, activeAgentId, removalProgress)
                } else {
                    null
                },
                log = rowLog(row.id, activeAgentId, log),
                logDropped = logDropped,
            )
        } else {
            // An installed agent keeps the same three actions in every state. The
            // check is what says whether an update is worth taking; removal never
            // depends on that answer, and a check that failed must not take the
            // remove button with it — that is exactly the state a sandbox with no
            // network is in.
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                when (update) {
                    AgentUpdate.NotInstalled -> {
                        AgentAction(
                            icon = Res.drawable.ic_agent_install,
                            description = Res.string.pots_build_agents_install,
                            enabled = !busy,
                            onClick = onInstall,
                        )
                    }

                    is AgentUpdate.Unchecked, is AgentUpdate.CheckFailed, is AgentUpdate.UpToDate,
                    is AgentUpdate.Available, is AgentUpdate.UpdateFailed -> {
                        AgentAction(
                            icon = Res.drawable.ic_agent_check,
                            description = if (update is AgentUpdate.CheckFailed) {
                                Res.string.pots_build_agents_retry
                            } else {
                                Res.string.pots_build_agents_check
                            },
                            enabled = !busy,
                            onClick = onCheck,
                        )
                        AgentAction(
                            icon = Res.drawable.ic_agent_update,
                            description = Res.string.pots_build_agents_update,
                            enabled = !busy,
                            onClick = onUpdate,
                        )
                        AgentAction(
                            icon = Res.drawable.ic_delete_forever,
                            description = Res.string.pots_build_agents_remove,
                            enabled = !busy,
                            onClick = onRemove,
                            error = true,
                        )
                    }

                    // Both are in flight: the buttons would race the job that owns them.
                    AgentUpdate.Checking, AgentUpdate.Installing, AgentUpdate.Updating,
                    AgentUpdate.Removing -> Unit
                }
            }
        }
        AgentAboutSection(row.id)
    }
}

/**
 * The row's action area while a task runs: a bar, its status line and the log.
 *
 * Removal counts paths it knows before the first delete, so its bar is
 * determinate. An install or an update waits on a network and a registry with no
 * denominator — the bar runs, and no percentage is invented for it.
 */
@Composable
private fun AgentTaskProgress(
    status: String,
    fraction: Float?,
    log: String?,
    logDropped: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val bar = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp))
            if (fraction != null) {
                LinearProgressIndicator(progress = { fraction }, modifier = bar)
            } else {
                LinearProgressIndicator(modifier = bar)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (log != null) {
            Spacer(Modifier.height(4.dp))
            InstallLogPane(
                log = log,
                dropped = logDropped,
                logModifier = Modifier.heightIn(max = AGENT_LOG_MAX_HEIGHT),
                title = stringResource(Res.string.pots_build_agents_log),
            )
        }
    }
}

/**
 * The agent's own description, folded away until asked for, and the two links
 * that go with it. pi-web is Pi's panel package rather than an agent of its own,
 * so it has no row and nothing to open here.
 */
@Composable
private fun AgentAboutSection(agentId: String) {
    val about = agentAbout(agentId)
    var open by remember(agentId) { mutableStateOf(false) }
    if (about == null) return
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .toggleable(value = open, onValueChange = { open = it })
                .handCursor(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(Res.drawable.ic_arrow_drop_down),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp).rotate(if (open) 180f else 0f),
            )
            Text(
                text = stringResource(Res.string.pots_build_agents_about),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (open) {
            Text(
                text = stringResource(about.description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                AgentLink(
                    icon = Res.drawable.github_mark,
                    description = Res.string.pots_build_agents_github,
                    url = about.github,
                )
                AgentLink(
                    icon = Res.drawable.ic_agent_docs,
                    description = Res.string.pots_build_agents_docs,
                    url = about.docs,
                )
            }
        }
    }
}

/** An icon-only action: the glyph on screen, its name for TalkBack. */
@Composable
private fun AgentAction(
    icon: DrawableResource,
    description: StringResource,
    enabled: Boolean,
    onClick: () -> Unit,
    error: Boolean = false,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.handCursor()) {
        Icon(
            painter = painterResource(icon),
            contentDescription = stringResource(description),
            tint = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** A link out of the app: the browser, through the same helper the panels use. */
@Composable
private fun AgentLink(icon: DrawableResource, description: StringResource, url: String) {
    IconButton(onClick = { openUrl(url) }, modifier = Modifier.handCursor()) {
        Icon(
            painter = painterResource(icon),
            contentDescription = stringResource(description),
            tint = LocalContentColor.current,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** What the row's status line has to say, before a composition turns it into text. */
internal data class AgentStatusLine(val template: StringResource, val args: List<Any> = emptyList())

/**
 * One line saying where this agent stands — never a guess, see [AgentUpdate].
 *
 * Kept out of the composition so the states that are easy to get wrong (a failed
 * check, a removal in progress) are a value a test can assert on.
 */
internal fun statusLine(row: AgentVersionRow, removal: AgentRemovalProgress? = null): AgentStatusLine =
    when (val update = row.update) {
        AgentUpdate.NotInstalled -> AgentStatusLine(Res.string.pots_build_agents_not_installed)
        is AgentUpdate.Unchecked -> update.installed
            ?.let { AgentStatusLine(Res.string.pots_build_agents_not_checked, listOf(it)) }
            ?: AgentStatusLine(Res.string.settings_sandbox_agent_version_unknown)
        AgentUpdate.Checking -> AgentStatusLine(Res.string.pots_build_agents_checking)
        is AgentUpdate.UpToDate -> update.checkedAt
            ?.let { AgentStatusLine(Res.string.pots_build_agents_up_to_date_at, listOf(update.installed, it)) }
            ?: AgentStatusLine(Res.string.pots_build_agents_up_to_date, listOf(update.installed))
        is AgentUpdate.Available -> AgentStatusLine(
            Res.string.pots_build_agents_available,
            listOf(update.installed, update.latest),
        )
        is AgentUpdate.CheckFailed -> AgentStatusLine(Res.string.pots_build_agents_check_failed)
        // The cap is the agent's own (900 s / 1800 s / 5400 s): a spinner with no
        // bound reads as "stuck" the moment the download is slow.
        AgentUpdate.Updating -> AgentStatusLine(
            Res.string.pots_build_agents_updating_until,
            listOf(updateCapMinutes(row.id)),
        )
        AgentUpdate.Installing -> AgentStatusLine(Res.string.pots_build_agents_installing)
        AgentUpdate.Removing -> removal
            ?.let { AgentStatusLine(Res.string.pots_build_agents_removing_progress, listOf(it.done, it.total)) }
            ?: AgentStatusLine(Res.string.pots_build_agents_removing)
        is AgentUpdate.UpdateFailed -> AgentStatusLine(Res.string.pots_build_agents_update_failed)
    }

@Composable
private fun rowStatus(row: AgentVersionRow, removal: AgentRemovalProgress?): String {
    val line = statusLine(row, removal)
    return stringResource(line.template, *line.args.toTypedArray())
}

/** True while a job owns the row: its buttons would race that job. */
internal fun inFlight(update: AgentUpdate): Boolean = when (update) {
    AgentUpdate.Checking, AgentUpdate.Installing, AgentUpdate.Updating, AgentUpdate.Removing -> true
    else -> false
}

/** True for the three states that replace the buttons with a bar and the log. */
internal fun showsProgress(update: AgentUpdate): Boolean = when (update) {
    AgentUpdate.Installing, AgentUpdate.Updating, AgentUpdate.Removing -> true
    else -> false
}

/**
 * The log belongs to the row that owns the running task, and to no other: the
 * buffer is one for the whole app, so every other row would show a stranger's
 * output as its own.
 */
internal fun rowLog(rowId: String, activeAgentId: String?, log: String): String? =
    log.takeIf { it.isNotEmpty() && rowId == activeAgentId }

/** How full the removal bar is — only for the row that owns the running removal. */
private fun removalFraction(rowId: String, activeAgentId: String?, progress: AgentRemovalProgress?): Float? =
    progress?.takeIf { rowId == activeAgentId }?.fraction()

private fun updateCapMinutes(agentId: String): Int =
    ((AgentVersions.get(agentId)?.updateTimeoutSeconds ?: 1800L) / 60).toInt()

@Composable
private fun statusColor(update: AgentUpdate) = when (update) {
    is AgentUpdate.Available -> MaterialTheme.colorScheme.primary
    is AgentUpdate.CheckFailed, is AgentUpdate.UpdateFailed -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * What each agent is, in the sandbox's terms, and where its own project keeps its
 * code and its manual. The text is the README's facts, not a sales pitch.
 */
private class AgentAbout(
    val description: StringResource,
    val github: String,
    val docs: String,
)

private fun agentAbout(agentId: String): AgentAbout? = when (agentId) {
    BuildAgents.OPENCODE -> AgentAbout(
        description = Res.string.pots_build_agents_opencode_description,
        github = "https://github.com/anomalyco/opencode",
        docs = "https://opencode.ai/docs",
    )

    BuildAgents.PI -> AgentAbout(
        description = Res.string.pots_build_agents_pi_description,
        github = "https://github.com/earendil-works/pi",
        docs = "https://github.com/earendil-works/pi/tree/main/packages/coding-agent",
    )

    BuildAgents.HERMES -> AgentAbout(
        description = Res.string.pots_build_agents_hermes_description,
        github = "https://github.com/NousResearch/hermes-agent",
        docs = "https://hermes-agent.nousresearch.com/docs",
    )

    else -> null
}

/** Tall enough for the last lines of a failure, short enough to leave the row usable. */
private val AGENT_LOG_MAX_HEIGHT = 140.dp

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
