package com.pusios.pots.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import com.pusios.pots.data.AppSettings
import com.pusios.pots.data.VectorizationManager
import com.pusios.pots.mcp.PopularMcpServer
import com.pusios.pots.network.tools.ToolInfo
import com.pusios.pots.skills.RegistrySkillEntry
import com.pusios.pots.skills.SkillManifest
import com.pusios.pots.tools.ObsidianVaultPicker
import com.pusios.pots.ui.handCursor
import com.pusios.pots.ui.potsAdaptiveCardBorder
import com.pusios.pots.ui.potsAdaptiveCardColors
import com.pusios.pots.ui.rememberPermissionLauncher
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.settings_obsidian_vault
import pots.composeapp.generated.resources.settings_obsidian_vault_configured
import pots.composeapp.generated.resources.settings_obsidian_vault_description
import pots.composeapp.generated.resources.settings_obsidian_vault_not_configured
import pots.composeapp.generated.resources.settings_tools_description
import pots.composeapp.generated.resources.settings_tools_none_available

@Composable
internal fun ToolsContent(
    tools: ImmutableList<ToolInfo>,
    onToggleTool: (String, Boolean) -> Unit,
    mcpServers: ImmutableList<McpServerUiState>,
    onAddMcpServer: (String, String, Map<String, String>) -> Unit,
    onRemoveMcpServer: (String) -> Unit,
    onToggleMcpServer: (String, Boolean) -> Unit,
    onRefreshMcpServer: (String) -> Unit,
    showAddMcpServerDialog: Boolean,
    onShowAddMcpServerDialog: (Boolean) -> Unit,
    onAddPopularMcpServer: (PopularMcpServer) -> Unit,
    skills: ImmutableList<SkillManifest>,
    onUninstallSkill: (String) -> Unit,
    showAddSkillDialog: Boolean,
    onShowAddSkillDialog: (Boolean) -> Unit,
    onInstallGitHubSkill: (String) -> Unit,
    onInstallBrowsedSkill: (RegistrySkillEntry) -> Unit,
    isInstallingSkill: Boolean,
    skillInstallError: String?,
    browsableSkills: ImmutableList<RegistrySkillEntry>,
    isBrowsingSkills: Boolean,
    browseSkillsFailed: Boolean,
    showSkills: Boolean,
    isSandboxInstalled: Boolean,
    onNavigateToSandbox: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        val appSettings = if (LocalInspectionMode.current) null else koinInject<AppSettings>()
        var obsidianVaultUri by remember(appSettings) { mutableStateOf(appSettings?.getObsidianVaultUri().orEmpty()) }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = stringResource(Res.string.settings_obsidian_vault), style = MaterialTheme.typography.titleMedium)
                Text(text = stringResource(Res.string.settings_obsidian_vault_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = if (obsidianVaultUri.isBlank()) {
                        stringResource(Res.string.settings_obsidian_vault_not_configured)
                    } else {
                        stringResource(Res.string.settings_obsidian_vault_configured, obsidianVaultUri.substringAfterLast('/'))
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                ObsidianVaultPicker(currentUri = obsidianVaultUri) { uri ->
                    if (uri != null) {
                        appSettings?.setObsidianVaultUri(uri)
                        obsidianVaultUri = uri
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        if (!LocalInspectionMode.current) VectorStorageSection()

        Spacer(Modifier.height(24.dp))

        // MCP Servers section
        McpServersSection(
            mcpServers = mcpServers,
            onAddMcpServer = onAddMcpServer,
            onRemoveMcpServer = onRemoveMcpServer,
            onToggleMcpServer = onToggleMcpServer,
            onRefreshMcpServer = onRefreshMcpServer,
            onToggleTool = onToggleTool,
            showAddDialog = showAddMcpServerDialog,
            onShowAddDialog = onShowAddMcpServerDialog,
            onAddPopularMcpServer = onAddPopularMcpServer,
        )

        // Skills section — sandbox-backed, so Android only.
        if (showSkills) {
            Spacer(Modifier.height(24.dp))
            SkillsSection(
                skills = skills,
                onUninstallSkill = onUninstallSkill,
                showAddDialog = showAddSkillDialog,
                onShowAddDialog = onShowAddSkillDialog,
                onInstallGitHub = onInstallGitHubSkill,
                onInstallBrowsed = onInstallBrowsedSkill,
                isInstalling = isInstallingSkill,
                installError = skillInstallError,
                browsableSkills = browsableSkills,
                isBrowsing = isBrowsingSkills,
                browseFailed = browseSkillsFailed,
                isSandboxInstalled = isSandboxInstalled,
                onNavigateToSandbox = onNavigateToSandbox,
            )
        }

        Spacer(Modifier.height(24.dp))

        // Native tools section
        Text(
            text = stringResource(Res.string.settings_tools_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))

        if (tools.isEmpty()) {
            Text(
                text = stringResource(Res.string.settings_tools_none_available),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val columns = when {
                    maxWidth >= 800.dp -> 3
                    maxWidth >= 500.dp -> 2
                    else -> 1
                }
                val rows = tools.chunked(columns)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    rows.forEach { rowTools ->
                        Row(
                            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            rowTools.forEach { tool ->
                                ToolItem(
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                    tool = tool,
                                    onToggle = { enabled -> onToggleTool(tool.id, enabled) },
                                )
                            }
                            // Fill empty slots so last row items don't stretch
                            repeat(columns - rowTools.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VectorStorageSection() {
    if (LocalInspectionMode.current) return

    val manager: VectorizationManager = koinInject()
    val scope = rememberCoroutineScope()
    val isReady by manager.isModelReady.collectAsState()
    val isIndexing by manager.isIndexing.collectAsState()
    val isPaused by manager.isPaused.collectAsState()
    val progress by manager.progress.collectAsState()
    val status by manager.status.collectAsState()
    val autoPhotos by manager.autoIndexPhotos.collectAsState()
    val autoDocs by manager.autoIndexDocuments.collectAsState()
    val indexedPhotos by manager.indexedPhotoCount.collectAsState()
    val indexedDocs by manager.indexedDocumentCount.collectAsState()
    val currentTotal by manager.currentTotalCount.collectAsState()
    val currentProcessed by manager.currentProcessedCount.collectAsState()
    val needsPhotoPermission by manager.needsPhotoPermission.collectAsState()
    val needsDocumentPermission by manager.needsDocumentPermission.collectAsState()
    val indexStale by manager.indexStale.collectAsState()
    val requestPermission = rememberPermissionLauncher { granted ->
        if (granted) {
            scope.launch {
                if (needsPhotoPermission) manager.indexPhotos()
                if (needsDocumentPermission) manager.indexDocuments()
            }
        }
    }

    val localInferenceEngine = koinInject<com.pusios.pots.inference.LocalInferenceEngine>()
    val downloadingId by localInferenceEngine.downloadingModelId.collectAsState()
    val downloadProgress by localInferenceEngine.downloadProgress.collectAsState()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "Локальный векторный поиск", style = MaterialTheme.typography.titleMedium)
            Text(text = "Скачайте модель один раз — и ассистент сможет искать по вашим фото и документам. Например: «найди фото с котом», «где договор аренды», «покажи скриншоты с кодом».", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            var detailsExpanded by remember { mutableStateOf(false) }
            androidx.compose.material3.TextButton(onClick = { detailsExpanded = !detailsExpanded }) {
                Text(if (detailsExpanded) "Скрыть подробности" else "Подробнее")
            }
            if (detailsExpanded) {
                Text(text = "Модель работает на устройстве: фото и тексты превращаются в числовые векторы и сохраняются в локальной базе. Индексация идёт в фоне, можно закрыть экран. Включите автоиндексацию, чтобы новые фото и документы добавлялись автоматически. Затем спрашивайте ассистента о своих файлах — он найдёт нужное по смыслу, а не только по имени.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (!isReady) {
                Text(text = "Модель не загружена", style = MaterialTheme.typography.bodyMedium)
                if (downloadingId == "embeddinggemma-2-text-vision-440m") {
                    LinearProgressIndicator(progress = { downloadProgress ?: 0f }, modifier = Modifier.fillMaxWidth())
                    Text(text = "${((downloadProgress ?: 0f) * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                } else {
                    androidx.compose.material3.TextButton(onClick = { manager.downloadModel() }) {
                        Text("Скачать модель")
                    }
                }
            } else {
                Text(text = "Модель готова", style = MaterialTheme.typography.bodyMedium)

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Автоиндексация фото", modifier = Modifier.weight(1f))
                    Switch(checked = autoPhotos, onCheckedChange = { manager.setAutoIndexPhotos(it) })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Автоиндексация документов", modifier = Modifier.weight(1f))
                    Switch(checked = autoDocs, onCheckedChange = { manager.setAutoIndexDocuments(it) })
                }

                if (needsPhotoPermission) {
                    androidx.compose.material3.TextButton(onClick = { requestPermission(manager.requiredPhotoPermission) }) {
                        Text("Разрешить доступ к фото")
                    }
                }
                if (needsDocumentPermission) {
                    androidx.compose.material3.TextButton(onClick = { requestPermission(manager.requiredDocumentPermission) }) {
                        Text("Разрешить доступ к документам")
                    }
                }

                if (isIndexing) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        androidx.compose.material3.TextButton(onClick = {
                            if (isPaused) manager.resumeIndexing() else manager.pauseIndexing()
                        }) {
                            Text(if (isPaused) "Продолжить" else "Пауза")
                        }
                        androidx.compose.material3.TextButton(onClick = { manager.cancelIndexing() }) {
                            Text("Остановить")
                        }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        androidx.compose.material3.TextButton(onClick = { scope.launch { manager.indexPhotos() } }, enabled = !needsPhotoPermission) {
                            Text("Индексировать фото")
                        }
                        androidx.compose.material3.TextButton(onClick = { scope.launch { manager.indexDocuments() } }, enabled = !needsDocumentPermission) {
                            Text("Индексировать документы")
                        }
                    }
                }

                Text(text = "Фото в базе: $indexedPhotos", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(text = "Документов в базе: $indexedDocs", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                if (isIndexing) {
                    LinearProgressIndicator(progress = { progress ?: 0f }, modifier = Modifier.fillMaxWidth())
                    if (currentTotal > 0) {
                        Text(text = "Обработано: $currentProcessed / $currentTotal", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (indexStale) {
                    Text(text = "Индекс устарел — нужна переиндексация", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    androidx.compose.material3.TextButton(onClick = { scope.launch { manager.indexPhotos() } }, enabled = !isIndexing) {
                        Text("Пересобрать индекс")
                    }
                }
                if (status.isNotBlank()) {
                    Text(text = status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ToolItem(
    tool: ToolInfo,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .clip(CardDefaults.shape)
            .clickable { onToggle(!tool.isEnabled) }
            .handCursor(),
        colors = potsAdaptiveCardColors(),
        border = potsAdaptiveCardBorder(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = tool.nameRes?.let { stringResource(it) } ?: tool.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = tool.descriptionRes?.let { stringResource(it) } ?: tool.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.width(16.dp))

            Switch(
                checked = tool.isEnabled,
                onCheckedChange = onToggle,
            )
        }
    }
}
