package com.pusios.pots.ui.media

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.pusios.pots.media.VideoEditException
import com.pusios.pots.media.VideoEditProgress
import com.pusios.pots.media.VideoFileInfo
import com.pusios.pots.media.createVideoProcessor
import com.pusios.pots.writeAppStorageBytes
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Clock

private const val FILM_FRAMES = 7
private const val MIN_TRIM_MS = 200L
private val VIDEO_SPEED_PRESETS = floatArrayOf(0.5f, 1f, 1.5f, 2f)
private val AUDIO_TRACK_EXTENSIONS = listOf("mp3", "wav", "m4a", "ogg", "opus", "flac", "aac")

/** One filmstrip cell: the timecode it stands for plus its PNG, if grabbed. */
private data class FilmFrame(val atMs: Long, val png: ByteArray?)

/** "0.25×"–"4.0×" without locale-dependent formatting. */
private fun formatFactor(factor: Float): String {
    val rounded = kotlin.math.round(factor * 100f) / 100f
    return "$rounded×"
}

private fun videoElementLabel(element: CanvasElement): String = element.localPath?.substringAfterLast('/') ?: element.url?.substringAfterLast('/')?.take(24) ?: "видео"

/**
 * Full-screen video editor (spec §6 lineage): trim by draggable handles over
 * a filmstrip, speed change with presets, audio track removal or replacement
 * from a canvas audio element or a file. Every operation renders a NEW mp4
 * into app storage through [onResult]; the source element is never touched.
 *
 * Local processing runs on the system ffmpeg (desktop only); the sheet opens
 * with a runtime tool check and shows the §8 card with install steps when the
 * binary is missing.
 */
