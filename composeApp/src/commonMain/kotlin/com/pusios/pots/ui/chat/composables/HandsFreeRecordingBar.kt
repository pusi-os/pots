package com.pusios.pots.ui.chat.composables

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pusios.pots.handsfree.HandsFreeRecording
import com.pusios.pots.handsfree.RecordingPhase
import com.pusios.pots.handsfree.SpeechToTextError
import com.pusios.pots.ui.handCursor
import com.pusios.pots.ui.potsRecordingColors
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.handsfree_action_keep_text
import pots.composeapp.generated.resources.handsfree_action_restart
import pots.composeapp.generated.resources.handsfree_action_retry
import pots.composeapp.generated.resources.handsfree_action_send_now
import pots.composeapp.generated.resources.handsfree_error_engine
import pots.composeapp.generated.resources.handsfree_error_failed
import pots.composeapp.generated.resources.handsfree_error_mic
import pots.composeapp.generated.resources.handsfree_error_no_service
import pots.composeapp.generated.resources.handsfree_error_no_speech
import pots.composeapp.generated.resources.handsfree_state_error
import pots.composeapp.generated.resources.handsfree_state_finalising
import pots.composeapp.generated.resources.handsfree_state_listening
import pots.composeapp.generated.resources.handsfree_state_sending
import pots.composeapp.generated.resources.handsfree_state_speech
import pots.composeapp.generated.resources.handsfree_timer_content_description
import pots.composeapp.generated.resources.ic_mic_done
import pots.composeapp.generated.resources.ic_refresh
import pots.composeapp.generated.resources.ic_send

/** Bars in the wave: one slot per ≈64 ms of audio, so the strip shows the last second. */
private const val WAVE_BARS = 16
private val BAR_WIDTH = 3.dp
private val BAR_GAP = 2.dp
private val BAR_MIN_HEIGHT = 2.dp
private val BAR_MAX_HEIGHT = 28.dp

/** One bar eases towards its new height; short enough to follow the voice, long enough not to strobe. */
private const val BAR_ANIMATION_MS = 90

/** The LED clock ticks four times a second — a per-second jump would look dead. */
private const val TIMER_TICK_MS = 250L

/**
 * The recording indicator above the composer.
 *
 * It exists because the hands-free mode must never be silent about what it is
 * doing: the user has to see that the microphone is open, that the voice is
 * being heard, how long it has been recording, and — when something breaks —
 * why. The recognised text is shown in the composer itself; this strip only
 * previews it, so a long phrase is never hidden behind a single line.
 *
 * The wave is real data: [HandsFreeRecording.level] comes from the audio the
 * engine is recognising (RMS of the PCM buffer for Vosk, `onRmsChanged` for the
 * system recognizer). Nothing here is a canned animation.
 *
 * Actions are icon-only with a `contentDescription`, 48 dp targets, vector
 * icons. In [RecordingPhase.Finalising] they are disabled — the engine is
 * already producing the result and a second command would race it.
 */
@Composable
internal fun HandsFreeRecordingBar(
    recording: HandsFreeRecording,
    transcript: String,
    onRestart: () -> Unit,
    onKeepText: () -> Unit,
    onSendNow: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = potsRecordingColors()
    val elapsedMs = rememberElapsedMs(recording)
    val phase = recording.phase
    val stateText = when (phase) {
        RecordingPhase.Listening -> stringResource(Res.string.handsfree_state_listening)
        RecordingPhase.SpeechDetected -> stringResource(Res.string.handsfree_state_speech)
        RecordingPhase.Finalising -> stringResource(Res.string.handsfree_state_finalising)
        RecordingPhase.Sending -> stringResource(Res.string.handsfree_state_sending)
        RecordingPhase.Error -> stringResource(Res.string.handsfree_state_error)
    }
    val errorText = recording.error?.let { reason ->
        when (reason) {
            SpeechToTextError.NoMatch -> stringResource(Res.string.handsfree_error_no_speech)
            SpeechToTextError.PermissionDenied -> stringResource(Res.string.handsfree_error_mic)
            SpeechToTextError.Unavailable -> stringResource(Res.string.handsfree_error_no_service)
            SpeechToTextError.Timeout -> stringResource(Res.string.handsfree_error_engine)
            SpeechToTextError.Failed -> stringResource(Res.string.handsfree_error_failed)
        }
    }
    val timerText = formatElapsed(elapsedMs)
    // The screen-reader text changes every five seconds, not on every tick: a
    // per-quarter-second announcement would flood the reader.
    val announcedMs = (elapsedMs / 1_000 / 5) * 5 * 1_000
    val timerDescription = stringResource(
        Res.string.handsfree_timer_content_description,
        formatElapsed(announcedMs),
    )
    val shape = RoundedCornerShape(20.dp)
    val live = phase == RecordingPhase.Listening || phase == RecordingPhase.SpeechDetected

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, colors.border, shape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RecordingWave(
                level = recording.level,
                active = phase == RecordingPhase.SpeechDetected,
                color = colors.wave,
                idleColor = colors.waveIdle,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stateText,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // A screen reader announces the phase changes without
                    // stealing focus from the composer.
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                val line = errorText ?: transcript
                if (line.isNotEmpty()) {
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (errorText != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = timerText,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 20.sp,
                ),
                color = colors.led,
                modifier = Modifier.clearAndSetSemantics { contentDescription = timerDescription },
            )
        }

        if (phase != RecordingPhase.Sending) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (phase == RecordingPhase.Error) {
                    RecordingAction(
                        icon = Res.drawable.ic_refresh,
                        description = stringResource(Res.string.handsfree_action_retry),
                        tint = MaterialTheme.colorScheme.primary,
                        onClick = onRetry,
                    )
                }
                RecordingAction(
                    icon = Res.drawable.ic_refresh,
                    description = stringResource(Res.string.handsfree_action_restart),
                    tint = MaterialTheme.colorScheme.error,
                    enabled = live,
                    onClick = onRestart,
                )
                RecordingAction(
                    icon = Res.drawable.ic_mic_done,
                    description = stringResource(Res.string.handsfree_action_keep_text),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    enabled = live || phase == RecordingPhase.Error,
                    onClick = onKeepText,
                )
                if (phase != RecordingPhase.Error) {
                    RecordingAction(
                        icon = Res.drawable.ic_send,
                        description = stringResource(Res.string.handsfree_action_send_now),
                        tint = MaterialTheme.colorScheme.primary,
                        enabled = live,
                        onClick = onSendNow,
                    )
                }
            }
        }
    }
}

