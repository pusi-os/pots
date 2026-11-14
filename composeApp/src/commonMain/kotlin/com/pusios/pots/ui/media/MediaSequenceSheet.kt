package com.pusios.pots.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.pusios.pots.media.SEQUENCE_IMAGE_DEFAULT_MS
import com.pusios.pots.media.SEQUENCE_IMAGE_MAX_MS
import com.pusios.pots.media.SEQUENCE_IMAGE_MIN_MS
import com.pusios.pots.media.SequenceAssemblyRequest
import com.pusios.pots.media.SequenceTransition
import com.pusios.pots.media.VideoEditException
import com.pusios.pots.media.VideoEditProgress
import com.pusios.pots.media.createVideoProcessor
import com.pusios.pots.media.sequenceDurationMs
import com.pusios.pots.writeAppStorageBytes
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.time.Clock

private val SEQUENCE_AUDIO_EXTENSIONS = listOf("mp3", "wav", "m4a", "ogg", "opus", "flac", "aac")
private val SEQUENCE_OUTPUT_HEIGHTS = listOf(720, 1080)
private val SEQUENCE_OUTPUT_FPS = listOf(24, 30)

/**
 * Full-screen sequence sheet (spec §7): photo/video elements line up in
 * playback order, every gap carries a transition (crossfade default — the
 * hyperframes «always a transition» pattern), one optional soundtrack loops
 * under the result and optional subtitle text burns into the frame. One
 * button assembles the clip through the ffmpeg processor; progress, cancel,
 * retry and the §8 error card match the video editor.
 *
 * Local processing runs on the system ffmpeg (desktop only); the sheet opens
 * with a runtime tool check and shows the §8 card with install steps when the
 * binary is missing.
 */
