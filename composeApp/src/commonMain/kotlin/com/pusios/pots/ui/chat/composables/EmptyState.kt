package com.pusios.pots.ui.chat.composables

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.pusios.pots.ui.components.animatedGradientBorder
import com.pusios.pots.ui.handCursor
import com.pusios.pots.ui.potsik.PotsikAnchor
import com.pusios.pots.ui.potsik.PotsikAnchorKind
import org.jetbrains.compose.resources.stringResource
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.pots_build_open
import pots.composeapp.generated.resources.start_interactive_ui

/**
 * Phosphor green for the Pots Build button, taken from the ANSI palette its own
 * terminal paints with: the bright green on dark backgrounds, the darker normal
 * green where a light one would wash it out. Colors only — the button keeps the
 * shape and label style it shares with the rest of the empty state.
 */
private val TerminalGreenOnDark = Color(0xFF16C60C)
private val TerminalGreenOnLight = Color(0xFF13A10E)

/**
 * The chat's empty state.
 *
 * Потсик stands where the welcome text used to be: the character's height is
 * roughly what the logo and the greeting line took up together, so the layout
 * does not jump when the first message arrives.
 */
@Composable
internal fun EmptyState(
    modifier: Modifier,
    onStartInteractiveMode: (() -> Unit)? = null,
    onOpenPotsBuild: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The character's place in the empty screen. He is drawn by the
        // overlay over the chat, so there is only ever one of him.
        PotsikAnchor(
            kind = PotsikAnchorKind.EMPTY,
            size = EmptyStateCharacterSize,
        )
        if (onStartInteractiveMode != null) {
            Spacer(Modifier.height(16.dp))
            AnimatedBorderButton(
                text = stringResource(Res.string.start_interactive_ui),
                onClick = onStartInteractiveMode,
            )
            Spacer(Modifier.height(8.dp))
        }
        if (onOpenPotsBuild != null) {
            val terminalGreen = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
                TerminalGreenOnDark
            } else {
                TerminalGreenOnLight
            }
            OutlinedButton(
                onClick = onOpenPotsBuild,
                modifier = Modifier.handCursor(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = terminalGreen),
                border = BorderStroke(1.dp, terminalGreen.copy(alpha = 0.6f)),
            ) {
                Icon(
                    imageVector = Icons.Default.Terminal,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.pots_build_open))
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** How tall Потсик stands in the empty state. */
private val EmptyStateCharacterSize = 132.dp

@Composable
private fun AnimatedBorderButton(
    text: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .handCursor()
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .animatedGradientBorder(
                cornerRadius = 50.dp,
                borderWidth = 3.dp,
                backgroundColor = MaterialTheme.colorScheme.background,
            ),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}
