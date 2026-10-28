package com.pusios.pots.ui.settings

import android.speech.tts.TextToSpeech
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pusios.pots.ui.PotsOutlinedTextField
import com.pusios.pots.ui.handCursor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.ic_arrow_drop_down
import pots.composeapp.generated.resources.settings_tts_system_voice
import pots.composeapp.generated.resources.settings_tts_system_voice_auto
import pots.composeapp.generated.resources.settings_tts_voices_unavailable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@Composable
internal actual fun NativeVoicePicker(
    selectedVoice: String,
    onChangeVoice: (String) -> Unit,
) {
    val context = LocalContext.current
    val voices by produceState(initialValue = emptyList<String>(), context) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                var tts: TextToSpeech? = null
                try {
                    val ready = CountDownLatch(1)
                    var statusOk = false
                    tts = TextToSpeech(context.applicationContext) { status ->
                        statusOk = status == TextToSpeech.SUCCESS
                        ready.countDown()
                    }
                    ready.await(4, TimeUnit.SECONDS)
                    if (!statusOk) return@runCatching emptyList()
                    tts?.voices.orEmpty()
                        .mapNotNull { it.name }
                        .sorted()
                } finally {
                    runCatching { tts?.shutdown() }
                }
            }.getOrElse { emptyList() }
        }
    }
    var expanded by remember { mutableStateOf(false) }
    val autoLabel = stringResource(Res.string.settings_tts_system_voice_auto)
    val shown = selectedVoice.ifBlank { autoLabel }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(Res.string.settings_tts_system_voice),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        if (voices.isEmpty()) {
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
                voices.forEach { name ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = {
                            expanded = false
                            onChangeVoice(name)
                        },
                    )
                }
            }
        }
    }
}
