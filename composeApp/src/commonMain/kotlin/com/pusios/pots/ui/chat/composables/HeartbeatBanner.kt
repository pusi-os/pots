package com.pusios.pots.ui.chat.composables

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pusios.pots.ui.handCursor
import com.pusios.pots.ui.potsAdaptiveCardBorder
import com.pusios.pots.ui.potsAdaptiveCardColors
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.heartbeat_banner_dismiss
import pots.composeapp.generated.resources.heartbeat_banner_message
import pots.composeapp.generated.resources.ic_close
import pots.composeapp.generated.resources.ic_history

@Composable
internal fun HeartbeatBanner(
    visible: Boolean,
    onTap: () -> Unit,
    onDismiss: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { -it },
        exit = slideOutVertically { -it },
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .handCursor()
                .clickable { onTap() },
            colors = potsAdaptiveCardColors(),
            border = potsAdaptiveCardBorder(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = vectorResource(Res.drawable.ic_history),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(Res.string.heartbeat_banner_message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                IconButton(
                    modifier = Modifier.size(24.dp).handCursor(),
                    onClick = onDismiss,
                ) {
                    Icon(
                        imageVector = vectorResource(Res.drawable.ic_close),
                        contentDescription = stringResource(Res.string.heartbeat_banner_dismiss),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}
