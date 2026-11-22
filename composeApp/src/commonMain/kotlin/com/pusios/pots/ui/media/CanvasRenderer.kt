package com.pusios.pots.ui.media

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import kotlin.math.min

/** Element footprint on the canvas (canvas units): the fixed WIDTH; height follows the aspect. */
internal const val CANVAS_ELEMENT_SIZE_DP = 232f

/** Fallback frame for a video whose aspect is not probed yet: the common 16:9 generation output. */
internal const val DEFAULT_VIDEO_ASPECT_RATIO = 16f / 9f

/** Corner radius of a layer card, canvas units. */
internal const val CANVAS_CARD_CORNER_DP = 12f

/**
 * Element box height from the source aspect (width / height), @joni's block-4
 * contract: the width stays 232.dp, the height follows the ratio, clamped to
 * 16:9…9:16 — beyond the clamp the content renders Fit inside the clamped
 * frame. Unknown or junk ratios fall back to the old square, so a remote
 * image does not jump before its painter arrives.
 */
internal fun canvasElementHeightDp(aspectRatio: Float?): Float {
    val ratio = aspectRatio?.takeIf { it.isFinite() && it > 0f } ?: 1f
    return (CANVAS_ELEMENT_SIZE_DP / ratio).coerceIn(
        CANVAS_ELEMENT_SIZE_DP * 9f / 16f,
        CANVAS_ELEMENT_SIZE_DP * 16f / 9f,
    )
}

/**
 * The one canvas renderer: draws the layers onto a [DrawScope] in list order.
 *
 * It is the same code for the screen and for the export — the screen calls it
 * from `Canvas`, an export records a `GraphicsLayer` and calls it there, so the
 * file cannot drift away from what the user sees. It knows nothing about the
 * ViewModel, the selection or the UI chrome: everything arrives as arguments.
 *
 * Today it draws Image and Video frames and text. Strokes and arrows are the
 * next cards; [fonts] and [measurer] are here for the text layer — a
 * [FontFamily] can only be resolved in a composition (`Font()` is @Composable),
 * so the renderer receives the resolved families instead of loading them. A
 * text layer without a [measurer] draws nothing rather than an empty box.
 *
 * @param layers draw order — the list is the z-order, nothing is sorted here.
 * @param scale canvas px per canvas unit at 100 % zoom: the screen passes the
 *   display density, an export passes its own factor. A layer's box is
 *   [CANVAS_ELEMENT_SIZE_DP] × [canvasElementHeightDp] units, so at density 2
 *   it is the same 232.dp wide as the old composable path.
 * @param frames decoded frames by layer id. A layer without a frame draws only
 *   its card (an image that is still loading, a video — see below).
 * @param cardBackground fill of the layer card; the frame is drawn Fit inside
 *   it, so the letterbox bars show this colour. `null` leaves the card empty.
 * @param zoom canvas zoom. The box grows around its *layout* centre, exactly
 *   like the old `graphicsLayer(scaleX = layer.scale * canvasScale)` did; with
 *   the zoom folded into [scale] the box would grow from its top-left corner.
 *
 * Layer `x`/`y` are canvas px and are NOT multiplied by [scale] — that is what
 * the project file has always stored (pan deltas in px) and the screen keeps
 * that geometry. A caller rendering at another scale translates the scope.
 *
 * Video: `MediaVideoPlayer` is a composable, its frame cannot be drawn here
 * yet, so the screen keeps the player above the canvas and the renderer paints
 * only the video card. A video frame therefore does not reach an export — that
 * is a separate card.
 */
internal fun DrawScope.renderLayers(
    layers: List<CanvasLayer>,
    scale: Float,
    fonts: Map<String, FontFamily> = emptyMap(),
    measurer: TextMeasurer? = null,
    frames: Map<String, Painter> = emptyMap(),
    cardBackground: Color? = null,
    zoom: Float = 1f,
) {
    layers.forEach { layer ->
        if (!layer.transform.visible) return@forEach
        when (layer) {
            is ImageLayer -> drawMediaLayer(layer, frames[layer.id], scale, zoom, cardBackground)

            is VideoLayer -> drawMediaLayer(layer, frames[layer.id], scale, zoom, cardBackground)

            is TextLayer -> drawTextLayer(layer, measurer, fonts, scale, zoom)

            // Audio is a player card, not a frame; arrows are the next card.
            // They are skipped, not drawn as empty boxes.
            is AudioLayer, is ArrowLayer -> Unit
        }
    }
}

