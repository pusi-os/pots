// The «ADB devices» settings tab: known devices, the mDNS search behind the local-network
// permission, and the only pairing UI in the app. The agent has no pairing tool and no scan: a
// six-digit code is typed here by the owner.
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
import com.pusios.pots.adb.AdbDeviceStore
import com.pusios.pots.adb.AdbDiscovery
import com.pusios.pots.adb.AdbDiscoveryServiceType
import com.pusios.pots.permissionQualifier
import com.pusios.pots.tools.AppPermission
import com.pusios.pots.tools.PermissionController
import com.pusios.pots.ui.handCursor
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import pots.composeapp.generated.resources.*

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
                    enabled = !state.isScanning,
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
                if (state.isScanning) {
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = stringResource(Res.string.adb_scanning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = stringResource(Res.string.adb_discovery_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.discovered.forEach { discovery ->
                DiscoveryRow(discovery = discovery, onClick = { model.onDiscoveredSelected(discovery) })
            }
            if (state.hasScanned && !state.isScanning && state.discovered.isEmpty() && !state.scanPermissionDenied) {
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
                text = stringResource(Res.string.adb_old_android_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DeviceRow(
    device: AdbDevice,
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
}

private fun isError(notice: AdbNotice): Boolean = when (notice) {
    AdbNotice.Paired, is AdbNotice.Connected -> false
    else -> true
}