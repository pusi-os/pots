package com.pusios.pots.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pusios.pots.data.SttEngine
import com.pusios.pots.handsfree.SttFallback
import com.pusios.pots.handsfree.routeSttEngine
import com.pusios.pots.handsfree.sttRuntimeFacts
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.settings_stt
import pots.composeapp.generated.resources.settings_stt_description
import pots.composeapp.generated.resources.settings_stt_engine
import pots.composeapp.generated.resources.settings_stt_engine_neural
import pots.composeapp.generated.resources.settings_stt_engine_neural_description
import pots.composeapp.generated.resources.settings_stt_engine_system
import pots.composeapp.generated.resources.settings_stt_engine_system_description
import pots.composeapp.generated.resources.settings_stt_engine_vosk
import pots.composeapp.generated.resources.settings_stt_engine_vosk_description
import pots.composeapp.generated.resources.settings_stt_model
import pots.composeapp.generated.resources.settings_stt_model_bundled
import pots.composeapp.generated.resources.settings_stt_model_cancel
import pots.composeapp.generated.resources.settings_stt_model_delete
import pots.composeapp.generated.resources.settings_stt_model_download
import pots.composeapp.generated.resources.settings_stt_model_downloading
import pots.composeapp.generated.resources.settings_stt_model_gigaam_plain
import pots.composeapp.generated.resources.settings_stt_model_gigaam_punct
import pots.composeapp.generated.resources.settings_stt_model_installed
import pots.composeapp.generated.resources.settings_stt_model_installing
import pots.composeapp.generated.resources.settings_stt_model_neural
import pots.composeapp.generated.resources.settings_stt_model_not_installed
import pots.composeapp.generated.resources.settings_stt_model_size_hint
import pots.composeapp.generated.resources.settings_stt_neural_hint
import pots.composeapp.generated.resources.settings_stt_neural_low_memory
import pots.composeapp.generated.resources.settings_stt_neural_model_missing
import pots.composeapp.generated.resources.settings_stt_test_empty
import pots.composeapp.generated.resources.settings_stt_test_mic
import pots.composeapp.generated.resources.settings_stt_test_no_model
import pots.composeapp.generated.resources.settings_stt_test_no_permission
import pots.composeapp.generated.resources.settings_stt_test_recording
import pots.composeapp.generated.resources.settings_stt_test_result

/**
 * Speech recognition settings: the engine for the hands-free loop and, for the
 * offline engines, the model to recognize with.
 *
 * The card grows with the choice — a model list appears only for the engine it
 * belongs to (progressive disclosure), so three engines and two model lists
 * never compete for attention at once.
 */
