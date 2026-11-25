package com.pusios.pots.tools

import com.pusios.pots.adb.AdbToolDependencies
import com.pusios.pots.adb.AdbTools
import com.pusios.pots.data.AppSettings
import com.pusios.pots.data.Attachment
import com.pusios.pots.data.EmailStore
import com.pusios.pots.data.MemoryStore
import com.pusios.pots.data.TaskStore
import com.pusios.pots.data.miniapp.MiniAppRepository
import com.pusios.pots.data.vault.VaultToolDependencies
import com.pusios.pots.data.vault.VaultTools
import com.pusios.pots.mcp.McpServerManager
import com.pusios.pots.media.ActiveMediaProvider
import com.pusios.pots.media.activeMediaService
import com.pusios.pots.network.tools.Tool
import com.pusios.pots.ui.potsik.PotsikStage

/**
 * The tool set every platform builds, in the order the agent receives it.
 *
 * Each platform's `getAvailableTools()` used to repeat this gating block verbatim, which is how
 * they drifted — Android in particular had re-implemented the common-tool checks inline instead
 * of calling [CommonTools.getCommonTools]. Platform-specific tools are contributed through
 * [platformExtras]; MCP tools stay last everywhere.
 */
fun buildAgentToolSet(
    appSettings: AppSettings,
    memoryStore: MemoryStore,
    taskStore: TaskStore,
    mcpServerManager: McpServerManager,
    // The character's stage: the tool the agent uses to move him around.
    potsikStage: PotsikStage,
    // Web has no email support and injects no store.
    emailStore: EmailStore? = null,
    // The app editor's data store. Injected so the tool and the runner share
    // one SQLite store; null in environments without it.
    miniAppRepository: MiniAppRepository? = null,
    // Image attachments of the current chat: media tools use the last photo
    // when the agent passes reference_image="attachment".
    attachmentProvider: () -> List<Attachment> = { emptyList() },
    // The encrypted vault's session and bridges; null in tests without Koin.
    vault: VaultToolDependencies? = null,
    // ADB over the local network; null on every platform without an ADB backend.
    adb: AdbToolDependencies? = null,
    platformExtras: MutableList<Tool>.() -> Unit = {},
): List<Tool> = buildList {
    addAll(CommonTools.getCommonTools(appSettings))

    if (appSettings.isMemoryEnabled()) {
        addAll(CommonTools.getMemoryTools(memoryStore))
    }

    // Heartbeat tools ride the scheduling switch — they are only reachable from a scheduled run.
    if (appSettings.isSchedulingEnabled()) {
        addAll(SchedulingTools.getSchedulingTools(taskStore))
        addAll(HeartbeatTools.getHeartbeatTools(memoryStore, appSettings))
    }

    if (emailStore != null && appSettings.isEmailEnabled()) {
        addAll(EmailTools.getEmailTools(emailStore))
    }

    // The mini-app designer rides the Dynamic UI switch: without pots-ui there
    // is no app to give a schema to.
    if (appSettings.isDynamicUiEnabled() && miniAppRepository != null) {
        add(MiniAppDbTool.create(miniAppRepository))
    }

    // The character's own tool. Off unless the user asks for it.
    if (appSettings.isToolEnabled(PotsikTool.ID, defaultEnabled = false)) {
        add(PotsikTool.create(potsikStage))
    }

    // Media generation tools. Provider, key and model defaults all come from the
    // active media service (Settings → Services → «Сервис для медиатеки»),
    // resolved on every call so switching it applies without rebuilding the set.
    val mediaProvider = ActiveMediaProvider(appSettings)
    val mediaApiKey = { activeMediaService(appSettings)?.apiKey.orEmpty() }
    val mediaInstanceId = { activeMediaService(appSettings)?.instanceId.orEmpty() }
    if (appSettings.isToolEnabled(MediaTools.ID_LIST_MODELS, defaultEnabled = true)) {
        add(MediaTools.listModelsTool(apiKey = mediaApiKey, provider = mediaProvider))
    }
    if (appSettings.isToolEnabled(MediaTools.ID_ENHANCE_PROMPT, defaultEnabled = true)) {
        add(MediaTools.enhancePromptTool())
    }
    if (appSettings.isToolEnabled(MediaTools.ID_GENERATE_IMAGE, defaultEnabled = true)) {
        add(
            MediaTools.generateImageTool(
                appSettings = appSettings,
                apiKey = mediaApiKey,
                instanceId = mediaInstanceId,
                provider = mediaProvider,
                attachmentProvider = attachmentProvider,
            ),
        )
    }
    if (appSettings.isToolEnabled(MediaTools.ID_GENERATE_VIDEO, defaultEnabled = true)) {
        add(
            MediaTools.generateVideoTool(
                appSettings = appSettings,
                apiKey = mediaApiKey,
                instanceId = mediaInstanceId,
                provider = mediaProvider,
            ),
        )
    }

    // Password vault tools. One master toggle, on by default — the owner can turn the
    // whole set off from the Tools tab; the spend-tools additionally ask the owner on
    // every use. Null where Koin has no vault session.
    if (vault != null && appSettings.isToolEnabled(VaultTools.MASTER_ID, defaultEnabled = true)) {
        addAll(VaultTools.create(vault))
    }

    // ADB devices. One master toggle, OFF by default: reaching into another device is a
    // privilege the owner grants deliberately in Settings → ADB devices.
    if (adb != null && appSettings.isToolEnabled(AdbTools.MASTER_ID, defaultEnabled = false)) {
        addAll(AdbTools.create(adb))
    }

    platformExtras()

    addAll(mcpServerManager.getEnabledMcpTools(reservedNames = mapTo(HashSet()) { it.schema.name }))
}
