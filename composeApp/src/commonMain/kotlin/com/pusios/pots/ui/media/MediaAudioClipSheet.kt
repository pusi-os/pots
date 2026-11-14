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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MovieFilter
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import com.pusios.pots.media.AudioAnalysis
import com.pusios.pots.media.AudioClipAssemblyRequest
import com.pusios.pots.media.AudioClipSceneSource
import com.pusios.pots.media.AudioClipStyle
import com.pusios.pots.media.AudioEditException
import com.pusios.pots.media.MediaHistoryEntry
import com.pusios.pots.media.MediaHistoryStatus
import com.pusios.pots.media.VideoEditException
import com.pusios.pots.media.analyzeAudio
import com.pusios.pots.media.createVideoProcessor
import com.pusios.pots.media.decodeAudioToPcm
import com.pusios.pots.readAppStorageBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Output geometry: one fixed 720p30 target — the clip is one button (§11). */
private const val CLIP_OUTPUT_WIDTH = 1280
private const val CLIP_OUTPUT_HEIGHT = 720
private const val CLIP_OUTPUT_FPS = 30

/** The sheet's fast preview renders only the first 10 seconds (§11). */
private const val CLIP_PREVIEW_MS = 10_000L

/** One visual source candidate: a canvas element or a journal result. */
private data class ClipSource(
    val key: String,
    val relPath: String,
    val isVideo: Boolean,
    val label: String,
    val fromJournal: Boolean,
)

/**
 * Full-screen audio-reactive clip sheet (spec §11): one button turns the
 * selected track into a music video. The flow is fully deterministic and
 * free — track envelopes are analyzed locally once (no LLM, no provider
 * calls), scene cuts align to energy peaks, bass drives the zoom pulse,
 * treble drives the glow, loudness drifts the color temperature. Visuals
 * come from the canvas and the journal (drag order replaced by up/down —
 * the sequence-sheet precedent); with an empty selection the clip rides a
 * deterministic gradient background, so a clip is always possible.
 *
 * Local processing runs on the system ffmpeg (desktop only); the sheet
 * opens with a runtime tool check and shows the §8 card with install steps
 * when the binary is missing.
 */
