package com.pusios.pots.ui.media

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.pusios.pots.media.AudioEditException
import com.pusios.pots.media.AudioEditProgress
import com.pusios.pots.media.AudioPcm
import com.pusios.pots.media.PlusVibeMediaProvider
import com.pusios.pots.media.changeSpeed
import com.pusios.pots.media.concatAudio
import com.pusios.pots.media.decodeAudioToPcm
import com.pusios.pots.media.pcm16ToWav
import com.pusios.pots.media.peaks
import com.pusios.pots.media.trimRange
import com.pusios.pots.readAppStorageBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private const val EDITOR_WAVE_BARS = 96

/** Editor speed presets for the render slider (spec §4: 0.5 / 1 / 1.5 / 2). */
private val EDITOR_SPEED_PRESETS = floatArrayOf(0.5f, 1f, 1.5f, 2f)

/** Bytes of a canvas audio element: app storage first, signed URL as fallback. */
private suspend fun readElementBytes(element: CanvasElement): ByteArray? = when {
    element.localPath != null -> readAppStorageBytes(element.localPath!!)
    element.url != null -> runCatching { PlusVibeMediaProvider().downloadResult("", element.url!!) }.getOrNull()
    else -> null
}

private fun elementLabel(element: CanvasElement): String = element.localPath?.substringAfterLast('/') ?: element.url?.substringAfterLast('/')?.take(24) ?: "аудио"

/**
 * Full-screen audio editor (spec §4): real waveform with a draggable
 * selection, trim to selection, speed change, concatenation of canvas tracks
 * in a user-chosen order. Every operation renders a NEW WAV into app storage
 * through [onResult]; the source element is never touched.
 */