/**
 * The level wave: one bar per recent audio frame, right to left.
 *
 * Heights are smoothed with a fast attack and a slow release, so the wave rises
 * with the voice and falls back instead of flickering on every quiet frame. The
 * easing itself is [animateFloatAsState], which follows the platform animation
 * scale — with animations switched off the bars simply snap to the new level.
 */
@Composable
private fun RecordingWave(
    level: Float,
    active: Boolean,
    color: Color,
    idleColor: Color,
) {
    val bars = remember { mutableStateListOf<Float>().apply { repeat(WAVE_BARS) { add(0f) } } }
    LaunchedEffect(level) {
        val previous = bars.last()
        val smoothed = if (level > previous) level else previous * 0.75f + level * 0.25f
        bars.removeAt(0)
        bars.add(smoothed)
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(BAR_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (index in 0 until WAVE_BARS) {
            val target = if (active) bars[index] else 0f
            val height by animateFloatAsState(
                targetValue = target,
                animationSpec = tween(BAR_ANIMATION_MS),
                label = "recordingWaveBar",
            )
            Box(
                modifier = Modifier
                    .width(BAR_WIDTH)
                    .height(BAR_MIN_HEIGHT + (BAR_MAX_HEIGHT - BAR_MIN_HEIGHT) * height)
                    .clip(RoundedCornerShape(BAR_WIDTH))
                    .background(if (active) color else idleColor),
            )
        }
    }
}

/** One icon-only action: a 48 dp target, a vector icon and a spoken label. */
@Composable
private fun RecordingAction(
    icon: org.jetbrains.compose.resources.DrawableResource,
    description: String,
    tint: Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    IconButton(
        modifier = Modifier.size(48.dp).handCursor(),
        enabled = enabled,
        onClick = onClick,
    ) {
        Icon(
            imageVector = vectorResource(icon),
            contentDescription = description,
            tint = tint.copy(alpha = if (enabled) 1f else 0.38f),
            modifier = Modifier.size(24.dp),
        )
    }
}

/**
 * The session length, counted from the session start and frozen once the engine
 * stops listening: *Finalising*, *Error* and *Sending* must not keep counting,
 * or the number would claim the microphone was open when it was not.
 */
@Composable
private fun rememberElapsedMs(recording: HandsFreeRecording): Long {
    val running = recording.phase == RecordingPhase.Listening ||
        recording.phase == RecordingPhase.SpeechDetected
    var elapsed by remember(recording.startedAt) { mutableStateOf(0L) }
    LaunchedEffect(recording.startedAt, running) {
        if (!running) {
            elapsed = recording.startedAt.elapsedNow().inWholeMilliseconds
            return@LaunchedEffect
        }
        while (true) {
            elapsed = recording.startedAt.elapsedNow().inWholeMilliseconds
            delay(TIMER_TICK_MS)
        }
    }
    return elapsed
}

/** `m:ss` — the LED-clock format, always two digits for the seconds. */
private fun formatElapsed(elapsedMs: Long): String {
    val totalSeconds = (elapsedMs / 1_000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}
