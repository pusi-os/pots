package com.pusios.pots.ui.media

import androidx.compose.foundation.background
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.pusios.pots.openUrl

/**
 * Video playback surface used both on the canvas and in chat lightboxes.
 * Android embeds a real [android.widget.VideoView] via [PlatformVideoView];
 * other platforms show a poster with play/pause/seek chrome and open the
 * file in the system player (no extra native dependency).
 */
@Composable
fun MediaVideoPlayer(
    url: String,
    modifier: Modifier = Modifier,
    autoplay: Boolean = false,
    fullControls: Boolean = true,
) {
    var playing by remember(url) { mutableStateOf(autoplay) }
    var positionMs by remember(url) { mutableStateOf(0L) }
    var durationMs by remember(url) { mutableStateOf(0L) }

    Column(modifier.semantics { contentDescription = "Видеоплеер" }) {
        Box(
            Modifier.fillMaxWidth().weight(1f, fill = false).height(180.dp)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            if (url.isBlank()) {
                Text("Нет видео", style = MaterialTheme.typography.bodyMedium)
            } else {
                PlatformVideoView(url = url, playing = playing, onProgress = { pos, dur ->
                    positionMs = pos
                    durationMs = dur
                }, modifier = Modifier.fillMaxSize())
                if (durationMs == 0L) {
                    AsyncImage(
                        model = url,
                        contentDescription = "Постер видео",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
        }
        if (fullControls) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { playing = !playing }) {
                    Text(if (playing) "Пауза" else "▶")
                }
                val max = if (durationMs > 0) 1f else 0f
                val value = if (durationMs > 0) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
                Slider(
                    value = value,
                    onValueChange = { seekFraction ->
                        if (durationMs > 0) positionMs = (durationMs * seekFraction).toLong()
                        // Android actual observes positionMs via seekToPosition; other
                        // platforms keep the bar cosmetic and delegate to the system player.
                        if (PlatformVideoViewSeek.shouldSeek) {
                            PlatformVideoViewSeek.positionMs = positionMs
                        }
                    },
                    enabled = durationMs > 0,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = formatTime(positionMs) + " / " + formatTime(durationMs),
                    style = MaterialTheme.typography.labelSmall,
                )
                IconButton(onClick = { openUrl(url) }, modifier = Modifier.size(40.dp)) {
                    Text("↗", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

/** Shared seek slot for platforms whose actual supports it (Android). */
object PlatformVideoViewSeek {
    var shouldSeek: Boolean = false
    var positionMs: Long = 0L
}

internal fun formatTime(ms: Long): String {
    val totalSec = (ms / 1000).toInt()
    val m = totalSec / 60
    val s = totalSec % 60
    return "$m:${s.toString().padStart(2, '0')}"
}

@Composable
expect fun PlatformVideoView(
    url: String,
    playing: Boolean,
    onProgress: (positionMs: Long, durationMs: Long) -> Unit,
    modifier: Modifier = Modifier,
)