@Composable
internal fun MediaAudioEditorSheet(
    element: CanvasElement,
    audioElements: List<CanvasElement>,
    onDismiss: () -> Unit,
    onResult: (ByteArray) -> Boolean,
) {
    val scope = rememberCoroutineScope()
    val engine = remember { createMediaAudioEngine() }
    DisposableEffect(engine) {
        onDispose { engine.release() }
    }

    val source = element.localPath ?: element.url
    var bytes by remember { mutableStateOf<ByteArray?>(null) }
    var pcm by remember { mutableStateOf<AudioPcm?>(null) }
    var loadError by remember { mutableStateOf<AudioEditException?>(null) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }

    LaunchedEffect(source) {
        val data = withContext(Dispatchers.Default) {
            source?.let {
                if (it.contains("://")) runCatching { PlusVibeMediaProvider().downloadResult("", it) }.getOrNull() else readAppStorageBytes(it)
            }
        }
        if (data == null) {
            loadError = AudioEditException.DecodeFailed()
            return@LaunchedEffect
        }
        bytes = data
        withContext(Dispatchers.Default) { engine.load(data) }
        durationMs = engine.durationMs
        pcm = try {
            decodeAudioToPcm(data)
        } catch (e: AudioEditException) {
            loadError = e
            null
        }
    }

    LaunchedEffect(bytes) {
        while (bytes != null) {
            delay(100)
            positionMs = engine.positionMs
            durationMs = engine.durationMs.takeIf { it > 0 } ?: durationMs
            isPlaying = engine.isPlaying
        }
    }

    // Selection on the waveform, in milliseconds (-1 = no selection).
    var selStartMs by remember { mutableLongStateOf(-1L) }
    var selEndMs by remember { mutableLongStateOf(-1L) }
    val hasSelection = selStartMs >= 0 && selEndMs > selStartMs

    // Operation state: progress 0..1, cancel flag, §8 error card.
    var opRunning by remember { mutableStateOf(false) }
    var opProgress by remember { mutableFloatStateOf(0f) }
    var opCancel by remember { mutableStateOf(false) }
    var opError by remember { mutableStateOf<AudioEditException?>(null) }
    var opJob by remember { mutableStateOf<Job?>(null) }
    var lastOp by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun launchOp(label: String, op: suspend (AudioEditProgress) -> ByteArray) {
        if (opJob?.isActive == true) return
        opError = null
        opCancel = false
        opProgress = 0f
        opRunning = true
        lastOp = { launchOp(label, op) }
        opJob = scope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    op { fraction ->
                        opProgress = fraction
                        !opCancel
                    }
                }
                if (!onResult(result)) throw AudioEditException.ExportFailed()
                onDismiss()
            } catch (e: AudioEditException) {
                opError = e
            } catch (e: CancellationException) {
                opError = AudioEditException.Cancelled()
            } finally {
                opRunning = false
            }
        }
    }

    var speedFactor by remember { mutableFloatStateOf(1f) }
    var playbackSpeed by remember { mutableFloatStateOf(1f) }
    var volume by remember { mutableFloatStateOf(1f) }
    var muted by remember { mutableStateOf(false) }
    var looping by remember { mutableStateOf(false) }

    var concatIds by remember { mutableStateOf(listOf(element.id)) }
    var showAddTrack by remember { mutableStateOf(false) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Редактор аудио", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        elementLabel(element) + " · " + formatTime(durationMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Закрыть редактор", tint = MaterialTheme.colorScheme.onSurface)
                }
            }

            when {
                loadError != null && bytes == null -> AudioEditErrorCard(loadError!!, null) { loadError = null }

                bytes == null -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                else -> {
                    val peaks = remember(pcm) { pcm?.peaks(EDITOR_WAVE_BARS) }
                    EditorWaveform(
                        peaks = peaks,
                        positionFraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f,
                        selectionStartFraction = selStartMs.takeIf { it >= 0 && durationMs > 0 }?.toFloat()?.div(durationMs),
                        selectionEndFraction = selEndMs.takeIf { it > 0 && durationMs > 0 }?.toFloat()?.div(durationMs),
                        durationMs = durationMs,
                        onSeek = { ms -> engine.seek(ms) },
                        onSelection = { startMs, endMs ->
                            selStartMs = startMs
                            selEndMs = endMs
                        },
                        modifier = Modifier.fillMaxWidth().height(120.dp).padding(vertical = 8.dp),
                    )

                    // Transport row: play/pause and time.
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            onClick = {
                                if (engine.isSupported) {
                                    if (engine.isPlaying) engine.pause() else engine.play()
                                }
                            },
                            modifier = Modifier.size(44.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = if (isPlaying) "Пауза" else "Воспроизвести",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            formatTime(positionMs) + " / " + formatTime(durationMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (engine.supportsExtendedControls) {
                        AudioExtendedControls(
                            speed = playbackSpeed,
                            volume = volume,
                            muted = muted,
                            looping = looping,
                            onSpeed = { next ->
                                playbackSpeed = next
                                engine.setSpeed(next)
                            },
                            onVolume = { next ->
                                volume = next
                                muted = next <= 0f
                                engine.setVolume(next)
                            },
                            onToggleMute = {
                                muted = !muted
                                engine.setVolume(if (muted) 0f else volume)
                            },
                            onToggleLoop = {
                                looping = !looping
                                engine.setLooping(looping)
                            },
                        )
                    }

                    Spacer(Modifier.height(12.dp))

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

                        opError != null -> AudioEditErrorCard(opError!!, lastOp?.let { retry -> { retry() } }) { opError = null }

                        loadError != null && pcm == null -> AudioEditErrorCard(loadError!!, null) { loadError = null }
                    }

                    Spacer(Modifier.height(8.dp))

                    // -- Trim -----------------------------------------------------
                    Text("Обрезка", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        if (hasSelection) {
                            "Выделение: ${formatTime(selStartMs)} – ${formatTime(selEndMs)} (${formatTime(selEndMs - selStartMs)})"
                        } else {
                            "Проведите по волне, чтобы выделить диапазон"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Button(
                        onClick = {
                            val current = pcm ?: return@Button
                            val startMs = selStartMs
                            val endMs = selEndMs
                            launchOp("Обрезка") { _ ->
                                val trimmed = current.trimRange(startMs, endMs)
                                pcm16ToWav(trimmed.samples, trimmed.sampleRate, trimmed.channels)
                            }
                        },
                        enabled = hasSelection && pcm != null && !opRunning,
                    ) {
                        Icon(Icons.Filled.ContentCut, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Обрезать до выделения")
                    }

                    Spacer(Modifier.height(12.dp))

                    // -- Speed ------------------------------------------------------
                    Text("Скорость", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "Скорость меняет и темп, и тон (как плёнка). Диапазон 0.25× – 4×.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Slider(
                            value = speedFactor,
                            onValueChange = { speedFactor = it },
                            valueRange = 0.25f..4f,
                            modifier = Modifier.weight(1f).height(44.dp),
                        )
                        Text(
                            formatSpeed(speedFactor),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        EDITOR_SPEED_PRESETS.forEach { preset ->
                            FilterChip(
                                selected = speedFactor == preset,
                                onClick = { speedFactor = preset },
                                label = { Text(formatSpeed(preset), style = MaterialTheme.typography.labelSmall) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer),
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Button(
                        onClick = {
                            val current = pcm ?: return@Button
                            val factor = speedFactor
                            launchOp("Скорость") { progress ->
                                val resampled = current.changeSpeed(factor, progress)
                                pcm16ToWav(resampled.samples, resampled.sampleRate, resampled.channels)
                            }
                        },
                        enabled = pcm != null && !opRunning,
                    ) { Text("Изменить скорость") }

                    Spacer(Modifier.height(12.dp))

                    // -- Concat -------------------------------------------------------
                    Text("Склейка треков", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "Треки соединяются по порядку списка, сверху вниз.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    concatIds.forEachIndexed { index, id ->
                        val track = audioElements.firstOrNull { it.id == id }
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("${index + 1}.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                track?.let(::elementLabel) ?: "трек",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = {
                                    concatIds = concatIds.toMutableList().apply { add(index - 1, removeAt(index)) }
                                },
                                enabled = index > 0 && !opRunning,
                                modifier = Modifier.size(30.dp),
                            ) {
                                Icon(Icons.Filled.ArrowUpward, contentDescription = "Выше", modifier = Modifier.size(14.dp))
                            }
                            IconButton(
                                onClick = {
                                    concatIds = concatIds.toMutableList().apply { add(index + 1, removeAt(index)) }
                                },
                                enabled = index < concatIds.lastIndex && !opRunning,
                                modifier = Modifier.size(30.dp),
                            ) {
                                Icon(Icons.Filled.ArrowDownward, contentDescription = "Ниже", modifier = Modifier.size(14.dp))
                            }
                            IconButton(
                                onClick = { concatIds = concatIds - id },
                                enabled = concatIds.size > 1 && !opRunning,
                                modifier = Modifier.size(30.dp),
                            ) {
                                Icon(Icons.Filled.Delete, contentDescription = "Убрать трек", modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                    Box {
                        OutlinedButton(onClick = { showAddTrack = true }, enabled = !opRunning) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Добавить трек")
                        }
                        val candidates = audioElements.filter { candidate -> candidate.id !in concatIds }
                        DropdownMenu(expanded = showAddTrack, onDismissRequest = { showAddTrack = false }) {
                            if (candidates.isEmpty()) {
                                DropdownMenuItem(text = { Text("Других аудио-элементов нет") }, onClick = { showAddTrack = false })
                            }
                            candidates.forEach { candidate ->
                                DropdownMenuItem(
                                    text = { Text(elementLabel(candidate)) },
                                    onClick = {
                                        concatIds = concatIds + candidate.id
                                        showAddTrack = false
                                    },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Button(
                        onClick = {
                            val tracks = concatIds.mapNotNull { id -> audioElements.firstOrNull { it.id == id } }
                            launchOp("Склейка") { progress ->
                                val decoded = ArrayList<AudioPcm>(tracks.size)
                                for ((i, track) in tracks.withIndex()) {
                                    val data = readElementBytes(track) ?: throw AudioEditException.DecodeFailed()
                                    decoded.add(decodeAudioToPcm(data))
                                    if (!progress(0.5f * (i + 1) / tracks.size)) throw AudioEditException.Cancelled()
                                }
                                val joined = concatAudio(decoded) { fraction -> progress(0.5f + fraction * 0.5f) }
                                pcm16ToWav(joined.samples, joined.sampleRate, joined.channels)
                            }
                        },
                        enabled = concatIds.size >= 2 && !opRunning,
                    ) { Text("Склеить") }

                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

/** §8 error card: essence, cause and a rescue action on the error container. */
@Composable
private fun AudioEditErrorCard(error: AudioEditException, onRetry: (() -> Unit)?, onDismiss: () -> Unit) {
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
                if (onRetry != null) {
                    Button(onClick = onRetry) { Text("Повторить") }
                }
                OutlinedButton(onClick = onDismiss) { Text("Скрыть") }
            }
        }
    }
}

/**
 * Waveform with tap-to-seek, drag-to-select and draggable edge handles.
 * Selection is reported in milliseconds.
 */
@Composable
private fun EditorWaveform(
    peaks: FloatArray?,
    positionFraction: Float,
    selectionStartFraction: Float?,
    selectionEndFraction: Float?,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    onSelection: (Long, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var widthPx by remember { mutableStateOf(0) }
    var dragAnchorMs by remember { mutableLongStateOf(-1L) }

    // Colors are read outside the Canvas: the draw scope is not composable.
    val barColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    val selectionColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
    val playheadColor = MaterialTheme.colorScheme.primary

    fun fractionToMs(fraction: Float): Long = (fraction.coerceIn(0f, 1f) * durationMs).toLong()

    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .onSizeChanged { widthPx = it.width }
            .pointerInput(durationMs) {
                if (durationMs <= 0) return@pointerInput
                detectTapGestures { offset ->
                    onSeek(fractionToMs(offset.x / size.width))
                }
                detectDragGestures(
                    onDragStart = { offset -> dragAnchorMs = fractionToMs(offset.x / size.width) },
                    onDragEnd = { dragAnchorMs = -1L },
                ) { change, _ ->
                    change.consume()
                    val anchor = dragAnchorMs
                    if (anchor < 0) return@detectDragGestures
                    val current = fractionToMs(change.position.x / size.width)
                    onSelection(minOf(anchor, current), maxOf(anchor, current))
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val bars = peaks?.size ?: 0
            if (bars == 0) return@Canvas
            val step = size.width / bars
            val barWidth = step * 0.6f
            val maxBarHeight = size.height * 0.86f
            for (i in 0 until bars) {
                val amplitude = peaks!![i]
                val height = (maxBarHeight * amplitude).coerceAtLeast(barWidth)
                val x = i * step
                val y = (size.height - height) / 2f
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(x, y),
                    size = Size(barWidth, height),
                    cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
                )
            }
            // Selection highlight.
            val selStart = selectionStartFraction
            val selEnd = selectionEndFraction
            if (selStart != null && selEnd != null && selEnd > selStart) {
                drawRect(
                    color = selectionColor,
                    topLeft = Offset(selStart * size.width, 0f),
                    size = Size((selEnd - selStart) * size.width, size.height),
                )
            }
            // Playhead.
            val headX = positionFraction * size.width
            drawRect(
                color = playheadColor,
                topLeft = Offset(headX - 1f, 0f),
                size = Size(2f, size.height),
            )
        }

        // Edge handles: drag to fine-tune the selection edges.
        if (selectionStartFraction != null && selectionEndFraction != null && widthPx > 0 && durationMs > 0) {
            val selStartMs = (selectionStartFraction * durationMs).toLong()
            val selEndMs = (selectionEndFraction * durationMs).toLong()
            WaveHandle(
                fraction = selectionStartFraction,
                widthPx = widthPx,
                durationMs = durationMs,
                onMove = { ms -> onSelection(ms.coerceIn(0L, selEndMs - 1), selEndMs) },
            )
            WaveHandle(
                fraction = selectionEndFraction,
                widthPx = widthPx,
                durationMs = durationMs,
                onMove = { ms -> onSelection(selStartMs, ms.coerceIn(selStartMs + 1, durationMs)) },
            )
        }
    }
}

/** A vertical trim handle draggable along the waveform. */
@Composable
private fun BoxScope.WaveHandle(
    fraction: Float,
    widthPx: Int,
    durationMs: Long,
    onMove: (Long) -> Unit,
) {
    val density = LocalDensity.current
    val handleWidthPx = with(density) { 14.dp.toPx() }
    val leftPx = (fraction * widthPx - handleWidthPx / 2).roundToInt()
    Box(
        Modifier
            .offset { IntOffset(leftPx, 0) }
            .width(14.dp)
            .fillMaxHeight()
            .pointerInput(durationMs, widthPx) {
                detectDragGestures { change, _ ->
                    change.consume()
                    val parentX = change.position.x + leftPx
                    val ms = ((parentX / widthPx).coerceIn(0f, 1f) * durationMs).toLong()
                    onMove(ms)
                }
            }
            .semantics { contentDescription = "Ручка выделения" },
    ) {
        Box(
            Modifier
                .align(Alignment.Center)
                .width(4.dp)
                .height(56.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}