@Composable
fun SttSection(
    engine: SttEngine,
    voskModel: String,
    neuralModel: String,
    onChangeEngine: (SttEngine) -> Unit,
    onChangeVoskModel: (String) -> Unit,
    onChangeNeuralModel: (String) -> Unit,
) {
    if (!sttSettingsSupported()) return

    // Both model lists are read from disk (installed? downloading?) rather than
    // from the view model, so one tick drives every status line in the card.
    var refreshTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(700)
            refreshTick++
        }
    }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.Mic,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(Res.string.settings_stt),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
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
            Triple(SttEngine.Neural, stringResource(Res.string.settings_stt_engine_neural), stringResource(Res.string.settings_stt_engine_neural_description)),
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
                    modifier = Modifier.padding(12.dp).heightIn(min = 32.dp),
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

        when (engine) {
            SttEngine.Vosk -> VoskModelPicker(
                selectedModel = voskModel,
                onChangeModel = onChangeVoskModel,
                refreshTick = refreshTick,
            )

            SttEngine.Neural -> NeuralModelPicker(
                selectedModel = neuralModel,
                voskModel = voskModel,
                onChangeModel = onChangeNeuralModel,
                refreshTick = refreshTick,
            )

            SttEngine.System -> Unit
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
    refreshTick: Int,
) {
    val installState = remember(refreshTick) { sttInstallState() }

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

/**
 * The neural models: size, download state, the two things the engine cannot do
 * (Russian only, whole phrases only) and the microphone test that proves the
 * selected model understands this device's microphone.
 *
 * A model that is not downloaded offers `Скачать` as its primary action; a
 * downloaded one trades that button for the test. `Удалить` stays a secondary
 * text button, so the destructive action never takes the place the main one
 * had.
 */
@Composable
private fun NeuralModelPicker(
    selectedModel: String,
    voskModel: String,
    onChangeModel: (String) -> Unit,
    refreshTick: Int,
) {
    val options = remember(refreshTick) { neuralSttModelOptions() }
    val download = remember(refreshTick) { neuralSttDownloadState() }
    val route = remember(refreshTick, selectedModel) {
        routeSttEngine(SttEngine.Neural, sttRuntimeFacts(selectedModel))
    }
    var testState by remember { mutableStateOf<MicTestState>(MicTestState.Idle) }
    val scope = rememberCoroutineScope()

    Text(
        text = stringResource(Res.string.settings_stt_model_neural),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Text(
        text = stringResource(Res.string.settings_stt_neural_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        text = stringResource(Res.string.settings_stt_model_size_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    // «Выбрано Нейро, а слышит Vosk» is a sentence, not a mystery: the routed
    // engine is the one the dialogue will use, so the card says so up front.
    when (route.fallback) {
        SttFallback.ModelMissing -> NeuralNotice(stringResource(Res.string.settings_stt_neural_model_missing))
        SttFallback.LowMemory -> NeuralNotice(stringResource(Res.string.settings_stt_neural_low_memory))
        null -> Unit
    }

    options.forEach { option ->
        val isSelected = option.id == selectedModel
        val isDownloadingThis = download?.modelId == option.id
        Card(
            onClick = { if (!isDownloadingThis) onChangeModel(option.id) },
            colors = CardDefaults.cardColors(
                containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // A model that is downloading is not selectable: its files
                    // are half there and a session on it would fail.
                    RadioButton(selected = isSelected, onClick = null, enabled = !isDownloadingThis)
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = neuralModelLabel(option.id), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = neuralModelStatusLine(option, isDownloadingThis, download?.progress),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (isDownloadingThis && download != null && download.progress in 0..100) {
                    LinearProgressIndicator(
                        progress = { download.progress / 100f },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(6.dp),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when {
                        isDownloadingThis -> Button(
                            onClick = { cancelNeuralSttDownload() },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(Res.string.settings_stt_model_cancel))
                        }

                        !option.installed -> Button(
                            onClick = { downloadNeuralSttModel(option.id) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(Res.string.settings_stt_model_download))
                        }

                        isSelected -> Button(
                            onClick = {
                                testState = MicTestState.Recording
                                scope.launch {
                                    testState = MicTestState.Done(
                                        testSttOnMicrophone(SttEngine.Neural, voskModel, option.id),
                                    )
                                }
                            },
                            enabled = testState != MicTestState.Recording,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Icon(Icons.Outlined.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(Res.string.settings_stt_test_mic))
                        }

                        else -> Unit
                    }
                    if (option.installed && !isDownloadingThis) {
                        TextButton(
                            onClick = {
                                testState = MicTestState.Idle
                                deleteNeuralSttModel(option.id)
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(Res.string.settings_stt_model_delete))
                        }
                    }
                }
                if (isSelected) MicTestOutcome(testState)
            }
        }
    }
}

/** What the microphone test is doing, and what it heard. */
private sealed interface MicTestState {
    data object Idle : MicTestState

    data object Recording : MicTestState

    data class Done(val result: SttMicTestResult) : MicTestState
}

@Composable
private fun MicTestOutcome(state: MicTestState) {
    when (state) {
        MicTestState.Idle -> Unit

        MicTestState.Recording -> Text(
            text = stringResource(Res.string.settings_stt_test_recording),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )

        is MicTestState.Done -> {
            val line = when (val result = state.result) {
                is SttMicTestResult.Recognized ->
                    stringResource(Res.string.settings_stt_test_result, result.text)

                SttMicTestResult.NothingHeard -> stringResource(Res.string.settings_stt_test_empty)
                SttMicTestResult.Unsupported -> null
                is SttMicTestResult.Failed -> when (result.reason) {
                    SttMicTestError.PermissionDenied -> stringResource(Res.string.settings_stt_test_no_permission)
                    SttMicTestError.ModelMissing -> stringResource(Res.string.settings_stt_test_no_model)
                    SttMicTestError.RecordingFailed -> stringResource(Res.string.settings_stt_test_no_permission)
                }
            }
            if (line != null) {
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** The honest «what runs instead» line, in the theme's warning colour. */
@Composable
private fun NeuralNotice(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Outlined.WarningAmber,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/** The model name; an id this build does not know (a newer settings file) falls back to the id. */
@Composable
private fun neuralModelLabel(id: String): String = when (id) {
    "gigaam-v3-ctc-punct-int8" -> stringResource(Res.string.settings_stt_model_gigaam_punct)
    "gigaam-v3-ctc-int8" -> stringResource(Res.string.settings_stt_model_gigaam_plain)
    else -> id
}

@Composable
private fun neuralModelStatusLine(
    option: NeuralSttModelOption,
    isDownloadingThis: Boolean,
    progress: Int?,
): String = when {
    isDownloadingThis && progress != null && progress in 0..100 ->
        "${stringResource(Res.string.settings_stt_model_downloading)} $progress%"

    isDownloadingThis -> stringResource(Res.string.settings_stt_model_downloading)

    option.installed -> "${stringResource(Res.string.settings_stt_model_installed)} · ${option.sizeMb} MB"

    else -> "${stringResource(Res.string.settings_stt_model_not_installed)} · ${option.sizeMb} MB"
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

/** One downloadable neural model and whether it is on disk. */
data class NeuralSttModelOption(
    val id: String,
    /** Approximate installed size, for the settings card. */
    val sizeMb: Int,
    /** True when the model also emits punctuation and capitalisation. */
    val punctuated: Boolean,
    val installed: Boolean,
)

/** The currently running neural-model download, if any. */
data class NeuralSttDownloadState(
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

/** All neural models the app can download, with their on-disk state. */
expect fun neuralSttModelOptions(): List<NeuralSttModelOption>

/** The running neural-model download, or null when idle. */
expect fun neuralSttDownloadState(): NeuralSttDownloadState?

/** Starts downloading a neural model in the background; the card polls the state. */
expect fun downloadNeuralSttModel(modelId: String)

/** Stops the running download and removes its partial file. */
expect fun cancelNeuralSttDownload()

/** Removes a downloaded neural model from disk (it can be downloaded again later). */
expect fun deleteNeuralSttModel(modelId: String)
