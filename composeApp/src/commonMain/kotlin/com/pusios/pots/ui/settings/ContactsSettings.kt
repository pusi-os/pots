package com.pusios.pots.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.pusios.pots.Platform
import com.pusios.pots.currentPlatform
import com.pusios.pots.permissionQualifier
import com.pusios.pots.tools.AppPermission
import com.pusios.pots.tools.PermissionController
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.settings_contacts_allow
import pots.composeapp.generated.resources.settings_contacts_description
import pots.composeapp.generated.resources.settings_contacts_granted
import pots.composeapp.generated.resources.settings_contacts_not_granted
import pots.composeapp.generated.resources.settings_contacts_open_settings
import pots.composeapp.generated.resources.settings_contacts_title

/**
 * The contacts permission row: current state plus a button that shows the system prompt.
 *
 * Only Android has contact tools at all — they read and write `ContactsContract`, which
 * exists since API 5, below this app's minSdk, so no OS-version gate is needed and the
 * section is hidden on every other platform.
 */
@Composable
internal fun ContactsSettingsSection() {
    if (currentPlatform !is Platform.Mobile.Android) return

    // Screenshot tests render without Koin; the card still has to draw.
    val inspection = LocalInspectionMode.current
    val controller = if (inspection) {
        null
    } else {
        koinInject<PermissionController>(permissionQualifier(AppPermission.CONTACTS))
    }
    var granted by remember(controller) { mutableStateOf(controller?.hasPermission() == true) }
    var requesting by remember { mutableStateOf(false) }
    // A refusal that comes back instantly means the system will not show the dialog again;
    // from then on the only path left is the app's page in the system settings.
    var denied by remember(controller) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // The user may grant the permission from system settings and come back.
    if (controller != null) {
        LifecycleResumeEffect(controller) {
            granted = controller.hasPermission()
            if (granted) denied = false
            onPauseOrDispose { }
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(Res.string.settings_contacts_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(Res.string.settings_contacts_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(
                            if (granted) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                        ),
                )
                Text(
                    text = stringResource(
                        if (granted) Res.string.settings_contacts_granted else Res.string.settings_contacts_not_granted,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (granted) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (!granted) {
                if (denied) {
                    TextButton(onClick = { controller?.openAppSettings() }) {
                        Text(stringResource(Res.string.settings_contacts_open_settings))
                    }
                } else {
                    TextButton(
                        onClick = {
                            val contacts = controller ?: return@TextButton
                            requesting = true
                            scope.launch {
                                val result = contacts.requestPermission()
                                granted = result
                                denied = !result
                                requesting = false
                            }
                        },
                        enabled = !requesting,
                    ) {
                        Text(stringResource(Res.string.settings_contacts_allow))
                    }
                }
            }
        }
    }
}
