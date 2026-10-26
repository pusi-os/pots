package com.pusios.pots.ui.chat.composables

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pusios.pots.ui.potsik.PotsikAnchor
import com.pusios.pots.ui.potsik.PotsikAnchorKind
import kotlinx.collections.immutable.ImmutableList
import org.jetbrains.compose.resources.stringResource
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.potsiki_working_content_description
import pots.composeapp.generated.resources.tools_count

@Composable
internal fun toolSummaryText(
    executingTools: ImmutableList<Pair<String, String>>,
): String? = when {
    executingTools.isEmpty() -> null
    executingTools.size == 1 -> executingTools.first().second
    else -> stringResource(Res.string.tools_count, executingTools.size)
}

/**
 * The widget that shows the agent is working.
 *
 * Потсик stands in for the old pulsing dot and the "Thinking… / Working… /
 * Brewing…" carousel: he picks the animation that matches the tool currently
 * running, so the screen says what the agent is doing by showing it rather
 * than by spelling it out.
 *
 * The tile comes first and the state under it — the label is what is being
 * done, the character is who is doing it, and the label reads as a caption
 * under the picture rather than as a second thing next to it.
 *
 * The caption's place is measured out whether there is a caption or not: it
 * fades in and out over the space it always has, so nothing around it moves
 * when the line changes. A caption that pushed the layout around would take
 * the character with it, and he would jump out of his tile every time the
 * agent started or finished a tool.
 */
@Composable
internal fun WaitingResponseRow(
    executingTools: ImmutableList<Pair<String, String>>,
    isStatusOnly: Boolean = false,
    statusText: String? = null,
    modifier: Modifier = Modifier,
) {
    val summary = statusText ?: toolSummaryText(executingTools)
    val waitingCd = stringResource(Res.string.potsiki_working_content_description)

    Column(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clipToBounds(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                // The tile has no background of its own: the character stands
                // on the chat, not on a card. The box is still here — it is the
                // character's slot, and the caption under it is measured
                // against it — but nothing is drawn behind him.
                .padding(horizontal = 6.dp, vertical = 4.dp)
                .semantics { contentDescription = waitingCd },
        ) {
            // The character's place in the working widget. The overlay draws
            // him over the chat, so there is only ever one of him.
            PotsikAnchor(
                kind = PotsikAnchorKind.WORKING,
                size = WaitingCharacterSize,
            )
        }
        // The caption's place, measured out in advance: the line fades in and
        // out over the space it always has, and nothing else on the screen
        // moves when it does.
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .height(CaptionHeight),
            contentAlignment = Alignment.TopCenter,
        ) {
            // The line fades in and out over the space it always has: the
            // tile above it does not move when the line comes or goes.
            val alpha by animateFloatAsState(
                targetValue = if (summary != null) 1f else 0f,
                animationSpec = tween(durationMillis = 200),
            )
            Text(
                text = summary.orEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { this.alpha = alpha },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** How tall Потсик stands in the working widget. */
private val WaitingCharacterSize = 144.dp

/** The room the caption has, whether it is there or not. */
private val CaptionHeight = 40.dp
