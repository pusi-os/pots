package com.pusios.pots.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pusios.pots.companion.CompanionController
import com.pusios.pots.ui.rememberPermissionLauncher
import com.pusios.pots.companion.CompanionStatus
import org.koin.compose.koinInject

@Composable
internal fun CompanionContent() {
    val controller = koinInject<CompanionController>()
    val status by controller.status.collectAsStateWithLifecycle()
    val devices by controller.devices.collectAsStateWithLifecycle()
    var code by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Компаньон-устройство", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Подключение устройства на прошивке PotsCharm. Потсик работает как сервер, устройство — клиент через BLE/Wi-Fi.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text("Статус: ${status.name}")

                val permissions = remember {
                    listOf(
                        "android.permission.BLUETOOTH_CONNECT",
                        "android.permission.BLUETOOTH_ADVERTISE",
                        "android.permission.BLUETOOTH_SCAN",
                    )
                }
                var pendingIndex by remember { mutableStateOf(-1) }
                var permissionError by remember { mutableStateOf(false) }
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
                Button(onClick = {
                    permissionError = false
                    pendingIndex = 0
                }) { Text("Сканировать и связать") }
                if (permissionError) {
                    Text(
                        "Нужны разрешения Bluetooth (CONNECT/ADVERTISE/SCAN)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                OutlinedButton(onClick = controller::cancelPairing) { Text("Отмена") }
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("Код с устройства") },
                    modifier = Modifier,
                )
                OutlinedButton(onClick = { controller.confirmCode(code) }) { Text("Подтвердить код") }
            }
        }

        SettingsCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Устройства", style = MaterialTheme.typography.titleMedium)
                if (devices.isEmpty()) {
                    Text("Нет связанных устройств", style = MaterialTheme.typography.bodySmall)
                } else {
                    devices.forEach { device ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(device.name, style = MaterialTheme.typography.titleSmall)
                            Text("${device.transport} • ${device.mac}", style = MaterialTheme.typography.bodySmall)
                            Text(device.status.name, style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = { controller.forget(device.id) }) { Text("Забыть") }
                        }
                    }
                }
            }
        }

        SettingsCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Включение", style = MaterialTheme.typography.titleMedium)
                RowActions(
                    onEnable = { controller.setEnabled(true) },
                    onDisable = { controller.setEnabled(false) },
                )
            }
        }
    }
}

@Composable
private fun RowActions(onEnable: () -> Unit, onDisable: () -> Unit) {
    androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onEnable) { Text("Включить") }
        OutlinedButton(onClick = onDisable) { Text("Выключить") }
    }
}
