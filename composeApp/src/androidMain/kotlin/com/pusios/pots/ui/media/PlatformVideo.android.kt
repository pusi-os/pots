package com.pusios.pots.ui.media

import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@Composable
actual fun PlatformVideoView(
    url: String,
    playing: Boolean,
    onProgress: (positionMs: Long, durationMs: Long) -> Unit,
    modifier: Modifier,
) {
    val videoView = remember(url) {
        VideoView(PotsAppContextHolder.context).apply {
            setVideoURI(android.net.Uri.parse(url))
            setMediaController(MediaController(context).apply { setAnchorView(this@apply) })
        }
    }
    LaunchedEffect(url) {
        videoView.setVideoURI(android.net.Uri.parse(url))
    }
    LaunchedEffect(playing) {
        if (playing) videoView.start() else videoView.pause()
    }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(500)
            val dur = try {
                videoView.duration.toLong()
            } catch (_: Exception) {
                0L
            }
            val pos = try {
                videoView.currentPosition.toLong()
            } catch (_: Exception) {
                0L
            }
            if (dur > 0) onProgress(pos, dur)
            val seek = PlatformVideoViewSeek.positionMs
            if (PlatformVideoViewSeek.shouldSeek && seek > 0 && seek != pos) {
                runCatching { videoView.seekTo(seek.toInt()) }
                PlatformVideoViewSeek.shouldSeek = false
            }
        }
    }
    AndroidView(factory = { videoView }, modifier = modifier)
}

private object PotsAppContextHolder {
    val context: android.content.Context
        get() = com.pusios.pots.PotsAppContext.application
            ?: throw IllegalStateException("App context not initialized")
}
