package com.pusios.pots.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment.Companion.CenterVertically
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.pusios.pots.Platform
import com.pusios.pots.SandboxBackgroundController
import com.pusios.pots.SandboxBackgroundState
import com.pusios.pots.currentPlatform
import com.pusios.pots.formatFileSize
import com.pusios.pots.linux.LinuxDistro
import com.pusios.pots.ui.build.AgentVersionsSection
import com.pusios.pots.ui.handCursor
import com.pusios.pots.ui.sandbox.SandboxProgressRow
import com.pusios.pots.ui.sandbox.sandboxStatusText
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.pots_build_open
import pots.composeapp.generated.resources.settings_sandbox_agents_title
import pots.composeapp.generated.resources.settings_sandbox_background_allow
import pots.composeapp.generated.resources.settings_sandbox_background_autostart
import pots.composeapp.generated.resources.settings_sandbox_background_battery_settings
import pots.composeapp.generated.resources.settings_sandbox_background_battery_warning
import pots.composeapp.generated.resources.settings_sandbox_background_description
import pots.composeapp.generated.resources.settings_sandbox_background_notifications_hidden
import pots.composeapp.generated.resources.settings_sandbox_background_oem_body
import pots.composeapp.generated.resources.settings_sandbox_background_oem_title
import pots.composeapp.generated.resources.settings_sandbox_background_off
import pots.composeapp.generated.resources.settings_sandbox_background_on
import pots.composeapp.generated.resources.settings_sandbox_background_title
import pots.composeapp.generated.resources.settings_sandbox_cancel
import pots.composeapp.generated.resources.settings_sandbox_description
import pots.composeapp.generated.resources.settings_sandbox_disk_usage
import pots.composeapp.generated.resources.settings_sandbox_distro_alpine
import pots.composeapp.generated.resources.settings_sandbox_distro_alpine_detail
import pots.composeapp.generated.resources.settings_sandbox_distro_alpine_installed
import pots.composeapp.generated.resources.settings_sandbox_distro_debian
import pots.composeapp.generated.resources.settings_sandbox_distro_debian_detail
import pots.composeapp.generated.resources.settings_sandbox_distro_debian_installed
import pots.composeapp.generated.resources.settings_sandbox_distro_title
import pots.composeapp.generated.resources.settings_sandbox_install
import pots.composeapp.generated.resources.settings_sandbox_install_packages
import pots.composeapp.generated.resources.settings_sandbox_migrate_action
import pots.composeapp.generated.resources.settings_sandbox_migrate_detail
import pots.composeapp.generated.resources.settings_sandbox_migrate_title
import pots.composeapp.generated.resources.settings_sandbox_uninstall
import pots.composeapp.generated.resources.settings_sandbox_uninstall_confirm
import pots.composeapp.generated.resources.settings_sandbox_uninstall_confirm_shared

