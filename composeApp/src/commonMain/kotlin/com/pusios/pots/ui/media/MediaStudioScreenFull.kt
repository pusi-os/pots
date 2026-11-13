package com.pusios.pots.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.pusios.pots.isImageCompressionSupported
import com.pusios.pots.media.AudioFlow
import com.pusios.pots.media.MediaKind
import com.pusios.pots.media.MediaParamDef
import com.pusios.pots.media.MediaReference
import com.pusios.pots.media.MediaToolbarChip
import com.pusios.pots.media.applyMediaOptDefaults
import com.pusios.pots.media.audioFlowModelsWithLocal
import com.pusios.pots.media.audioToolbarParams
import com.pusios.pots.media.cloneVoiceAxis
import com.pusios.pots.media.formatRub
import com.pusios.pots.media.mediaToolbarParams
import com.pusios.pots.media.qualityOptionLabel
import com.pusios.pots.media.qualityPriceInfo
import com.pusios.pots.media.qualitySelectorAxis
import com.pusios.pots.readAppStorageBytes
import com.pusios.pots.saveFileToDevice
import com.pusios.pots.ui.handCursor
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.launch

@Composable
fun MediaStudioScreenFull(
    viewModel: MediaStudioViewModel,
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit = {},
    onSpeak: (String) -> Unit = {},
    speechEnabled: Boolean = false,
) {
    val elements by viewModel.elements.collectAsState()
    val selectedId by viewModel.selectedId.collectAsState()
    val models by viewModel.models.collectAsState()
    val jobs by viewModel.jobs.collectAsState()
    val references by viewModel.references.collectAsState()
    val error by viewModel.error.collectAsState()
    val isStarting by viewModel.isStarting.collectAsState()
    val isLocalBusy by viewModel.isLocalSynthesizing.collectAsState()
    val isEnhancing by viewModel.isEnhancing.collectAsState()
    val justFinished by viewModel.justFinished.collectAsState()
    val myVoices by viewModel.myVoices.collectAsState()
    val isCreatingVoice by viewModel.isCreatingVoice.collectAsState()
    val selectedImageModelId by viewModel.selectedImageModelId.collectAsState()
    val selectedVideoModelId by viewModel.selectedVideoModelId.collectAsState()
    val selectedTtsModelId by viewModel.selectedTtsModelId.collectAsState()
    val selectedMusicModelId by viewModel.selectedMusicModelId.collectAsState()
    val audioFlow by viewModel.audioFlow.collectAsState()
    val drafts by viewModel.drafts.collectAsState()
    val historyEntries by viewModel.historyEntries.collectAsState()
    val priceTables by viewModel.priceTables.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(justFinished) {
        val done = justFinished ?: return@LaunchedEffect
        if (speechEnabled) onSpeak("Генерация готова")
        viewModel.consumeFinished()
    }

    // Errors surface as snackbars; a key problem offers a shortcut to settings.
    // The error is cleared only after the snackbar is dismissed, so showing it does not cancel it.
    LaunchedEffect(error) {
        val studioError = error ?: return@LaunchedEffect
        val compressIndex = studioError.oversizedReferenceIndex?.takeIf { isImageCompressionSupported }
        val result = snackbarHostState.showSnackbar(
            message = studioError.message,
            actionLabel = when {
                compressIndex != null -> "Сжать"
                studioError.isKeyProblem -> "Настройки"
                else -> null
            },
        )
        viewModel.dismissError()
        if (result != SnackbarResult.ActionPerformed) return@LaunchedEffect
        if (compressIndex != null) {
            viewModel.compressReference(compressIndex)
        } else if (studioError.isKeyProblem) {
            onOpenSettings()
        }
    }

    // Drafts live in the view model (MediaSettings), keyed per mode and audio
    // flow: switching modes/flows or an error never erases them (spec §13, §17).
    var mode by remember { mutableStateOf(MediaMode.Image) }
    val promptKey = mediaDraftKey(mode, audioFlow)
    val prompt = drafts[promptKey].orEmpty()
    var canvasScale by remember { mutableFloatStateOf(1f) }
    var canvasOffset by remember { mutableStateOf(Offset.Zero) }
    var params by remember { mutableStateOf(mapOf<String, String>()) }
    var showModelSheet by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showAspectRatio by remember { mutableStateOf(false) }
    var showExpandedPrompt by remember { mutableStateOf(false) }
    var openParam by remember { mutableStateOf<String?>(null) }
    var showVoiceCloneSheet by remember { mutableStateOf(false) }
    var lightboxUrl by remember { mutableStateOf<String?>(null) }
    var lightboxIsVideo by remember { mutableStateOf(false) }
    var lightboxIsAudio by remember { mutableStateOf(false) }

    val pending = jobs.values.filter { !it.isTerminal }
    val imageModels = models.filter { it.kind == MediaKind.Image }
    val videoModels = models.filter { it.kind == MediaKind.Video }
    val hasSunoHistory = historyEntries.any { it.model == "suno" }
    // Local engines ride the TTS flow on platforms that can synthesize to a
    // file (Android only — spec §14); elsewhere the list is untouched.
    val audioModels = audioFlowModelsWithLocal(models, audioFlow, hasSunoHistory)
    val currentModels = when (mode) {
        MediaMode.Image -> imageModels
        MediaMode.Video -> videoModels
        MediaMode.Audio -> audioModels
    }
    val currentModelId = when (mode) {
        MediaMode.Image -> selectedImageModelId
        MediaMode.Video -> selectedVideoModelId
        MediaMode.Audio -> if (audioFlow == AudioFlow.Tts) selectedTtsModelId else selectedMusicModelId
    }
    val currentModel = currentModels.firstOrNull { it.id == currentModelId } ?: currentModels.firstOrNull()
    // The generalized «axis → chip» mechanism (spec §19.3): resolution, quality
    // dropdown, duration and the sound toggle — all decided by the model's axes.
    val toolbarChips: List<MediaToolbarChip> = when (mode) {
        MediaMode.Audio -> audioToolbarParams(currentModel).map { MediaToolbarChip(MediaToolbarChip.Kind.Sheet, it) }
        else -> mediaToolbarParams(currentModel)
    }
    // Chip labels and submit share one view: explicit defaults applied
    // (quality default Low, catalog audio default), saved quality wins (spec §19.1).
    val savedQuality = currentModel?.let { viewModel.savedQuality(it.id) }
    val effectiveParams = applyMediaOptDefaults(params, currentModel, savedQuality)
    val qualityAxis = if (mode != MediaMode.Audio) currentModel?.qualitySelectorAxis() else null
    val priceInfo = qualityAxis?.let { axis ->
        qualityPriceInfo(
            priceTables[currentModel?.id],
            axis,
            effectiveParams,
            mode = if (currentModel?.kind == MediaKind.Image) {
                if (references.isNotEmpty()) "image-to-image" else "text-to-image"
            } else {
                null
            },
        )
    }
    // The dropdown's price table is fetched once per model; failures degrade
    // to the note mode (spec §19.4).
    LaunchedEffect(currentModel?.id, mode) {
        if (qualityAxis != null) viewModel.loadPriceTable(currentModel?.id.orEmpty())
    }
    // A model/mode switch drops selections the new model does not accept
    // (stale choice silently rolls back, spec §19.4).
    LaunchedEffect(currentModel?.id, mode) {
        val model = currentModel ?: return@LaunchedEffect
        val accepted = model.params.map { it.key }.toSet()
        if (params.keys.any { it !in accepted }) {
            params = params.filterKeys { it in accepted }
        }
    }
    val aspectRatioDef: MediaParamDef? = if (mode == MediaMode.Image) {
        currentModel
            ?.params
            ?.firstOrNull { it.key == "aspect_ratio" && it.options.isNotEmpty() }
    } else {
        null
    }
    val modelChipLabel = when {
        currentModel != null -> currentModel.id
        models.isEmpty() -> "Каталог пуст"
        else -> "Выбрать модель"
    }
    val dockPlaceholder = when {
        mode == MediaMode.Audio && audioFlow == AudioFlow.Music -> "Опиши трек: жанр, настроение…"
        mode == MediaMode.Audio -> "Введите текст для озвучки…"
        mode == MediaMode.Video -> "Опиши видео…"
        else -> "Опиши изображение…"
    }
    // The model's documented text limit drives the counter; overflow blocks
    // sending but never truncates the text (spec §17).
    val promptLimit = if (mode == MediaMode.Audio) currentModel?.promptLimit else null
    val overLimit = promptLimit != null && prompt.length > promptLimit

    // Model switched under a too-long text: warn, trim only on explicit action.
    LaunchedEffect(mode, audioFlow, currentModel?.id) {
        val limit = currentModel?.promptLimit ?: return@LaunchedEffect
        if (mode != MediaMode.Audio || prompt.length <= limit) return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "Текст длиннее лимита выбранной модели",
            actionLabel = "Обрезать до лимита",
        )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.setDraft(mode, audioFlow, prompt.take(limit))
        }
    }

    // Canvas import: dropped on the board as an element.
    val importPicker = rememberFilePickerLauncher(
        type = FileKitType.File(extensions = listOf("png", "jpg", "jpeg", "webp", "mp4", "mov", "mp3", "wav", "m4a", "ogg")),
    ) { file: PlatformFile? ->
        file ?: return@rememberFilePickerLauncher
        scope.launch {
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@launch
            val lower = file.name.lowercase()
            val isVideo = lower.endsWith(".mp4") || lower.endsWith(".mov")
            val isAudio = lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".m4a") || lower.endsWith(".ogg")
            val ext = when {
                isVideo -> "mp4"
                isAudio -> lower.substringAfterLast('.', "mp3")
                else -> "png"
            }
            val path = "media/imports/${kotlin.time.Clock.System.now().toEpochMilliseconds()}.$ext"
            viewModel.importFromGallery(path, isVideo, bytes, isAudio = isAudio)
        }
    }

    // Reference import: attached to the next generation, not the board.
    val referencePicker = rememberFilePickerLauncher(type = FileKitType.File(extensions = listOf("png", "jpg", "jpeg", "webp", "mp4", "mov"))) { file: PlatformFile? ->
        file ?: return@rememberFilePickerLauncher
        scope.launch {
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@launch
            val isVideo = file.name.endsWith(".mp4", ignoreCase = true) || file.name.endsWith(".mov", ignoreCase = true)
            val path = "media/refs/${kotlin.time.Clock.System.now().toEpochMilliseconds()}." + if (isVideo) "mp4" else "png"
            if (com.pusios.pots.writeAppStorageBytes(path, bytes)) {
                viewModel.addReference(MediaReference(localPath = path, isVideo = isVideo))
            }
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (references.isNotEmpty()) {
                    ReferenceStrip(
                        references = references,
                        onRemoveReference = viewModel::removeReference,
                        onAddReference = { referencePicker.launch() },
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                MediaDock(
                    prompt = prompt,
                    onPromptChange = { viewModel.setDraft(mode, audioFlow, it) },
                    placeholder = dockPlaceholder,
                    isBusy = isStarting || isLocalBusy || pending.isNotEmpty(),
                    isEnhancing = isEnhancing,
                    onCreate = {
                        if (prompt.isBlank()) {
                            scope.launch { snackbarHostState.showSnackbar("Опиши, что нужно сгенерировать") }
                        } else {
                            when (mode) {
                                MediaMode.Image -> viewModel.generateImage(prompt, params)
                                MediaMode.Video -> viewModel.generateVideo(prompt, params)
                                MediaMode.Audio -> viewModel.generateAudio(prompt, params)
                            }
                        }
                    },
                    onEnhancePrompt = {
                        viewModel.enhancePrompt(prompt) { viewModel.setDraft(mode, audioFlow, it) }
                    },
                    onExpand = { showExpandedPrompt = true },
                    createEnabled = !overLimit,
                    overLimitNote = if (overLimit) "Лимит модели: $promptLimit символов" else null,
                )
            }
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            MediaCanvas(
                elements = elements,
                selectedId = selectedId,
                pendingJobs = pending,
                canvasScale = canvasScale,
                canvasOffset = canvasOffset,
                onTransform = { zoomChange, panChange ->
                    canvasScale = (canvasScale * zoomChange).coerceIn(0.5f, 3f)
                    canvasOffset += panChange
                },
                onTapBackground = {
                    viewModel.select(null)
                },
                onSelect = { id ->
                    viewModel.select(id)
                },
                onMove = viewModel::move,
                onRotate = viewModel::rotate,
                onScale = viewModel::scale,
                onAction = viewModel::applyAction,
                onExportToGallery = viewModel::exportToGallery,
                onOpenLightbox = { element ->
                    lightboxIsVideo = element.isVideo
                    lightboxIsAudio = element.isAudio
                    lightboxUrl = element.url ?: element.localPath
                },
            )

            MediaStudioTopBar(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp),
                onRecenter = {
                    canvasScale = 1f
                    canvasOffset = Offset.Zero
                },
                onImport = { importPicker.launch() },
                onOpenModels = { showModelSheet = true },
                onOpenHistory = { showHistory = true },
                historyBadge = historyEntries.count { it.isActive },
                mode = mode,
                onSelectMode = { mode = it },
                currentModelLabel = modelChipLabel,
                aspectRatioLabel = if (mode == MediaMode.Image) aspectRatioDef?.let { def -> params[def.key] ?: def.defaultValue } else null,
                onOpenAspectRatio = { showAspectRatio = true },
                paramChips = toolbarChips.map { chip ->
                    when (chip.kind) {
                        MediaToolbarChip.Kind.Sheet -> {
                            val value = effectiveParams[chip.def.key] ?: chip.def.defaultValue.orEmpty()
                            // The clone-voice axis carries a voice id, not a catalog option:
                            // show the cloned voice's name (or «Мой голос» while unset).
                            val cloneLabel = if (chip.def.key == currentModel?.cloneVoiceAxis()?.key) {
                                myVoices?.firstOrNull { it.id == value }?.name ?: "Мой голос"
                            } else {
                                null
                            }
                            MediaChipUi.Sheet(chip.def.key, cloneLabel ?: chip.def.options.firstOrNull { it.value == value }?.label ?: value)
                        }

                        MediaToolbarChip.Kind.QualityDropdown -> {
                            val current = effectiveParams[chip.def.key].orEmpty()
                            MediaChipUi.QualityDropdown(
                                key = chip.def.key,
                                label = chip.def.options.firstOrNull { it.value == current }?.let(::qualityOptionLabel) ?: "Качество",
                                options = chip.def.options.map { option ->
                                    QualityOptionUi(
                                        value = option.value,
                                        label = qualityOptionLabel(option),
                                        priceLabel = priceInfo?.optionPrices?.get(option.value)?.let { formatRub(it) + " ₽" },
                                        selected = option.value == current,
                                    )
                                },
                                footerNote = priceInfo?.footerNote,
                                onSelect = { value ->
                                    params = params + (chip.def.key to value)
                                    currentModel?.let { viewModel.selectQuality(it.id, value) }
                                },
                            )
                        }

                        MediaToolbarChip.Kind.AudioToggle -> {
                            val withSound = (effectiveParams[chip.def.key] ?: chip.def.defaultValue) == "true"
                            MediaChipUi.AudioToggle(chip.def.key, withSound = withSound) {
                                params = params + (chip.def.key to if (withSound) "false" else "true")
                            }
                        }
                    }
                },
                onOpenParam = { key -> openParam = key },
                audioFlow = audioFlow,
                onSelectAudioFlow = { flow ->
                    viewModel.selectAudioFlow(flow)
                    params = emptyMap()
                },
            )
        }
    }

    val openSheetDef = toolbarChips.firstOrNull { it.kind == MediaToolbarChip.Kind.Sheet && it.def.key == openParam }?.def
    val openCloneAxis = openSheetDef?.takeIf { it.key == currentModel?.cloneVoiceAxis()?.key }
    if (openCloneAxis != null) {
        // «Мой голос»: built-in voices + cloned voices + the creation entry
        // (spec §15–16). The list refreshes on every open.
        LaunchedEffect(openCloneAxis.key) { viewModel.loadMyVoices() }
        MediaVoiceSheet(
            title = openCloneAxis.label,
            builtInOptions = openCloneAxis.options,
            selected = params[openCloneAxis.key].orEmpty(),
            myVoices = myVoices,
            onSelect = { params = params + (openCloneAxis.key to it) },
            onOpenCreate = {
                openParam = null
                showVoiceCloneSheet = true
            },
            onDeleteVoice = { voice -> viewModel.deleteMyVoice(voice.id) },
            onDismiss = { openParam = null },
        )
    } else if (openSheetDef != null) {
        MediaParamSheet(
            title = openSheetDef.label,
            options = openSheetDef.options,
            selected = params[openSheetDef.key] ?: openSheetDef.defaultValue.orEmpty(),
            onSelect = { params = params + (openSheetDef.key to it) },
            onDismiss = { openParam = null },
        )
    }

    if (showVoiceCloneSheet) {
        MediaVoiceCloneSheet(
            isCreating = isCreatingVoice,
            onCreate = { name, level, samplePath, consent ->
                viewModel.createMyVoice(name, level, samplePath, consent) { ok ->
                    if (ok) {
                        showVoiceCloneSheet = false
                        viewModel.loadMyVoices()
                    }
                }
            },
            onDismiss = { showVoiceCloneSheet = false },
        )
    }

    if (showAspectRatio && aspectRatioDef != null) {
        MediaAspectRatioSheet(
            options = aspectRatioDef.options,
            selected = params[aspectRatioDef.key] ?: aspectRatioDef.defaultValue.orEmpty(),
            onSelect = { params = params + (aspectRatioDef.key to it) },
            onDismiss = { showAspectRatio = false },
        )
    }

    if (showModelSheet) {
        MediaModelSheet(
            models = models,
            kind = when (mode) {
                MediaMode.Image -> MediaKind.Image
                MediaMode.Video -> MediaKind.Video
                MediaMode.Audio -> MediaKind.Audio
            },
            audioFlow = audioFlow,
            hasSunoHistory = hasSunoHistory,
            selectedAudioOpts = params,
            selectedImageModelId = selectedImageModelId,
            selectedVideoModelId = selectedVideoModelId,
            selectedTtsModelId = selectedTtsModelId,
            selectedMusicModelId = selectedMusicModelId,
            onSelectImage = {
                viewModel.selectImageModel(it)
                params = emptyMap()
            },
            onSelectVideo = {
                viewModel.selectVideoModel(it)
                params = emptyMap()
            },
            onSelectAudio = {
                viewModel.selectAudioModel(it, audioFlow)
                params = emptyMap()
            },
            onDismiss = { showModelSheet = false },
        )
    }

    if (showExpandedPrompt) {
        MediaPromptSheet(
            prompt = prompt,
            onPromptChange = { viewModel.setDraft(mode, audioFlow, it) },
            placeholder = dockPlaceholder,
            promptLimit = promptLimit,
            isBusy = isStarting || isLocalBusy || pending.isNotEmpty(),
            isEnhancing = isEnhancing,
            createEnabled = !overLimit,
            onCreate = {
                if (prompt.isBlank()) {
                    scope.launch { snackbarHostState.showSnackbar("Опиши, что нужно сгенерировать") }
                } else {
                    when (mode) {
                        MediaMode.Image -> viewModel.generateImage(prompt, params)
                        MediaMode.Video -> viewModel.generateVideo(prompt, params)
                        MediaMode.Audio -> viewModel.generateAudio(prompt, params)
                    }
                    showExpandedPrompt = false
                }
            },
            onEnhancePrompt = {
                viewModel.enhancePrompt(prompt) { viewModel.setDraft(mode, audioFlow, it) }
            },
            onDismiss = { showExpandedPrompt = false },
        )
    }

    if (showHistory) {
        MediaHistorySheet(
            entries = historyEntries,
            onDismiss = { showHistory = false },
            onOpen = { entry ->
                val source = entry.localPath ?: entry.resultUrl ?: return@MediaHistorySheet
                lightboxIsVideo = entry.isVideo
                lightboxIsAudio = entry.isAudio
                lightboxUrl = source
            },
            onSave = viewModel::saveToGallery,
            onRepeat = viewModel::repeat,
            onPlaceOnCanvas = viewModel::placeOnCanvas,
            onDelete = viewModel::deleteFromHistory,
            onClearFinished = viewModel::clearFinishedHistory,
        )
    }

    lightboxUrl?.let { url ->
        MediaLightbox(
            url = url,
            isVideo = lightboxIsVideo,
            isAudio = lightboxIsAudio,
            onDismiss = { lightboxUrl = null },
            onSave = {
                scope.launch {
                    val bytes = readAppStorageBytes(url)
                        ?: runCatching { com.pusios.pots.media.PlusVibeMediaProvider().downloadResult("", url) }.getOrNull()
                    if (bytes != null) {
                        val ext = when {
                            lightboxIsAudio -> url.substringAfterLast('.', "mp3").takeIf { it.length <= 4 } ?: "mp3"
                            lightboxIsVideo -> "mp4"
                            else -> "png"
                        }
                        saveFileToDevice(bytes, "pots-lightbox", ext)
                    }
                }
            },
        )
    }
}

/** Full-screen viewer for a finished result, with save and close icon buttons. */
@Composable
fun MediaLightbox(url: String, isVideo: Boolean, onDismiss: () -> Unit, onSave: () -> Unit, isAudio: Boolean = false) {
    var scale by remember { mutableFloatStateOf(1f) }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.92f)).clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        when {
            isAudio -> Surface(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                MediaAudioPlayer(source = url, fullControls = true, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp))
            }

            isVideo -> MediaVideoPlayer(url = url, autoplay = true, fullControls = true, modifier = Modifier.fillMaxSize())

            else -> Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                    }
                },
            ) {
                AsyncImage(
                    model = rememberImageModel(url),
                    contentDescription = "Изображение (зум)",
                    modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale),
                    contentScale = ContentScale.Fit,
                )
            }
        }
        Row(Modifier.align(Alignment.TopEnd).padding(12.dp)) {
            IconButton(onClick = onSave, modifier = Modifier.handCursor()) {
                Icon(Icons.Filled.SaveAlt, contentDescription = "Сохранить", tint = Color.White)
            }
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onDismiss, modifier = Modifier.handCursor()) {
                Icon(Icons.Filled.Close, contentDescription = "Закрыть", tint = Color.White)
            }
        }
    }
}
