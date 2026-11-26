// The «ADB devices» settings tab: known devices with their live state, the mDNS search and the
// 5555 subnet scan behind the local-network permission, the only pairing UI in the app, and the
// step log that turns "the TV does not connect" into a line the owner can act on.
//
// The agent has no pairing tool and no scan: a six-digit code is typed here by the owner.
//
// Android-only. The tab itself is hidden on other platforms (SettingsScreen filters it).

package com.pusios.pots.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pusios.pots.adb.AdbBackend
import com.pusios.pots.adb.AdbDevice
import com.pusios.pots.adb.AdbDeviceState
import com.pusios.pots.adb.AdbDeviceStore
import com.pusios.pots.adb.AdbDiscovery
import com.pusios.pots.adb.AdbDiscoveryServiceType
import com.pusios.pots.adb.AdbProbeResult
import com.pusios.pots.adb.PotsAdbLog
import com.pusios.pots.permissionQualifier
import com.pusios.pots.tools.AppPermission
import com.pusios.pots.tools.PermissionController
import com.pusios.pots.ui.handCursor
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import pots.composeapp.generated.resources.*

/** How many ADB log lines the screen shows; the buffer keeps more, the screen stays readable. */
private const val VISIBLE_LOG_LINES = 20

@Composable
fun AdbSettingsScreen(
    backend: AdbBackend = koinInject(),
    store: AdbDeviceStore = koinInject(),
    localNetworkPermission: PermissionController = koinInject(permissionQualifier(AppPermission.LOCAL_NETWORK)),
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val model = remember(backend, store, localNetworkPermission, scope) {
        AdbSettingsViewModel(backend, store, localNetworkPermission, scope)
    }
    val state by model.state.collectAsState()
    // The step log is a process-wide singleton by design (the backend writes to it from a socket
    // thread), so the screen reads it directly instead of copying it through the view model.
    val adbLog by PotsAdbLog.lines.collectAsState()

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(Res.drawable.ic_adb_devices),
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(Res.string.adb_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        Text(
            text = stringResource(Res.string.adb_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(Res.string.adb_master_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )

        SettingsCard {
            Text(
                text = stringResource(Res.string.adb_known_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(8.dp))
            if (state.devices.isEmpty()) {
                Text(
                    text = stringResource(Res.string.adb_known_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.devices.forEach { device ->
                DeviceRow(
                    device = device,
                    status = state.statuses[device.id],
                    onTrustChange = { trusted -> model.onTrustChange(device, trusted) },
                    onRemove = { model.onRemove(device) },
                )
            }
        }

        SettingsCard {
            Text(
                text = stringResource(Res.string.adb_add_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(8.dp))

            if (state.scanPermissionDenied) {
                Text(
                    text = stringResource(Res.string.adb_scan_denied_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = stringResource(Res.string.adb_scan_denied_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { model.onOpenAppSettings() }, modifier = Modifier.handCursor()) {
                    Text(stringResource(Res.string.adb_open_settings))
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { model.onScan() },
                    enabled = !state.isScanning && !state.isScanningLan,
                    modifier = Modifier.handCursor(),
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_refresh),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(Res.string.adb_scan))
                }
                Spacer(Modifier.width(12.dp))
                TextButton(
                    onClick = { model.onScanLan() },
                    enabled = !state.isScanning && !state.isScanningLan,
                    modifier = Modifier.handCursor(),
                ) {
                    Text(stringResource(Res.string.adb_scan_lan))
                }
            }
            if (state.isScanning || state.isScanningLan) {
                Text(
                    text = stringResource(if (state.isScanningLan) Res.string.adb_scanning_lan else Res.string.adb_scanning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(Res.string.adb_discovery_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.discovered.forEach { discovery ->
                DiscoveryRow(discovery = discovery, onClick = { model.onDiscoveredSelected(discovery) })
            }
            if (state.hasScanned && !state.isScanning && !state.isScanningLan && state.discovered.isEmpty() &&
                !state.scanPermissionDenied
            ) {
                // Silence is not an answer: after a scan that found nothing, say so.
                Text(
                    text = stringResource(Res.string.adb_scan_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.endpoint,
                onValueChange = model::onEndpointChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(Res.string.adb_endpoint_label)) },
                placeholder = { Text(stringResource(Res.string.adb_endpoint_hint)) },
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.pairingPort,
                    onValueChange = model::onPairingPortChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text(stringResource(Res.string.adb_pairing_port_label)) },
                    placeholder = { Text(stringResource(Res.string.adb_pairing_port_hint)) },
                )
                OutlinedTextField(
                    value = state.code,
                    onValueChange = model::onCodeChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text(stringResource(Res.string.adb_code_label)) },
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { model.onPairAndConnect() },
                    enabled = !state.busy,
                    modifier = Modifier.handCursor(),
                ) {
                    Text(stringResource(Res.string.adb_pair_action))
                }
                Spacer(Modifier.width(12.dp))
                TextButton(
                    onClick = { model.onConnect() },
                    enabled = !state.busy,
                    modifier = Modifier.handCursor(),
                ) {
                    Text(stringResource(Res.string.adb_connect_action))
                }
                Spacer(Modifier.width(12.dp))
                TextButton(
                    onClick = { model.onDiagnose() },
                    enabled = !state.busy,
                    modifier = Modifier.handCursor(),
                ) {
                    Text(stringResource(Res.string.adb_diagnose))
                }
                // Only while a device is actually showing its dialog: that is the one wait that can
                // last minutes, and the one the owner may want to abort (for example after walking
                // away from the TV). A «Диагностика» can leave a device in the waiting state too, so
                // the model decides this in `canCancelConnect` — from the live attempt and its own
                // target's status, never from any device's status.
                if (state.canCancelConnect) {
                    Spacer(Modifier.width(12.dp))
                    TextButton(
                        onClick = { model.onCancelConnect() },
                        modifier = Modifier.handCursor(),
                    ) {
                        Text(stringResource(Res.string.adb_cancel))
                    }
                }
            }

            state.notice?.let { notice ->
                Text(
                    text = noticeText(notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isError(notice)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(Res.string.adb_tv_steps),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(Res.string.adb_old_android_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingsCard {
            Text(
                text = stringResource(Res.string.adb_diagnostics_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(8.dp))
            if (adbLog.isEmpty()) {
                Text(
                    text = stringResource(Res.string.adb_diagnostics_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = adbLog.takeLast(VISIBLE_LOG_LINES).joinToString("\n") { line -> line.toString() },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DeviceRow(
    device: AdbDevice,
    status: AdbDeviceStatus?,
    onTrustChange: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = device.label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = device.endpoint + if (device.tls) " · TLS" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(if (device.trusted) Res.string.adb_trusted else Res.string.adb_untrusted),
                style = MaterialTheme.typography.bodySmall,
                color = if (device.trusted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            status?.let { current -> statusLine(current, statusText(current)) }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(Res.string.adb_trust_toggle),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Switch(checked = device.trusted, onCheckedChange = onTrustChange)
        }
        IconButton(onClick = onRemove, modifier = Modifier.handCursor()) {
            Icon(
                painter = painterResource(Res.drawable.ic_delete_forever),
                contentDescription = stringResource(Res.string.adb_remove),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The state line under a device: «не подключено» until an attempt says otherwise. */
@Composable
private fun statusText(status: AdbDeviceStatus): String = when (status.state) {
    AdbDeviceState.IDLE -> stringResource(Res.string.adb_status_idle)

    AdbDeviceState.PROBING -> stringResource(Res.string.adb_status_probing)

    AdbDeviceState.WAITING_FOR_CONFIRMATION -> stringResource(Res.string.adb_status_waiting)

    AdbDeviceState.CONNECTED -> stringResource(Res.string.adb_status_connected)

    AdbDeviceState.ERROR -> status.detail?.let { detail ->
        stringResource(Res.string.adb_status_error, detail)
    } ?: stringResource(Res.string.adb_status_error, stringResource(Res.string.adb_status_idle))
}

@Composable
private fun statusLine(status: AdbDeviceStatus, text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (status.state == AdbDeviceState.ERROR) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.primary
        },
    )
}

@Composable
private fun DiscoveryRow(discovery: AdbDiscovery, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .handCursor()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${discovery.host}:${discovery.port}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = discovery.serviceType.mdnsServiceType,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun noticeText(notice: AdbNotice): String = when (notice) {
    AdbNotice.Paired -> stringResource(Res.string.adb_pair_success)
    AdbNotice.InvalidCode -> stringResource(Res.string.adb_pair_invalid_code)
    AdbNotice.PairingWindowClosed -> stringResource(Res.string.adb_pair_window_closed)
    AdbNotice.PairingTimeout -> stringResource(Res.string.adb_pair_timeout)
    is AdbNotice.PairingFailed -> stringResource(Res.string.adb_pair_failed, notice.reason)
    is AdbNotice.Connected -> stringResource(Res.string.adb_connected, notice.address)
    is AdbNotice.NoAnswer -> stringResource(Res.string.adb_no_answer, notice.address)
    is AdbNotice.ConnectFailed -> stringResource(Res.string.adb_connect_failed, notice.reason)
    AdbNotice.PairingRequiredAgain -> stringResource(Res.string.adb_pairing_required)
    AdbNotice.EndpointInvalid -> stringResource(Res.string.adb_endpoint_invalid)
    AdbNotice.EndpointNotPrivate -> stringResource(Res.string.adb_endpoint_not_private)
    AdbNotice.PairingPortInvalid -> stringResource(Res.string.adb_pairing_port_invalid)
    AdbNotice.CodeInvalid -> stringResource(Res.string.adb_code_invalid)
    AdbNotice.EnterConnectPort -> stringResource(Res.string.adb_enter_connect_port)
    is AdbNotice.WaitingForConfirmation -> stringResource(Res.string.adb_waiting_confirmation, notice.address)
    is AdbNotice.DeviceDidNotConfirm -> stringResource(Res.string.adb_not_confirmed, notice.address)
    is AdbNotice.NotAdb -> stringResource(Res.string.adb_not_adb, notice.address)
    is AdbNotice.ConnectCancelled -> stringResource(Res.string.adb_connect_cancelled, notice.address)
    is AdbNotice.Diagnosis -> diagnosisText(notice.result)
}

@Composable
private fun diagnosisText(result: AdbProbeResult): String = when (result) {
    AdbProbeResult.AuthRequired -> stringResource(Res.string.adb_diag_auth_required)
    AdbProbeResult.TlsRequired -> stringResource(Res.string.adb_diag_tls_required)
    AdbProbeResult.Connected -> stringResource(Res.string.adb_diag_connected)
    AdbProbeResult.Silent, is AdbProbeResult.NotAdb -> stringResource(Res.string.adb_diag_not_adb)
    AdbProbeResult.PortClosed -> stringResource(Res.string.adb_diag_port_closed)
    AdbProbeResult.NoRoute -> stringResource(Res.string.adb_diag_no_route)
    AdbProbeResult.Timeout -> stringResource(Res.string.adb_diag_timeout)
    is AdbProbeResult.Failed -> stringResource(Res.string.adb_connect_failed, result.detail)
}

private fun isError(notice: AdbNotice): Boolean = when (notice) {
    AdbNotice.Paired, is AdbNotice.Connected, is AdbNotice.ConnectCancelled -> false
    else -> true
}
