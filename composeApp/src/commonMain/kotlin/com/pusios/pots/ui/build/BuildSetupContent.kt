package com.pusios.pots.ui.build

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.sp
import com.pusios.pots.build.BuildAgents
import com.pusios.pots.build.BuildEnvironmentState
import com.pusios.pots.build.BuildStep
import com.pusios.pots.build.INSTALL_LOG_MAX_CHARS
import com.pusios.pots.build.PotsBuildState
import com.pusios.pots.ui.handCursor
import com.pusios.pots.ui.sandbox.SandboxProgressRow
import com.pusios.pots.ui.settings.SettingsCard
import kotlinx.collections.immutable.ImmutableSet
import org.jetbrains.compose.resources.stringResource
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.pots_build_install_log_title
import pots.composeapp.generated.resources.pots_build_install_log_truncated
import pots.composeapp.generated.resources.pots_build_setup_agents_description
import pots.composeapp.generated.resources.pots_build_setup_agents_title
import pots.composeapp.generated.resources.pots_build_setup_description
import pots.composeapp.generated.resources.pots_build_setup_install
import pots.composeapp.generated.resources.pots_build_setup_title
import pots.composeapp.generated.resources.pots_build_step_base_packages
import pots.composeapp.generated.resources.pots_build_step_configure
import pots.composeapp.generated.resources.pots_build_step_download
import pots.composeapp.generated.resources.pots_build_step_extract
import pots.composeapp.generated.resources.pots_build_step_install_agent

/**
 * First screen a user lands on: install Debian, optionally with coding agents.
 * Doubles as the progress surface for later agent installs.
 */
@Composable
internal fun BuildSetupContent(
    state: PotsBuildState,
    selectedAgents: ImmutableSet<String>,
    onToggleAgent: (String) -> Unit,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val installing = state.environment as? BuildEnvironmentState.Installing

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(Res.string.pots_build_setup_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = stringResource(Res.string.pots_build_setup_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SettingsCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(Res.string.pots_build_setup_agents_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(Res.string.pots_build_setup_agents_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            BuildAgents.all.forEach { agent ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = installing == null) { onToggleAgent(agent.id) }
                        .handCursor(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = agent.id in selectedAgents || agent.id in state.installedAgents,
                        onCheckedChange = { onToggleAgent(agent.id) },
                        enabled = installing == null && agent.id !in state.installedAgents,
                    )
                    Text(
                        text = agent.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        if (installing != null) {
            SandboxProgressRow(
                progress = installing.progress,
                statusText = stepLabel(installing),
                onCancel = onCancel,
            )
        } else {
            Button(onClick = onInstall, modifier = Modifier.handCursor()) {
                Text(stringResource(Res.string.pots_build_setup_install))
            }
        }

        state.lastError?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        // Under the progress bar while it runs, and left in place afterwards:
        // a failure's log is the reason the error line above cannot give.
        if (state.installLog.isNotEmpty()) {
            InstallLogPane(log = state.installLog, dropped = state.installLogDropped)
        }
    }
}

/**
 * The installer's console, as far as the last [INSTALL_LOG_MAX_CHARS] reach.
 *
 * Shared by both surfaces an install can end on: the setup screen while it runs,
 * and the project list, where a failure lands because a ready Debian is what the
 * screen switches to. A failure that shows its reason on only one of them would
 * be missing exactly where the owner sees it.
 *
 * Selection is on so a failure can be copied out of the app; the view follows
 * the tail until the reader scrolls up, because a live log that keeps pulling
 * itself down cannot be read while an install is running.
 */
@Composable
internal fun InstallLogPane(log: String, dropped: Boolean) {
    val scroll = rememberScrollState()
    var followTail by remember { mutableStateOf(true) }
    // Recomputed from the scroll state rather than from drag gestures: this also
    // covers a fling, and it is the same rule for a mouse wheel on desktop.
    LaunchedEffect(scroll.value, scroll.maxValue) {
        followTail = scroll.value >= scroll.maxValue - FOLLOW_TAIL_SLOP_PX
    }
    LaunchedEffect(log) {
        if (followTail) scroll.scrollTo(scroll.maxValue)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(8.dp),
    ) {
        Text(
            text = stringResource(Res.string.pots_build_install_log_title),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (dropped) {
            Text(
                text = stringResource(
                    Res.string.pots_build_install_log_truncated,
                    INSTALL_LOG_MAX_CHARS / 1024,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(INSTALL_LOG_HEIGHT)
                .verticalScroll(scroll),
        ) {
            SelectionContainer {
                Text(
                    text = log,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** Tall enough for a failure's last lines, short enough to leave the button visible. */
private val INSTALL_LOG_HEIGHT = 148.dp

/** Within this many pixels of the end counts as "following the tail". */
private const val FOLLOW_TAIL_SLOP_PX = 24

/**
 * The Compose resource formatter substitutes `%1$d` but passes the `%%` escape through verbatim,
 * so `"%1$d%%"` would render as `42%%`. No-op once the formatter unescapes it itself.
 */
internal fun String.collapsePercentEscape(): String = replace("%%", "%")

@Composable
private fun stepLabel(state: BuildEnvironmentState.Installing): String = when (state.step) {
    BuildStep.Download -> stringResource(
        Res.string.pots_build_step_download,
        ((state.progress ?: 0f) * 100).toInt(),
    ).collapsePercentEscape()
    BuildStep.Extract -> stringResource(Res.string.pots_build_step_extract)
    BuildStep.Configure -> stringResource(Res.string.pots_build_step_configure)
    BuildStep.BasePackages -> stringResource(Res.string.pots_build_step_base_packages)
    BuildStep.Agent -> stringResource(
        Res.string.pots_build_step_install_agent,
        BuildAgents.get(state.agentId)?.title.orEmpty(),
    )
}
