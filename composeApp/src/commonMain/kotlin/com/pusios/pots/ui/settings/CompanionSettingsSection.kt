package com.pusios.pots.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pusios.pots.companion.CompanionController
import com.pusios.pots.ui.rememberPermissionLauncher
import com.pusios.pots.companion.CompanionStatus
import org.koin.compose.koinInject

@Composable
internal fun CompanionContent() {
    val controller = koinInject<CompanionController>()
    val enabled by controller.enabled.collectAsStateWithLifecycle()
    val status by controller.status.collectAsStateWithLifecycle()
    val devices by controller.devices.collectAsStateWithLifecycle()
    var code by remember { mutableStateOf("") }
    var pendingIndex by remember { mutableStateOf(-1) }
    var permissionError by remember { mutableStateOf(false) }
    val permissions = remember {
        listOf(
            "android.permission.BLUETOOTH_ADVERTISE",
            "android.permission.BLUETOOTH_CONNECT",
            "android.permission.BLUETOOTH_SCAN",
        )
    }
    val requestPermission = rememberPermissionLauncher { granted ->
        if (!granted) {
            permissionError = true
            pendingIndex = -1
        } else {
            pendingIndex++
        }
    }
    androidx.compose.runtime.LaunchedEffect(pendingIndex) {
        when {
            pendingIndex in 0 until permissions.size -> requestPermission(permissions[pendingIndex])
            pendingIndex == permissions.size -> {
                pendingIndex = -1
                controller.startPairing()
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Компаньон-устройство", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Потсик выступает как сервер. Устройство PotsCharm подключается по BLE, pairing инициируется на устройстве кнопкой «Список».",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FilledTonalButton(onClick = { controller.setEnabled(!enabled) }, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
                        )
                        Text(if (enabled) "Выключить сервер" else "Включить сервер")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                when (status) {
                                    CompanionStatus.Available -> MaterialTheme.colorScheme.primary
                                    CompanionStatus.Pairing -> MaterialTheme.colorScheme.secondary
                                    CompanionStatus.Unavailable -> MaterialTheme.colorScheme.outline
                                    CompanionStatus.Disconnected -> MaterialTheme.colorScheme.outline
                                    else -> MaterialTheme.colorScheme.secondary
                                }
                            ),
                    )
                    Text("Статус: " + status.label(), style = MaterialTheme.typography.bodySmall)
                }
                if (status == CompanionStatus.Unavailable) {
                    Text(
                        "Не поддерживается на этой платформе.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (permissionError) {
                    Text(
                        "Нужны разрешения Bluetooth (CONNECT/ADVERTISE/SCAN)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (enabled && devices.isEmpty() && status != CompanionStatus.Pairing) {
                    Button(onClick = {
                        permissionError = false
                        pendingIndex = 0
                    }) { Text("Связать") }
                }
                if (enabled && status == CompanionStatus.Pairing) {
                    Text(
                        "Ожидаем подключение устройства PotsCharm...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = controller::cancelPairing) { Text("Отмена") }
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        label = { Text("Код с устройства") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = {
                        controller.confirmCode(code)
                        code = ""
                    }) { Text("Подтвердить код") }
                }
                if (enabled && devices.isNotEmpty()) {
                    devices.forEach { device ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(device.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${device.transport} • ${device.mac}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(device.status.name, style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = { controller.forget(device.id) }) { Text("Забыть") }
                        }
                    }
                }
                if (!enabled) {
                    Text(
                        "Сервер выключен: устройство не сможет подключиться.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun CompanionStatus.label(): String = when (this) {
    CompanionStatus.Unavailable -> "недоступно"
    CompanionStatus.Disconnected -> "не связано"
    CompanionStatus.Pairing -> "ожидание связи"
    CompanionStatus.Available -> "связано"
    else -> this.name.lowercase()
}
