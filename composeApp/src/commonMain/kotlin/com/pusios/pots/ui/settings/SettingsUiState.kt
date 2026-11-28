package com.pusios.pots.ui.settings

import androidx.compose.runtime.Immutable
import com.pusios.pots.assistant.AssistantAvailability
import com.pusios.pots.data.AgentProfile
import com.pusios.pots.data.AgentTuning
import com.pusios.pots.data.EmailAccount
import com.pusios.pots.data.EmailSyncState
import com.pusios.pots.data.HeartbeatLogEntry
import com.pusios.pots.data.HeartbeatRepeatWarning
import com.pusios.pots.data.LocalToolSelection
import com.pusios.pots.data.MemoryEntry
import com.pusios.pots.data.ProviderBalance
import com.pusios.pots.data.ScheduledTask
import com.pusios.pots.data.Service
import com.pusios.pots.data.ServiceEntry
import com.pusios.pots.data.SmsSyncState
import com.pusios.pots.data.ThemeMode
import com.pusios.pots.inference.DownloadError
import com.pusios.pots.inference.LocalModel
import com.pusios.pots.inference.ModelImportError
import com.pusios.pots.mcp.McpConfirmRequest
import com.pusios.pots.mcp.McpFailureKind
import com.pusios.pots.mcp.McpServerConfig
import com.pusios.pots.mcp.McpServerStateKind
import com.pusios.pots.mcp.McpServerStatus
import com.pusios.pots.network.dtos.SponsorsResponseDto
import com.pusios.pots.network.tools.ToolInfo
import com.pusios.pots.skills.RegistrySkillEntry
import com.pusios.pots.skills.SkillManifest
import com.pusios.pots.ui.potsik.PotsikLook
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import org.jetbrains.compose.resources.StringResource

@Immutable
data class ConfiguredServiceEntry(
    val instanceId: String,
    val service: Service,
    val connectionStatus: ConnectionStatus = ConnectionStatus.Unknown,
    val apiKey: String = "",
    val baseUrl: String = "",
    val selectedModel: SettingsModel? = null,
    val models: ImmutableList<SettingsModel> = persistentListOf(),
    /** OpenAI-Compatible only: when true, [customModelId] is used for chat instead of [selectedModel]. */
    val useCustomModel: Boolean = false,
    val customModelId: String = "",
    /** Prepaid account balance, shown in the card header when the provider exposes one. */
    val balance: ProviderBalance? = null,
)

enum class ConnectionStatus {
    Unknown,
    Checking,
    Connected,
    ErrorInvalidKey,
    ErrorQuotaExhausted,
    ErrorRateLimited,
    ErrorConnectionFailed,
    ErrorLocalNetworkDenied,
    Error,
}

enum class SettingsTab {
    General,
    Agent,
    Services,
    Tools,
    Vault,
    Adb,
    Sandbox,
    Integrations,
    Potsiki,
    Companion,
}

