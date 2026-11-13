package com.pusios.pots.ui.media

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pusios.pots.media.decodeAudioToPcm
import com.pusios.pots.media.peaks
import com.pusios.pots.readAppStorageBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Decorative bar heights (audio-wave.svg style, 24×24 → fractions). */
private val WAVE_PATTERN = floatArrayOf(0.30f, 0.70f, 1.00f, 0.50f, 0.20f, 0.62f, 0.88f, 0.42f, 0.94f, 0.34f)
private const val WAVE_BARS = 28

/** Playback speed presets (spec §3). */
internal val AUDIO_SPEED_PRESETS = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

/** Two-decimal speed label without platform formatters (common code). */
internal fun formatSpeed(factor: Float): String {
    val rounded = (factor * 100).roundToInt() / 100.0
    return rounded.toString().removeSuffix(".0") + "×"
}

/**
 * In-app audio player for canvas cards and the lightbox: waveform with played
 * progress, play/pause, ±10 s, time. Seeks by tap on the wave; the lightbox
 * variant adds a slider row plus speed, volume and loop controls. The waveform
 * is real when the bytes decode to PCM, decorative otherwise. Playback runs on
 * [MediaAudioEngine]; platforms without an engine show a static card.
 */
@Composable
internal fun MediaAudioPlayer(
    source: String?,
    modifier: Modifier = Modifier,
    autoplay: Boolean = false,
    fullControls: Boolean = false,
) {
    val engine = remember(source) { createMediaAudioEngine() }
    DisposableEffect(engine) {
        onDispose { engine.release() }
    }

    val bytes by produceState<ByteArray?>(null, source) {
        value = when {
            source == null -> null

            source.contains("://") -> runCatching {
                com.pusios.pots.media.PlusVibeMediaProvider().downloadResult("", source)
            }.getOrNull()

            else -> withContext(Dispatchers.Default) { readAppStorageBytes(source) }
        }
    }

    // Real waveform peaks; null keeps the decorative pattern as a fallback.
    val wavePeaks by produceState<FloatArray?>(null, bytes) {
        value = bytes?.let { data ->
            withContext(Dispatchers.Default) {
                runCatching { decodeAudioToPcm(data).peaks(WAVE_BARS) }.getOrNull()
            }
        }
    }

    var durationMs by remember(source) { mutableLongStateOf(0L) }
    var positionMs by remember(source) { mutableLongStateOf(0L) }
    var isPlaying by remember(source) { mutableStateOf(false) }
    var speed by remember(source) { mutableFloatStateOf(1f) }
    var volume by remember(source) { mutableFloatStateOf(1f) }
    var muted by remember(source) { mutableStateOf(false) }
    var looping by remember(source) { mutableStateOf(false) }

    LaunchedEffect(bytes) {
        val data = bytes ?: return@LaunchedEffect
        withContext(Dispatchers.Default) { engine.load(data) }
        durationMs = engine.durationMs
        engine.setSpeed(speed)
        engine.setVolume(if (muted) 0f else volume)
        engine.setLooping(looping)
        if (autoplay && engine.isSupported) engine.play()
    }

    LaunchedEffect(engine, bytes) {
        while (true) {
            delay(200)
            positionMs = engine.positionMs
            durationMs = engine.durationMs.takeIf { it > 0 } ?: durationMs
            isPlaying = engine.isPlaying
        }
    }

    val progress = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    Column(modifier.semantics { contentDescription = "Аудиоплеер" }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(if (fullControls) 96.dp else 72.dp).padding(horizontal = 12.dp)) {
            AudioWave(
                progress = progress,
                peaks = wavePeaks,
                playedColor = MaterialTheme.colorScheme.primary,
                restColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxSize().align(Alignment.Center).pointerInput(durationMs) {
                    if (durationMs <= 0) return@pointerInput
                    detectTapGestures { offset ->
                        val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                        engine.seek((fraction * durationMs).toLong())
                    }
                },
            )
            Row(
                Modifier.align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(onClick = { engine.seek((positionMs - 10_000L).coerceAtLeast(0L)) }, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Filled.FastRewind,
                        contentDescription = "Назад 10 секунд",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Surface(
                    onClick = {
                        if (!engine.isSupported) return@Surface
                        if (engine.isPlaying) engine.pause() else engine.play()
                    },
                    modifier = Modifier.size(52.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlaying) "Пауза" else "Воспроизвести",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }
                IconButton(onClick = { engine.seek((positionMs + 10_000L).coerceAtMost(durationMs)) }, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Filled.FastForward,
                        contentDescription = "Вперёд 10 секунд",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            formatTime(positionMs) + " / " + formatTime(durationMs),
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (fullControls) {
            Slider(
                value = progress,
                onValueChange = { fraction -> engine.seek((fraction * durationMs).toLong()) },
                enabled = durationMs > 0,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }
        if (fullControls && engine.supportsExtendedControls) {
            AudioExtendedControls(
                speed = speed,
                volume = volume,
                muted = muted,
                looping = looping,
                onSpeed = { next ->
                    speed = next
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
        if (!engine.isSupported && bytes != null) {
            Text(
                "Плеер недоступен на этой платформе",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
    }
}

/** Speed presets, volume with mute, loop — shared by the lightbox and the editor. */
@Composable
internal fun AudioExtendedControls(
    speed: Float,
    volume: Float,
    muted: Boolean,
    looping: Boolean,
    onSpeed: (Float) -> Unit,
    onVolume: (Float) -> Unit,
    onToggleMute: () -> Unit,
    onToggleLoop: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AUDIO_SPEED_PRESETS.forEach { preset ->
            FilterChip(
                selected = speed == preset,
                onClick = { onSpeed(preset) },
                label = {
                    Text(formatSpeed(preset), style = MaterialTheme.typography.labelSmall)
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onToggleMute, modifier = Modifier.size(34.dp)) {
            Icon(
                if (muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                contentDescription = if (muted) "Включить звук" else "Выключить звук",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (muted) 0.4f else 1f),
                modifier = Modifier.size(18.dp),
            )
        }
        Slider(
            value = if (muted) 0f else volume,
            onValueChange = onVolume,
            modifier = Modifier.weight(1f).height(44.dp),
        )
        Text(
            ((if (muted) 0f else volume) * 100).toInt().toString() + " %",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onToggleLoop, modifier = Modifier.size(34.dp)) {
            Icon(
                Icons.Filled.Repeat,
                contentDescription = if (looping) "Выключить зацикливание" else "Зациклить",
                tint = if (looping) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** Bar waveform; the played part is [playedColor], the rest [restColor]. */
@Composable
private fun AudioWave(progress: Float, peaks: FloatArray?, playedColor: Color, restColor: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val barWidth = 3.dp.toPx()
        val gap = (size.width - barWidth * WAVE_BARS) / (WAVE_BARS - 1)
        val maxBarHeight = size.height * 0.9f
        for (i in 0 until WAVE_BARS) {
            val amplitude = peaks?.getOrNull(i) ?: WAVE_PATTERN[i % WAVE_PATTERN.size]
            val height = (maxBarHeight * amplitude).coerceAtLeast(barWidth)
            val x = i * (barWidth + gap)
            val y = (size.height - height) / 2f
            val played = (i.toFloat() / WAVE_BARS) <= progress
            drawRoundRect(
                color = if (played) playedColor else restColor,
                topLeft = Offset(x, y),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}