/**
 * One text layer.
 *
 * The layout is measured in canvas units ([textLayout], `Density(1f, 1f)`) and
 * the scope is scaled by [scale] before the glyphs are drawn, so the line
 * breaks are the same on the screen, on another device and in an export at any
 * size: only the factor differs, not the layout. Layer `scale`/`rotation`/zoom
 * turn around the box centre, exactly like a card does, and the alpha goes into
 * the glyphs — one text layer has nothing inside it to show through.
 *
 * A text layer's own `scale` therefore scales the whole box (the frame and the
 * glyphs together), not [TextLayer.fontSizePx]: pinching a caption must not
 * re-wrap it, or the lines the user arranged would jump around under the
 * fingers. The font size stays the number the panel shows.
 */
private fun DrawScope.drawTextLayer(
    layer: TextLayer,
    measurer: TextMeasurer?,
    fonts: Map<String, FontFamily>,
    scale: Float,
    zoom: Float,
) {
    if (measurer == null || layer.transform.opacity <= 0f) return
    val layout = textLayout(layer, measurer, fonts)
    val box = textLayerBox(layout)
    val topLeft = Offset(layer.transform.x, layer.transform.y)
    val pivot = Offset(
        topLeft.x + box.width * scale / 2f,
        topLeft.y + box.height * scale / 2f,
    )
    val grow = layer.transform.scale * zoom
    withTransform({
        rotate(layer.transform.rotation, pivot)
        scale(grow, grow, pivot)
        // The layout is in canvas units; scale it to the caller's factor.
        translate(topLeft.x, topLeft.y)
        scale(scale, scale)
    }) {
        drawText(layout, alpha = layer.transform.opacity)
    }
}

/** One media layer: translate to its position, then rotate/scale around the layout centre. */
private fun DrawScope.drawMediaLayer(
    layer: MediaLayer,
    frame: Painter?,
    scale: Float,
    zoom: Float,
    cardBackground: Color?,
) {
    val box = Size(
        width = CANVAS_ELEMENT_SIZE_DP * scale,
        height = canvasElementHeightDp(canvasLayerAspect(layer, frame)) * scale,
    )
    val topLeft = Offset(layer.transform.x, layer.transform.y)
    val pivot = Offset(topLeft.x + box.width / 2f, topLeft.y + box.height / 2f)
    val grow = layer.transform.scale * zoom
    withTransform({
        rotate(layer.transform.rotation, pivot)
        scale(grow, grow, pivot)
    }) {
        drawCard(topLeft, box, CANVAS_CARD_CORNER_DP * scale, cardBackground, layer.transform.opacity, frame)
    }
}

/**
 * The rounded card plus the frame fitted into it, clipped to the card.
 *
 * The whole card composites as ONE group: with `opacity < 1` the background and
 * the frame go into a `saveLayer` that carries the layer alpha, exactly like
 * the old `graphicsLayer(alpha)` on the element box. Fading them separately
 * would stack them — 0.5 over 0.5 = 0.75 in the frame area against 0.5 in the
 * letterbox bars, i.e. one layer with two different opacities.
 */
private fun DrawScope.drawCard(
    topLeft: Offset,
    box: Size,
    corner: Float,
    background: Color?,
    alpha: Float,
    frame: Painter?,
) {
    if (alpha <= 0f) return
    if (alpha >= 1f) {
        drawCardContent(topLeft, box, corner, background, frame)
        return
    }
    drawIntoCanvas { canvas ->
        canvas.saveLayer(Rect(topLeft, box), Paint().apply { this.alpha = alpha })
        drawCardContent(topLeft, box, corner, background, frame)
        canvas.restore()
    }
}

