package com.pusios.pots.ui.media

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CallMade
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.pusios.pots.ui.handCursor

/** One layer row, as measured in the designer's mockup. */
internal const val LAYER_ROW_HEIGHT_DP = 50

/** Every control in the row is at least this big, per the tap-zone audit. */
internal const val LAYER_TAP_TARGET_DP = 44

/** The collapsed strip: layers icon, count, three thumbnails, chevron. */
internal const val LAYERS_STRIP_HEIGHT_DP = 58

/** The list scrolls instead of pushing the canvas off the screen. */
internal const val LAYERS_LIST_MAX_HEIGHT_DP = 250

/** Hidden rows stay readable: 55 %, not 30 % — on dark 30 % stops being legible. */
private const val HIDDEN_ROW_ALPHA = 0.55f

/**
 * The layer panel: a collapsed strip under the canvas that opens into the
 * stack list. Rows are the top layer first (`panelOrder`), selection is the
 * studio's single `selectedId`, and every control — grip, opacity rail, eye —
 * lives in the row, with no menu in between.
 *
 * The panel holds no state of its own: [layers] is the draw list, a reorder is
 * [onReorder] with draw-order indices, and the open/collapsed flag belongs to
 * `MediaSettings` through the view model.
 */
@Composable
internal fun MediaLayersPanel(
    layers: List<CanvasLayer>,
    selectedId: String?,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onSelect: (String?) -> Unit,
    onReorder: (fromDrawIndex: Int, toDrawIndex: Int) -> Unit,
    onVisibility: (layerId: String, visible: Boolean) -> Unit,
    onOpacity: (layerId: String, opacity: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!expanded) {
        LayersStrip(layers = layers, onToggleExpanded = onToggleExpanded, modifier = modifier)
    } else {
        LayersSheet(
            layers = layers,
            selectedId = selectedId,
            onToggleExpanded = onToggleExpanded,
            onSelect = onSelect,
            onReorder = onReorder,
            onVisibility = onVisibility,
            onOpacity = onOpacity,
            modifier = modifier,
        )
    }
}

