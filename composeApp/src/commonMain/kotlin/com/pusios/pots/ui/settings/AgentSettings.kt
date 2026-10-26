@file:OptIn(ExperimentalMaterial3Api::class)

package com.pusios.pots.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pusios.pots.data.AgentPersonaPresets
import com.pusios.pots.data.AgentTuning
import com.pusios.pots.data.AppSettings
import com.pusios.pots.data.HeartbeatLogEntry
import com.pusios.pots.data.MemoryEntry
import com.pusios.pots.data.ReasoningEffort
import com.pusios.pots.data.ScheduledTask
import com.pusios.pots.data.TaskTrigger
import com.pusios.pots.ui.PotsOutlinedTextField
import com.pusios.pots.ui.build.collapsePercentEscape
import com.pusios.pots.ui.components.PotsSlider
import com.pusios.pots.ui.components.SettingsListItem
import com.pusios.pots.ui.handCursor
import com.pusios.pots.ui.icons.Replay
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.default_soul
import pots.composeapp.generated.resources.execution_log_status_fail
import pots.composeapp.generated.resources.execution_log_status_ok
import pots.composeapp.generated.resources.settings_agent_auto_compact
import pots.composeapp.generated.resources.settings_agent_auto_compact_description
import pots.composeapp.generated.resources.settings_agent_compact_target
import pots.composeapp.generated.resources.settings_agent_compact_target_description
import pots.composeapp.generated.resources.settings_agent_compact_threshold
import pots.composeapp.generated.resources.settings_agent_compact_threshold_description
import pots.composeapp.generated.resources.settings_agent_decrease
import pots.composeapp.generated.resources.settings_agent_increase
import pots.composeapp.generated.resources.settings_agent_keep_recent
import pots.composeapp.generated.resources.settings_agent_keep_recent_description
import pots.composeapp.generated.resources.settings_agent_max_iterations
import pots.composeapp.generated.resources.settings_agent_max_iterations_description
import pots.composeapp.generated.resources.settings_agent_max_parallel
import pots.composeapp.generated.resources.settings_agent_max_parallel_description
import pots.composeapp.generated.resources.settings_agent_parameters
import pots.composeapp.generated.resources.settings_agent_parameters_description
import pots.composeapp.generated.resources.settings_agent_presets
import pots.composeapp.generated.resources.settings_agent_profile_create
import pots.composeapp.generated.resources.settings_agent_profile_delete
import pots.composeapp.generated.resources.settings_agent_profile_delete_confirm
import pots.composeapp.generated.resources.settings_agent_profile_name
import pots.composeapp.generated.resources.settings_agent_profile_no_skills
import pots.composeapp.generated.resources.settings_agent_profile_rename
import pots.composeapp.generated.resources.settings_agent_profile_skills
import pots.composeapp.generated.resources.settings_agent_profiles_description
import pots.composeapp.generated.resources.settings_agent_profiles_title
import pots.composeapp.generated.resources.settings_agent_reasoning
import pots.composeapp.generated.resources.settings_agent_reasoning_auto
import pots.composeapp.generated.resources.settings_agent_reasoning_description
import pots.composeapp.generated.resources.settings_agent_reasoning_high
import pots.composeapp.generated.resources.settings_agent_reasoning_low
import pots.composeapp.generated.resources.settings_agent_reasoning_medium
import pots.composeapp.generated.resources.settings_agent_repeated
import pots.composeapp.generated.resources.settings_agent_repeated_description
import pots.composeapp.generated.resources.settings_agent_retries
import pots.composeapp.generated.resources.settings_agent_retries_description
import pots.composeapp.generated.resources.settings_agent_tool_rate
import pots.composeapp.generated.resources.settings_agent_tool_rate_description
import pots.composeapp.generated.resources.settings_agent_value_per_minute
import pots.composeapp.generated.resources.settings_agent_value_percent
import pots.composeapp.generated.resources.settings_heartbeat_recent
import pots.composeapp.generated.resources.settings_memories
import pots.composeapp.generated.resources.settings_memories_all_title
import pots.composeapp.generated.resources.settings_memories_delete
import pots.composeapp.generated.resources.settings_memories_description
import pots.composeapp.generated.resources.settings_memories_edit_cancel
import pots.composeapp.generated.resources.settings_memories_edit_save
import pots.composeapp.generated.resources.settings_memories_edit_title
import pots.composeapp.generated.resources.settings_memories_show_all
import pots.composeapp.generated.resources.settings_scheduled_tasks
import pots.composeapp.generated.resources.settings_scheduled_tasks_cancel
import pots.composeapp.generated.resources.settings_scheduled_tasks_description
import pots.composeapp.generated.resources.settings_soul
import pots.composeapp.generated.resources.settings_soul_description
import pots.composeapp.generated.resources.settings_soul_reset
import pots.composeapp.generated.resources.settings_soul_reset_cancel
import pots.composeapp.generated.resources.settings_soul_reset_confirm
import pots.composeapp.generated.resources.settings_soul_save
import pots.composeapp.generated.resources.settings_task_details_consecutive_failures
import pots.composeapp.generated.resources.settings_task_details_created
import pots.composeapp.generated.resources.settings_task_details_last_result
import pots.composeapp.generated.resources.settings_task_details_next_run
import pots.composeapp.generated.resources.settings_task_details_no_heartbeat_runs
import pots.composeapp.generated.resources.settings_task_details_no_runs
import pots.composeapp.generated.resources.settings_task_details_on_every_heartbeat
import pots.composeapp.generated.resources.settings_task_details_schedule
import pots.composeapp.generated.resources.settings_task_details_scheduled_for
import pots.composeapp.generated.resources.settings_task_details_status
import pots.composeapp.generated.resources.settings_task_details_trigger
import kotlin.math.roundToInt
import kotlin.time.Instant