@Immutable
data class SettingsUiState(
    val currentTab: SettingsTab = SettingsTab.Services,
    val configuredServices: ImmutableList<ConfiguredServiceEntry> = persistentListOf(),
    val expandedServiceId: String? = null,
    val availableServicesToAdd: ImmutableList<Service> = persistentListOf(),
    val tools: ImmutableList<ToolInfo> = persistentListOf(),
    val soulText: String = "",
    val agentProfiles: ImmutableList<AgentProfile> = persistentListOf(),
    val activeAgentProfileId: String = "default",
    val activeProfileSkillIds: ImmutableSet<String> = persistentSetOf(),
    val activeProfileSkillSelectionConfigured: Boolean = false,
    val agentTuning: AgentTuning = AgentTuning.Default,
    val isDynamicUiEnabled: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.System,
    val appLanguage: com.pusios.pots.data.AppLanguage = com.pusios.pots.data.AppLanguage.System,
    val animatedBackground: com.pusios.pots.ui.background.AnimatedBackground = com.pusios.pots.ui.background.AnimatedBackground.NONE,
    /** How the animated character (Потсик) looks. */
    val potsikLook: PotsikLook = PotsikLook.Default,
    val isMemoryEnabled: Boolean = true,
    val memories: ImmutableList<MemoryEntry> = persistentListOf(),
    val isSchedulingEnabled: Boolean = true,
    val scheduledTasks: ImmutableList<ScheduledTask> = persistentListOf(),
    val isDaemonEnabled: Boolean = false,
    val showDaemonToggle: Boolean = false,
    val isHeartbeatEnabled: Boolean = true,
    val heartbeatIntervalMinutes: Int = 30,
    val heartbeatActiveHoursStart: Int = 8,
    val heartbeatActiveHoursEnd: Int = 22,
    val heartbeatPrompt: String = "",
    val heartbeatLog: ImmutableList<HeartbeatLogEntry> = persistentListOf(),
    /** Set when the pulse keeps delivering the same report; the settings card says so. */
    val heartbeatRepeatWarning: HeartbeatRepeatWarning? = null,
    val heartbeatServiceEntries: ImmutableList<ServiceEntry> = persistentListOf(),
    val heartbeatSelectedInstanceId: String? = null,
    val heartbeatFallbackInstanceId: String? = null,
    val isRefreshingHeartbeat: Boolean = false,
    val isEmailEnabled: Boolean = true,
    val showEmailToggle: Boolean = false,
    val emailAccounts: ImmutableList<EmailAccount> = persistentListOf(),
    val emailPollIntervalMinutes: Int = 15,
    val emailPendingCount: Int = 0,
    val emailSyncStates: ImmutableMap<String, EmailSyncState> = persistentMapOf(),
    val refreshingEmailAccountIds: ImmutableSet<String> = persistentSetOf(),
    val showSmsSection: Boolean = false,
    val isSmsEnabled: Boolean = false,
    val smsPermissionGranted: Boolean = false,
    val smsPollIntervalMinutes: Int = 15,
    val smsPendingCount: Int = 0,
    val smsSyncState: SmsSyncState = SmsSyncState(),
    val isRefreshingSms: Boolean = false,
    val isSmsSendEnabled: Boolean = false,
    val smsSendPermissionGranted: Boolean = false,
    val showNotificationsSection: Boolean = false,
    val isNotificationsEnabled: Boolean = false,
    val notificationListenerAccessGranted: Boolean = false,
    val notificationListenerBound: Boolean = false,
    val notificationPendingCount: Int = 0,
    val uiScale: Float = 1.0f,
    val showUiScale: Boolean = false,
    val ttsEngine: com.pusios.pots.data.TtsEngine = com.pusios.pots.data.TtsEngine.Native,
    val ttsSystemVoice: String = "",
    val ttsPiperVoice: String = "",
    val ttsRate: Float = 1.0f,
    val ttsPitch: Float = 1.0f,
    val isTestingPiperVoice: Boolean = false,
    val sttEngine: com.pusios.pots.data.SttEngine = com.pusios.pots.data.SttEngine.Vosk,
    val sttVoskModel: String = com.pusios.pots.data.AppSettings.DEFAULT_STT_VOSK_MODEL,
    val mcpServers: ImmutableList<McpServerUiState> = persistentListOf(),
    val showAddMcpServerDialog: Boolean = false,
    /** A pending confirmation the agent asked for through one of the mcp_* tools. */
    val mcpPendingConfirm: McpConfirmRequest? = null,
    /** Tools and MCP servers the on-device model may call, already resolved to the effective set. */
    val localToolSelection: LocalToolSelection = LocalToolSelection(),
    /** True when the user configured the selection themselves (false means "use the defaults"). */
    val isLocalToolSelectionConfigured: Boolean = false,
    val skills: ImmutableList<SkillManifest> = persistentListOf(),
    val showAddSkillDialog: Boolean = false,
    val isInstallingSkill: Boolean = false,
    val skillInstallError: String? = null,
    val browsableSkills: ImmutableList<RegistrySkillEntry> = persistentListOf(),
    val isBrowsingSkills: Boolean = false,
    val browseSkillsFailed: Boolean = false,
    val localAvailableModels: ImmutableList<LocalModel> = persistentListOf(),
    val localImportedModels: ImmutableList<LocalModel> = persistentListOf(),
    val totalDeviceMemoryBytes: Long = Long.MAX_VALUE,
    val localFreeSpaceBytes: Long = 0L,
    val localDownloadingModelId: String? = null,
    val localDownloadProgress: Float? = null,
    val localDownloadError: DownloadError? = null,
    val localImportingFileName: String? = null,
    val localImportProgress: Float? = null,
    val localImportError: ModelImportError? = null,
    val modelContextTokens: ImmutableMap<String, Int> = persistentMapOf(),
    val currentSponsors: ImmutableList<SponsorsResponseDto.Sponsor> = persistentListOf(),
    val pastSponsors: ImmutableList<SponsorsResponseDto.Sponsor> = persistentListOf(),
    val update: AppUpdateUiState = AppUpdateUiState(),
    val assistant: AssistantAvailability = AssistantAvailability.NotOnThisPlatform,
    val pendingDeletion: PendingDeletion? = null,
)