@Composable
internal fun MediaSequenceSheet(
    state: MediaSequenceState,
    canvasElements: List<CanvasElement>,
    onAddClip: (CanvasElement, Long) -> Unit,
    onRemoveClip: (Int) -> Unit,
    onMoveClip: (Int, Int) -> Unit,
    onClipDuration: (Int, Long) -> Unit,
    onTransition: (Int, SequenceTransition) -> Unit,
    onResult: (relPath: String, clipCount: Int, durationMs: Long, withAudio: Boolean, withSubtitles: Boolean) -> Boolean,
    onFailure: (clipCount: Int, reason: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val processor = remember { createVideoProcessor() }

    var toolMissing by remember { mutableStateOf(false) }
    var probeTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(probeTick) {
        toolMissing = !processor.isAvailable()
    }

    // Assembly state: progress 0..1, cancel flag, §8 error card.
    var opRunning by remember { mutableStateOf(false) }
    var opProgress by remember { mutableFloatStateOf(0f) }
    var opCancel by remember { mutableStateOf(false) }
    var opError by remember { mutableStateOf<VideoEditException?>(null) }
    var opJob by remember { mutableStateOf<Job?>(null) }
    var lastOp by remember { mutableStateOf<(() -> Unit)?>(null) }

    // Track, subtitles and output options.
    var audioRel by remember { mutableStateOf<String?>(null) }
    var audioLabel by remember { mutableStateOf<String?>(null) }
    var subtitleText by remember { mutableStateOf("") }
    var outputHeight by remember { mutableIntStateOf(1080) }
    var outputFps by remember { mutableIntStateOf(30) }
    var addingElementId by remember { mutableStateOf<String?>(null) }

    val audioFilePicker = rememberFilePickerLauncher(type = FileKitType.File(extensions = SEQUENCE_AUDIO_EXTENSIONS)) { file: PlatformFile? ->
        file ?: return@rememberFilePickerLauncher
        scope.launch {
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@launch
            val ext = file.name.substringAfterLast('.', "mp3").lowercase().takeIf { it.length <= 4 } ?: "mp3"
            val path = "media/tmp/${Clock.System.now().toEpochMilliseconds()}-sequence-audio.$ext"
            if (writeAppStorageBytes(path, bytes)) {
                audioRel = path
                audioLabel = file.name
            }
        }
    }

    val totalMs = remember(state.clips, state.transitions) {
        runCatching { sequenceDurationMs(state.clips, state.transitions) }.getOrDefault(0L)
    }

    fun launchAssembly() {
        if (opJob?.isActive == true) return
        val request = SequenceAssemblyRequest(
            clips = state.clips,
            transitions = state.transitions,
            width = if (outputHeight == 720) 1280 else 1920,
            height = outputHeight,
            fps = outputFps,
            audioRelPath = audioRel,
            subtitleText = subtitleText.takeIf { it.isNotBlank() },
        )
        val clipCount = state.clips.size
        val expectedMs = totalMs
        opError = null
        opCancel = false
        opProgress = 0f
        opRunning = true
        lastOp = { launchAssembly() }
        opJob = scope.launch {
            try {
                val relPath = processor.assembleSequence(request) { fraction ->
                    opProgress = fraction
                    !opCancel
                }
                val withSubtitles = request.subtitleText != null
                if (!onResult(relPath, clipCount, expectedMs, audioRel != null, withSubtitles)) {
                    throw VideoEditException.ExportFailed("не удалось добавить элемент на канвас")
                }
                onDismiss()
            } catch (e: VideoEditException) {
                opError = e
                if (e !is VideoEditException.Cancelled) onFailure(clipCount, e.detail)
            } catch (e: CancellationException) {
                opError = VideoEditException.Cancelled()
            } finally {
                opRunning = false
            }
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Сборка видео", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        if (totalMs > 0) "Итог: ${formatTime(totalMs)} · клипов: ${state.clips.size}" else "Добавьте фото и видео в нужном порядке",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Закрыть сборку", tint = MaterialTheme.colorScheme.onSurface)
                }
            }

            when {
                toolMissing -> {
                    val missing = remember { VideoEditException.ToolMissing() }
                    VideoEditErrorCard(missing, onFix = { probeTick++ }, fixLabel = missing.fix, onDismissError = onDismiss)
                }

                else -> {
                    // Operation status: progress with cancel, or the §8 error card.
                    when {
                        opRunning -> Row(
                            Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LinearProgressIndicator(
                                progress = { opProgress },
                                modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)),
                            )
                            Spacer(Modifier.width(12.dp))
                            OutlinedButton(onClick = { opCancel = true }) { Text("Отмена") }
                        }

                        opError != null -> VideoEditErrorCard(opError!!, lastOp, "Повторить") { opError = null }
                    }

                    Spacer(Modifier.height(8.dp))

                    // -- Strip: clips with junction rows between them --------
                    state.clips.forEachIndexed { index, clip ->
                        SequenceClipRow(
                            index = index,
                            clip = clip,
                            count = state.clips.size,
                            enabled = !opRunning,
                            onDuration = { onClipDuration(index, it) },
                            onMoveUp = { onMoveClip(index, index - 1) },
                            onMoveDown = { onMoveClip(index, index + 1) },
                            onRemove = { onRemoveClip(index) },
                        )
                        if (index < state.transitions.size) {
                            SequenceJunctionRow(
                                selected = state.transitions[index],
                                enabled = !opRunning,
                                onSelect = { onTransition(index, it) },
                            )
                        }
                    }

                    // -- Add from canvas --------------------------------------
                    Spacer(Modifier.height(8.dp))
                    Text("Добавить в последовательность", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(4.dp))
                    val candidates = canvasElements.filter { !it.isAudio && it.localPath != null }
                    if (candidates.isEmpty()) {
                        Text(
                            "На канвасе нет фото или видео с локальным файлом. Сгенерируйте или импортируйте их.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            candidates.forEach { element ->
                                FilterChip(
                                    selected = false,
                                    enabled = !opRunning && addingElementId == null,
                                    onClick = {
                                        val rel = element.localPath ?: return@FilterChip
                                        if (!element.isVideo) {
                                            onAddClip(element, SEQUENCE_IMAGE_DEFAULT_MS)
                                        } else {
                                            addingElementId = element.id
                                            scope.launch {
                                                try {
                                                    val info = processor.probe(rel)
                                                    onAddClip(element, info.durationMs)
                                                } catch (e: VideoEditException) {
                                                    opError = e
                                                } finally {
                                                    addingElementId = null
                                                }
                                            }
                                        }
                                    },
                                    label = {
                                        if (addingElementId == element.id) {
                                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                        } else {
                                            Text((if (element.isVideo) "▶ " else "") + (element.localPath?.substringAfterLast('/') ?: "элемент"))
                                        }
                                    },
                                )
                            }
                        }
                    }

                    // -- Soundtrack --------------------------------------------
                    Spacer(Modifier.height(16.dp))
                    Text("Звуковая дорожка", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "Короткий трек зацикливается, длинный обрезается по длительности ролика (хронометраж, не кадры) и затухает в конце. Звук самих клипов в ролик не попадает.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = audioRel == null, enabled = !opRunning, onClick = {
                            audioRel = null
                            audioLabel = null
                        }, label = { Text("Без звука") })
                        canvasElements.filter { it.isAudio && it.localPath != null }.forEach { track ->
                            FilterChip(
                                selected = audioRel == track.localPath,
                                enabled = !opRunning,
                                onClick = {
                                    audioRel = track.localPath
                                    audioLabel = track.localPath!!.substringAfterLast('/')
                                },
                                label = { Text(track.localPath!!.substringAfterLast('/')) },
                            )
                        }
                        OutlinedButton(onClick = { audioFilePicker.launch() }, enabled = !opRunning) { Text("Файл…") }
                    }
                    if (audioLabel != null && audioRel?.startsWith("media/tmp/") == true) {
                        Text("Файл: $audioLabel", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    // -- Subtitles ----------------------------------------------
                    Spacer(Modifier.height(16.dp))
                    Text("Субтитры", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = subtitleText,
                        onValueChange = { subtitleText = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 84.dp),
                        enabled = !opRunning,
                        label = { Text("Текст субтитров (необязательно)") },
                        supportingText = { Text("Фразы распределяются равномерно по всей длине ролика и вшиваются в кадр. Пусто — без субтитров.") },
                    )

                    // -- Output options -------------------------------------------
                    Spacer(Modifier.height(12.dp))
                    Text("Разрешение", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SEQUENCE_OUTPUT_HEIGHTS.forEach { height ->
                            FilterChip(
                                selected = outputHeight == height,
                                enabled = !opRunning,
                                onClick = { outputHeight = height },
                                label = { Text("${height}p") },
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Частота кадров", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SEQUENCE_OUTPUT_FPS.forEach { fps ->
                            FilterChip(
                                selected = outputFps == fps,
                                enabled = !opRunning,
                                onClick = { outputFps = fps },
                                label = { Text("$fps fps") },
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { launchAssembly() }, enabled = !opRunning && state.clips.isNotEmpty()) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Собрать видео")
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

/** One strip row: thumbnail, label with duration, screen-time slider for stills, reorder and delete. */
@Composable
private fun SequenceClipRow(
    index: Int,
    clip: com.pusios.pots.media.SequenceClip,
    count: Int,
    enabled: Boolean,
    onDuration: (Long) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                if (clip.isVideo) {
                    Icon(Icons.Filled.Movie, contentDescription = "Видео-клип", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    AsyncImage(
                        model = rememberImageModel(clip.relPath),
                        contentDescription = "Кадр клипа",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${index + 1}. " + (clip.relPath.substringAfterLast('/')),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (!clip.isVideo) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Slider(
                            value = clip.durationMs.toFloat(),
                            onValueChange = { onDuration(it.toLong()) },
                            valueRange = SEQUENCE_IMAGE_MIN_MS.toFloat()..SEQUENCE_IMAGE_MAX_MS.toFloat(),
                            steps = ((SEQUENCE_IMAGE_MAX_MS - SEQUENCE_IMAGE_MIN_MS) / 1000 - 1).toInt(),
                            enabled = enabled,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${clip.durationMs / 1000} с",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                } else {
                    Text(formatTime(clip.durationMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = onMoveUp, enabled = enabled && index > 0) {
                Icon(Icons.Filled.ArrowUpward, contentDescription = "Выше", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onMoveDown, enabled = enabled && index < count - 1) {
                Icon(Icons.Filled.ArrowDownward, contentDescription = "Ниже", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onRemove, enabled = enabled) {
                Icon(Icons.Filled.Delete, contentDescription = "Убрать из последовательности", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The transition slot between two clips; crossfade is the default, a cut is an explicit choice. */
@Composable
private fun SequenceJunctionRow(
    selected: SequenceTransition,
    enabled: Boolean,
    onSelect: (SequenceTransition) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Переход:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SequenceTransition.entries.forEach { transition ->
            FilterChip(
                selected = selected == transition,
                enabled = enabled,
                onClick = { onSelect(transition) },
                label = { Text(transition.labelRu) },
            )
        }
    }
}
