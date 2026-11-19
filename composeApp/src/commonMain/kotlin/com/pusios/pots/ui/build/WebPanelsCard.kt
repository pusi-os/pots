package com.pusios.pots.ui.build

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.pusios.pots.build.WebPanel
import com.pusios.pots.build.WebPanelState
import com.pusios.pots.build.WebPanelStatus
import com.pusios.pots.build.WebPanels
import com.pusios.pots.ui.handCursor
import com.pusios.pots.ui.settings.SettingsCard
import kotlinx.collections.immutable.ImmutableMap
import org.jetbrains.compose.resources.stringResource
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.pots_build_web_panel_autostart
import pots.composeapp.generated.resources.pots_build_web_panel_password_hide
import pots.composeapp.generated.resources.pots_build_web_panel_password_show
import pots.composeapp.generated.resources.pots_build_web_panel_retry
import pots.composeapp.generated.resources.pots_build_web_panel_start
import pots.composeapp.generated.resources.pots_build_web_panel_starting
import pots.composeapp.generated.resources.pots_build_web_panel_stop
import pots.composeapp.generated.resources.pots_build_web_panels_description
import pots.composeapp.generated.resources.pots_build_web_panels_title

/**
 * The agents' web panels, one row each: start it, see the address it actually
 * listens on, open it in the device's own browser, stop it.
 *
 * Nothing here is an in-app WebView. The panels are served on loopback — the
 * phone's own, because proot shares the network — so the URL is handed to
 * whatever browser the device defaults to.
 */
@Composable
internal fun WebPanelsCard(
    panels: ImmutableMap<String, WebPanelState>,
    onStart: (String) -> Unit,
    onStop: (String) -> Unit,
    onOpen: (String) -> Unit,
    onSetAutostart: (String, Boolean) -> Unit,
    onPassword: (String) -> String?,
    modifier: Modifier = Modifier,
) {
    // Catalog order, not map order: the rows must not shuffle between refreshes.
    val rows = remember(panels) {
        WebPanels.all.mapNotNull { panel -> panels[panel.id]?.let { panel to it } }
    }
    if (rows.isEmpty()) return

    SettingsCard(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(Res.string.pots_build_web_panels_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(Res.string.pots_build_web_panels_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        rows.forEachIndexed { index, (panel, state) ->
            if (index > 0) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            Spacer(Modifier.height(8.dp))
            WebPanelRow(
                panel = panel,
                state = state,
                onStart = onStart,
                onStop = onStop,
                onOpen = onOpen,
                onSetAutostart = onSetAutostart,
                onPassword = onPassword,
            )
        }
    }
}

@Composable
private fun WebPanelRow(
    panel: WebPanel,
    state: WebPanelState,
    onStart: (String) -> Unit,
    onStop: (String) -> Unit,
    onOpen: (String) -> Unit,
    onSetAutostart: (String, Boolean) -> Unit,
    onPassword: (String) -> String?,
) {
    // The password is fetched when the button is tapped, not while composing: the
    // getter reads stored settings, and composing must stay free of side effects.
    var revealedPassword by remember(panel.id, state.status) { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = panel.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            when (state.status) {
                WebPanelStatus.NotRunning -> TextButton(
                    onClick = { onStart(panel.id) },
                    modifier = Modifier.handCursor(),
                ) {
                    Text(stringResource(Res.string.pots_build_web_panel_start))
                }

                WebPanelStatus.Starting -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        text = stringResource(Res.string.pots_build_web_panel_starting),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is WebPanelStatus.Running -> TextButton(
                    onClick = { onStop(panel.id) },
                    modifier = Modifier.handCursor(),
                ) {
                    Text(stringResource(Res.string.pots_build_web_panel_stop))
                }

                is WebPanelStatus.Failed -> TextButton(
                    onClick = { onStart(panel.id) },
                    modifier = Modifier.handCursor(),
                ) {
                    Text(stringResource(Res.string.pots_build_web_panel_retry))
                }
            }
        }

        when (val status = state.status) {
            // The address, tappable: it opens in the browser the phone defaults to.
            is WebPanelStatus.Running -> {
                Text(
                    text = status.url,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(panel.id) }
                        .handCursor(),
                )
                if (panel.passwordEnv != null) {
                    PasswordLine(
                        password = revealedPassword,
                        onToggle = {
                            revealedPassword = if (revealedPassword == null) onPassword(panel.id) else null
                        },
                    )
                }
            }

            // Never an empty screen: the reason, in the words the runtime used.
            is WebPanelStatus.Failed -> Text(
                text = status.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )

            else -> Unit
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.pots_build_web_panel_autostart),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = state.autostart,
                onCheckedChange = { onSetAutostart(panel.id, it) },
            )
        }
    }
}

/** Hidden by default: a password on screen is a password in a screenshot. */
@Composable
private fun PasswordLine(
    password: String?,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (password != null) {
            Text(
                text = password,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
        TextButton(onClick = onToggle, modifier = Modifier.handCursor()) {
            Text(
                stringResource(
                    if (password != null) {
                        Res.string.pots_build_web_panel_password_hide
                    } else {
                        Res.string.pots_build_web_panel_password_show
                    },
                ),
            )
        }
    }
}