@Immutable
data class McpServerUiState(
    val id: String,
    val name: String,
    val url: String,
    val isEnabled: Boolean,
    val connectionStatus: McpConnectionStatus,
    val tools: ImmutableList<ToolInfo>,
    /** Why the last connect failed, as the server reported it — the "red dot" needs a reason. */
    val failureKind: McpFailureKind? = null,
    val failureDetail: String = "",
    /** True when an OAuth/manual token is stored for this server. */
    val hasStoredToken: Boolean = false,
    /** True when the agent added the server through `mcp_add_server`. */
    val addedByAgent: Boolean = false,
    /** Set while a browser sign-in is waiting for the redirect. */
    val pendingAuthorizationUrl: String? = null,
    /** Tools above come from the persisted cache, not from a live connection. */
    val toolsFromCache: Boolean = false,
) {
    val toolCount: Int get() = tools.size

    /**
     * True when the server itself asked for a token (401, or a scope challenge). A server that is
     * merely down must not be pushed through a browser flow: that is what [canRetry] is for.
     */
    val canAuthorize: Boolean
        get() = connectionStatus != McpConnectionStatus.Connected &&
            (
                connectionStatus == McpConnectionStatus.NeedsAuth ||
                    failureKind == McpFailureKind.NeedsAuth ||
                    failureKind == McpFailureKind.NeedsScope
                )

    /** A plain reconnect is the fix when the server is down or was never reached at all. */
    val canRetry: Boolean
        get() = connectionStatus == McpConnectionStatus.Failed || connectionStatus == McpConnectionStatus.Unknown

    /** A sign-in was started and the manager is waiting for the browser to come back. */
    val isWaitingForBrowser: Boolean get() = pendingAuthorizationUrl != null
}

/**
 * Builds one card from the manager's status record.
 *
 * The status is the only place that knows the difference between "this server wants a sign-in" and
 * "this server is down", so the card is derived from it and never from a local guess. The tool cache
 * rides along: a server that is currently unreachable still shows what it offered last time.
 */
internal fun mcpServerUiState(
    config: McpServerConfig,
    status: McpServerStatus?,
    tools: List<ToolInfo>,
    hasStoredToken: Boolean,
    pendingAuthorizationUrl: String?,
): McpServerUiState {
    val connected = status?.state == McpServerStateKind.Connected
    return McpServerUiState(
        id = config.id,
        name = config.name,
        url = config.url,
        isEnabled = config.isEnabled,
        connectionStatus = when {
            !config.isEnabled -> McpConnectionStatus.Disabled
            connected -> McpConnectionStatus.Connected
            status?.state == McpServerStateKind.NeedsAuth -> McpConnectionStatus.NeedsAuth
            status?.state == McpServerStateKind.Failed -> McpConnectionStatus.Failed
            status?.state == McpServerStateKind.Connecting -> McpConnectionStatus.Connecting
            else -> McpConnectionStatus.Unknown
        },
        tools = tools.toImmutableList(),
        failureKind = status?.failure?.kind,
        failureDetail = status?.failure?.detail.orEmpty(),
        hasStoredToken = hasStoredToken,
        addedByAgent = config.addedByAgent,
        pendingAuthorizationUrl = pendingAuthorizationUrl,
        toolsFromCache = tools.isNotEmpty() && !connected,
    )
}

enum class McpConnectionStatus {
    Unknown,
    Disabled,
    Connecting,
    Connected,
    NeedsAuth,
    Failed,
}

sealed interface PendingDeletion {
    data class Memory(val key: String) : PendingDeletion
    data class Task(val id: String) : PendingDeletion
    data class EmailAccount(val id: String) : PendingDeletion
    data class Service(val instanceId: String) : PendingDeletion
    data class McpServer(val serverId: String) : PendingDeletion
    data class Skill(val id: String) : PendingDeletion
}

sealed interface ImportResult {
    data object Success : ImportResult
    data class PartialSuccess(val errorCount: Int) : ImportResult
    data object Failure : ImportResult
}

@Immutable
data class SettingsModel(
    val id: String,
    val subtitle: String,
    val description: String? = null,
    val descriptionRes: StringResource? = null,
    val isSelected: Boolean = false,
    /** Human-readable name to display in place of [id], when the provider exposes one. */
    val displayName: String? = null,
    /** Max context window in tokens, from the API or the curated catalog. */
    val contextWindow: Long? = null,
    /** Release date as "YYYY-MM" or "YYYY-MM-DD", from the API or the curated catalog. */
    val releaseDate: String? = null,
    /** Parameter count, pre-formatted for display (e.g. "70B", "8B", "3.3B"). */
    val parameterCount: String? = null,
    /** LMArena Elo score, or null when unknown. */
    val arenaScore: Int? = null,
    /** Prompt price per million tokens, or null when unknown. */
    val pricePerMillion: Double? = null,
    /** Output price per million tokens, or null when unknown. */
    val outputPricePerMillion: Double? = null,
    /** Cached input price per million tokens, or null when unknown. */
    val cachePricePerMillion: Double? = null,
    /** True when the provider advertises function calling for this model. */
    val supportsTools: Boolean = false,
    /** True when the provider advertises image input for this model. */
    val supportsVision: Boolean = false,
    /**
     * True when this model is on the provider's free tier according to the
     * curated [com.pusios.pots.data.FreeTierModels] catalog
     * (currently Ollama Cloud and OpenRouter only).
     */
    val isFreeTier: Boolean = false,
    /**
     * True when this entry was typed by the user and is not (yet) present in the
     * provider's model list. At most one manual entry is kept; retyping replaces it
     * instead of accumulating intermediate keystrokes in the picker.
     */
    val isManualEntry: Boolean = false,
)
