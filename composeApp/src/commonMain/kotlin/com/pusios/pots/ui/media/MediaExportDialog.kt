package com.pusios.pots.ui.media

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.pusios.pots.media.captureVideoFrame
import com.pusios.pots.ui.handCursor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap

/**
 * The export dialog (mock `04-export`): whole canvas or the dragged region, the
 * background, the preview and «Сохранить в галерею».
 *
 * The preview is not a second drawing path: it is the bitmap [renderCanvas]
 * just produced at the export size, and that same bitmap is what
 * [onSave] hands to the PNG encoder. Whatever the user sees here is the file —
 * no divergence is possible, because there is nothing to diverge.
 *
 * Rendering runs off the main thread inside [renderCanvas]; the dialog only
 * holds the result (one bitmap at a time, ≤ [EXPORT_MAX_SIDE_PX]² ).
 */
@Composable
internal fun MediaExportDialog(
    layers: List<CanvasLayer>,
    region: ExportScope.Region?,
    /** Reads a layer's bytes: the cached copy first, the URL as a fallback. */
    loadImageBytes: suspend (ImageLayer) -> ByteArray?,
    onSave: (ImageBitmap) -> Unit,
    /** «Выделить на канвасе»: closes the dialog and arms the frame gesture. */
    onPickRegion: () -> Unit,
    onDismiss: () -> Unit,
) {
    val measurer = rememberTextMeasurer()
    val fonts = rememberCanvasFonts()
    var regionMode by remember(region) { mutableStateOf(region != null) }
    var backgroundIndex by remember { mutableIntStateOf(0) }
    var frames by remember(layers) { mutableStateOf<Map<String, Painter>>(emptyMap()) }
    // False until the sources are decoded: rendering before that would preview
    // cards without their pictures — and the user could save exactly that.
    var framesReady by remember(layers) { mutableStateOf(false) }
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var rendering by remember { mutableStateOf(true) }

    // Decoding the sources once per layer list: a switch of background or scope
    // must not re-read the files.
    LaunchedEffect(layers) {
        framesReady = false
        frames = withContext(Dispatchers.Default) { exportFrames(layers, loadImageBytes) }
        framesReady = true
    }

    val background = when (backgroundIndex) {
        0 -> null
        1 -> EXPORT_DARK_BACKGROUND
        else -> EXPORT_LIGHT_BACKGROUND
    }
    val scope: ExportScope? = if (regionMode) region else ExportScope.Whole

    LaunchedEffect(frames, framesReady, scope, background, layers) {
        if (!framesReady) return@LaunchedEffect
        val exportScope = scope ?: return@LaunchedEffect
        rendering = true
        bitmap = null
        bitmap = renderCanvas(
            layers = layers,
            scope = exportScope,
            includeAnnotation = false,
            background = background,
            frames = frames,
            fonts = fonts,
            measurer = measurer,
        )
        rendering = false
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.widthIn(max = 360.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Экспорт",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp).handCursor()) {
                        Icon(Icons.Filled.Close, contentDescription = "Закрыть", modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(Modifier.height(10.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ExportChoice(
                        label = "Весь канвас",
                        selected = !regionMode,
                        modifier = Modifier.weight(1f),
                    ) { regionMode = false }
                    ExportChoice(
                        label = "Выделенная область",
                        selected = regionMode,
                        modifier = Modifier.weight(1f),
                    ) { if (region != null) regionMode = true else onPickRegion() }
                }
                Spacer(Modifier.height(12.dp))

                Text(
                    "Фон",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ExportChoice(
                        label = "Прозрачный",
                        selected = backgroundIndex == 0,
                        modifier = Modifier.weight(1f),
                        swatch = { CheckerSwatch() },
                    ) { backgroundIndex = 0 }
                    ExportChoice(
                        label = "Тёмный",
                        selected = backgroundIndex == 1,
                        modifier = Modifier.weight(1f),
                        swatch = { SolidSwatch(EXPORT_DARK_BACKGROUND) },
                    ) { backgroundIndex = 1 }
                    ExportChoice(
                        label = "Светлый",
                        selected = backgroundIndex == 2,
                        modifier = Modifier.weight(1f),
                        swatch = { SolidSwatch(EXPORT_LIGHT_BACKGROUND) },
                    ) { backgroundIndex = 2 }
                }
                Spacer(Modifier.height(12.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(206.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    // The checkerboard is UI only: it says «this area is
                    // transparent», it is not part of the file.
                    if (background == null) Checkerboard(Modifier.fillMaxSize())
                    val preview = bitmap
                    when {
                        regionMode && region == null -> Text(
                            "Область не выделена",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        preview != null -> Image(
                            bitmap = preview,
                            contentDescription = "Превью экспорта",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().padding(8.dp),
                        )

                        rendering -> CircularProgressIndicator(Modifier.size(28.dp))

                        else -> Text(
                            "Нечего сохранять: на канвасе нет видимых слоёв",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    val size = bitmap?.let { it.width.toString() + " × " + it.height } ?: "—"
                    Text(
                        size + " · PNG",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (background == null) {
                        Text(
                            "альфа",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))

                if (regionMode && region == null) {
                    TextButton(onClick = onPickRegion, modifier = Modifier.fillMaxWidth()) {
                        Text("Выделить область на канвасе")
                    }
                } else {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .handCursor()
                            .clickable(enabled = bitmap != null) { bitmap?.let(onSave) },
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primary,
                    ) {
                        Row(
                            Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.SaveAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Сохранить в галерею",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                    }
                    TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                        Text("Отмена")
                    }
                }
            }
        }
    }
}

/**
 * Decodes the visible layers for the renderer: images from their bytes, videos
 * from one still frame where the platform can capture one.
 *
 * `decodeToImageBitmap` is Compose Resources' common decoder, so this needs no
 * composition, no Coil loader and no platform context, and the decoded bitmap
 * is a plain software bitmap — drawing it into the export canvas is safe on
 * every target (Coil may hand out a hardware bitmap on Android, which a
 * software canvas refuses to draw).
 *
 * A video frame is best effort: `captureVideoFrame` exists on desktop (ffmpeg)
 * and returns null elsewhere, where the video exports as its empty card — the
 * same honest degradation the pencil capture over a video node has.
 */
internal suspend fun exportFrames(
    layers: List<CanvasLayer>,
    loadImageBytes: suspend (ImageLayer) -> ByteArray?,
): Map<String, Painter> = layers
    .filter { it.transform.visible }
    .mapNotNull { layer ->
        val bytes = when (layer) {
            is ImageLayer -> loadImageBytes(layer)
            is VideoLayer -> videoFrameBytes(layer)
            else -> null
        } ?: return@mapNotNull null
        val image = runCatching { bytes.decodeToImageBitmap() }.getOrNull() ?: return@mapNotNull null
        layer.id to BitmapPainter(image)
    }
    .toMap()

/** One still frame of a video layer; null when the target cannot capture one. */
private suspend fun videoFrameBytes(layer: VideoLayer): ByteArray? {
    val source = layer.localPath ?: layer.url ?: return null
    return runCatching { captureVideoFrame(source) }.getOrNull()
}

/** One option of the dialog: a segmented tab or a background swatch. */
@Composable
private fun ExportChoice(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    swatch: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.height(44.dp).handCursor().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        },
        border = if (selected) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
        } else {
            null
        },
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (swatch != null) {
                swatch()
                Spacer(Modifier.width(6.dp))
            }
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun SolidSwatch(color: Color) {
    Box(Modifier.size(14.dp).background(color, CircleShape))
}

@Composable
private fun CheckerSwatch() {
    Checkerboard(Modifier.size(14.dp), cell = 5.dp)
}

/** The transparency checkerboard, UI only. */
@Composable
private fun Checkerboard(modifier: Modifier = Modifier, cell: androidx.compose.ui.unit.Dp = 10.dp) {
    Canvas(modifier) {
        val step = cell.toPx()
        val dark = Color(0xFF22222A)
        val light = Color(0xFF3A3A46)
        drawRect(light)
        var y = 0f
        var row = 0
        while (y < size.height) {
            var x = if (row % 2 == 0) 0f else step
            while (x < size.width) {
                drawRect(dark, topLeft = Offset(x, y), size = Size(step, step))
                x += step * 2f
            }
            y += step
            row++
        }
    }
}