@Composable
internal fun AgentContent(uiState: SettingsUiState, actions: SettingsActions) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsCard {
            AgentProfilesSection(uiState = uiState, actions = actions)
        }
        StaggeredSettingsColumns(
            start = {
                SettingsCard {
                    SoulEditor(
                        profileId = uiState.activeAgentProfileId,
                        soulText = uiState.soulText,
                        onSaveSoul = actions.onSaveSoul,
                    )
                }
                SettingsCard {
                    ScheduledTaskList(
                        tasks = uiState.scheduledTasks,
                        heartbeatLog = uiState.heartbeatLog,
                        onCancelTask = actions.onCancelTask,
                        isSchedulingEnabled = uiState.isSchedulingEnabled,
                        onToggleScheduling = actions.onToggleScheduling,
                    )
                }
                SettingsCard {
                    MemoryList(
                        memories = uiState.memories,
                        onDeleteMemory = actions.onDeleteMemory,
                        onUpdateMemory = actions.onUpdateMemory,
                        isMemoryEnabled = uiState.isMemoryEnabled,
                        onToggleMemory = actions.onToggleMemory,
                    )
                }
            },
            end = {
                SettingsCard {
                    HeartbeatSection(
                        isHeartbeatEnabled = uiState.isHeartbeatEnabled,
                        heartbeatIntervalMinutes = uiState.heartbeatIntervalMinutes,
                        activeHoursStart = uiState.heartbeatActiveHoursStart,
                        activeHoursEnd = uiState.heartbeatActiveHoursEnd,
                        heartbeatPrompt = uiState.heartbeatPrompt,
                        heartbeatLog = uiState.heartbeatLog,
                        heartbeatServiceEntries = uiState.heartbeatServiceEntries,
                        heartbeatSelectedInstanceId = uiState.heartbeatSelectedInstanceId,
                        heartbeatFallbackInstanceId = uiState.heartbeatFallbackInstanceId,
                        isRefreshing = uiState.isRefreshingHeartbeat,
                        onToggleHeartbeat = actions.onToggleHeartbeat,
                        onChangeInterval = actions.onChangeHeartbeatInterval,
                        onChangeActiveHours = actions.onChangeHeartbeatActiveHours,
                        onSaveHeartbeatPrompt = actions.onSaveHeartbeatPrompt,
                        onChangeHeartbeatService = actions.onChangeHeartbeatService,
                        onChangeHeartbeatFallbackService = actions.onChangeHeartbeatFallbackService,
                        onRefresh = actions.onRefreshHeartbeat,
                    )
                }
                if (uiState.showEmailToggle) {
                    SettingsCard {
                        EmailSection(
                            isEmailEnabled = uiState.isEmailEnabled,
                            emailAccounts = uiState.emailAccounts,
                            pollIntervalMinutes = uiState.emailPollIntervalMinutes,
                            pendingCount = uiState.emailPendingCount,
                            syncStates = uiState.emailSyncStates,
                            refreshingAccountIds = uiState.refreshingEmailAccountIds,
                            onToggleEmail = actions.onToggleEmail,
                            onRemoveAccount = actions.onRemoveEmailAccount,
                            onChangePollInterval = actions.onChangeEmailPollInterval,
                            onRefreshAccount = actions.onRefreshEmailAccount,
                        )
                    }
                }
                if (uiState.showSmsSection) {
                    SettingsCard {
                        SmsSection(
                            isSmsEnabled = uiState.isSmsEnabled,
                            permissionGranted = uiState.smsPermissionGranted,
                            pollIntervalMinutes = uiState.smsPollIntervalMinutes,
                            pendingCount = uiState.smsPendingCount,
                            syncState = uiState.smsSyncState,
                            isRefreshing = uiState.isRefreshingSms,
                            isSmsSendEnabled = uiState.isSmsSendEnabled,
                            sendPermissionGranted = uiState.smsSendPermissionGranted,
                            onToggleSms = actions.onToggleSms,
                            onChangePollInterval = actions.onChangeSmsPollInterval,
                            onRefresh = actions.onRefreshSms,
                            onToggleSmsSend = actions.onToggleSmsSend,
                        )
                    }
                }
                if (uiState.showNotificationsSection) {
                    SettingsCard {
                        NotificationsSection(
                            isEnabled = uiState.isNotificationsEnabled,
                            accessGranted = uiState.notificationListenerAccessGranted,
                            listenerBound = uiState.notificationListenerBound,
                            pendingCount = uiState.notificationPendingCount,
                            onToggle = actions.onToggleNotifications,
                            onOpenAccessSettings = actions.onOpenNotificationListenerSettings,
                            onClearPending = actions.onClearPendingNotifications,
                        )
                    }
                }
            },
        )
        SettingsCard {
            AgentTuningSection(
                tuning = uiState.agentTuning,
                onChange = actions.onChangeAgentTuning,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AgentProfilesSection(uiState: SettingsUiState, actions: SettingsActions) {
    val activeProfile = uiState.agentProfiles.firstOrNull { it.id == uiState.activeAgentProfileId }
    var newProfileName by remember { mutableStateOf("") }
    var editedName by remember(activeProfile?.id, activeProfile?.name) { mutableStateOf(activeProfile?.name.orEmpty()) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(Res.string.settings_agent_profiles_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = stringResource(Res.string.settings_agent_profiles_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            uiState.agentProfiles.forEach { profile ->
                FilterChip(
                    selected = profile.id == uiState.activeAgentProfileId,
                    onClick = { actions.onActivateAgentProfile(profile.id) },
                    label = { Text(profile.name) },
                    modifier = Modifier.handCursor(),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            PotsOutlinedTextField(
                modifier = Modifier.weight(1f),
                value = newProfileName,
                onValueChange = { newProfileName = it.take(60) },
                singleLine = true,
                label = { Text(stringResource(Res.string.settings_agent_profile_name)) },
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    actions.onCreateAgentProfile(newProfileName)
                    newProfileName = ""
                },
                enabled = newProfileName.isNotBlank(),
                modifier = Modifier.handCursor(),
            ) {
                Text(stringResource(Res.string.settings_agent_profile_create))
            }
        }

        if (activeProfile != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PotsOutlinedTextField(
                    modifier = Modifier.weight(1f),
                    value = editedName,
                    onValueChange = { editedName = it.take(60) },
                    singleLine = true,
                    label = { Text(stringResource(Res.string.settings_agent_profile_name)) },
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = { actions.onRenameAgentProfile(activeProfile.id, editedName) },
                    enabled = editedName.isNotBlank() && editedName != activeProfile.name,
                    modifier = Modifier.handCursor(),
                ) {
                    Text(stringResource(Res.string.settings_agent_profile_rename))
                }
                if (uiState.agentProfiles.size > 1) {
                    TextButton(onClick = { showDeleteDialog = true }, modifier = Modifier.handCursor()) {
                        Text(stringResource(Res.string.settings_agent_profile_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(Res.string.settings_agent_profile_skills),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (uiState.skills.isEmpty()) {
                Text(
                    text = stringResource(Res.string.settings_agent_profile_no_skills),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else {
                uiState.skills.forEach { skill ->
                    val checked = !uiState.activeProfileSkillSelectionConfigured || skill.id in uiState.activeProfileSkillIds
                    Row(
                        modifier = Modifier.fillMaxWidth().handCursor(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { enabled ->
                                val selected = if (uiState.activeProfileSkillSelectionConfigured) {
                                    uiState.activeProfileSkillIds.toMutableSet()
                                } else {
                                    uiState.skills.map { it.id }.toMutableSet()
                                }
                                if (enabled) selected += skill.id else selected -= skill.id
                                actions.onSetActiveProfileSkills(selected)
                            },
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(skill.displayName, style = MaterialTheme.typography.bodyMedium)
                            Text(skill.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    if (showDeleteDialog && activeProfile != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(Res.string.settings_agent_profile_delete)) },
            text = { Text(stringResource(Res.string.settings_agent_profile_delete_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        actions.onDeleteAgentProfile(activeProfile.id)
                    },
                    modifier = Modifier.handCursor(),
                ) {
                    Text(stringResource(Res.string.settings_agent_profile_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }, modifier = Modifier.handCursor()) {
                    Text(stringResource(Res.string.settings_soul_reset_cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AgentTuningSection(
    tuning: AgentTuning,
    onChange: (AgentTuning) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(Res.string.settings_agent_parameters),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = stringResource(Res.string.settings_agent_parameters_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )

        ToggleableHeadline(
            title = stringResource(Res.string.settings_agent_auto_compact),
            description = stringResource(Res.string.settings_agent_auto_compact_description),
            checked = tuning.autoCompact,
            onCheckedChange = { onChange(tuning.copy(autoCompact = it)) },
        )

        if (tuning.autoCompact) {
            Spacer(Modifier.height(12.dp))
            PercentTuningSlider(
                title = stringResource(Res.string.settings_agent_compact_threshold),
                description = stringResource(Res.string.settings_agent_compact_threshold_description),
                value = tuning.compactThresholdPercent,
                range = 30..95,
                onChange = { onChange(tuning.copy(compactThresholdPercent = it)) },
            )
            Spacer(Modifier.height(12.dp))
            PercentTuningSlider(
                title = stringResource(Res.string.settings_agent_compact_target),
                description = stringResource(Res.string.settings_agent_compact_target_description),
                value = tuning.compactTargetPercent.coerceAtMost(tuning.compactThresholdPercent),
                range = 10..tuning.compactThresholdPercent,
                onChange = { onChange(tuning.copy(compactTargetPercent = it)) },
            )
            Spacer(Modifier.height(8.dp))
            StepperSetting(
                title = stringResource(Res.string.settings_agent_keep_recent),
                description = stringResource(Res.string.settings_agent_keep_recent_description),
                value = tuning.compactKeepRecentTurns,
                range = 1..20,
                onChange = { onChange(tuning.copy(compactKeepRecentTurns = it)) },
            )
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))

        StepperSetting(
            title = stringResource(Res.string.settings_agent_max_iterations),
            description = stringResource(Res.string.settings_agent_max_iterations_description),
            value = tuning.maxToolIterations,
            range = 1..100,
            step = 5,
            onChange = { onChange(tuning.copy(maxToolIterations = it)) },
        )
        StepperSetting(
            title = stringResource(Res.string.settings_agent_max_parallel),
            description = stringResource(Res.string.settings_agent_max_parallel_description),
            value = tuning.maxParallelToolCalls,
            range = 1..16,
            onChange = { onChange(tuning.copy(maxParallelToolCalls = it)) },
        )
        StepperSetting(
            title = stringResource(Res.string.settings_agent_tool_rate),
            description = stringResource(Res.string.settings_agent_tool_rate_description),
            value = tuning.toolCallsPerMinute,
            range = 0..600,
            step = 15,
            valueLabel = if (tuning.toolCallsPerMinute == 0) {
                "∞"
            } else {
                stringResource(Res.string.settings_agent_value_per_minute, tuning.toolCallsPerMinute)
            },
            onChange = { onChange(tuning.copy(toolCallsPerMinute = it)) },
        )
        StepperSetting(
            title = stringResource(Res.string.settings_agent_repeated),
            description = stringResource(Res.string.settings_agent_repeated_description),
            value = tuning.maxRepeatedToolCalls,
            range = 2..10,
            onChange = { onChange(tuning.copy(maxRepeatedToolCalls = it)) },
        )
        StepperSetting(
            title = stringResource(Res.string.settings_agent_retries),
            description = stringResource(Res.string.settings_agent_retries_description),
            value = tuning.maxApiRetries,
            range = 0..5,
            onChange = { onChange(tuning.copy(maxApiRetries = it)) },
        )

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(Res.string.settings_agent_reasoning),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = stringResource(Res.string.settings_agent_reasoning_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val options = listOf(
                ReasoningEffort.AUTO to stringResource(Res.string.settings_agent_reasoning_auto),
                ReasoningEffort.LOW to stringResource(Res.string.settings_agent_reasoning_low),
                ReasoningEffort.MEDIUM to stringResource(Res.string.settings_agent_reasoning_medium),
                ReasoningEffort.HIGH to stringResource(Res.string.settings_agent_reasoning_high),
            )
            options.forEach { (effort, label) ->
                FilterChip(
                    selected = tuning.reasoningEffort == effort,
                    onClick = { onChange(tuning.copy(reasoningEffort = effort)) },
                    label = { Text(label) },
                    modifier = Modifier.handCursor(),
                )
            }
        }
    }
}

@Composable
private fun PercentTuningSlider(
    title: String,
    description: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
) {
    var sliderValue by remember(value, range) { mutableStateOf(value.toFloat()) }
    val step = 5
    val steps = (((range.last - range.first) / step) - 1).coerceAtLeast(0)

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(Res.string.settings_agent_value_percent, value).collapsePercentEscape(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PotsSlider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = {
                val rounded = (sliderValue / step).roundToInt() * step
                onChange(rounded.coerceIn(range))
            },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = steps,
        )
    }
}

@Composable
private fun StepperSetting(
    title: String,
    description: String,
    value: Int,
    range: IntRange,
    step: Int = 1,
    valueLabel: String = value.toString(),
    onChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        TextButton(
            onClick = { onChange((value - step).coerceAtLeast(range.first)) },
            enabled = value > range.first,
            modifier = Modifier.widthIn(min = 40.dp).handCursor(),
        ) {
            Text("−", style = MaterialTheme.typography.titleMedium)
        }
        Text(
            text = valueLabel,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 48.dp),
        )
        TextButton(
            onClick = { onChange((value + step).coerceAtMost(range.last)) },
            enabled = value < range.last,
            modifier = Modifier.widthIn(min = 40.dp).handCursor(),
        ) {
            Text("+", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun SoulEditor(
    profileId: String,
    soulText: String,
    onSaveSoul: (String) -> Unit,
) {
    val appSettings = if (LocalInspectionMode.current) null else koinInject<AppSettings>()
    var presetId by remember(profileId, appSettings) {
        mutableStateOf(appSettings?.getSoulPresetId() ?: AgentPersonaPresets.ASSISTANT)
    }
    val localizedDefault = stringResource(Res.string.default_soul)
    val displayText = soulText.ifEmpty { localizedDefault }
    var editedText by remember(displayText) { mutableStateOf(displayText) }
    val hasChanges = editedText != displayText
    val maxChars = 4000

    var showResetDialog by remember { mutableStateOf(false) }

    fun selectPreset(id: String) {
        presetId = id
        appSettings?.setSoulPresetId(id)
        if (id != AgentPersonaPresets.CUSTOM) {
            val text = AgentPersonaPresets.soulText(id)
            appSettings?.setSoulText(text)
            onSaveSoul(text)
            editedText = text.ifEmpty { localizedDefault }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(Res.string.settings_agent_presets),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "Выберите готовый характер. «Свой» позволяет написать свой.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AgentPersonaPresets.all.forEach { id ->
                FilterChip(
                    selected = presetId == id,
                    onClick = { selectPreset(id) },
                    label = { Text(AgentPersonaPresets.presetName(id, Locale.current.language)) },
                    modifier = Modifier.handCursor(),
                )
            }
        }
        Text(
            text = AgentPersonaPresets.presetDescription(presetId, Locale.current.language),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
        )

        if (presetId == AgentPersonaPresets.CUSTOM) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(Res.string.settings_soul),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                if (soulText.isNotEmpty()) {
                    IconButton(
                        onClick = { showResetDialog = true },
                        modifier = Modifier.handCursor(),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Replay,
                            contentDescription = stringResource(Res.string.settings_soul_reset),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Text(
                text = stringResource(Res.string.settings_soul_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            PotsOutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = editedText,
                onValueChange = { if (it.length <= maxChars) editedText = it },
                minLines = 8,
                maxLines = 8,
                label = {
                    Text(
                        stringResource(Res.string.settings_soul),
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                },
            )

            Text(
                text = "${editedText.length}/$maxChars",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.End,
            )

            if (hasChanges) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { onSaveSoul(editedText.trim()) },
                    modifier = Modifier.align(CenterHorizontally).handCursor(),
                ) {
                    Text(stringResource(Res.string.settings_soul_save))
                }
            }
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text(stringResource(Res.string.settings_soul_reset)) },
            text = { Text(stringResource(Res.string.settings_soul_reset_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetDialog = false
                        onSaveSoul("")
                        editedText = localizedDefault
                    },
                    modifier = Modifier.handCursor(),
                ) {
                    Text(stringResource(Res.string.settings_soul_reset))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showResetDialog = false },
                    modifier = Modifier.handCursor(),
                ) {
                    Text(stringResource(Res.string.settings_soul_reset_cancel))
                }
            },
        )
    }
}

@Composable
private fun MemoryList(
    memories: ImmutableList<MemoryEntry>,
    onDeleteMemory: (String) -> Unit,
    onUpdateMemory: (String, String) -> Unit,
    isMemoryEnabled: Boolean,
    onToggleMemory: (Boolean) -> Unit,
) {
    var showAllDialog by remember { mutableStateOf(false) }
    var editingMemory by remember { mutableStateOf<MemoryEntry?>(null) }

    val sortedMemories = remember(memories) {
        memories.sortedByDescending { it.updatedAt }.toImmutableList()
    }
    val previewMemories = remember(sortedMemories) { sortedMemories.take(5).toImmutableList() }

    Column(modifier = Modifier.fillMaxWidth()) {
        ToggleableHeadline(
            title = stringResource(Res.string.settings_memories),
            description = stringResource(Res.string.settings_memories_description),
            checked = isMemoryEnabled,
            onCheckedChange = onToggleMemory,
        )
        Spacer(Modifier.height(12.dp))

        if (isMemoryEnabled) {
            previewMemories.forEach { memory ->
                SettingsListItem(
                    title = memory.key,
                    subtitle = memory.content,
                    onDelete = { onDeleteMemory(memory.key) },
                    deleteContentDescription = stringResource(Res.string.settings_memories_delete),
                    subtitleMaxLines = 3,
                    onClick = { editingMemory = memory },
                )
                Spacer(Modifier.height(8.dp))
            }
            if (sortedMemories.size > previewMemories.size) {
                OutlinedButton(
                    onClick = { showAllDialog = true },
                    modifier = Modifier.align(CenterHorizontally).handCursor(),
                ) {
                    Text(stringResource(Res.string.settings_memories_show_all, sortedMemories.size))
                }
            }
        }
    }

    if (showAllDialog) {
        AllMemoriesSheet(
            memories = sortedMemories,
            onDismiss = { showAllDialog = false },
            onDeleteMemory = onDeleteMemory,
            onEditMemory = { editingMemory = it },
        )
    }

    editingMemory?.let { memory ->
        EditMemorySheet(
            memory = memory,
            onDismiss = { editingMemory = null },
            onSave = { newContent ->
                onUpdateMemory(memory.key, newContent)
                editingMemory = null
            },
        )
    }
}

@Composable
private fun AllMemoriesSheet(
    memories: ImmutableList<MemoryEntry>,
    onDismiss: () -> Unit,
    onDeleteMemory: (String) -> Unit,
    onEditMemory: (MemoryEntry) -> Unit,
) {
    val deleteContentDescription = stringResource(Res.string.settings_memories_delete)
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
                text = stringResource(Res.string.settings_memories_all_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(12.dp))
            memories.forEach { memory ->
                SettingsListItem(
                    title = memory.key,
                    subtitle = memory.content,
                    onDelete = { onDeleteMemory(memory.key) },
                    deleteContentDescription = deleteContentDescription,
                    subtitleMaxLines = 3,
                    onClick = { onEditMemory(memory) },
                )
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun EditMemorySheet(
    memory: MemoryEntry,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var content by remember(memory.key) { mutableStateOf(memory.content) }
    val hasChanges = content != memory.content && content.isNotBlank()

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
                text = stringResource(Res.string.settings_memories_edit_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = memory.key,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(8.dp))
            PotsOutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = content,
                onValueChange = { content = it },
                minLines = 4,
                maxLines = 10,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.handCursor(),
                ) {
                    Text(stringResource(Res.string.settings_memories_edit_cancel))
                }
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = { onSave(content.trim()) },
                    enabled = hasChanges,
                    modifier = Modifier.handCursor(),
                ) {
                    Text(stringResource(Res.string.settings_memories_edit_save))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ScheduledTaskList(
    tasks: ImmutableList<ScheduledTask>,
    heartbeatLog: ImmutableList<HeartbeatLogEntry>,
    onCancelTask: (String) -> Unit,
    isSchedulingEnabled: Boolean,
    onToggleScheduling: (Boolean) -> Unit,
) {
    var selectedTaskId by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxWidth()) {
        ToggleableHeadline(
            title = stringResource(Res.string.settings_scheduled_tasks),
            description = stringResource(Res.string.settings_scheduled_tasks_description),
            checked = isSchedulingEnabled,
            onCheckedChange = onToggleScheduling,
        )
        Spacer(Modifier.height(12.dp))

        val onEveryHeartbeat = stringResource(Res.string.settings_task_details_on_every_heartbeat)
        if (isSchedulingEnabled && tasks.isNotEmpty()) {
            tasks.forEach { task ->
                val subtitle = when (task.trigger) {
                    TaskTrigger.HEARTBEAT -> "${task.status} - $onEveryHeartbeat"

                    TaskTrigger.CRON -> "${task.status} - ${task.cron?.let { describeCron(it) } ?: "cron"}"

                    TaskTrigger.TIME -> {
                        val instant = Instant.fromEpochMilliseconds(task.scheduledAtEpochMs)
                        val zone = TimeZone.currentSystemDefault()
                        val scheduledTime = instant.toLocalDateTime(zone)
                        val offset = zone.offsetAt(instant)
                        "${task.status} - $scheduledTime $offset"
                    }
                }
                SettingsListItem(
                    title = task.description,
                    subtitle = subtitle,
                    onClick = { selectedTaskId = task.id },
                    onDelete = { onCancelTask(task.id) },
                    deleteContentDescription = stringResource(Res.string.settings_scheduled_tasks_cancel),
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
        )
    }
}

@Composable
private fun TaskDetailsSheet(
    task: ScheduledTask,
    heartbeatLog: ImmutableList<HeartbeatLogEntry>,
    onDismiss: () -> Unit,
) {
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
            Spacer(Modifier.height(12.dp))

            TaskDetailRow(
                label = stringResource(Res.string.settings_task_details_trigger),
                value = task.trigger.name,
            )
            TaskDetailRow(
                label = stringResource(Res.string.settings_task_details_status),
                value = task.status.name,
            )
            when (task.trigger) {
                TaskTrigger.TIME -> TaskDetailRow(
                    label = stringResource(Res.string.settings_task_details_scheduled_for),
                    value = formatTaskInstant(task.scheduledAtEpochMs),
                )

                TaskTrigger.CRON -> {
                    TaskDetailRow(
                        label = stringResource(Res.string.settings_task_details_schedule),
                        value = task.cron?.let { describeCron(it) } ?: "cron",
                    )
                    TaskDetailRow(
                        label = stringResource(Res.string.settings_task_details_next_run),
                        value = formatTaskInstant(task.scheduledAtEpochMs),
                    )
                }

                TaskTrigger.HEARTBEAT -> TaskDetailRow(
                    label = stringResource(Res.string.settings_task_details_schedule),
                    value = stringResource(Res.string.settings_task_details_on_every_heartbeat),
                )
            }
            TaskDetailRow(
                label = stringResource(Res.string.settings_task_details_created),
                value = formatTaskInstant(task.createdAtEpochMs),
            )
            if (task.consecutiveFailures > 0) {
                TaskDetailRow(
                    label = stringResource(Res.string.settings_task_details_consecutive_failures),
                    value = task.consecutiveFailures.toString(),
                )
            }
            // The scheduler stores its retry/backoff phrasing in `lastResult` ("Failed at ...:
            // ... (retry after 120s backoff)"). Surface it so the user can see what the
            // scheduler is going to do next, not just what already happened.
            task.lastResult?.takeIf { it.isNotBlank() }?.let { result ->
                TaskDetailRow(
                    label = stringResource(Res.string.settings_task_details_last_result),
                    value = result,
                )
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(Res.string.settings_heartbeat_recent),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(4.dp))

            if (task.trigger == TaskTrigger.HEARTBEAT) {
                // Heartbeat additions don't carry their own log — they fire as part of every
                // heartbeat run, so the heartbeat-wide log is the right surface.
                if (heartbeatLog.isEmpty()) {
                    EmptyLogText(stringResource(Res.string.settings_task_details_no_heartbeat_runs))
                } else {
                    heartbeatLog.forEach { entry ->
                        ExecutionLogRow(
                            success = entry.success,
                            timestampEpochMs = entry.timestampEpochMs,
                            message = entry.error,
                        )
                    }
                }
            } else {
                if (task.recentExecutions.isEmpty()) {
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
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun TaskDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        // The label column is sized in dp for a fixed amount of text, so it has to
        // track the font scale; the value needs a weight or it overflows the row.
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(140.dp * LocalDensity.current.fontScale),
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
            text = if (success) stringResource(Res.string.execution_log_status_ok) else stringResource(Res.string.execution_log_status_fail),
            style = MaterialTheme.typography.labelSmall,
            color = if (success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.widthIn(min = 36.dp * LocalDensity.current.fontScale),
        )
        Column {
            Text(
                text = formatTaskInstant(timestampEpochMs),
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

private fun formatTaskInstant(epochMs: Long): String {
    if (epochMs <= 0L) return "—"
    val instant = Instant.fromEpochMilliseconds(epochMs)
    val zone = TimeZone.currentSystemDefault()
    val local = instant.toLocalDateTime(zone)
    val month = local.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
    val minute = local.minute.toString().padStart(2, '0')
    return "${local.day} $month ${local.year} ${local.hour}:$minute"
}
