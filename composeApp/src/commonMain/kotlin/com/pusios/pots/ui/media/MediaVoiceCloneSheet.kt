package com.pusios.pots.ui.media

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pusios.pots.media.MediaLog
import com.pusios.pots.media.VoiceLevel
import com.pusios.pots.media.mp3DurationSecondsEstimate
import com.pusios.pots.media.validateVoiceSample
import com.pusios.pots.media.wavDurationSeconds
import com.pusios.pots.ui.handCursor
import com.pusios.pots.writeAppStorageBytes
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** A sample picked from disk and already copied into app storage. */
private data class PickedVoiceSample(
    val fileName: String,
    val path: String,
    val sizeBytes: Int,
    val durationSec: Double?,
    val bytes: ByteArray,
)

/**
 * Voice-clone creation sheet (spec §16): file picker with the provider
 * limits validated BEFORE upload, a listen-before-send player, the voice
 * name, the level with its price shown up front, and the mandatory consent
 * checkbox without which the create button stays disabled.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalUuidApi::class)
@Composable
internal fun MediaVoiceCloneSheet(
    isCreating: Boolean,
    onCreate: (name: String, level: VoiceLevel, samplePath: String, consent: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var level by remember { mutableStateOf(VoiceLevel.ElevenLabs) }
    var consent by remember { mutableStateOf(false) }
    var sample by remember { mutableStateOf<PickedVoiceSample?>(null) }
    val scope = rememberCoroutineScope()

    val picker = rememberFilePickerLauncher(type = FileKitType.File(extensions = listOf("wav", "mp3"))) { file: PlatformFile? ->
        file ?: return@rememberFilePickerLauncher
        scope.launch {
            val bytes = runCatching { file.readBytes() }.getOrNull()
            if (bytes == null) {
                MediaLog.w("Voice sample unreadable: ${file.name}")
                return@launch
            }
            val ext = if (file.name.endsWith(".mp3", ignoreCase = true)) "mp3" else "wav"
            val path = "media/voices/${Uuid.random()}.$ext"
            if (!writeAppStorageBytes(path, bytes)) {
                MediaLog.w("Voice sample save failed: ${file.name}")
                return@launch
            }
            val duration = if (ext == "wav") wavDurationSeconds(bytes) else mp3DurationSecondsEstimate(bytes)
            sample = PickedVoiceSample(file.name, path, bytes.size, duration, bytes)
        }
    }

    val validationError = sample?.let { validateVoiceSample(it.fileName, it.bytes, level) }
    val createEnabled = !isCreating && consent && name.isNotBlank() && sample != null && validationError == null && level.isCreatable

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text("Мой голос — клонирование", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(4.dp))
            Text(
                "Загрузи запись своего голоса: 10–60 секунд, wav или mp3, не больше 10 МБ. Запись уходит провайдеру для создания голоса.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            val picked = sample
            if (picked == null) {
                Surface(
                    onClick = { picker.launch() },
                    modifier = Modifier.fillMaxWidth().handCursor().semantics { contentDescription = "Выбрать запись голоса" },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                ) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.UploadFile, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        Spacer(Modifier.width(10.dp))
                        Text("Выбрать запись (wav или mp3)", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            } else {
                Text(
                    "${picked.fileName} · ${formatSize(picked.sizeBytes)}" + (picked.durationSec?.let { " · ~${it.toInt()} с" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                MediaAudioPlayer(source = picked.path, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { picker.launch() }, modifier = Modifier.handCursor()) {
                    Text("Выбрать другую запись")
                }
            }
            validationError?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Имя голоса") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Имя голоса" },
            )
            Spacer(Modifier.height(16.dp))

            Text("Уровень", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(8.dp))
            VoiceLevel.entries.forEach { option ->
                val creatable = option.isCreatable
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = level == option, enabled = creatable, onClick = { level = option })
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = level == option, onClick = { if (creatable) level = option }, enabled = creatable)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            option.titleRu,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (creatable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(option.priceNoteRu, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!creatable) {
                            Text(
                                "Скоро: движок создания ещё не задокументирован провайдером",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = consent, onCheckedChange = { consent = it })
                Spacer(Modifier.width(4.dp))
                Text(
                    "Подтверждаю, что это мой голос или у меня есть право его использовать",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(12.dp))

            Button(
                onClick = { sample?.let { onCreate(name.trim(), level, it.path, consent) } },
                enabled = createEnabled,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Создать голос" },
            ) {
                if (isCreating) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Создаю голос…")
                } else {
                    Text("Создать голос")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun formatSize(sizeBytes: Int): String = if (sizeBytes >= 1024 * 1024) "${sizeBytes / 1024 / 1024} МБ" else "${sizeBytes / 1024} КБ"
