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
import com.pusios.pots.media.MediaKind
import com.pusios.pots.media.MediaParamDef
import com.pusios.pots.media.MediaReference
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
    val isEnhancing by viewModel.isEnhancing.collectAsState()
    val justFinished by viewModel.justFinished.collectAsState()
    val selectedImageModelId by viewModel.selectedImageModelId.collectAsState()
    val selectedVideoModelId by viewModel.selectedVideoModelId.collectAsState()
    val historyEntries by viewModel.historyEntries.collectAsState()

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

    var prompt by remember { mutableStateOf("") }
    var isVideoMode by remember { mutableStateOf(false) }
    var canvasScale by remember { mutableFloatStateOf(1f) }
    var canvasOffset by remember { mutableStateOf(Offset.Zero) }
    var params by remember { mutableStateOf(mapOf<String, String>()) }
    var showModelSheet by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showAspectRatio by remember { mutableStateOf(false) }
    var lightboxUrl by remember { mutableStateOf<String?>(null) }
    var lightboxIsVideo by remember { mutableStateOf(false) }

    val pending = jobs.values.filter { !it.isTerminal }
    val imageModels = models.filter { it.kind == MediaKind.Image }
    val videoModels = models.filter { it.kind == MediaKind.Video }
    val currentModels = if (isVideoMode) videoModels else imageModels
    val currentModelId = if (isVideoMode) selectedVideoModelId else selectedImageModelId
    val currentModel = currentModels.firstOrNull { it.id == currentModelId } ?: currentModels.firstOrNull()
    val paramDefs: List<MediaParamDef> = currentModel
        ?.params
        ?.filter { it.key in listOf("duration", "resolution") && it.options.isNotEmpty() }
        ?.filter { it.key != "aspect_ratio" }
        .orEmpty()
    val aspectRatioDef: MediaParamDef? = if (isVideoMode) {
        null
    } else {
        currentModel
            ?.params
            ?.firstOrNull { it.key == "aspect_ratio" && it.options.isNotEmpty() }
    }
    val modelChipLabel = when {
        currentModel != null -> currentModel.id
        models.isEmpty() -> "Каталог пуст"
        else -> "Выбрать модель"
    }

    // Canvas import: dropped on the board as an element.
    val importPicker = rememberFilePickerLauncher(type = FileKitType.File(extensions = listOf("png", "jpg", "jpeg", "webp", "mp4", "mov"))) { file: PlatformFile? ->
        file ?: return@rememberFilePickerLauncher
        scope.launch {
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@launch
            val isVideo = file.name.endsWith(".mp4", ignoreCase = true) || file.name.endsWith(".mov", ignoreCase = true)
            val path = "media/imports/${kotlin.time.Clock.System.now().toEpochMilliseconds()}." + if (isVideo) "mp4" else "png"
            viewModel.importFromGallery(path, isVideo, bytes)
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
                    onPromptChange = { prompt = it },
                    isVideoMode = isVideoMode,
                    isBusy = isStarting || pending.isNotEmpty(),
                    isEnhancing = isEnhancing,
                    onCreate = {
                        if (prompt.isBlank()) {
                            scope.launch { snackbarHostState.showSnackbar("Опиши, что нужно сгенерировать") }
                        } else if (isVideoMode) {
                            viewModel.generateVideo(prompt, params)
                        } else {
                            viewModel.generateImage(prompt, params)
                        }
                    },
                    paramDefs = paramDefs,
                    paramValues = params,
                    onParamSelect = { key, value -> params = params + (key to value) },
                    onEnhancePrompt = {
                        viewModel.enhancePrompt(prompt) { prompt = it }
                    },
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
                isVideoMode = isVideoMode,
                onToggleMode = { isVideoMode = !isVideoMode },
                currentModelLabel = modelChipLabel,
                aspectRatioLabel = if (isVideoMode) null else aspectRatioDef?.let { def -> params[def.key] ?: def.defaultValue },
                onOpenAspectRatio = { showAspectRatio = true },
            )
        }
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
            kind = if (isVideoMode) MediaKind.Video else MediaKind.Image,
            selectedImageModelId = selectedImageModelId,
            selectedVideoModelId = selectedVideoModelId,
            onSelectImage = {
                viewModel.selectImageModel(it)
                params = emptyMap()
            },
            onSelectVideo = {
                viewModel.selectVideoModel(it)
                params = emptyMap()
            },
            onDismiss = { showModelSheet = false },
        )
    }

    if (showHistory) {
        MediaHistorySheet(
            entries = historyEntries,
            onDismiss = { showHistory = false },
            onOpen = { entry ->
                val source = entry.localPath ?: entry.resultUrl ?: return@MediaHistorySheet
                lightboxIsVideo = entry.isVideo
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
            onDismiss = { lightboxUrl = null },
            onSave = {
                scope.launch {
                    val bytes = readAppStorageBytes(url)
                        ?: runCatching { com.pusios.pots.media.PlusVibeMediaProvider().downloadResult("", url) }.getOrNull()
                    if (bytes != null) saveFileToDevice(bytes, "pots-lightbox", if (lightboxIsVideo) "mp4" else "png")
                }
            },
        )
    }
}

/** Full-screen viewer for a finished result, with save and close icon buttons. */
@Composable
fun MediaLightbox(url: String, isVideo: Boolean, onDismiss: () -> Unit, onSave: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.92f)).clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        if (isVideo) {
            MediaVideoPlayer(url = url, autoplay = true, fullControls = true, modifier = Modifier.fillMaxSize())
        } else {
            Box(
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
