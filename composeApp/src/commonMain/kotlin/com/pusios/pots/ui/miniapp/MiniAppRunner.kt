package com.pusios.pots.ui.miniapp

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pusios.pots.PlatformBackHandler
import com.pusios.pots.data.miniapp.MiniApp
import com.pusios.pots.data.miniapp.MiniAppKind
import com.pusios.pots.data.miniapp.MiniAppRepository
import com.pusios.pots.data.miniapp.MiniAppServer
import com.pusios.pots.openUrl
import com.pusios.pots.ui.dynamicui.LocalMiniAppRuntime
import com.pusios.pots.ui.dynamicui.PotsUiParser
import com.pusios.pots.ui.dynamicui.PotsUiRenderer
import com.pusios.pots.ui.handCursor
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.mini_app_automations
import pots.composeapp.generated.resources.mini_app_close
import pots.composeapp.generated.resources.mini_app_needs_local_action
import pots.composeapp.generated.resources.mini_app_no_screens
import pots.composeapp.generated.resources.mini_app_open_editor
import pots.composeapp.generated.resources.mini_app_run_automation

/**
 * A saved app, running on its own.
 *
 * The screen is the last one the agent produced; local `store_put` /
 * `store_delete` / `navigate` actions work without the network, and records
 * come from the same SQLite store the app was built against. Anything that
 * needs the agent — an automation or a plain callback — leaves the runner and
 * opens a fresh interactive session seeded with this app.
 */
@Composable
fun MiniAppRunnerScreen(
    appId: String,
    onExit: () -> Unit,
    onEdit: (String) -> Unit,
    onRunAutomation: (appId: String, automationId: String) -> Unit,
    onRunAgent: (appId: String, prompt: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val repository: MiniAppRepository = koinInject()
    val server: MiniAppServer = koinInject()
    val isPreview = LocalInspectionMode.current
    PlatformBackHandler(enabled = true) { onExit() }
    val apps by repository.apps.collectAsStateWithLifecycle()
    val app = apps.firstOrNull { it.id == appId }

    // A web app is a folder served over loopback; opening it is the whole
    // launch flow. Native apps continue into the runner below.
    LaunchedEffect(app?.id, app?.kind, app?.entryPoint) {
        val current = app ?: return@LaunchedEffect
        val entry = current.entryPoint
        if (!isPreview && current.kind == MiniAppKind.WEB && !entry.isNullOrBlank()) {
            server.start()
            openUrl("${server.state.value.url}/apps/${current.id}/$entry")
            onExit()
        }
    }

    if (app == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(Res.string.mini_app_no_screens))
        }
        return
    }

    var currentScreenId by rememberSaveable(appId) { mutableStateOf(app.latestScreen?.id) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val notice = stringResource(Res.string.mini_app_needs_local_action)
    val backStack = remember(appId) { mutableStateListOf<String>() }
    val session = remember(app.id) {
        MiniAppSession(
            appId = app.id,
            repository = repository,
            onNavigate = { target ->
                val index = app.screens.indexOfFirst { it.id == target || it.title == target }
                if (index >= 0) {
                    currentScreenId?.let { backStack.add(it) }
                    currentScreenId = app.screens[index].id
                }
            },
        )
    }
    val screen = app.screens.firstOrNull { it.id == currentScreenId } ?: app.latestScreen

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onExit, modifier = Modifier.handCursor()) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(Res.string.mini_app_close))
                }
                Text(
                    text = app.icon.ifBlank { "🧩" },
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.size(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(app.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    if (app.description.isNotBlank()) {
                        Text(
                            text = app.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                IconButton(onClick = { onEdit(app.id) }, modifier = Modifier.handCursor()) {
                    Icon(Icons.Default.Edit, contentDescription = stringResource(Res.string.mini_app_open_editor))
                }
            }

            if (app.screens.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    app.screens.forEachIndexed { index, item ->
                        val selected = item.id == screen?.id
                        Surface(
                            modifier = Modifier.clip(RoundedCornerShape(50)).handCursor(),
                            shape = RoundedCornerShape(50),
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                            },
                            onClick = {
                                if (!selected) {
                                    currentScreenId?.let { backStack.add(it) }
                                    currentScreenId = item.id
                                }
                            },
                        ) {
                            Text(
                                text = item.title.ifBlank { "Screen ${index + 1}" },
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (screen == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(Res.string.mini_app_no_screens))
                    }
                } else {
                    val node = remember(screen.potsUiJson) {
                        (PotsUiParser.parseUiBlockBody(screen.potsUiJson) as? PotsUiParser.UiBlockResult.Ui)?.node
                    }
                    CompositionLocalProvider(LocalMiniAppRuntime provides session) {
                        if (node == null) {
                            Text(screen.potsUiJson, modifier = Modifier.padding(16.dp))
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                PotsUiRenderer(
                                    node = node,
                                    isInteractive = true,
                                    onCallback = { event, data ->
                                        // A saved app runs without the model unless it
                                        // declared that its own code uses it.
                                        if (app.capabilities.contains("llm")) {
                                            onRunAgent(
                                                app.id,
                                                "The user pressed \"$event\" in the mini-app \"${app.name}\"" +
                                                    if (data.isNotEmpty()) " with: $data." else ".",
                                            )
                                        } else {
                                            scope.launch { snackbar.showSnackbar(notice) }
                                        }
                                    },
                                    wrapInCard = false,
                                )
                                if (app.automations.isNotEmpty()) {
                                    AutomationsCard(
                                        app = app,
                                        onRun = { automation -> onRunAutomation(app.id, automation.id) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            SnackbarHost(hostState = snackbar, modifier = Modifier.padding(horizontal = 16.dp))
        }
    }
}

@Composable
private fun AutomationsCard(
    app: MiniApp,
    onRun: (com.pusios.pots.data.miniapp.MiniAppAutomation) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(Res.string.mini_app_automations), style = MaterialTheme.typography.titleSmall)
            app.automations.forEach { automation ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(automation.title, style = MaterialTheme.typography.bodyMedium)
                        automation.cron?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    TextButton(onClick = { onRun(automation) }, modifier = Modifier.handCursor()) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.size(4.dp))
                        Text(stringResource(Res.string.mini_app_run_automation))
                    }
                }
            }
        }
    }
}
