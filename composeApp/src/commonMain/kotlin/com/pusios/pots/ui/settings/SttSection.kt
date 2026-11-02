package com.pusios.pots.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pusios.pots.data.SttEngine
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.settings_stt
import pots.composeapp.generated.resources.settings_stt_description
import pots.composeapp.generated.resources.settings_stt_engine
import pots.composeapp.generated.resources.settings_stt_engine_system
import pots.composeapp.generated.resources.settings_stt_engine_system_description
import pots.composeapp.generated.resources.settings_stt_engine_vosk
import pots.composeapp.generated.resources.settings_stt_engine_vosk_description
import pots.composeapp.generated.resources.settings_stt_model
import pots.composeapp.generated.resources.settings_stt_model_bundled
import pots.composeapp.generated.resources.settings_stt_model_delete
import pots.composeapp.generated.resources.settings_stt_model_installed
import pots.composeapp.generated.resources.settings_stt_model_installing

/**
 * Speech recognition settings: the engine for the hands-free loop and, for
 * the offline Vosk engine, the model to recognize with.
 */
@Composable
fun SttSection(
    engine: SttEngine,
    voskModel: String,
    onChangeEngine: (SttEngine) -> Unit,
    onChangeVoskModel: (String) -> Unit,
) {
    if (!sttSettingsSupported()) return

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(Res.string.settings_stt),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = stringResource(Res.string.settings_stt_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(Res.string.settings_stt_engine),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        val engineOptions = listOf(
            Triple(SttEngine.Vosk, stringResource(Res.string.settings_stt_engine_vosk), stringResource(Res.string.settings_stt_engine_vosk_description)),
            Triple(SttEngine.System, stringResource(Res.string.settings_stt_engine_system), stringResource(Res.string.settings_stt_engine_system_description)),
        )
        engineOptions.forEach { (option, label, description) ->
            Card(
                onClick = { onChangeEngine(option) },
                colors = CardDefaults.cardColors(
                    containerColor = if (engine == option) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = engine == option, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = label, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (engine == SttEngine.Vosk) {
            VoskModelPicker(selectedModel = voskModel, onChangeModel = onChangeVoskModel)
        }
    }
}

/**
 * The list of Vosk models with their install state. While an install runs the
 * card polls for progress so the bar keeps moving without a view-model round
 * trip.
 */
@Composable
private fun VoskModelPicker(
    selectedModel: String,
    onChangeModel: (String) -> Unit,
) {
    var refreshTick by remember { mutableIntStateOf(0) }
    val installState = remember(refreshTick) { sttInstallState() }
    LaunchedEffect(Unit) {
        while (true) {
            delay(700)
            refreshTick++
        }
    }

    Text(
        text = stringResource(Res.string.settings_stt_model),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onBackground,
    )
    val options = remember(refreshTick) { sttModelOptions() }
    options.forEach { option ->
        val isSelected = option.id == selectedModel
        val isInstallingThis = installState?.modelId == option.id
        Card(
            onClick = { onChangeModel(option.id) },
            colors = CardDefaults.cardColors(
                containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = isSelected, onClick = null)
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = option.label, style = MaterialTheme.typography.bodyLarge)
                    val statusLine = when {
                        isInstallingThis && installState.progress in 0..100 ->
                            "${stringResource(Res.string.settings_stt_model_installing)} ${installState.progress}%"

                        isInstallingThis -> stringResource(Res.string.settings_stt_model_installing)

                        option.installed -> {
                            val base = if (option.bundled) {
                                stringResource(Res.string.settings_stt_model_bundled)
                            } else {
                                stringResource(Res.string.settings_stt_model_installed)
                            }
                            "$base · ${option.sizeMb} MB"
                        }

                        else -> "${option.sizeMb} MB"
                    }
                    Text(
                        text = statusLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (option.installed && !isSelected && !isInstallingThis) {
                    TextButton(onClick = { deleteSttModel(option.id) }) {
                        Text(stringResource(Res.string.settings_stt_model_delete))
                    }
                }
            }
        }
    }
}

/** One selectable Vosk model and its on-disk state. */
data class SttModelOption(
    val id: String,
    val label: String,
    val sizeMb: Int,
    /** Ships inside the app and installs without network. */
    val bundled: Boolean,
    val installed: Boolean,
)

/** The currently running install/download, if any. */
data class SttInstallState(
    val modelId: String,
    /** 0–100, or -1 when the progress is indeterminate. */
    val progress: Int,
)

/** True on platforms that have a speech recognizer to configure. */
expect fun sttSettingsSupported(): Boolean

/** All Vosk models the app knows, with their install state. */
expect fun sttModelOptions(): List<SttModelOption>

/** The running install/download, or null when idle. */
expect fun sttInstallState(): SttInstallState?

/** Removes an installed model from disk (it can be installed again later). */
expect fun deleteSttModel(modelId: String)
