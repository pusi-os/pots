package com.pusios.pots.ui.media

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatAlignLeft
import androidx.compose.material.icons.filled.FormatAlignRight
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import com.pusios.pots.ui.handCursor
import kotlin.math.roundToInt

/** One row of the text panel; three rows are the mockup's 144 dp. */
internal val MediaTextPanelRowHeight = 48.dp

/** Size step of the «−/+» buttons, canvas units. */
internal const val TEXT_SIZE_STEP = 8f

/** The size range the panel allows, canvas units. */
internal const val MIN_TEXT_SIZE = 16f
internal const val MAX_TEXT_SIZE = 160f

/**
 * Ink the panel offers: white first, because the studio canvas is dark and a
 * black caption would be invisible; black for a light photo, red for emphasis.
 */
internal val TextInkColors = listOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFE53935.toInt())

/**
 * The text formatting panel of the studio (mockup 02).
 *
 * It sits in the screen's layout — the bottom bar — instead of floating over
 * the canvas, so the text being typed can never end up under it; with the
 * keyboard up the whole bar rides above the IME (`imePadding` in the caller).
 *
 * Three rows, because one does not fit: font + size + colour + alignment +
 * «готово» need 400+ dp on a 393 dp phone and clip the right edge.
 *
 * The caret lives here, in the field, and nowhere else: the canvas shows the
 * text as the result, so there is only ever one place where typing happens.
 */
@Composable
internal fun MediaTextPanel(
    layer: TextLayer,
    onTextChange: (String) -> Unit,
    onFont: (String) -> Unit,
    onSize: (Float) -> Unit,
    onColor: (Int) -> Unit,
    onAlign: (TextAlignKind) -> Unit,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    // Opening the editor means typing: the field takes focus and the keyboard
    // comes up. Clearing focus on the way out hides it again.
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val leave: (() -> Unit) -> Unit = { action ->
        keyboard?.hide()
        focusManager.clearFocus()
        action()
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().height(MediaTextPanelRowHeight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PanelIcon(Icons.Filled.Close, "Выйти без изменений") { leave(onCancel) }
                BasicTextField(
                    value = layer.text,
                    onValueChange = onTextChange,
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp).focusRequester(focusRequester),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    maxLines = 3,
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (layer.text.isEmpty()) {
                                Text(
                                    "Текст подписи",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            inner()
                        }
                    },
                )
                PanelIcon(Icons.Filled.Check, "Готово") { leave(onDone) }
            }

            Row(
                modifier = Modifier.fillMaxWidth().height(MediaTextPanelRowHeight),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TEXT_FONT_KEYS.forEach { fontId ->
                    FontSegment(
                        label = textFontLabel(fontId),
                        selected = layer.fontId == fontId,
                        onClick = { onFont(fontId) },
                    )
                }
                Spacer(Modifier.weight(1f))
                PanelIcon(Icons.Filled.Remove, "Меньше шрифт") {
                    onSize((layer.fontSizePx - TEXT_SIZE_STEP).coerceIn(MIN_TEXT_SIZE, MAX_TEXT_SIZE))
                }
                Text(
                    "${layer.fontSizePx.roundToInt()}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                PanelIcon(Icons.Filled.Add, "Больше шрифт") {
                    onSize((layer.fontSizePx + TEXT_SIZE_STEP).coerceIn(MIN_TEXT_SIZE, MAX_TEXT_SIZE))
                }
                TextInkColors.forEach { argb ->
                    InkSwatch(argb = argb, selected = layer.colorArgb == argb) { onColor(argb) }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().height(MediaTextPanelRowHeight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PanelIcon(Icons.Filled.FormatAlignLeft, "По левому краю", selected = layer.align == TextAlignKind.Left) {
                    onAlign(TextAlignKind.Left)
                }
                PanelIcon(Icons.Filled.FormatAlignCenter, "По центру", selected = layer.align == TextAlignKind.Center) {
                    onAlign(TextAlignKind.Center)
                }
                PanelIcon(Icons.Filled.FormatAlignRight, "По правому краю", selected = layer.align == TextAlignKind.Right) {
                    onAlign(TextAlignKind.Right)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "Текст · ${textFontLabel(layer.fontId)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun PanelIcon(
    icon: ImageVector,
    contentDescription: String,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp).handCursor()) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** One of the two font choices; a segment, not a dropdown — there are two. */
@Composable
private fun FontSegment(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.handCursor(),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            maxLines = 1,
        )
    }
}

/** A colour sample of the text ink; the 32 dp box is the tap target. */
@Composable
private fun InkSwatch(argb: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .handCursor()
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(24.dp)
                .background(Color(argb), CircleShape)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    shape = CircleShape,
                ),
        )
    }
}
