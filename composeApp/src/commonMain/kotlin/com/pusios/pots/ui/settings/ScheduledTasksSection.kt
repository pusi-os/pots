@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, kotlin.time.ExperimentalTime::class)

package com.pusios.pots.ui.settings

// The «Запланированные задачи» section of Settings → Agent.
//
// The list used to be hidden whenever scheduling was off (`if (isSchedulingEnabled && …)`), so an
// owner looking at an empty list could not tell "nothing scheduled" from "the section is off" —
// and could not reach the tasks at all. The section is always rendered now; the toggle only says
// whether the scheduler may fire anything.

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pusios.pots.data.HeartbeatLogEntry
import com.pusios.pots.data.ScheduledTask
import com.pusios.pots.data.TaskTrigger
import com.pusios.pots.ui.PotsOutlinedTextField
import com.pusios.pots.ui.handCursor
import com.pusios.pots.ui.potsAdaptiveCardSurface
import kotlinx.collections.immutable.ImmutableList
import org.jetbrains.compose.resources.stringResource
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.execution_log_status_fail
import pots.composeapp.generated.resources.execution_log_status_ok
import pots.composeapp.generated.resources.settings_scheduled_tasks
import pots.composeapp.generated.resources.settings_scheduled_tasks_add
import pots.composeapp.generated.resources.settings_scheduled_tasks_cancel
import pots.composeapp.generated.resources.settings_scheduled_tasks_counts
import pots.composeapp.generated.resources.settings_scheduled_tasks_description
import pots.composeapp.generated.resources.settings_scheduled_tasks_disabled
import pots.composeapp.generated.resources.settings_scheduled_tasks_empty_body
import pots.composeapp.generated.resources.settings_scheduled_tasks_empty_title
import pots.composeapp.generated.resources.settings_task_action_delete
import pots.composeapp.generated.resources.settings_task_action_edit
import pots.composeapp.generated.resources.settings_task_action_pause
import pots.composeapp.generated.resources.settings_task_action_resume
import pots.composeapp.generated.resources.settings_task_action_run_now
import pots.composeapp.generated.resources.settings_task_cancel_action
import pots.composeapp.generated.resources.settings_task_create_action
import pots.composeapp.generated.resources.settings_task_create_title
import pots.composeapp.generated.resources.settings_task_delete_body
import pots.composeapp.generated.resources.settings_task_delete_confirm
import pots.composeapp.generated.resources.settings_task_delete_title
import pots.composeapp.generated.resources.settings_task_details_created
import pots.composeapp.generated.resources.settings_task_details_history
import pots.composeapp.generated.resources.settings_task_details_last_run
import pots.composeapp.generated.resources.settings_task_details_next_run
import pots.composeapp.generated.resources.settings_task_details_no_heartbeat_runs
import pots.composeapp.generated.resources.settings_task_details_no_runs
import pots.composeapp.generated.resources.settings_task_details_prompt
import pots.composeapp.generated.resources.settings_task_details_runs
import pots.composeapp.generated.resources.settings_task_details_type
import pots.composeapp.generated.resources.settings_task_edit_title
import pots.composeapp.generated.resources.settings_task_field_cron
import pots.composeapp.generated.resources.settings_task_field_description
import pots.composeapp.generated.resources.settings_task_field_invalid_cron
import pots.composeapp.generated.resources.settings_task_field_invalid_time
import pots.composeapp.generated.resources.settings_task_field_prompt
import pots.composeapp.generated.resources.settings_task_field_time
import pots.composeapp.generated.resources.settings_task_field_type
import pots.composeapp.generated.resources.settings_task_never_ran
import pots.composeapp.generated.resources.settings_task_row_next_run
import pots.composeapp.generated.resources.settings_task_save
import pots.composeapp.generated.resources.settings_task_state_active
import pots.composeapp.generated.resources.settings_task_state_done
import pots.composeapp.generated.resources.settings_task_state_paused
import pots.composeapp.generated.resources.settings_task_type_heartbeat
import pots.composeapp.generated.resources.settings_task_type_one_off
import pots.composeapp.generated.resources.settings_task_type_recurring
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

