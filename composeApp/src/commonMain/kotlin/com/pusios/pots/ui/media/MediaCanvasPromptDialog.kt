package com.pusios.pots.ui.media

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.pusios.pots.media.MediaModel
import com.pusios.pots.ui.handCursor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * «Спросить модель по канвасу»: the request screen.
 *
 * What it shows is what it sends. The preview is the very bitmap
 * [renderCanvas] produced at the size the model will receive, with the
 * annotation included (the arrows and strokes the user drew) and an opaque
 * background — models read transparency badly. Nothing is drawn around the
 * dragged zone: at any zoom but 1 a baked frame lands in the wrong place (cards
 * grow around their centre, ink from the origin), so the zone travels as its
 * own crop, which carries the pan and zoom it was dragged at.
 *
 * The line under the preview spells out the arity: «Уйдёт: канвас 1280×720 +
 * зона 300×200», «Уйдёт: канвас 1280×720», or «Уйдёт: фрагмент 300×200» when
 * the model takes one picture and the zone is therefore what it gets.
 *
 * Rendering runs off the main thread inside [renderCanvas], and only the
 * pictures that will actually travel are encoded; the dialog holds at most two
 * bitmaps and drops them when it closes.
 */
@Composable
internal fun MediaCanvasPromptDialog(
    layers: List<CanvasLayer>,
    /** The frame the user dragged, or null for the whole canvas. */
    region: ExportScope.Region?,
    model: MediaModel?,
    /** True while the previous tap is encoding the screenshots: the button waits. */
    preparing: Boolean = false,
    /** Reads a layer's bytes: the cached copy first, the URL as a fallback. */
    loadImageBytes: suspend (ImageLayer) -> ByteArray?,
    onSend: (CanvasPromptRequest) -> Unit,
    onDismiss: () -> Unit,
) {
    val measurer = rememberTextMeasurer()
    val fonts = rememberCanvasFonts()
    val density = LocalDensity.current.density
    var frames by remember(layers) { mutableStateOf<Map<String, Painter>>(emptyMap()) }
    var framesReady by remember(layers) { mutableStateOf(false) }
    var canvas by remember { mutableStateOf<ImageBitmap?>(null) }
    var zone by remember { mutableStateOf<ImageBitmap?>(null) }
    var rendering by remember { mutableStateOf(true) }
    var instruction by remember { mutableStateOf("") }

    LaunchedEffect(layers) {
        framesReady = false
        frames = withContext(Dispatchers.Default) { exportFrames(layers, loadImageBytes) }
        framesReady = true
    }

    val support = canvasPromptSupport(model)
    val limit = (support as? CanvasPromptSupport.Ready)?.limit ?: 0
    // Decided before anything is rendered: only the pictures that travel are
    // encoded. On a one-picture model the whole canvas is seconds of work for a
    // file that would never leave the device.
    val images = canvasPromptImages(limit, hasZone = region != null)

    LaunchedEffect(frames, framesReady, region, layers, density, images) {
        if (!framesReady) return@LaunchedEffect
        rendering = true
        canvas = null
        zone = null
        // The model path, not the gallery one: annotation in, transparency out.
        if (images.canvas) {
            canvas = renderCanvas(
                layers = layers,
                scope = ExportScope.Whole,
                includeAnnotation = true,
                background = EXPORT_DARK_BACKGROUND,
                frames = frames,
                fonts = fonts,
                measurer = measurer,
                density = density,
            )
        }
        // The crop carries the pan and the zoom it was dragged at, so it is the
        // zone the finger saw at any zoom — no frame, no coordinate math.
        if (images.zone) {
            zone = region?.let { dragged ->
                renderCanvas(
                    layers = layers,
                    scope = dragged,
                    includeAnnotation = true,
                    background = EXPORT_DARK_BACKGROUND,
                    frames = frames,
                    fonts = fonts,
                    measurer = measurer,
                    density = density,
                )
            }
        }
        rendering = false
    }

    val refusal = canvasPromptRefusal(support, model)
    val attachedCanvas = canvas.takeIf { images.canvas }
    val attachedZone = zone.takeIf { images.zone }
    val send = canvasPromptSend(
        refusal = refusal,
        instruction = instruction,
        canvasReady = !rendering && (attachedCanvas != null || attachedZone != null),
        preparing = preparing,
    )

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.widthIn(max = 380.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Спросить модель по канвасу",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp).handCursor()) {
                        Icon(Icons.Filled.Close, contentDescription = "Закрыть", modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Нарисуйте на канвасе, что менять, и опишите правку словами — модель получит картинку с разметкой.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                // The preview *is* the picture that will be sent: the annotated
                // canvas when it travels, the zone's crop when the model takes
                // one picture and the zone is what it gets.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(170.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                        .background(EXPORT_DARK_BACKGROUND, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    val preview = attachedCanvas ?: attachedZone
                    when {
                        preview != null -> Image(
                            bitmap = preview,
                            contentDescription = "Картинка, как она уйдёт в модель",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().padding(6.dp),
                        )

                        rendering -> CircularProgressIndicator(Modifier.size(26.dp))

                        else -> Text(
                            "Нечего отправить: на канвасе нет видимого содержимого",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }

                // The zone beside the canvas, when both travel.
                if (attachedCanvas != null && attachedZone != null) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            bitmap = attachedZone,
                            contentDescription = "Зона правки",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .size(width = 64.dp, height = 44.dp)
                                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(8.dp)),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Зона правки " + attachedZone.width + "×" + attachedZone.height,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = instruction,
                    onValueChange = { instruction = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 92.dp),
                    placeholder = {
                        Text(
                            "Что сделать? Например: убери подпись в обведённой зоне и сделай фон светлее",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    textStyle = MaterialTheme.typography.bodyMedium,
                    maxLines = 5,
                    shape = RoundedCornerShape(14.dp),
                )
                Spacer(Modifier.height(10.dp))

                // «Уйдёт: …» — the promise the request keeps: exactly the
                // attached bitmaps, by their own sizes.
                Text(
                    text = when {
                        refusal != null -> refusal

                        attachedCanvas != null && attachedZone != null ->
                            "Уйдёт: канвас ${attachedCanvas.width}×${attachedCanvas.height} + зона ${attachedZone.width}×${attachedZone.height}"

                        attachedCanvas != null -> "Уйдёт: канвас ${attachedCanvas.width}×${attachedCanvas.height}"

                        attachedZone != null ->
                            "Уйдёт: фрагмент ${attachedZone.width}×${attachedZone.height} — модель принимает одну картинку, поэтому едет сама зона"

                        else -> "Ничего не уйдёт: нечего рендерить"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (refusal != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                model?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Модель: " + it.id,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(12.dp))

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .handCursor()
                        .clickable(enabled = send.enabled) {
                            if (attachedCanvas == null && attachedZone == null) return@clickable
                            onSend(
                                CanvasPromptRequest(
                                    instruction = instruction,
                                    canvas = attachedCanvas,
                                    zone = attachedZone,
                                ),
                            )
                        },
                    shape = RoundedCornerShape(16.dp),
                    color = if (send.enabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ) {
                    Row(
                        Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = if (send.enabled) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            send.label,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (send.enabled) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
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