/** Card fill and frame at full opacity; the caller owns the layer alpha. */
private fun DrawScope.drawCardContent(
    topLeft: Offset,
    box: Size,
    corner: Float,
    background: Color?,
    frame: Painter?,
) {
    if (background != null) {
        drawRoundRect(background, topLeft, box, CornerRadius(corner, corner))
    }
    if (frame == null) return
    val source = frame.intrinsicSize
    if (!source.isSpecified || source.width <= 0f || source.height <= 0f) return
    // ContentScale.Fit: the whole frame inside the card, centred.
    val fit = min(box.width / source.width, box.height / source.height)
    val drawn = Size(source.width * fit, source.height * fit)
    val origin = Offset(
        topLeft.x + (box.width - drawn.width) / 2f,
        topLeft.y + (box.height - drawn.height) / 2f,
    )
    val card = Path().apply { addRoundRect(RoundRect(Rect(topLeft, box), CornerRadius(corner, corner))) }
    clipPath(card) {
        withTransform({ translate(origin.x, origin.y) }) {
            drawPainter(frame, drawn)
        }
    }
}

/**
 * Draws [painter] into a [size] box at the current origin.
 *
 * `Painter.draw(size)` shrinks the scope to [size] first, and that shrink
 * (`DrawTransform.inset`) rejects a negative result with "Width and height must
 * be greater than or equal to zero". A layer box can be larger than the canvas
 * — a zoomed-in card, a tall card on a short screen, an export tile — so the
 * scope size is set to the box before drawing: the shrink becomes a no-op and
 * the painter keeps drawing its own size.
 */
private fun DrawScope.drawPainter(painter: Painter, size: Size) {
    val previous = drawContext.size
    drawContext.size = size
    try {
        with(painter) { draw(size) }
    } finally {
        drawContext.size = previous
    }
}

/**
 * The card aspect (width / height) for a layer: a loaded frame wins over the
 * stored ratio, because the frame is what the user sees. A video without a
 * probed ratio keeps the 16:9 fallback; audio and the vector kinds have no
 * frame and keep the square card.
 */
internal fun canvasLayerAspect(layer: CanvasLayer, frame: Painter?): Float? = when (layer) {
    is ImageLayer -> frameAspect(frame) ?: layer.aspectRatio
    is VideoLayer -> layer.aspectRatio ?: frameAspect(frame) ?: DEFAULT_VIDEO_ASPECT_RATIO
    is AudioLayer, is TextLayer, is StrokeLayer, is ArrowLayer -> null
}

/** Card height in canvas units: square for audio, otherwise from [canvasLayerAspect]. */
internal fun canvasLayerHeightDp(layer: CanvasLayer, frame: Painter?): Float = if (layer is AudioLayer) {
    CANVAS_ELEMENT_SIZE_DP
} else {
    canvasElementHeightDp(canvasLayerAspect(layer, frame))
}

/**
 * The layer's box in canvas units — the renderer, the selection frame and the
 * hit target all ask here, so a frame can never sit somewhere the ink is not.
 *
 * Cards are [CANVAS_ELEMENT_SIZE_DP] wide with the source aspect; a text layer
 * is its stored wrapping width and its *measured* height, which is why the
 * measurement is passed in (a text box is as tall as its lines).
 */
internal fun canvasLayerBox(
    layer: CanvasLayer,
    frame: Painter?,
    measurer: TextMeasurer? = null,
    fonts: Map<String, FontFamily> = emptyMap(),
): Size = when (layer) {
    is TextLayer -> measurer?.let { textLayerBox(textLayout(layer, it, fonts)) }
        ?: Size(layer.widthPx, layer.fontSizePx * TEXT_FALLBACK_LINE_HEIGHT)

    else -> Size(CANVAS_ELEMENT_SIZE_DP, canvasLayerHeightDp(layer, frame))
}

private fun frameAspect(frame: Painter?): Float? {
    val size = frame?.intrinsicSize ?: return null
    if (!size.isSpecified || size.width <= 0f || size.height <= 0f) return null
    return size.width / size.height
}
