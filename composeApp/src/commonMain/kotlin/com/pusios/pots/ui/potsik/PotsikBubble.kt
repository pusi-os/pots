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
    val limit = maxWidth.coerceAtMost(size.width - 16f).coerceAtLeast(32f)
    val layout: TextLayoutResult = textMeasurer.measure(
        text = text,
        style = style,
        maxLines = 3,
        constraints = Constraints(maxWidth = limit.toInt()),
    )
    val padding = 10f
    val bubbleWidth = layout.size.width + padding * 2
    val bubbleHeight = layout.size.height + padding * 2
    val left = (centreX - bubbleWidth / 2f).coerceIn(0f, maxOf(0f, size.width - bubbleWidth))
    val top = topY.coerceAtLeast(0f)

    // The pop: the whole bubble grows out of its middle, overshoots and
    // settles — text and box together, or it would come apart.
    val amount = pop.coerceIn(0.05f, 1.12f)
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
