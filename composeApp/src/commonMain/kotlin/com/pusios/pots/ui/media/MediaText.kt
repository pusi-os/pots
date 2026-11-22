package com.pusios.pots.ui.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.Font
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.pots_text_serif
import kotlin.math.roundToInt

/**
 * The studio's two text families: the platform sans (`FontFamily.Default`, no
 * file at all) and one bundled serif
 * (`composeResources/font/pots_text_serif.ttf`, DejaVu Serif — it carries
 * Cyrillic, which the captions need). A third family waits until the resource
 * directory has more than one file; a list of fifty is not the point of a
 * caption tool.
 */
@Composable
internal fun rememberCanvasFonts(): Map<String, FontFamily> {
    // Font() is @Composable, so the family is resolved here and handed to the
    // renderer; the export path cannot load one itself.
    val serif = Font(Res.font.pots_text_serif)
    return remember(serif) {
        mapOf(
            TEXT_FONT_SANS to FontFamily.Default,
            TEXT_FONT_SERIF to FontFamily(serif),
        )
    }
}

/** Label of a font key in the panel. */
internal fun textFontLabel(fontId: String): String = when (fontId) {
    TEXT_FONT_SERIF -> "Serif"
    else -> "Sans"
}

/** The Compose alignment of the stored kind. */
internal fun TextAlignKind.composeAlign(): TextAlign = when (this) {
    TextAlignKind.Left -> TextAlign.Left
    TextAlignKind.Center -> TextAlign.Center
    TextAlignKind.Right -> TextAlign.Right
}

/**
 * The one measurement of a text layer — the renderer, the selection frame and
 * the tests all call it, so they cannot disagree.
 *
 * Two things are pinned here on purpose:
 *
 * 1. `Density(1f, fontScale = 1f)`: the layout is computed in canvas units, so
 *    the device density and the user's system font scale never leak in, and a
 *    caption wraps the same way on a phone, on desktop and in an exported file.
 *    The screen and an export then only differ by the factor they scale the
 *    whole layer with (see `renderLayers`), not by a different line breaking.
 * 2. `Constraints.fixedWidth(widthPx)`: the stored box width is the wrapping
 *    width. Measuring without it is the classic `TextMeasurer` trap — the
 *    measurement would use the viewport instead of the box.
 */
internal fun textLayout(
    layer: TextLayer,
    measurer: TextMeasurer,
    fonts: Map<String, FontFamily>,
): TextLayoutResult = measurer.measure(
    text = layer.text,
    style = TextStyle(
        color = Color(layer.colorArgb),
        fontSize = layer.fontSizePx.sp,
        fontFamily = fonts[layer.fontId] ?: FontFamily.Default,
        textAlign = layer.align.composeAlign(),
    ),
    overflow = TextOverflow.Clip,
    softWrap = true,
    maxLines = Int.MAX_VALUE,
    constraints = Constraints.fixedWidth(layer.widthPx.roundToInt().coerceAtLeast(1)),
    layoutDirection = LayoutDirection.Ltr,
    density = Density(1f, 1f),
)

/** The measured box of a text layer in canvas units: the stored width, the measured height. */
internal fun textLayerBox(layout: TextLayoutResult): Size = Size(
    width = layout.size.width.toFloat(),
    height = layout.size.height.toFloat(),
)