@Composable
internal fun SandboxSettingsCard(
    sandboxState: SandboxUiState,
    onToggleSandbox: (Boolean) -> Unit,
    onSelectDistro: (LinuxDistro) -> Unit,
    onSetupSandbox: () -> Unit,
    onCancelSandbox: () -> Unit,
    onResetSandbox: () -> Unit,
    onInstallPackages: () -> Unit,
    onMigrateHome: () -> Unit,
    onOpenPotsBuild: (() -> Unit)? = null,
    onCheckAgents: (Set<String>) -> Unit = {},
    onInstallAgent: (String) -> Unit = {},
    onUpdateAgent: (String) -> Unit = {},
    onRemoveAgent: (String, Boolean) -> Unit = { _, _ -> },
) {
    var showResetDialog by remember { mutableStateOf(false) }
    val statusText = sandboxStatusText(sandboxState.sandboxStatusLabel)
    SettingsCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    // Before install this names what would be installed; after, what is.
                    text = sandboxState.distro.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                if (sandboxState.sandboxReady) {
                    if (sandboxState.sandboxDiskUsageMB > 0) {
                        Text(
                            text = stringResource(Res.string.settings_sandbox_disk_usage, sandboxState.sandboxDiskUsageMB),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(Res.string.settings_sandbox_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (sandboxState.sandboxReady) {
                Switch(
                    checked = sandboxState.isSandboxEnabled,
                    onCheckedChange = onToggleSandbox,
                )
            }
        }

        // Only meaningful once there is an install to keep alive.
        if (sandboxState.sandboxReady) {
            SandboxBackgroundSection()
        }

        // Offered at any time: each distribution keeps its own install, so this
        // picks which one the shell integration runs in rather than replacing it.
        if (!sandboxState.isWorking) {
            DistroPicker(
                selected = sandboxState.distro,
                installed = sandboxState.installedDistros,
                onSelect = onSelectDistro,
            )
        }
        if (sandboxState.sandboxReady && onOpenPotsBuild != null) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onOpenPotsBuild, modifier = Modifier.handCursor()) {
                Text(stringResource(Res.string.pots_build_open))
            }
        }

        // Which agents are in this sandbox and what each one has, with the whole
        // flow on every row: check, update, install, remove. A version is what its
        // own `--version` printed, and "up to date" only after a check that
        // actually answered — see [AgentUpdate].
        if (sandboxState.agentRows.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(Res.string.settings_sandbox_agents_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AgentVersionsSection(
                rows = sandboxState.agentRows,
                busy = sandboxState.isWorking,
                onCheck = onCheckAgents,
                onInstall = onInstallAgent,
                onUpdate = onUpdateAgent,
                onRemove = onRemoveAgent,
            )
        }

        if (sandboxState.sandboxProgress != null) {
            SandboxProgressRow(sandboxState.sandboxProgress, statusText, onCancelSandbox)
        } else if (sandboxState.isWorking) {
            SandboxProgressRow(null, statusText, onCancelSandbox)
        } else if (sandboxState.hasError) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        // Offered whenever the distribution being left still holds files this one
        // does not, so switching never has to mean abandoning them. It stops
        // being shown once the copy is done, because then there is nothing left.
        val migration = sandboxState.migration
        if (migration != null && sandboxState.sandboxReady && !sandboxState.isWorking) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(
                    Res.string.settings_sandbox_migrate_title,
                    migration.from.displayName,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(
                    Res.string.settings_sandbox_migrate_detail,
                    migration.fileCount,
                    formatFileSize(migration.bytes),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            // Its own button rather than a third one in the action row below:
            // that row is already two wide on a phone, and this belongs to the
            // sentence above it.
            OutlinedButton(onClick = onMigrateHome, modifier = Modifier.handCursor()) {
                Text(stringResource(Res.string.settings_sandbox_migrate_action))
            }
        }

        if (!sandboxState.isWorking) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!sandboxState.sandboxReady) {
                    Button(onClick = onSetupSandbox, modifier = Modifier.handCursor()) {
                        Text(stringResource(Res.string.settings_sandbox_install))
                    }
                } else {
                    if (!sandboxState.sandboxPackagesInstalled) {
                        OutlinedButton(onClick = onInstallPackages, modifier = Modifier.handCursor()) {
                            Text(stringResource(Res.string.settings_sandbox_install_packages))
                        }
                    }
                    OutlinedButton(onClick = { showResetDialog = true }, modifier = Modifier.handCursor()) {
                        Text(stringResource(Res.string.settings_sandbox_uninstall))
                    }
                }
            }
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text(stringResource(Res.string.settings_sandbox_uninstall)) },
            text = {
                // A Debian sandbox is the same install Pots Build works in, so
                // uninstalling here takes Pots Build's Linux with it.
                val confirm = if (sandboxState.distro == LinuxDistro.DEBIAN) {
                    Res.string.settings_sandbox_uninstall_confirm_shared
                } else {
                    Res.string.settings_sandbox_uninstall_confirm
                }
                Text(stringResource(confirm))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetDialog = false
                        onResetSandbox()
                    },
                    modifier = Modifier.handCursor(),
                ) {
                    Text(stringResource(Res.string.settings_sandbox_uninstall))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showResetDialog = false },
                    modifier = Modifier.handCursor(),
                ) {
                    Text(stringResource(Res.string.settings_sandbox_cancel))
                }
            },
        )
    }
}

