package com.pusios.pots.ui.build

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.pusios.pots.build.WebPanel
import com.pusios.pots.build.WebPanelOpen
import com.pusios.pots.build.WebPanelState
import com.pusios.pots.build.WebPanelStatus
import com.pusios.pots.build.WebPanels
import com.pusios.pots.ui.handCursor
import com.pusios.pots.ui.rememberCopyToClipboard
import com.pusios.pots.ui.settings.SettingsCard
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.pots_build_web_panel_autostart
import pots.composeapp.generated.resources.pots_build_web_panel_hermes_lan_hint
import pots.composeapp.generated.resources.pots_build_web_panel_hermes_link_hint
import pots.composeapp.generated.resources.pots_build_web_panel_hermes_local_hint
import pots.composeapp.generated.resources.pots_build_web_panel_password_copied
import pots.composeapp.generated.resources.pots_build_web_panel_password_copy
import pots.composeapp.generated.resources.pots_build_web_panel_password_hide
import pots.composeapp.generated.resources.pots_build_web_panel_password_show
import pots.composeapp.generated.resources.pots_build_web_panel_pi_login_hint
import pots.composeapp.generated.resources.pots_build_web_panel_retry
import pots.composeapp.generated.resources.pots_build_web_panel_start
import pots.composeapp.generated.resources.pots_build_web_panel_starting
import pots.composeapp.generated.resources.pots_build_web_panel_stop
import pots.composeapp.generated.resources.pots_build_web_panel_username
import pots.composeapp.generated.resources.pots_build_web_panels_description
import pots.composeapp.generated.resources.pots_build_web_panels_lan_note
import pots.composeapp.generated.resources.pots_build_web_panels_title

/**
 * The agents' web panels, one row each: start it, see the address it actually
 * listens on, open it in the device's own browser, stop it.
 *
 * Nothing here is an in-app WebView. OpenCode and Pi serve on the phone's own
 * loopback — proot shares the network, so that address is the phone's — while
 * Hermes binds the local network and asks for a login, so its row shows the
 * address other devices dial and the account to sign in with. Either way the URL
 * is handed to whatever browser the device defaults to.
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
        // Said once, plainly: the Hermes port is open to every device on whatever
        // network the phone is on, and the password is what keeps them out.
        Text(
            text = stringResource(Res.string.pots_build_web_panels_lan_note),
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
    // Sensitive: the system keeps the clip out of its history and out of the paste
    // preview, so a copied password is not left lying around for the next app.
    val copyToClipboard = rememberCopyToClipboard(sensitive = true)

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
                // Two of the three panels cannot come up already signed in, and
                // saying so is cheaper than letting the owner wait for a login
                // that is not coming (pi-web) or tap a link that is already spent.
                when (status.open) {
                    WebPanelOpen.MINTED_LINK ->
                        PanelHint(stringResource(Res.string.pots_build_web_panel_hermes_link_hint))
                    // Behind the sign-in gate the address itself decides who can
                    // reach the panel: a LAN one opens from another device, a
                    // loopback one only from this phone, and the row says which.
                    WebPanelOpen.DASHBOARD_SIGN_IN ->
                        PanelHint(
                            stringResource(
                                if (WebPanels.isLoopback(status.url)) {
                                    Res.string.pots_build_web_panel_hermes_local_hint
                                } else {
                                    Res.string.pots_build_web_panel_hermes_lan_hint
                                },
                            ),
                        )
                    WebPanelOpen.DIRECT ->
                        if (panel.id == WebPanels.PI) {
                            PanelHint(stringResource(Res.string.pots_build_web_panel_pi_login_hint))
                        } else {
                            Unit
                        }
                }
                // The login the panel asks for, right above the password it goes with.
                if (panel.id == WebPanels.HERMES) {
                    Text(
                        text = stringResource(Res.string.pots_build_web_panel_username, WebPanels.HERMES_USER),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (panel.passwordEnv != null) {
                    PasswordLine(
                        password = revealedPassword,
                        onToggle = {
                            revealedPassword = if (revealedPassword == null) onPassword(panel.id) else null
                        },
                        onCopy = {
                            val password = onPassword(panel.id)
                            if (password != null) copyToClipboard(password)
                            password != null
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

/** Why this panel opens on a login form or a one-use link, in the row's own words. */
@Composable
private fun PanelHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Hidden by default: a password on screen is a password in a screenshot.
 *
 * Copy is the main path — the complaint that opened this was a 24-character
 * password being retyped by hand into a browser window — and the revealed value
 * is selectable as well, so a button that is somehow out of reach is not the only
 * way out. The buttons get their own line: 24 monospace characters and two
 * buttons do not fit a phone's width side by side.
 */
@Composable
private fun PasswordLine(
    password: String?,
    onToggle: () -> Unit,
    /** Copies the password; true when there was one to copy. */
    onCopy: () -> Boolean,
) {
    // "Copied" is the only confirmation there is: the clipboard is invisible, and
    // a silent button reads as a broken one.
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (!copied) return@LaunchedEffect
        delay(COPY_CONFIRMATION_MS)
        copied = false
    }

    Column(Modifier.fillMaxWidth()) {
        if (password != null) {
            SelectionContainer {
                Text(
                    text = password,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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
            TextButton(
                onClick = { if (onCopy()) copied = true },
                modifier = Modifier.handCursor(),
            ) {
                Text(stringResource(Res.string.pots_build_web_panel_password_copy))
            }
            if (copied) {
                Text(
                    text = stringResource(Res.string.pots_build_web_panel_password_copied),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** How long "Copied" stays on screen — long enough to read, short enough not to linger. */
private const val COPY_CONFIRMATION_MS = 2_000L
