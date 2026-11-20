package com.pusios.pots.ui.build

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.pusios.pots.build.WebPanel
import com.pusios.pots.build.WebPanelLog
import com.pusios.pots.build.WebPanelOpen
import com.pusios.pots.build.WebPanelState
import com.pusios.pots.build.WebPanelStatus
import com.pusios.pots.build.WebPanels
import com.pusios.pots.ui.handCursor
import com.pusios.pots.ui.rememberCopyToClipboard
import com.pusios.pots.ui.settings.SettingsCard
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.pots_build_web_panel_autostart
import pots.composeapp.generated.resources.pots_build_web_panel_hermes_lan_hint
import pots.composeapp.generated.resources.pots_build_web_panel_hermes_link_hint
import pots.composeapp.generated.resources.pots_build_web_panel_hermes_local_hint
import pots.composeapp.generated.resources.pots_build_web_panel_log
import pots.composeapp.generated.resources.pots_build_web_panel_log_empty
import pots.composeapp.generated.resources.pots_build_web_panel_log_loading
import pots.composeapp.generated.resources.pots_build_web_panel_log_missing
import pots.composeapp.generated.resources.pots_build_web_panel_log_refresh
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
    onLog: suspend (String) -> String,
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
                onLog = onLog,
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
    onLog: suspend (String) -> String,
) {
    // The password is fetched when the button is tapped, not while composing: the
    // getter reads stored settings, and composing must stay free of side effects.
    var revealedPassword by remember(panel.id, state.status) { mutableStateOf<String?>(null) }
    // Sensitive: the system keeps the clip out of its history and out of the paste
    // preview, so a copied password is not left lying around for the next app.
    val copyToClipboard = rememberCopyToClipboard(sensitive = true)

    // The log follows the same rule: read on the row's own actions — the section
    // being opened, a failed start, the refresh button — and never while composing.
    // It is not part of WebPanelState either: that is the polled state, and the log
    // grows while the panel starts.
    var logOpen by remember(panel.id) { mutableStateOf(false) }
    var log by remember(panel.id) { mutableStateOf<String?>(null) }
    val logScroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val loadLog: () -> Unit = { scope.launch { log = onLog(panel.id) } }
    // A failure is exactly when the log is wanted, so the section opens itself.
    // Keyed on the status: a refresh that reports the same failure again must not
    // reopen a section the owner just closed, and no other status touches it.
    LaunchedEffect(state.status) {
        if (state.status is WebPanelStatus.Failed) {
            logOpen = true
            log = onLog(panel.id)
        }
    }

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
                    // Nothing to explain: the tap mints a pairing code and the
                    // browser lands signed in. Wording for the row is the buttons
                    // card's, not this one's.
                    WebPanelOpen.PAIRING_CODE -> Unit
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

        // The panel's own log, folded away by default. It is the only thing that
        // tells "never started" from "started but serves nothing" — the two look
        // the same in a browser — and it is long, so it stays shut until asked for.
        PanelLogSection(
            open = logOpen,
            log = log,
            scrollState = logScroll,
            onToggle = {
                logOpen = !logOpen
                if (logOpen) loadLog()
            },
            onRefresh = loadLog,
        )

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

/** How tall the log pane gets before it scrolls; about a dozen lines on a phone. */
private val LOG_PANE_MAX_HEIGHT = 220.dp

/**
 * The panel's log, under the address and the password: a fold marker and a
 * refresh button on one line, the log itself in a fixed-height pane that scrolls.
 *
 * Read line by line, the way a log is: newlines are kept and the text wraps only
 * when a line is wider than the pane. The scroll state is passed in rather than
 * remembered here, so folding the section away and opening it again lands back
 * where the reader was.
 */
@Composable
private fun PanelLogSection(
    open: Boolean,
    /** Null until the first read lands. */
    log: String?,
    scrollState: ScrollState,
    onToggle: () -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onToggle)
                .handCursor(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = if (open) PanelLogChevronDown else PanelLogChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = stringResource(Res.string.pots_build_web_panel_log),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (open) {
            // The log grows while the panel starts, and the pane is a snapshot:
            // one button beats a poll nobody asked for.
            TextButton(onClick = onRefresh, modifier = Modifier.handCursor()) {
                Text(stringResource(Res.string.pots_build_web_panel_log_refresh))
            }
        }
    }
    if (open) {
        Text(
            text = logText(log),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            softWrap = true,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = LOG_PANE_MAX_HEIGHT)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                .verticalScroll(scrollState)
                .padding(8.dp),
        )
    }
}

/**
 * What the pane says. An empty pane reads as a broken section, so the two ways of
 * having nothing to show are named in the row's own language; everything else is
 * the log as the guest handed it over.
 */
@Composable
private fun logText(log: String?): String = when (log) {
    null -> stringResource(Res.string.pots_build_web_panel_log_loading)
    WebPanelLog.NO_FILE -> stringResource(Res.string.pots_build_web_panel_log_missing)
    WebPanelLog.EMPTY -> stringResource(Res.string.pots_build_web_panel_log_empty)
    else -> log
}

// The fold marker is drawn, not typed: "▸"/"▾" are text and come out at whatever
// weight the fallback font has, sized by the caption rather than the icon. Tinted
// with the row's own colour (currentColor, in Compose terms), like the other
// hand-drawn glyphs in this app.
private fun chevron(expanded: Boolean): ImageVector = ImageVector.Builder(
    name = if (expanded) "PanelLogChevronDown" else "PanelLogChevronRight",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    group(rotate = if (expanded) 90f else 0f, pivotX = 12f, pivotY = 12f) {
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2.5f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(9f, 5f)
            lineTo(16f, 12f)
            lineTo(9f, 19f)
        }
    }
}.build()

private val PanelLogChevronRight: ImageVector by lazy { chevron(expanded = false) }
private val PanelLogChevronDown: ImageVector by lazy { chevron(expanded = true) }
