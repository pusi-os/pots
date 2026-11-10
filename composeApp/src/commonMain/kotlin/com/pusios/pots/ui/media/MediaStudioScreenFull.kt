package com.pusios.pots.ui.media

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.rememberAsyncImagePainter
import com.pusios.pots.media.MediaKind
import com.pusios.pots.media.MediaParamDef
import com.pusios.pots.readAppStorageBytes
import com.pusios.pots.saveFileToDevice
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun MediaStudioScreenFull(
    viewModel: MediaStudioViewModel,
    modifier: Modifier = Modifier,
    onAskAgent: (String) -> Unit = {},
    onExit: () -> Unit = {},
    onSpeak: (String) -> Unit = {},
    speechEnabled: Boolean = false,
) {
    val elements by viewModel.elements.collectAsState()
    val selectedId by viewModel.selectedId.collectAsState()
    val models by viewModel.models.collectAsState()
    val jobs by viewModel.jobs.collectAsState()
    val references by viewModel.references.collectAsState()
    val error by viewModel.error.collectAsState()
    val justFinished by viewModel.justFinished.collectAsState()

    LaunchedEffect(justFinished) {
        val done = justFinished ?: return@LaunchedEffect
        if (speechEnabled) onSpeak("Генерация готова")
        viewModel.consumeFinished()
    }

    var prompt by remember { mutableStateOf("") }
    var isVideoMode by remember { mutableStateOf(false) }
    var canvasScale by remember { mutableFloatStateOf(1f) }
    var canvasOffset by remember { mutableStateOf(Offset.Zero) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var menuOffset by remember { mutableStateOf(Offset.Zero) }
    var params by remember { mutableStateOf<Coordinator>(Coordinator(opts = emptyMap())) }
    var lightboxUrl by remember { mutableStateOf<String?>(null) }
    var lightboxIsVideo by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val showAllElements = remember(elements) { elements.sortedBy { it.zIndex } }
    val pending = jobs.values.filter { !it.isTerminal }
    val imageModels = models.filter { it.kind == MediaKind.Image }
    val videoModels = models.filter { it.kind == MediaKind.Video }
    val currentKind = if (isVideoMode) MediaKind.Video else MediaKind.Image
    val currentModels = if (isVideoMode) videoModels else imageModels
    val newPicker = rememberFilePickerLauncher(type = FileKitType.File(extensions = listOf("png", "jpg", "jpeg", "webp", "mp4", "mov"))) { file: PlatformFile? ->
        file ?: return@rememberFilePickerLauncher
        scope.launch {
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@launch
            val isVideo = file.name.endsWith(".mp4", ignoreCase = true) || file.name.endsWith(".mov", ignoreCase = true)
            val path = "media/imports/${kotlin.time.Clock.System.now().toEpochMilliseconds()}." + (if (isVideo) "mp4" else "png")
            viewModel.importFromGallery(path, isVideo, bytes)
        }
    }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onExit) { Text("Чат") }
            Spacer(Modifier.weight(1f))
            Text("Медиа", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { newPicker.launch() }) { Text("Импорт") }
            TextButton(onClick = { viewModel.loadModels() }) { Text("Модели") }
        }

        Box(
            Modifier.weight(1f).fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .semantics { contentDescription = "Медиа-канвас" }
                .transformable(
                    state = rememberTransformableState { zoomChange, panChange, _ ->
                        canvasScale = (canvasScale * zoomChange).coerceIn(0.5f, 3f)
                        canvasOffset += panChange
                    },
                )
                .clickable {
                    viewModel.select(null)
                    menuFor = null
                },
        ) {
            showAllElements.forEach { element ->
                val beam = element.localPath
                val url = element.url
                Box(
                    Modifier
                        .offset { IntOffset((element.x + canvasOffset.x).roundToInt(), (element.y + canvasOffset.y).roundToInt()) }
                        .size(232.dp)
                        .graphicsLayer(scaleX = element.scale * canvasScale, scaleY = element.scale * canvasScale, rotationZ = element.rotation)
                        .then(if (element.id == selectedId || element.id == menuFor) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)) else Modifier)
                        .pointerInput(element.id) {
                            detectDragGestures(
                                onDragEnd = {},
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    viewModel.move(element.id, dragAmount.x, dragAmount.y)
                                },
                            )
                        }
                        .semantics { contentDescription = if (element.isVideo) "Видео-элемент" else "Изображение" }
                        .clickable(role = Role.Button) {
                            viewModel.select(element.id)
                            menuFor = element.id
                            menuOffset = Offset(element.x, element.y + 232f)
                        },
                ) {
                    if (element.isVideo) {
                        MediaVideoPlayer(url = url ?: beam ?: "", autoplay = false, fullControls = true, modifier = Modifier.fillMaxSize())
                    } else {
                        AsyncImage(
                            model = url ?: beam,
                            contentDescription = "Изображение на канвасе",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    }
                    // Mouse handles: rotate / scale chips appear when selected.
                    if (element.id == selectedId) {
                        Row(Modifier.align(Alignment.BottomCenter).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)).padding(2.dp)) {
                            TextButton(onClick = { viewModel.rotate(element.id, 15f) }) { Text("⟳") }
                            TextButton(onClick = { viewModel.rotate(element.id, -15f) }) { Text("⟲") }
                            TextButton(onClick = { viewModel.scale(element.id, 1.2f) }) { Text("＋") }
                            TextButton(onClick = { viewModel.scale(element.id, 0.85f) }) { Text("−") }
                            TextButton(onClick = {
                                lightboxIsVideo = element.isVideo
                                lightboxUrl = url ?: beam
                            }) { Text("⤢") }
                        }
                    }
                }
            }

            pending.forEach { CircularProgressIndicator(Modifier.align(Alignment.Center)) }

            if (showAllElements.isEmpty() && pending.isEmpty()) {
                Box(Modifier.align(Alignment.Center), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Сгенерируй первое изображение или видео", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        Text("Выбери модель ниже и нажми «Создать»", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            menuFor?.let { id ->
                Surface(modifier = Modifier.offset { IntOffset(menuOffset.x.roundToInt(), menuOffset.y.roundToInt()) }.padding(8.dp), tonalElevation = 4.dp) {
                    Column(Modifier.padding(6.dp)) {
                        listOf(
                            "Вперёд" to CanvasAction.BringForward,
                            "Назад" to CanvasAction.SendBack,
                            "Дублировать" to CanvasAction.Duplicate,
                            "Как референс" to CanvasAction.UseAsReference,
                            "В галерею" to null,
                            "Удалить" to CanvasAction.Delete,
                            "Закрыть" to CanvasAction.ClearSelection,
                        ).forEach { (label, action) ->
                            TextButton(onClick = {
                                if (label == "В галерею") {
                                    viewModel.exportToGallery(id)
                                } else if (action != null) {
                                    viewModel.applyAction(id, action)
                                }
                                menuFor = null
                            }) { Text(label) }
                        }
                    }
                }
            }
        }

        ParamChips(
            models = currentModels,
            currentKind = currentKind,
            params = params,
            onChange = { params = it },
        )

        if (references.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Референсы: ${references.size}", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { viewModel.clearReferences() }) { Text("Сбросить") }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = { scope.launch { saveFileToDevice(ByteArray(0), "refs", "txt") } }) { Text("Сохранить набор") }
            }
        }

        error?.let { message ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Text(message, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { viewModel.dismissError() }) { Text("OK") }
            }
        }

        LazyColumn(Modifier.fillMaxWidth().height(96.dp).padding(horizontal = 12.dp)) {
            items(currentModels) { model ->
                ModelRow(model = model, onSelect = {
                    if (model.kind == MediaKind.Video) viewModel.selectVideoModel(model.id) else viewModel.selectImageModel(model.id)
                    params = params.copy(models = models)
                })
            }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { isVideoMode = !isVideoMode }) { Text(if (isVideoMode) "Видео" else "Изображение") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { onAskAgent("Улучши этот промпт для генерации: $prompt") }) { Text("✨ Улучшить") }
            Spacer(Modifier.weight(1f))
            Button(onClick = {
                if (prompt.isBlank()) return@Button
                val opts = params.opts
                if (isVideoMode) viewModel.generateVideo(prompt, opts) else viewModel.generateImage(prompt, opts)
            }) { Text("Создать") }
        }

        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            placeholder = { Text("Опиши изображение или видео…") },
            singleLine = false,
            minLines = 1,
            maxLines = 3,
        )

        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Агент:", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(8.dp))
            Button(onClick = { if (prompt.isNotBlank()) onAskAgent(prompt) }) { Text("Спросить агента") }
            Spacer(Modifier.weight(1f))
            // Voice status mirror (mic stays in chat; studio announces via TTS).
            Text(if (speechEnabled) "Озвучка вкл." else "Озвучка выкл.", style = MaterialTheme.typography.labelSmall)
        }
    }

    lightboxUrl?.let { url ->
        MediaLightbox(url = url, isVideo = lightboxIsVideo, onDismiss = { lightboxUrl = null }, onSave = {
            scope.launch {
                val bytes = runCatching {
                    PlusVibeMediaProviderTestlessDownload(url)
                }.getOrNull() ?: readAppStorageBytes(url)
                if (bytes != null) saveFileToDevice(bytes, "pots-lightbox", if (lightboxIsVideo) "mp4" else "png")
            }
        })
    }
}

