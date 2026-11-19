package com.pusios.pots.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pusios.pots.assistant.AssistantAvailability
import com.pusios.pots.assistant.ScreenContextAvailability
import com.pusios.pots.assistant.currentScreenContextAvailability
import org.jetbrains.compose.resources.stringResource
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.settings_assistant_assign
import pots.composeapp.generated.resources.settings_assistant_description
import pots.composeapp.generated.resources.settings_assistant_held
import pots.composeapp.generated.resources.settings_assistant_needs_android
import pots.composeapp.generated.resources.settings_assistant_not_supported
import pots.composeapp.generated.resources.settings_assistant_open_settings
import pots.composeapp.generated.resources.settings_assistant_screen_context
import pots.composeapp.generated.resources.settings_assistant_screenshot_only
import pots.composeapp.generated.resources.settings_assistant_title

/**
 * Sends the user to the system default-apps screen. ROLE_ASSISTANT is not
 * requestable in code, so this is the only working assignment path.
 *
 * It also says what the role buys: the screen's text at every invocation. That
 * is the one difference the role makes — the screenshot flow works either way.
 */
@Composable
internal fun AssistantSettingsSection(
    availability: AssistantAvailability,
    onOpenDefaultApps: () -> Unit,
    screenContext: ScreenContextAvailability = currentScreenContextAvailability(),
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(Res.string.settings_assistant_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(Res.string.settings_assistant_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val contextLine = when (screenContext) {
            ScreenContextAvailability.AssistSnapshot -> Res.string.settings_assistant_screen_context
            ScreenContextAvailability.Screenshot -> Res.string.settings_assistant_screenshot_only
            ScreenContextAvailability.NotOnThisPlatform -> null
        }
        if (contextLine != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(contextLine),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        when (availability) {
            is AssistantAvailability.Available -> {
                if (availability.held) {
                    Text(
                        text = stringResource(Res.string.settings_assistant_held),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onOpenDefaultApps, enabled = true) {
                        Text(stringResource(Res.string.settings_assistant_open_settings))
                    }
                } else {
                    TextButton(onClick = onOpenDefaultApps, enabled = true) {
                        Text(stringResource(Res.string.settings_assistant_assign))
                    }
                }
            }

            is AssistantAvailability.NeedsNewerAndroid -> {
                Text(
                    text = stringResource(Res.string.settings_assistant_needs_android, availability.minLabel),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = {}, enabled = false) {
                    Text(stringResource(Res.string.settings_assistant_assign))
                }
            }

            AssistantAvailability.NotOnThisPlatform -> {
                Text(
                    text = stringResource(Res.string.settings_assistant_not_supported),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
