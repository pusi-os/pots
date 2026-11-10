package com.pusios.pots.ui.media

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.pusios.pots.media.PlusVibeMediaProvider
import com.pusios.pots.readAppStorageBytes
import com.pusios.pots.saveFileToDevice
import kotlinx.coroutines.launch

/**
 * Chat inline media: image with zoom lightbox, video with seek/pause player,
 * and explicit "В канвас" send action. Works for URLs and local paths.
 */
@Composable
fun ChatMediaInline(
    url: String,
    isVideo: Boolean,
    modifier: Modifier = Modifier,
    onSendToCanvas: ((String, Boolean) -> Unit)? = null,
) {
    var showLightbox by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        if (isVideo) {
            MediaVideoPlayer(url = url, autoplay = false, fullControls = true, modifier = Modifier.fillMaxWidth())
        } else {
            AsyncImage(
                model = url,
                contentDescription = "Изображение в чате",
                modifier = Modifier.fillMaxWidth().clickable { showLightbox = true }.semantics { contentDescription = "Открыть изображение" },
                contentScale = ContentScale.Fit,
            )
        }
        Row {
            TextButton(onClick = { showLightbox = true }) { Text(if (isVideo) "Плеер" else "Зум") }
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = {
                scope.launch {
                    val bytes = runCatching {
                        readAppStorageBytes(url) ?: PlusVibeMediaProvider().downloadResult("", url)
                    }.getOrNull()
                    if (bytes != null) saveFileToDevice(bytes, "pots-chat", if (isVideo) "mp4" else "png")
                }
            }) { Text("Сохранить") }
            Spacer(Modifier.width(4.dp))
            if (onSendToCanvas != null) {
                TextButton(onClick = { onSendToCanvas(url, isVideo) }) { Text("В канвас") }
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = if (isVideo) "Видео" else "Изображение",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
    if (showLightbox) {
        MediaLightbox(url = url, isVideo = isVideo, onDismiss = { showLightbox = false }, onSave = {})
    }
}