private suspend fun PlusVibeMediaProviderTestlessDownload(url: String): ByteArray {
    // Local paths use app storage; remote uses provider download (key-free for signed URLs).
    return readAppStorageBytes(url) ?: com.pusios.pots.media.PlusVibeMediaProvider().downloadResult("", url)
}

data class Coordinator(val models: List<com.pusios.pots.media.MediaModel> = emptyList(), val opts: Map<String, String> = emptyMap())

@Composable
private fun ParamChips(models: List<com.pusios.pots.media.MediaModel>, currentKind: MediaKind, params: Coordinator, onChange: (Coordinator) -> Unit) {
    // Show opts of the first catalog model that declares aspect_ratio/duration/resolution.
    val anyModel = models.firstOrNull() ?: return
    val defs = anyModel.params.filter { it.key in listOf("aspect_ratio", "duration", "resolution") && it.options.isNotEmpty() }
    if (defs.isEmpty()) return
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
        defs.forEach { def ->
            ParamRow(def = def, selected = params.opts[def.key] ?: def.defaultValue, onSelect = { v ->
                onChange(params.copy(models = models, opts = params.opts + (def.key to v)))
            })
        }
    }
}

@Composable
private fun ParamRow(def: MediaParamDef, selected: String?, onSelect: (String) -> Unit) {
    Text(def.label + ": ", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(end = 4.dp))
    def.options.take(6).forEach { opt ->
        AssistChip(
            onClick = { onSelect(opt.value) },
            label = { Text(opt.label) },
            modifier = Modifier.padding(end = 4.dp),
        )
    }
    Spacer(Modifier.width(8.dp))
}

@Composable
private fun ModelRow(model: com.pusios.pots.media.MediaModel, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(model.id, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        val price = model.fromRub?.let { "от $it ₽" } ?: ""
        val unit = model.unit ?: ""
        Text(price + if (price.isNotEmpty() && unit.isNotEmpty()) " · $unit" else unit, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun MediaLightbox(url: String, isVideo: Boolean, onDismiss: () -> Unit, onSave: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.92f)).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
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
                AsyncImage(model = url, contentDescription = "Изображение (зум)", modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale), contentScale = ContentScale.Fit)
            }
        }
        Row(Modifier.align(Alignment.TopEnd).padding(12.dp)) {
            TextButton(onClick = onSave) { Text("Сохранить") }
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        }
    }
}
