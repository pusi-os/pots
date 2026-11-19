package com.pusios.pots.ui.potsik

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit

/**
 * The bubble he talks in.
 *
 * The firmware's bubble is a round-cornered rectangle with a three-pixel
 * radius, filled with the background colour and outlined in pink, with a
 * small tail pointing down at whoever is speaking. It pops in over a fifth
 * of a second, overshooting slightly on the way — the same pop the firmware
 * does, and the same reason the bubble looks like it is being said rather
 * than being displayed.
 *
 * The text itself is measured and drawn by Compose rather than by the
 * firmware's bitmap font: the firmware has one font with no Cyrillic in it,
 * and the app has to say the same things in Russian.
 */
internal fun DrawScope.drawPotsikBubble(
    textMeasurer: TextMeasurer,
    text: String,
    style: TextStyle,
    centreX: Float,
    topY: Float,
    maxWidth: Float,
    fill: Color,
    border: Color,
    textColor: Color,
    pop: Float = 1f,
) {
    // The window has to hold the *popped* bubble, not the resting one: the
    // pop scales the box to POTSIK_BUBBLE_POP_PEAK about its middle, so the
    // text may only ask for the window divided by that factor, minus the
    // padding on both sides. The old rule (window − 16) let the resting
    // bubble run 4 px past the window and the peak 12 % past it — the clip
    // on the right the owner reported.
    val limit = potsikBubbleTextLimit(windowWidthPx = size.width, wantedPx = maxWidth)
    val layout: TextLayoutResult = textMeasurer.measure(
        text = text,
        style = style,
        maxLines = 3,
        constraints = Constraints(maxWidth = limit.toInt()),
    )
    val padding = BUBBLE_PADDING
    val bubbleWidth = layout.size.width + padding * 2
    val bubbleHeight = layout.size.height + padding * 2
    val left = potsikBubbleLeft(windowWidthPx = size.width, bubbleWidthPx = bubbleWidth, centreX = centreX)
    val top = topY.coerceAtLeast(0f)

    // The pop: the whole bubble grows out of its middle, overshoots and
    // settles — text and box together, or it would come apart.
    val amount = pop.coerceIn(0.05f, POTSIK_BUBBLE_POP_PEAK)
    scale(amount, amount, pivot = Offset(left + bubbleWidth / 2f, top + bubbleHeight / 2f)) {
        drawRoundRect(
            color = fill,
            topLeft = Offset(left, top),
            size = Size(bubbleWidth, bubbleHeight),
            cornerRadius = CornerRadius(8f, 8f),
        )
        drawRoundRect(
            color = border,
            topLeft = Offset(left, top),
            size = Size(bubbleWidth, bubbleHeight),
            cornerRadius = CornerRadius(8f, 8f),
            style = Stroke(width = 2f),
        )

        // The tail: a small triangle under the bubble, pointing at him.
        val tailWidth = 14f
        val tailHeight = 9f
        val tailX = (centreX - tailWidth / 2f).coerceIn(left, maxOf(left, left + bubbleWidth - tailWidth))
        val tailPath = Path().apply {
            moveTo(tailX + tailWidth / 2f, top + bubbleHeight + tailHeight)
            lineTo(tailX, top + bubbleHeight - 1f)
            lineTo(tailX + tailWidth, top + bubbleHeight - 1f)
            close()
        }
        drawPath(path = tailPath, color = fill, style = Fill)
        val tailEdge = Path().apply {
            moveTo(tailX + tailWidth / 2f, top + bubbleHeight + tailHeight)
            lineTo(tailX, top + bubbleHeight - 1f)
            lineTo(tailX + tailWidth, top + bubbleHeight - 1f)
        }
        drawPath(path = tailEdge, color = border, style = Stroke(width = 2f))

        drawText(
            textLayoutResult = layout,
            topLeft = Offset(left + padding, top + padding),
            color = textColor,
        )
    }
}

/** The text style the bubble is written in. */
internal fun potsikBubbleTextStyle(size: TextUnit): TextStyle = TextStyle(
    fontSize = size,
    lineHeight = size * 1.25f,
)

/** The padding between the bubble's border and its text, in pixels. */
private const val BUBBLE_PADDING = 10f

/**
 * How much the pop grows the bubble at its peak.
 *
 * Shared with the frame that drives the pop (`PotsikOverlayPet`), because the
 * text limit is derived from it: two copies of the number would let a change
 * of one clip the other.
 */
internal const val POTSIK_BUBBLE_POP_PEAK = 1.12f

/**
 * The widest text box the bubble may ask for in a window [windowWidthPx] wide.
 *
 * The bubble stands in the middle of the window and the pop scales it to
 * [POTSIK_BUBBLE_POP_PEAK] about that middle, so what has to fit is
 * `(limit + 2 · padding) · 1.12 ≤ window`. Solving for the limit gives the
 * rule below: the text box is `window / 1.12 − 20` px. The padding is a fixed
 * 20 px, so the box in dp grows with the density: at density 1 a 191 dp
 * window leaves 150.5 dp of text, and every denser screen leaves more. That
 * is where `POTSIK_MIN_WINDOW_WIDTH_DP` comes from.
 *
 * The floor of 32 dp keeps a window narrower than the padding from asking for
 * a negative box; the clamp to [windowWidthPx] keeps a caller's own limit from
 * being widened by this rule.
 */
internal fun potsikBubbleTextLimit(windowWidthPx: Float, wantedPx: Float): Float = (wantedPx.coerceAtMost(windowWidthPx) / POTSIK_BUBBLE_POP_PEAK - BUBBLE_PADDING * 2f).coerceAtLeast(32f)

/**
 * The widest the bubble gets in a window [windowWidthPx] wide — text box and
 * padding, before the pop.
 *
 * It is [potsikBubbleTextLimit] with nothing wanted: the limit of the widest
 * line the window allows, plus the padding on both sides, which is `window /
 * 1.12` for any window wide enough to matter. The clamp that keeps him on the
 * screen needs this number rather than the character's width, because the
 * bubble is drawn centred on him and cannot be moved off his middle — so it is
 * the bubble that has to fit on the screen, and it is the bubble's half-width
 * that he stops short of the edge by.
 */
internal fun potsikBubbleWidthFor(windowWidthPx: Float): Float = potsikBubbleTextLimit(windowWidthPx = windowWidthPx, wantedPx = windowWidthPx) + BUBBLE_PADDING * 2f

/**
 * The bubble's left edge: its middle on [centreX], but never off the window.
 *
 * With a text box from [potsikBubbleTextLimit] the bubble is always narrower
 * than the window and the clamp never bites. It stays as the last guard
 * against a bubble wider than its window being pinned to the left edge and
 * cut on the right — the shape the owner's bug had.
 */
internal fun potsikBubbleLeft(windowWidthPx: Float, bubbleWidthPx: Float, centreX: Float): Float = (centreX - bubbleWidthPx / 2f).coerceIn(0f, maxOf(0f, windowWidthPx - bubbleWidthPx))