@Composable
internal fun MediaAudioClipSheet(
    trackElement: CanvasElement,
    canvasElements: List<CanvasElement>,
    historyEntries: List<MediaHistoryEntry>,
    onResult: (relPath: String, style: AudioClipStyle, trackDurationMs: Long) -> Boolean,
    onFailure: (reason: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val processor = remember { createVideoProcessor() }

    var toolMissing by remember { mutableStateOf(false) }
    var probeTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(probeTick) {
        toolMissing = !processor.isAvailable()
    }

    // Track analysis (§11: once, locally, no LLM). Runs when the sheet opens.
    var analysis by remember { mutableStateOf<AudioAnalysis?>(null) }
    var analysisBusy by remember { mutableStateOf(false) }
    var analysisError by remember { mutableStateOf<VideoEditException?>(null) }
    var analysisTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(trackElement.localPath, analysisTick) {
        val rel = trackElement.localPath
        if (rel == null) {
            analysisError = VideoEditException.SourceMissing()
            return@LaunchedEffect
        }
        analysisBusy = true
        analysisError = null
        try {
            val bytes = readAppStorageBytes(rel) ?: throw AudioEditException.DecodeFailed()
            val pcm = withContext(Dispatchers.Default) { decodeAudioToPcm(bytes) }
            analysis = withContext(Dispatchers.Default) { analyzeAudio(pcm, fps = CLIP_OUTPUT_FPS) }
        } catch (e: AudioEditException) {
            analysisError = VideoEditException.TrackUnreadable()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            analysisError = VideoEditException.TrackUnreadable()
        } finally {
            analysisBusy = false
        }
    }

    // Visual sources: canvas elements first, then journal results; one entry per file.
    val sources = remember(canvasElements, historyEntries) {
        val canvas = canvasElements
            .filter { !it.isAudio && it.localPath != null }
            .map { ClipSource(it.id, it.localPath!!, it.isVideo, it.localPath!!.substringAfterLast('/'), fromJournal = false) }
        val journal = historyEntries
            .filter { it.status == MediaHistoryStatus.Success && it.localPath != null && !it.isAudio }
            .map { ClipSource(it.localId, it.localPath!!, it.isVideo, it.localPath!!.substringAfterLast('/'), fromJournal = true) }
        (canvas + journal).distinctBy { it.relPath }
    }

    // Selection order is the scene order.
    var selectedKeys by remember { mutableStateOf<List<String>>(emptyList()) }
    var style by remember { mutableStateOf(AudioClipStyle.Pulse) }

    // Render state: progress 0..1, cancel flag, §8 error card, preview result.
    var opRunning by remember { mutableStateOf(false) }
    var opProgress by remember { mutableFloatStateOf(0f) }
    var opCancel by remember { mutableStateOf(false) }
    var opError by remember { mutableStateOf<VideoEditException?>(null) }
    var opJob by remember { mutableStateOf<Job?>(null) }
    var lastOp by remember { mutableStateOf<(() -> Unit)?>(null) }
    var previewRel by remember { mutableStateOf<String?>(null) }

    fun launchRender(preview: Boolean) {
        if (opJob?.isActive == true) return
        val trackAnalysis = analysis ?: return
        val track = trackElement.localPath ?: return
        val request = AudioClipAssemblyRequest(
            trackRelPath = track,
            trackDurationMs = trackAnalysis.durationMs,
            scenes = selectedKeys.mapNotNull { key -> sources.firstOrNull { it.key == key } }
                .map { AudioClipSceneSource(if (it.fromJournal) null else it.key, it.relPath, it.isVideo) },
            style = style,
            width = CLIP_OUTPUT_WIDTH,
            height = CLIP_OUTPUT_HEIGHT,
            fps = CLIP_OUTPUT_FPS,
            previewLimitMs = if (preview) CLIP_PREVIEW_MS else null,
        )
        opError = null
        opCancel = false
        opProgress = 0f
        opRunning = true
        lastOp = { launchRender(preview) }
        opJob = scope.launch {
            try {
                val relPath = processor.assembleAudioClip(request, trackAnalysis) { fraction ->
                    opProgress = fraction
                    !opCancel
                }
                if (preview) {
                    previewRel = relPath
                } else {
                    if (!onResult(relPath, style, trackAnalysis.durationMs)) {
                        throw VideoEditException.ExportFailed("не удалось добавить клип на канвас")
                    }
                    onDismiss()
                }
            } catch (e: VideoEditException) {
                opError = e
                // Full builds land in the journal as failures; previews are scratch.
                if (!preview && e !is VideoEditException.Cancelled) onFailure(e.detail)
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
                    Text("Аудио-реактивный клип", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "Трек: ${trackElement.localPath?.substringAfterLast('/') ?: "—"} · без ЛЛМ · 0 ₽",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Закрыть клип", tint = MaterialTheme.colorScheme.onSurface)
                }
            }

            when {
                toolMissing -> {
                    val missing = remember { VideoEditException.ToolMissing() }
                    VideoEditErrorCard(missing, onFix = { probeTick++ }, fixLabel = missing.fix, onDismissError = onDismiss)
                }

                analysisBusy -> Row(
                    Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LinearProgressIndicator(
                        modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("Анализ звука…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                analysisError != null -> VideoEditErrorCard(
                    analysisError!!,
                    onFix = { analysisTick++ },
                    fixLabel = "Повторить",
                    onDismissError = onDismiss,
                )

                analysis != null -> {
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
                    Text(
                        "Длительность = длительность трека: ${formatTime(analysis!!.durationMs)} — клип монтируется под музыку.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // -- Visual row: selection order = scene order ------------
                    Spacer(Modifier.height(12.dp))
                    Text("Визуальный ряд", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "Отметьте фото и видео с канваса или из журнала — порядок задаётся стрелками. Пустой выбор — клип на абстрактном фоне.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    val selectedSources = selectedKeys.mapNotNull { key -> sources.firstOrNull { it.key == key } }
                    selectedSources.forEachIndexed { index, source ->
                        ClipSceneRow(
                            index = index,
                            source = source,
                            count = selectedSources.size,
                            enabled = !opRunning,
                            onMoveUp = {
                                selectedKeys = selectedKeys.toMutableList().also { list ->
                                    val from = list.indexOf(source.key)
                                    if (from > 0) {
                                        list.removeAt(from)
                                        list.add(from - 1, source.key)
                                    }
                                }
                            },
                            onMoveDown = {
                                selectedKeys = selectedKeys.toMutableList().also { list ->
                                    val from = list.indexOf(source.key)
                                    if (from in 0 until list.size - 1) {
                                        list.removeAt(from)
                                        list.add(from + 1, source.key)
                                    }
                                }
                            },
                            onRemove = { selectedKeys = selectedKeys - source.key },
                        )
                    }
                    if (sources.isEmpty()) {
                        Text(
                            "На канвасе и в журнале нет фото или видео с локальным файлом. Клип соберётся на абстрактном фоне.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            sources.forEach { source ->
                                FilterChip(
                                    selected = source.key in selectedKeys,
                                    enabled = !opRunning,
                                    onClick = {
                                        selectedKeys = if (source.key in selectedKeys) {
                                            selectedKeys - source.key
                                        } else {
                                            selectedKeys + source.key
                                        }
                                    },
                                    label = { Text((if (source.isVideo) "▶ " else "") + source.label) },
                                )
                            }
                        }
                    }

                    // -- Style presets (§11: three, no more) -------------------
                    Spacer(Modifier.height(16.dp))
                    Text("Стиль", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AudioClipStyle.entries.forEach { preset ->
                            FilterChip(
                                selected = style == preset,
                                enabled = !opRunning,
                                onClick = { style = preset },
                                label = { Text(preset.labelRu) },
                            )
                        }
                    }
                    Text(style.descriptionRu, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    // -- Preview -------------------------------------------------
                    previewRel?.let { rel ->
                        Spacer(Modifier.height(16.dp))
                        Text("Предпросмотр: первые 10 секунд", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(4.dp))
                        MediaVideoPlayer(url = rel, modifier = Modifier.fillMaxWidth().height(220.dp))
                    }

                    // -- Actions ---------------------------------------------------
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { launchRender(preview = true) }, enabled = !opRunning && !analysisBusy) {
                            Icon(Icons.Filled.PlayCircle, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Предпросмотр (10 с)")
                        }
                        Button(onClick = { launchRender(preview = false) }, enabled = !opRunning && !analysisBusy) {
                            Icon(Icons.Filled.MovieFilter, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Собрать клип")
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

/** One selected-scene row: thumbnail, label, reorder and delete. */
@Composable
private fun ClipSceneRow(
    index: Int,
    source: ClipSource,
    count: Int,
    enabled: Boolean,
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
                Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                if (source.isVideo) {
                    Icon(Icons.Filled.Movie, contentDescription = "Видео-сцена", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Icon(Icons.Filled.Movie, contentDescription = "Фото-сцена", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${index + 1}. ${source.label}" + if (source.fromJournal) " · журнал" else "",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(onClick = onMoveUp, enabled = enabled && index > 0) {
                Icon(Icons.Filled.ArrowUpward, contentDescription = "Выше", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onMoveDown, enabled = enabled && index < count - 1) {
                Icon(Icons.Filled.ArrowDownward, contentDescription = "Ниже", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onRemove, enabled = enabled) {
                Icon(Icons.Filled.Delete, contentDescription = "Убрать из клипа", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
