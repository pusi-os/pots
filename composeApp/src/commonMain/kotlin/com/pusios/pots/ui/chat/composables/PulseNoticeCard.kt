package com.pusios.pots.ui.chat.composables

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pusios.pots.ui.handCursor
import com.pusios.pots.ui.potsAdaptiveCardBorder
import com.pusios.pots.ui.potsAdaptiveCardColors
import org.jetbrains.compose.resources.stringResource
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.heartbeat_empty_description
import pots.composeapp.generated.resources.heartbeat_empty_title
import pots.composeapp.generated.resources.heartbeat_history_offer_body
import pots.composeapp.generated.resources.heartbeat_history_offer_clear
import pots.composeapp.generated.resources.heartbeat_history_offer_keep
import pots.composeapp.generated.resources.heartbeat_history_offer_title
import pots.composeapp.generated.resources.settings_heartbeat_repeating

/**
 * Empty Pulse conversation.
 *
 * The Pulse is not a chat: nothing the user types here reaches a run, and no run sees what came
 * before it. Saying that once, where the confusion would happen, is cheaper than explaining it
 * after a wrong assumption.
 */
@Composable
internal fun PulseEmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(Res.string.heartbeat_empty_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(Res.string.heartbeat_empty_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 420.dp),
        )
    }
}

/**
 * Top-of-conversation card for the two things the owner should know about the Pulse itself:
 * that the history accumulated before 3.21.8 is no longer read (and can be cleared), and that the
 * same report keeps coming back.
 */
@Composable
internal fun PulseNoticeCard(
    showHistoryOffer: Boolean,
    repeatCount: Int?,
    onClearHistory: () -> Unit,
    onDismissOffer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!showHistoryOffer && repeatCount == null) return
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = potsAdaptiveCardColors(),
        border = potsAdaptiveCardBorder(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (repeatCount != null) {
                Text(
                    text = stringResource(Res.string.settings_heartbeat_repeating, repeatCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (showHistoryOffer) {
                if (repeatCount != null) Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(Res.string.heartbeat_history_offer_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(Res.string.heartbeat_history_offer_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismissOffer, modifier = Modifier.handCursor()) {
                        Text(stringResource(Res.string.heartbeat_history_offer_keep))
                    }
                    TextButton(onClick = onClearHistory, modifier = Modifier.handCursor()) {
                        Text(stringResource(Res.string.heartbeat_history_offer_clear))
                    }
                }
            }
        }
    }
}