@Composable
internal fun MediaVideoEditorSheet(
    element: CanvasElement,
    audioElements: List<CanvasElement>,
    onDismiss: () -> Unit,
    onResult: (String) -> Boolean,
) {
    val scope = rememberCoroutineScope()
    val processor = remember { createVideoProcessor() }
    val localPath = element.localPath

    var toolMissing by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<VideoFileInfo?>(null) }
    var frames by remember { mutableStateOf<List<FilmFrame>>(emptyList()) }
    var loadError by remember { mutableStateOf<VideoEditException?>(null) }
    var probeTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(probeTick) {
        toolMissing = false
        loadError = null
        info = null
        frames = emptyList()
        if (localPath == null) {
            loadError = VideoEditException.SourceMissing()
            return@LaunchedEffect
        }
        if (!processor.isAvailable()) {
            toolMissing = true
            return@LaunchedEffect
        }
        try {
            val probed = processor.probe(localPath)
            info = probed
            val film = ArrayList<FilmFrame>(FILM_FRAMES)
            repeat(FILM_FRAMES) { index ->
                val at = ((index + 0.5) * probed.durationMs / FILM_FRAMES).toLong()
                film.add(FilmFrame(at, processor.grabFramePng(localPath, at)))
                frames = film.toList()
            }
        } catch (e: VideoEditException) {
            loadError = e
        }
    }

    // Operation state: progress 0..1, cancel flag, §8 error card.
    var opRunning by remember { mutableStateOf(false) }
    var opProgress by remember { mutableFloatStateOf(0f) }
    var opCancel by remember { mutableStateOf(false) }
    var opError by remember { mutableStateOf<VideoEditException?>(null) }
    var opJob by remember { mutableStateOf<Job?>(null) }
    var lastOp by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun launchOp(op: suspend (VideoEditProgress) -> String) {
        if (opJob?.isActive == true) return
        opError = null
        opCancel = false
        opProgress = 0f
        opRunning = true
        lastOp = { launchOp(op) }
        opJob = scope.launch {
            try {
                val relPath = op { fraction ->
                    opProgress = fraction
                    !opCancel
                }
                if (!onResult(relPath)) throw VideoEditException.ExportFailed("не удалось добавить элемент на канвас")
                onDismiss()
            } catch (e: VideoEditException) {
                opError = e
            } catch (e: CancellationException) {
                opError = VideoEditException.Cancelled()
            } finally {
                opRunning = false
            }
        }
    }

    var trimStartMs by remember { mutableLongStateOf(0L) }
    var trimEndMs by remember { mutableLongStateOf(0L) }
    var speedFactor by remember { mutableFloatStateOf(1f) }
    var pickedAudioRel by remember { mutableStateOf<String?>(null) }
    var pickedAudioLabel by remember { mutableStateOf<String?>(null) }

    val audioFilePicker = rememberFilePickerLauncher(type = FileKitType.File(extensions = AUDIO_TRACK_EXTENSIONS)) { file: PlatformFile? ->
        file ?: return@rememberFilePickerLauncher
        scope.launch {
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@launch
            val ext = file.name.substringAfterLast('.', "mp3").lowercase().takeIf { it.length <= 4 } ?: "mp3"
            val path = "media/tmp/${Clock.System.now().toEpochMilliseconds()}-audio.$ext"
            if (writeAppStorageBytes(path, bytes)) {
                pickedAudioRel = path
                pickedAudioLabel = file.name
            }
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Редактор видео", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        videoElementLabel(element) + (info?.let { " · " + formatTime(it.durationMs) } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Закрыть редактор", tint = MaterialTheme.colorScheme.onSurface)
                }
            }

            when {
                toolMissing -> {
                    val missing = remember { VideoEditException.ToolMissing() }
                    VideoEditErrorCard(missing, onFix = { probeTick++ }, fixLabel = missing.fix, onDismissError = onDismiss)
                }

                loadError != null -> VideoEditErrorCard(loadError!!, null, null, onDismissError = onDismiss)

                info == null -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                else -> {
                    val probed = info!!
                    val source = localPath!!

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

                    // -- Trim ---------------------------------------------------
                    Text("Обрезка", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "Потяните ручки начала и конца по ленте кадров. Кадр режется точно: видео перекодируется.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    VideoTrimFilmstrip(
                        frames = frames,
                        durationMs = probed.durationMs,
                        trimStartMs = trimStartMs,
                        trimEndMs = trimEndMs,
                        onTrim = { start, end ->
                            trimStartMs = start
                            trimEndMs = end
                        },
                        modifier = Modifier.fillMaxWidth().height(84.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Начало ${formatTime(trimStartMs)} · Конец ${formatTime(trimEndMs)} · Итог ${formatTime(trimEndMs - trimStartMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Button(
                        onClick = { launchOp { progress -> processor.trim(source, trimStartMs, trimEndMs, probed, progress) } },
                        enabled = !opRunning && trimEndMs - trimStartMs >= MIN_TRIM_MS,
                    ) {
                        Text("Обрезать")
                    }

                    Spacer(Modifier.height(16.dp))

                    // -- Speed --------------------------------------------------
                    Text("Скорость", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "Звук ускоряется вместе с видео — тон меняется, как на плёнке.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Slider(
                            value = speedFactor,
                            onValueChange = { speedFactor = it },
                            valueRange = 0.25f..4f,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            formatFactor(speedFactor),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        VIDEO_SPEED_PRESETS.forEach { preset ->
                            FilterChip(
                                selected = abs(speedFactor - preset) < 0.01f,
                                onClick = { speedFactor = preset },
                                label = { Text(formatFactor(preset)) },
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Длительность: ${formatTime(probed.durationMs)} → ${formatTime(com.pusios.pots.media.speedDurationMs(probed.durationMs, speedFactor))}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Button(
                        onClick = { launchOp { progress -> processor.changeSpeed(source, speedFactor, probed, progress) } },
                        enabled = !opRunning && abs(speedFactor - 1f) >= 0.01f,
                    ) {
                        Text("Применить")
                    }

                    Spacer(Modifier.height(16.dp))

                    // -- Audio track ---------------------------------------------
                    Text("Аудиодорожка", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "Видео копируется без перекодировки — замена занимает секунды.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    if (probed.hasAudio) {
                        OutlinedButton(
                            onClick = { launchOp { progress -> processor.removeAudio(source, probed, progress) } },
                            enabled = !opRunning,
                        ) {
                            Text("Убрать звук")
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    Text("Заменить на:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    val canvasTracks = audioElements.filter { it.isAudio && it.localPath != null && it.id != element.id }
                    canvasTracks.forEach { track ->
                        FilterChip(
                            selected = pickedAudioRel == track.localPath,
                            onClick = {
                                pickedAudioRel = track.localPath
                                pickedAudioLabel = track.localPath!!.substringAfterLast('/')
                            },
                            label = { Text(track.localPath!!.substringAfterLast('/')) },
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { audioFilePicker.launch() }, enabled = !opRunning) {
                            Text("Файл…")
                        }
                        if (pickedAudioLabel != null && canvasTracks.none { it.localPath == pickedAudioRel }) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                pickedAudioLabel!!,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Button(
                        onClick = {
                            val audioRel = pickedAudioRel ?: return@Button
                            launchOp { progress -> processor.replaceAudio(source, audioRel, probed, progress) }
                        },
                        enabled = !opRunning && pickedAudioRel != null,
                    ) {
                        Text("Заменить дорожку")
                    }

                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

/** Filmstrip with draggable start/end handles; excluded tails are dimmed. */
@Composable
private fun VideoTrimFilmstrip(
    frames: List<FilmFrame>,
    durationMs: Long,
    trimStartMs: Long,
    trimEndMs: Long,
    onTrim: (Long, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var widthPx by remember { mutableIntStateOf(0) }
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .onSizeChanged { widthPx = it.width },
    ) {
        Row(Modifier.fillMaxSize()) {
            if (frames.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceVariant))
            } else {
                frames.forEach { frame ->
                    Box(Modifier.weight(1f).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceVariant)) {
                        frame.png?.let { png ->
                            AsyncImage(
                                model = png,
                                contentDescription = "Кадр ленты",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        }
                    }
                }
            }
        }

        Canvas(Modifier.fillMaxSize()) {
            if (durationMs <= 0) return@Canvas
            val startF = (trimStartMs.toFloat() / durationMs).coerceIn(0f, 1f)
            val endF = (trimEndMs.toFloat() / durationMs).coerceIn(0f, 1f)
            val dim = Color.Black.copy(alpha = 0.55f)
            drawRect(dim, topLeft = Offset.Zero, size = Size(startF * size.width, size.height))
            drawRect(dim, topLeft = Offset(endF * size.width, 0f), size = Size((1f - endF) * size.width, size.height))
        }

        if (widthPx > 0 && durationMs > 0) {
            VideoTrimHandle(
                fraction = trimStartMs.toFloat() / durationMs,
                widthPx = widthPx,
                durationMs = durationMs,
                onMove = { ms -> onTrim(ms.coerceIn(0L, trimEndMs - MIN_TRIM_MS), trimEndMs) },
            )
            VideoTrimHandle(
                fraction = trimEndMs.toFloat() / durationMs,
                widthPx = widthPx,
                durationMs = durationMs,
                onMove = { ms -> onTrim(trimStartMs, ms.coerceIn(trimStartMs + MIN_TRIM_MS, durationMs)) },
            )
        }
    }
}

/** A vertical trim handle draggable along the filmstrip. */
@Composable
private fun BoxScope.VideoTrimHandle(
    fraction: Float,
    widthPx: Int,
    durationMs: Long,
    onMove: (Long) -> Unit,
) {
    val density = LocalDensity.current
    val handleWidthPx = with(density) { 18.dp.toPx() }
    val leftPx = (fraction * widthPx - handleWidthPx / 2).roundToInt()
    Box(
        Modifier
            .offset { IntOffset(leftPx, 0) }
            .width(18.dp)
            .fillMaxHeight()
            .pointerInput(durationMs, widthPx) {
                detectDragGestures { change, _ ->
                    change.consume()
                    val parentX = change.position.x + leftPx
                    val ms = ((parentX / widthPx).coerceIn(0f, 1f) * durationMs).toLong()
                    onMove(ms)
                }
            }
            .semantics { contentDescription = "Ручка обрезки видео" },
    ) {
        Box(
            Modifier
                .align(Alignment.Center)
                .width(5.dp)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

/** §8 error card: Russian title, human detail, rescue button. */
@Composable
internal fun VideoEditErrorCard(
    error: VideoEditException,
    onFix: (() -> Unit)?,
    fixLabel: String?,
    onDismissError: () -> Unit = {},
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Error,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(error.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
            }
            Spacer(Modifier.height(4.dp))
            Text(error.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onFix != null && fixLabel != null) {
                    Button(onClick = onFix) { Text(fixLabel) }
                }
                OutlinedButton(onClick = onDismissError) { Text("Скрыть") }
            }
        }
    }
}
