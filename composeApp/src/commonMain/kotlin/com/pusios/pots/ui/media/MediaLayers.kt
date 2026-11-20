package com.pusios.pots.ui.media

import com.pusios.pots.media.MediaElementKind
import kotlin.math.roundToInt

/**
 * The layer panel's view of the draw list. Everything here is pure: the panel
 * owns no state of its own, the list in the view model is the only truth
 * (`_elements`, index 0 = bottom), and these functions are what the UI and the
 * tests share.
 */

/**
 * Panel order: the top layer first. The draw list puts the bottom layer at
 * index 0, so the panel shows the same list reversed — a reorder in the panel
 * is a move in the list, never a number written into a field.
 */
internal fun List<CanvasLayer>.panelOrder(): List<CanvasLayer> = reversed()

/** Layers the canvas composes and hit-tests: a hidden layer is neither. */
internal fun List<CanvasLayer>.visibleLayers(): List<CanvasLayer> = filter { it.transform.visible }

/**
 * Moves the layer at [from] to [to] in draw order. Out-of-range input is a
 * no-op rather than an exception: the panel can ask for a move while the list
 * is being rewritten underneath it (autosave, project import).
 */
internal fun List<CanvasLayer>.movedLayer(from: Int, to: Int): List<CanvasLayer> {
    if (from !in indices || isEmpty()) return this
    val target = to.coerceIn(0, lastIndex)
    if (target == from) return this
    return toMutableList().apply { add(target, removeAt(from)) }
}

// -- Row labels --------------------------------------------------------------
// No `name` field in the model on purpose: the project format stays as the
// layer-model card left it, and a rename would be a schema change for a label
// the user has not asked to edit. Titles are derived, so they can never drift
// from the layer they name.

/** Stable display names for a whole list, numbered per kind: «Картинка 1», «Рисунок 2». */
internal fun List<CanvasLayer>.layerTitles(): Map<String, String> {
    val counters = mutableMapOf<MediaElementKind, Int>()
    return associate { layer ->
        val ordinal = (counters[layer.kind] ?: 0) + 1
        counters[layer.kind] = ordinal
        layer.id to layerTitle(layer, ordinal)
    }
}

private fun layerTitle(layer: CanvasLayer, ordinal: Int): String = when (layer) {
    is ImageLayer -> "Картинка $ordinal"
    is VideoLayer -> "Видео $ordinal"
    is AudioLayer -> "Аудио $ordinal"
    is TextLayer -> textTitle(layer.text, ordinal)
    is StrokeLayer -> "Рисунок $ordinal"
    is ArrowLayer -> "Стрелка $ordinal"
}

/** «Текст: первые слова» — the first words of the layer, clipped to one row. */
private fun textTitle(text: String, ordinal: Int): String {
    val words = text.trim().split(' ').filter { it.isNotBlank() }
    if (words.isEmpty()) return "Текст $ordinal"
    val joined = words.take(3).joinToString(" ")
    return "Текст: " + if (joined.length > TEXT_TITLE_LIMIT) joined.take(TEXT_TITLE_LIMIT - 1).trimEnd() + "…" else joined
}

private const val TEXT_TITLE_LIMIT = 22

/** The small second line: the kind, its one interesting parameter, and «скрыт». */
internal fun layerSubtitle(layer: CanvasLayer): String {
    val base = when (layer) {
        is ImageLayer -> "Изображение"
        is VideoLayer -> "Видео"
        is AudioLayer -> "Аудио"
        is TextLayer -> "Текст · " + layer.fontSizePx.roundToInt() + " px"
        is StrokeLayer -> "Рисунок · " + layer.points.size + " точек"
        is ArrowLayer -> "Стрелка"
    }
    return if (layer.transform.visible) base else base + " · скрыт"
}

// -- Opacity rail ------------------------------------------------------------

/** One tap on the rail is one tenth of the range; at 100 % it wraps to 10 %. */
internal const val OPACITY_TAP_STEP = 0.1f

internal fun opacityAfterTap(current: Float): Float = if (current >= 1f) OPACITY_TAP_STEP else (current + OPACITY_TAP_STEP).coerceAtMost(1f)

/** A horizontal drag on the rail: the whole rail width covers the whole range. */
internal fun opacityAfterDrag(current: Float, dxPx: Float, railWidthPx: Float): Float {
    if (railWidthPx <= 0f) return current
    return (current + dxPx / railWidthPx).coerceIn(0f, 1f)
}

/** «40%» — the number beside the rail, never a separate control. */
internal fun opacityLabel(value: Float): String = (value.coerceIn(0f, 1f) * 100f).roundToInt().toString() + "%"