@Composable
private fun LayersStrip(
    layers: List<CanvasLayer>,
    onToggleExpanded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().height(LAYERS_STRIP_HEIGHT_DP.dp).handCursor().clickable(onClick = onToggleExpanded),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Filled.Layers, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Text("Слои", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            LayersCountBadge(layers.size)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // The three topmost layers, in the same order the list will show them.
                layers.panelOrder().take(3).forEach { layer -> LayerThumb(layer, 22.dp, MaterialTheme.colorScheme.primary) }
            }
            Spacer(Modifier.weight(1f))
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Развернуть панель слоёв", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LayersSheet(
    layers: List<CanvasLayer>,
    selectedId: String?,
    onToggleExpanded: () -> Unit,
    onSelect: (String?) -> Unit,
    onReorder: (fromDrawIndex: Int, toDrawIndex: Int) -> Unit,
    onVisibility: (layerId: String, visible: Boolean) -> Unit,
    onOpacity: (layerId: String, opacity: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val titles = remember(layers) { layers.layerTitles() }
    val ordered = layers.panelOrder()
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.97f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
        shadowElevation = 12.dp,
    ) {
        Column(Modifier.padding(bottom = 6.dp)) {
            Box(Modifier.fillMaxWidth().height(14.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.width(38.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)))
            }
            Row(
                modifier = Modifier.fillMaxWidth().height(46.dp).padding(start = 14.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Слои", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                LayersCountBadge(layers.size)
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier.size(LAYER_TAP_TARGET_DP.dp).handCursor().clickable(onClick = onToggleExpanded),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Свернуть панель слоёв", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // Said out loud, so the order needs no guessing.
            Text(
                text = "верх списка = поверх всех",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
            )
            if (ordered.isEmpty()) {
                EmptyLayersHint()
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = LAYERS_LIST_MAX_HEIGHT_DP.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    ordered.forEach { layer ->
                        // Draw index is what the view model moves: index 0 is the bottom layer.
                        val drawIndex = layers.indexOfFirst { it.id == layer.id }
                        // Keyed by id: a reorder moves the composition group with
                        // the layer, so the drag gesture that caused the move is
                        // not cancelled by its own result.
                        key(layer.id) {
                            LayerRow(
                                layer = layer,
                                drawIndex = drawIndex,
                                title = titles[layer.id] ?: layer.id,
                                subtitle = layerSubtitle(layer),
                                selected = layer.id == selectedId,
                                onSelect = { onSelect(layer.id) },
                                onReorder = onReorder,
                                onVisibility = { visible -> onVisibility(layer.id, visible) },
                                onOpacity = { value -> onOpacity(layer.id, value) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyLayersHint() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            Icons.Filled.Layers,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
            modifier = Modifier.size(28.dp),
        )
        Text("Слоёв пока нет", style = MaterialTheme.typography.bodyMedium)
        Text(
            "Сгенерируй картинку, добавь текст или порисуй — слои появятся здесь",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun LayersCountBadge(count: Int) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

/**
 * One layer: grip · thumbnail · name/subtitle · opacity rail · eye.
 *
 * Dragging the grip moves the layer in the list one row per row of travel —
 * the row itself travels with the finger, so the drop position is where the
 * row already is (no ghost copy, no second selection highlight).
 */
@Composable
private fun LayerRow(
    layer: CanvasLayer,
    drawIndex: Int,
    title: String,
    subtitle: String,
    selected: Boolean,
    onSelect: () -> Unit,
    onReorder: (fromDrawIndex: Int, toDrawIndex: Int) -> Unit,
    onVisibility: (Boolean) -> Unit,
    onOpacity: (Float) -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current
    val rowHeightPx = with(density) { LAYER_ROW_HEIGHT_DP.dp.toPx() }
    val liftPx = with(density) { 8.dp.roundToPx() }
    // The pointer handler outlives a reorder, so it must read the fresh index.
    val currentIndex = rememberUpdatedState(drawIndex)
    var lifted by remember(layer.id) { mutableStateOf(false) }
    val visible = layer.transform.visible
    val shape = RoundedCornerShape(13.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(LAYER_ROW_HEIGHT_DP.dp)
            .offset { IntOffset(if (lifted) liftPx else 0, 0) }
            .shadow(if (lifted) 14.dp else 0.dp, shape)
            .clip(shape)
            .background(
                if (selected) {
                    Brush.horizontalGradient(listOf(accent.copy(alpha = 0.20f), accent.copy(alpha = 0.04f)))
                } else {
                    SolidColor(Color.Transparent)
                },
            )
            .then(if (selected) Modifier.border(1.dp, accent.copy(alpha = 0.34f), shape) else Modifier)
            .handCursor()
            .clickable(onClick = onSelect)
            .semantics { contentDescription = "Слой $title" },
    ) {
        if (selected) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .width(3.dp)
                    .height(32.dp)
                    .background(accent, RoundedCornerShape(2.dp)),
            )
        }
        if (lifted) {
            // The insertion line: this row's slot is where it will land.
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.2f), accent, accent.copy(alpha = 0.9f)))),
            )
        }
        Row(
            modifier = Modifier.fillMaxSize().padding(start = 2.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Everything but the eye dims on a hidden layer; the row stays selectable.
            Row(
                modifier = Modifier.weight(1f).graphicsLayer(alpha = if (visible) 1f else HIDDEN_ROW_ALPHA),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(LAYER_TAP_TARGET_DP.dp)
                        .pointerInput(layer.id) {
                            var carried = 0f
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    lifted = true
                                    carried = 0f
                                },
                                onDragEnd = {
                                    lifted = false
                                    carried = 0f
                                },
                                onDragCancel = {
                                    lifted = false
                                    carried = 0f
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    carried += dragAmount.y
                                    // Up in the panel is up in the draw list.
                                    while (carried <= -rowHeightPx) {
                                        carried += rowHeightPx
                                        onReorder(currentIndex.value, currentIndex.value + 1)
                                    }
                                    while (carried >= rowHeightPx) {
                                        carried -= rowHeightPx
                                        onReorder(currentIndex.value, currentIndex.value - 1)
                                    }
                                },
                            )
                        }
                        .semantics { contentDescription = "Перетащить слой $title" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.DragIndicator,
                        contentDescription = null,
                        tint = if (selected) accent.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(20.dp),
                    )
                }
                LayerThumb(layer, 32.dp, accent)
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                OpacityRail(
                    value = layer.transform.opacity,
                    selected = selected,
                    onChange = onOpacity,
                )
            }
            Box(
                modifier = Modifier
                    .size(LAYER_TAP_TARGET_DP.dp)
                    .handCursor()
                    .clickable { onVisibility(!visible) }
                    .semantics { contentDescription = if (visible) "Скрыть слой $title" else "Показать слой $title" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (visible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                    contentDescription = null,
                    tint = if (visible) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * Opacity in the row: a 44 dp rail plus its number. A tap steps 10 %, a drag
 * is exact — and the number sits inside the same hit area, so there is no
 * second control to aim at.
 */
@Composable
private fun OpacityRail(
    value: Float,
    selected: Boolean,
    onChange: (Float) -> Unit,
) {
    val railWidth = 44.dp
    val railWidthPx = with(LocalDensity.current) { railWidth.toPx() }
    val accent = MaterialTheme.colorScheme.primary
    val latest = rememberUpdatedState(value)
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.13f)
    val labelColor = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier.width(74.dp).height(LAYER_TAP_TARGET_DP.dp).semantics { contentDescription = "Прозрачность " + opacityLabel(value) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(railWidth)
                .height(LAYER_TAP_TARGET_DP.dp)
                .handCursor()
                .pointerInput(Unit) {
                    detectTapGestures { onChange(opacityAfterTap(latest.value)) }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { change, dragAmount ->
                        change.consume()
                        onChange(opacityAfterDrag(latest.value, dragAmount, railWidthPx))
                    }
                },
        ) {
            Canvas(Modifier.align(Alignment.Center).width(railWidth).height(14.dp)) {
                val railHeight = 4.dp.toPx()
                val top = (size.height - railHeight) / 2f
                val radius = CornerRadius(railHeight / 2f, railHeight / 2f)
                drawRoundRect(trackColor, topLeft = Offset(0f, top), size = Size(size.width, railHeight), cornerRadius = radius)
                drawRoundRect(
                    accent,
                    topLeft = Offset(0f, top),
                    size = Size(size.width * value.coerceIn(0f, 1f), railHeight),
                    cornerRadius = radius,
                )
                val knobRadius = 6.5.dp.toPx()
                val knobX = (size.width * value.coerceIn(0f, 1f)).coerceIn(knobRadius, size.width - knobRadius)
                drawCircle(Color.White, knobRadius, Offset(knobX, size.height / 2f))
            }
        }
        Text(
            text = opacityLabel(value),
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(30.dp),
        )
    }
}

/** 32 dp thumbnail: the picture itself for media, the kind's icon on an accent plate otherwise. */
@Composable
private fun LayerThumb(layer: CanvasLayer, size: Dp, accent: Color) {
    val media = layer as? MediaLayer
    val source = media?.localPath ?: media?.url
    val shape = RoundedCornerShape(size / 3.5f)
    if (layer is ImageLayer || layer is VideoLayer) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(shape)
                .background(accent.copy(alpha = 0.12f))
                .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f), shape),
        ) {
            if (source != null) {
                AsyncImage(
                    model = rememberImageModel(source),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(layerKindIcon(layer), contentDescription = null, tint = accent, modifier = Modifier.align(Alignment.Center).size(size * 0.55f))
            }
        }
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(shape)
                .background(accent.copy(alpha = 0.12f))
                .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f), shape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(layerKindIcon(layer), contentDescription = null, tint = accent, modifier = Modifier.size(size * 0.55f))
        }
    }
}

private fun layerKindIcon(layer: CanvasLayer): ImageVector = when (layer) {
    is ImageLayer -> Icons.Filled.Image
    is VideoLayer -> Icons.Filled.Movie
    is AudioLayer -> Icons.Filled.Audiotrack
    is TextLayer -> Icons.Filled.TextFields
    is StrokeLayer -> Icons.Filled.Brush
    is ArrowLayer -> Icons.Filled.CallMade
}