@Composable
private fun DistroPicker(
    selected: LinuxDistro,
    installed: Set<LinuxDistro>,
    onSelect: (LinuxDistro) -> Unit,
) {
    Spacer(Modifier.height(12.dp))
    Text(
        text = stringResource(Res.string.settings_sandbox_distro_title),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Column(Modifier.selectableGroup()) {
        DistroOption(
            distro = LinuxDistro.DEBIAN,
            title = stringResource(Res.string.settings_sandbox_distro_debian),
            // An install already on disk is a switch, not a download — the size
            // estimate would be the wrong thing to lead with.
            detail = if (LinuxDistro.DEBIAN in installed) {
                stringResource(Res.string.settings_sandbox_distro_debian_installed)
            } else {
                stringResource(Res.string.settings_sandbox_distro_debian_detail)
            },
            selected = selected == LinuxDistro.DEBIAN,
            onSelect = onSelect,
        )
        DistroOption(
            distro = LinuxDistro.ALPINE,
            title = stringResource(Res.string.settings_sandbox_distro_alpine),
            detail = if (LinuxDistro.ALPINE in installed) {
                stringResource(Res.string.settings_sandbox_distro_alpine_installed)
            } else {
                stringResource(Res.string.settings_sandbox_distro_alpine_detail)
            },
            selected = selected == LinuxDistro.ALPINE,
            onSelect = onSelect,
        )
    }
}

@Composable
private fun DistroOption(
    distro: LinuxDistro,
    title: String,
    detail: String,
    selected: Boolean,
    onSelect: (LinuxDistro) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = { onSelect(distro) },
            )
            .handCursor(),
        verticalAlignment = CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The sandbox's background mode.
 *
 * The toggle is the whole state: a missing battery-optimisation exemption or a
 * silenced notification is reported, never used to refuse the mode. Both are
 * re-read on every return to the screen, because both can be taken away in
 * system settings while the app is running.
 */
@Composable
private fun SandboxBackgroundSection() {
    if (currentPlatform !is Platform.Mobile.Android) return

    // Screenshot tests render without Koin; the block still has to draw.
    val inspection = LocalInspectionMode.current
    val controller = if (inspection) null else koinInject<SandboxBackgroundController>()
    var state by remember(controller) { mutableStateOf(SandboxBackgroundState(supported = true)) }
    var showOemChecklist by remember { mutableStateOf(false) }

    if (controller != null) {
        LifecycleResumeEffect(controller) {
            // The user may have changed the exemption, the notification permission,
            // or stopped the service from its notification while they were away.
            state = controller.refresh()
            onPauseOrDispose { }
        }
    }

    Spacer(Modifier.height(12.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(Res.string.settings_sandbox_background_title),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(
                    if (state.working) {
                        Res.string.settings_sandbox_background_on
                    } else {
                        Res.string.settings_sandbox_background_off
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (state.working) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Switch(
            checked = state.enabled,
            onCheckedChange = { enabled ->
                state = if (controller != null) {
                    controller.setEnabled(enabled)
                    controller.refresh()
                } else {
                    state.copy(enabled = enabled)
                }
            },
        )
    }

    Text(
        text = stringResource(Res.string.settings_sandbox_background_description),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    // A warning, not a gate: the mode is on either way.
    if (state.batteryWarning) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(Res.string.settings_sandbox_background_battery_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = { controller?.requestBatteryExemption() },
                modifier = Modifier.handCursor(),
            ) {
                Text(stringResource(Res.string.settings_sandbox_background_allow))
            }
            TextButton(
                onClick = { controller?.openBatterySettings() },
                modifier = Modifier.handCursor(),
            ) {
                Text(stringResource(Res.string.settings_sandbox_background_battery_settings))
            }
        }
    }

    // The service runs without the permission; only the status is invisible.
    if (state.notificationHidden) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(Res.string.settings_sandbox_background_notifications_hidden),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // Text, not code: on these ROMs the only honest fix is a setting the user flips.
    if (state.working) {
        TextButton(
            onClick = { showOemChecklist = !showOemChecklist },
            modifier = Modifier.handCursor(),
        ) {
            Text(stringResource(Res.string.settings_sandbox_background_oem_title))
        }
        if (showOemChecklist) {
            Text(
                text = stringResource(Res.string.settings_sandbox_background_oem_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = { controller?.openAutostartSettings() },
                modifier = Modifier.handCursor(),
            ) {
                Text(stringResource(Res.string.settings_sandbox_background_autostart))
            }
        }
    }
}
