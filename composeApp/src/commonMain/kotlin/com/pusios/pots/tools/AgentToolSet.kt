package com.pusios.pots.tools

import com.pusios.pots.data.AppSettings
import com.pusios.pots.data.EmailStore
import com.pusios.pots.data.MemoryStore
import com.pusios.pots.data.TaskStore
import com.pusios.pots.data.miniapp.MiniAppRepository
import com.pusios.pots.mcp.McpServerManager
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

    platformExtras()

    addAll(mcpServerManager.getEnabledMcpTools(reservedNames = mapTo(HashSet()) { it.schema.name }))
}
