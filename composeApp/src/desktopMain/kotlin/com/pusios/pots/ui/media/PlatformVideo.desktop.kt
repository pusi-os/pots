package com.pusios.pots.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage

@Composable
actual fun PlatformVideoView(
    url: String,
    playing: Boolean,
    onProgress: (positionMs: Long, durationMs: Long) -> Unit,
    modifier: Modifier,
) {
    // Desktop has no embedded player dependency in KMP: poster + system player.
    Box(modifier.background(Color.Black.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
        AsyncImage(model = url, contentDescription = "Постер видео", modifier = Modifier, contentScale = ContentScale.FillHeight)
    }
}