@Composable
internal fun ScheduledTaskList(
    tasks: ImmutableList<ScheduledTask>,
    heartbeatLog: ImmutableList<HeartbeatLogEntry>,
    isSchedulingEnabled: Boolean,
    onToggleScheduling: (Boolean) -> Unit,
    onCancelTask: (String) -> Unit,
    onPauseTask: (String) -> Unit,
    onResumeTask: (String) -> Unit,
    onRunTaskNow: (ScheduledTask) -> Unit,
    onUpdateTask: (ScheduledTask) -> Unit,
    onCreateTask: (String, String, TaskTrigger, Long, String?) -> Unit,
) {
    var selectedTaskId by remember { mutableStateOf<String?>(null) }
    var editingTaskId by remember { mutableStateOf<String?>(null) }
    var taskToDelete by remember { mutableStateOf<ScheduledTask?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        ToggleableHeadline(
            title = stringResource(Res.string.settings_scheduled_tasks),
            description = stringResource(Res.string.settings_scheduled_tasks_description),
            checked = isSchedulingEnabled,
            onCheckedChange = onToggleScheduling,
            actions = {
                IconButton(
                    onClick = { showCreateDialog = true },
                    modifier = Modifier.handCursor(),
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(Res.string.settings_scheduled_tasks_add),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )
        Spacer(Modifier.height(12.dp))

        if (tasks.isEmpty()) {
            Text(
                text = stringResource(Res.string.settings_scheduled_tasks_empty_title),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(Res.string.settings_scheduled_tasks_empty_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { showCreateDialog = true },
                modifier = Modifier.handCursor(),
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.settings_scheduled_tasks_add))
            }
        } else {
            val counts = scheduledTaskCounts(tasks)
            Text(
                text = stringResource(Res.string.settings_scheduled_tasks_counts, counts.active, counts.paused),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // The list stays readable with the schedule off; without this line an owner would
            // wait for a run that the scheduler is not allowed to make.
            if (!isSchedulingEnabled) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(Res.string.settings_scheduled_tasks_disabled),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(8.dp))
            tasks.forEach { task ->
                ScheduledTaskRowItem(
                    task = task,
                    onClick = { selectedTaskId = task.id },
                    onDelete = { taskToDelete = task },
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    val selectedTask = selectedTaskId?.let { id -> tasks.firstOrNull { it.id == id } }
    if (selectedTask != null) {
        TaskDetailsSheet(
            task = selectedTask,
            heartbeatLog = heartbeatLog,
            onDismiss = { selectedTaskId = null },
            onPause = { onPauseTask(selectedTask.id) },
            onResume = { onResumeTask(selectedTask.id) },
            onRunNow = { onRunTaskNow(selectedTask) },
            onEdit = {
                editingTaskId = selectedTask.id
                selectedTaskId = null
            },
            onDelete = {
                selectedTaskId = null
                taskToDelete = selectedTask
            },
        )
    }

    val editingTask = editingTaskId?.let { id -> tasks.firstOrNull { it.id == id } }
    if (editingTask != null) {
        TaskEditorDialog(
            existing = editingTask,
            onDismiss = { editingTaskId = null },
            onSave = { description, prompt, _, scheduledAtEpochMs, cron ->
                onUpdateTask(
                    editingTask.copy(
                        description = description,
                        prompt = prompt,
                        scheduledAtEpochMs = scheduledAtEpochMs,
                        cron = cron,
                    ),
                )
                editingTaskId = null
            },
        )
    }

    if (showCreateDialog) {
        TaskEditorDialog(
            existing = null,
            onDismiss = { showCreateDialog = false },
            onSave = { description, prompt, trigger, scheduledAtEpochMs, cron ->
                onCreateTask(description, prompt, trigger, scheduledAtEpochMs, cron)
                showCreateDialog = false
            },
        )
    }

    taskToDelete?.let { task ->
        AlertDialog(
            onDismissRequest = { taskToDelete = null },
            title = { Text(stringResource(Res.string.settings_task_delete_title)) },
            text = { Text(stringResource(Res.string.settings_task_delete_body, task.description)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onCancelTask(task.id)
                        taskToDelete = null
                    },
                    modifier = Modifier.handCursor(),
                ) {
                    Text(
                        text = stringResource(Res.string.settings_task_delete_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { taskToDelete = null },
                    modifier = Modifier.handCursor(),
                ) {
                    Text(stringResource(Res.string.settings_task_cancel_action))
                }
            },
        )
    }
}

/** One row: description, type + schedule line, and a status pill the eye catches first. */
@Composable
private fun ScheduledTaskRowItem(
    task: ScheduledTask,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val row = scheduledTaskRow(task)
    val typeLabel = when (row.type) {
        ScheduledTaskType.ONE_OFF -> stringResource(Res.string.settings_task_type_one_off)
        ScheduledTaskType.RECURRING -> stringResource(Res.string.settings_task_type_recurring)
        ScheduledTaskType.EVERY_HEARTBEAT -> stringResource(Res.string.settings_task_type_heartbeat)
    }
    val scheduleText = when {
        row.nextRunEpochMs != null ->
            stringResource(Res.string.settings_task_row_next_run, formatTaskTimestamp(row.nextRunEpochMs))

        row.state == ScheduledTaskState.PAUSED -> stringResource(Res.string.settings_task_state_paused)
        row.state == ScheduledTaskState.DONE -> stringResource(Res.string.settings_task_state_done)
        else -> typeLabel
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .potsAdaptiveCardSurface(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .handCursor()
            .heightIn(min = 56.dp)
            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = task.description,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "$typeLabel · $scheduleText",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        TaskStatePill(state = row.state)
        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(48.dp).handCursor(),
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = stringResource(Res.string.settings_scheduled_tasks_cancel),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TaskStatePill(state: ScheduledTaskState) {
    val (label, color) = when (state) {
        ScheduledTaskState.ACTIVE ->
            stringResource(Res.string.settings_task_state_active) to MaterialTheme.colorScheme.primary

        ScheduledTaskState.PAUSED ->
            stringResource(Res.string.settings_task_state_paused) to MaterialTheme.colorScheme.tertiary

        ScheduledTaskState.DONE ->
            stringResource(Res.string.settings_task_state_done) to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = color.copy(alpha = 0.16f),
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun TaskDetailsSheet(
    task: ScheduledTask,
    heartbeatLog: ImmutableList<HeartbeatLogEntry>,
    onDismiss: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRunNow: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val row = scheduledTaskRow(task)
    var historyExpanded by remember { mutableStateOf(false) }
    var promptExpanded by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                text = task.description,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(8.dp))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TaskActionButton(
                    icon = if (row.state == ScheduledTaskState.PAUSED) Icons.Default.PlayArrow else Icons.Default.Pause,
                    label = if (row.state == ScheduledTaskState.PAUSED) {
                        stringResource(Res.string.settings_task_action_resume)
                    } else {
                        stringResource(Res.string.settings_task_action_pause)
                    },
                    onClick = if (row.state == ScheduledTaskState.PAUSED) onResume else onPause,
                    // A finished one-off has nothing to pause or resume.
                    enabled = row.state != ScheduledTaskState.DONE,
                )
                TaskActionButton(
                    icon = Icons.Default.PlayArrow,
                    label = stringResource(Res.string.settings_task_action_run_now),
                    onClick = onRunNow,
                )
                TaskActionButton(
                    icon = Icons.Default.Edit,
                    label = stringResource(Res.string.settings_task_action_edit),
                    onClick = onEdit,
                )
                TaskActionButton(
                    icon = Icons.Default.Delete,
                    label = stringResource(Res.string.settings_task_action_delete),
                    onClick = onDelete,
                    destructive = true,
                )
            }

            Spacer(Modifier.height(12.dp))
            TaskDetailRow(
                label = stringResource(Res.string.settings_task_details_type),
                value = when (row.type) {
                    ScheduledTaskType.ONE_OFF -> stringResource(Res.string.settings_task_type_one_off)
                    ScheduledTaskType.RECURRING -> stringResource(Res.string.settings_task_type_recurring)
                    ScheduledTaskType.EVERY_HEARTBEAT -> stringResource(Res.string.settings_task_type_heartbeat)
                },
            )
            if (task.cron != null) {
                TaskDetailRow(
                    label = stringResource(Res.string.settings_task_field_cron),
                    value = task.cron,
                )
            }
            if (row.nextRunEpochMs != null) {
                TaskDetailRow(
                    label = stringResource(Res.string.settings_task_details_next_run),
                    value = formatTaskTimestamp(row.nextRunEpochMs),
                )
            }
            TaskDetailRow(
                label = stringResource(Res.string.settings_task_details_created),
                value = formatTaskTimestamp(task.createdAtEpochMs),
            )
            TaskDetailRow(
                label = stringResource(Res.string.settings_task_details_last_run),
                value = if (task.runCount > 0) {
                    formatTaskTimestamp(task.lastRunEpochMs)
                } else {
                    stringResource(Res.string.settings_task_never_ran)
                },
            )
            TaskDetailRow(
                label = stringResource(Res.string.settings_task_details_runs),
                value = task.runCount.toString(),
            )

            Spacer(Modifier.height(12.dp))
            CollapsibleBlock(
                title = stringResource(Res.string.settings_task_details_history),
                expanded = historyExpanded,
                onToggle = { historyExpanded = !historyExpanded },
            ) {
                if (task.trigger == TaskTrigger.HEARTBEAT) {
                    // Heartbeat additions have no log of their own — they fire inside every
                    // heartbeat run, so the shared journal is the honest surface.
                    if (heartbeatLog.isEmpty()) {
                        EmptyLogText(stringResource(Res.string.settings_task_details_no_heartbeat_runs))
                    } else {
                        heartbeatLog.forEach { entry ->
                            ExecutionLogRow(
                                success = entry.success,
                                timestampEpochMs = entry.timestampEpochMs,
                                message = entry.error ?: entry.note,
                            )
                        }
                    }
                } else if (task.recentExecutions.isEmpty()) {
                    EmptyLogText(stringResource(Res.string.settings_task_details_no_runs))
                } else {
                    task.recentExecutions.forEach { entry ->
                        ExecutionLogRow(
                            success = entry.success,
                            timestampEpochMs = entry.timestampEpochMs,
                            message = entry.message,
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            CollapsibleBlock(
                title = stringResource(Res.string.settings_task_details_prompt),
                expanded = promptExpanded,
                onToggle = { promptExpanded = !promptExpanded },
            ) {
                Text(
                    text = task.prompt.ifBlank { "—" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun TaskActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.heightIn(min = 48.dp).handCursor(),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * A secondary block folded by default: the owner asked for the task list, not for a wall of
 * history and prompts. The 48dp header is the whole tap target, so the chevron is decoration.
 */
@Composable
private fun CollapsibleBlock(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).handCursor(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            if (expanded) {
                Column(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                    content()
                }
            }
        }
    }
}

/**
 * One dialog for both "create" and "edit": the fields are identical, and a second composable
 * would drift. [existing] null = create (the type selector is live); otherwise the type is fixed
 * because the trigger decides which extra field the scheduler reads.
 */
@Composable
private fun TaskEditorDialog(
    existing: ScheduledTask?,
    onDismiss: () -> Unit,
    onSave: (description: String, prompt: String, trigger: TaskTrigger, scheduledAtEpochMs: Long, cron: String?) -> Unit,
) {
    val isEdit = existing != null
    var description by remember(existing?.id) { mutableStateOf(existing?.description.orEmpty()) }
    var prompt by remember(existing?.id) { mutableStateOf(existing?.prompt.orEmpty()) }
    var trigger by remember(existing?.id) { mutableStateOf(existing?.trigger ?: TaskTrigger.TIME) }
    var timeText by remember(existing?.id) {
        mutableStateOf(
            formatTaskDateTimeInput(
                existing?.scheduledAtEpochMs
                    ?: (Clock.System.now() + 1.hours).toEpochMilliseconds(),
            ),
        )
    }
    var cronText by remember(existing?.id) { mutableStateOf(existing?.cron.orEmpty()) }

    val parsedTime = if (trigger == TaskTrigger.TIME) parseTaskDateTime(timeText) else null
    val timeValid = trigger != TaskTrigger.TIME || parsedTime != null
    val cronValid = trigger != TaskTrigger.CRON || isValidCron(cronText)
    val canSave = description.isNotBlank() && timeValid && cronValid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (isEdit) Res.string.settings_task_edit_title else Res.string.settings_task_create_title,
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PotsOutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = description,
                    onValueChange = { description = it },
                    maxLines = 2,
                    label = { Text(stringResource(Res.string.settings_task_field_description)) },
                )
                PotsOutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = prompt,
                    onValueChange = { prompt = it },
                    minLines = 3,
                    maxLines = 6,
                    label = { Text(stringResource(Res.string.settings_task_field_prompt)) },
                )

                if (!isEdit) {
                    Text(
                        text = stringResource(Res.string.settings_task_field_type),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = trigger == TaskTrigger.TIME,
                            onClick = { trigger = TaskTrigger.TIME },
                            label = { Text(stringResource(Res.string.settings_task_type_one_off)) },
                            modifier = Modifier.handCursor(),
                        )
                        FilterChip(
                            selected = trigger == TaskTrigger.CRON,
                            onClick = { trigger = TaskTrigger.CRON },
                            label = { Text(stringResource(Res.string.settings_task_type_recurring)) },
                            modifier = Modifier.handCursor(),
                        )
                        FilterChip(
                            selected = trigger == TaskTrigger.HEARTBEAT,
                            onClick = { trigger = TaskTrigger.HEARTBEAT },
                            label = { Text(stringResource(Res.string.settings_task_type_heartbeat)) },
                            modifier = Modifier.handCursor(),
                        )
                    }
                }

                when (trigger) {
                    TaskTrigger.TIME -> {
                        PotsOutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = timeText,
                            onValueChange = { timeText = it },
                            label = { Text(stringResource(Res.string.settings_task_field_time)) },
                        )
                        if (timeText.isNotBlank() && parsedTime == null) {
                            Text(
                                text = stringResource(Res.string.settings_task_field_invalid_time),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }

                    TaskTrigger.CRON -> {
                        PotsOutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = cronText,
                            onValueChange = { cronText = it },
                            label = { Text(stringResource(Res.string.settings_task_field_cron)) },
                        )
                        if (cronText.isNotBlank() && !cronValid) {
                            Text(
                                text = stringResource(Res.string.settings_task_field_invalid_cron),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }

                    TaskTrigger.HEARTBEAT -> Unit
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        description.trim(),
                        prompt.trim(),
                        trigger,
                        if (trigger == TaskTrigger.TIME) parsedTime ?: 0L else 0L,
                        if (trigger == TaskTrigger.CRON) cronText.trim() else null,
                    )
                },
                enabled = canSave,
                modifier = Modifier.handCursor(),
            ) {
                Text(
                    stringResource(if (isEdit) Res.string.settings_task_save else Res.string.settings_task_create_action),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.handCursor()) {
                Text(stringResource(Res.string.settings_task_cancel_action))
            }
        },
    )
}

@Composable
private fun TaskDetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(140.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ExecutionLogRow(success: Boolean, timestampEpochMs: Long, message: String?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = if (success) {
                stringResource(Res.string.execution_log_status_ok)
            } else {
                stringResource(Res.string.execution_log_status_fail)
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.widthIn(min = 36.dp),
        )
        Column {
            Text(
                text = formatTaskTimestamp(timestampEpochMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!message.isNullOrBlank()) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (success) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun EmptyLogText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
