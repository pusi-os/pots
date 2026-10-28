package com.pusios.pots.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.settings_tts_system_voice
import pots.composeapp.generated.resources.settings_tts_system_voice_auto

/**
 * Desktop/iOS/Web have no in-app system voice list yet: the platform engine
 * keeps speaking with its own default (or the app-language match in App.kt).
 * The picker therefore only offers the auto mode.
 */
@Composable
internal actual fun NativeVoicePicker(
    selectedVoice: String,
    onChangeVoice: (String) -> Unit,
) {
    Text(
        text = stringResource(Res.string.settings_tts_system_voice),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(bottom = 4.dp),
    )
    Text(
        text = selectedVoice.ifBlank { stringResource(Res.string.settings_tts_system_voice_auto) },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
