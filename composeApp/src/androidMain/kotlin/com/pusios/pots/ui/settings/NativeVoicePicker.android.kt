package com.pusios.pots.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pusios.pots.LocalAppLanguageCode
import com.pusios.pots.ui.PotsOutlinedTextField
import com.pusios.pots.ui.handCursor
import nl.marc_apps.tts.TextToSpeechEngine
import nl.marc_apps.tts.experimental.ExperimentalVoiceApi
import nl.marc_apps.tts.rememberTextToSpeechOrNull
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.ic_arrow_drop_down
import pots.composeapp.generated.resources.settings_tts_system_voice
import pots.composeapp.generated.resources.settings_tts_system_voice_auto
import pots.composeapp.generated.resources.settings_tts_voices_unavailable

@OptIn(ExperimentalVoiceApi::class)
@Composable
internal actual fun NativeVoicePicker(
    selectedVoice: String,
    onChangeVoice: (String) -> Unit,
) {
    val tts = rememberTextToSpeechOrNull(TextToSpeechEngine.SystemDefault)
    val voices = remember(tts) {
        tts?.voices
            ?.filter { tag ->
                val languageTag = tag.languageTag.lowercase()
                languageTag.startsWith("en") || languageTag.startsWith("ru")
            }
            ?.sortedWith(compareBy({ it.languageTag }, { it.name }))
            .orEmpty()
    }
    val uiIsRussian = LocalAppLanguageCode.current == "ru"
    val labeledVoices = remember(voices, uiIsRussian) {
        voices.mapIndexed { index, voice ->
            val base = if (voice.languageTag.lowercase().startsWith("ru")) {
                if (uiIsRussian) "Русский голос" else "Russian voice"
            } else {
                if (uiIsRussian) "Английский голос" else "English voice"
            }
            voice to "$base ${index + 1}"
        }
    }
    var expanded by remember { mutableStateOf(false) }
    val autoLabel = stringResource(Res.string.settings_tts_system_voice_auto)
    val shown = labeledVoices.firstOrNull { it.first.name == selectedVoice }?.second
        ?: if (selectedVoice.isBlank()) autoLabel else selectedVoice

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(Res.string.settings_tts_system_voice),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        if (labeledVoices.count() == 0) {
            Text(
                text = stringResource(Res.string.settings_tts_voices_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        Box(modifier = Modifier.fillMaxWidth()) {
            PotsOutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = shown,
                onValueChange = {},
                readOnly = true,
                trailingIcon = {
                    Icon(
                        imageVector = vectorResource(Res.drawable.ic_arrow_drop_down),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                },
            )
            Box(
                modifier = Modifier.matchParentSize().handCursor().clickable { expanded = true },
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text(autoLabel) },
                    onClick = {
                        expanded = false
                        onChangeVoice("")
                    },
                )
                labeledVoices.forEach { (voice, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            expanded = false
                            onChangeVoice(voice.name)
                        },
                    )
                }
            }
        }
    }
}
