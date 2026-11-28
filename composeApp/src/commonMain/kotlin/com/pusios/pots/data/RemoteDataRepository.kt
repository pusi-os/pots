@file:OptIn(ExperimentalEncodingApi::class, ExperimentalTime::class, ExperimentalUuidApi::class)

package com.pusios.pots.data

import com.pusios.pots.SandboxController
import com.pusios.pots.build.WebPanelPreferences
import com.pusios.pots.compressImageBytes
import com.pusios.pots.currentPlatform
import com.pusios.pots.data.HeadlessRunElement
import com.pusios.pots.data.providers.buildAnthropicMessages
import com.pusios.pots.data.providers.buildOpenAIMessages
import com.pusios.pots.data.providers.toResponsesInput
import com.pusios.pots.email.EmailPoller
import com.pusios.pots.formatFileSize
import com.pusios.pots.getAvailableTools
import com.pusios.pots.getPlatformToolDefinitions
import com.pusios.pots.inference.DownloadError
import com.pusios.pots.inference.DownloadedModel
import com.pusios.pots.inference.EngineState
import com.pusios.pots.inference.InferenceMessage
import com.pusios.pots.inference.LocalEmptyResponseException
import com.pusios.pots.inference.LocalInferenceEngine
import com.pusios.pots.inference.LocalInferenceException
import com.pusios.pots.inference.LocalModel
import com.pusios.pots.inference.LocalTool
import com.pusios.pots.inference.ModelImportError
import com.pusios.pots.inference.ModelImportResult
import com.pusios.pots.inference.NoModelDownloadedException
import com.pusios.pots.inference.getTotalMemoryBytes
import com.pusios.pots.linux.LinuxDistro
import com.pusios.pots.mcp.McpConfirmRequest
import com.pusios.pots.mcp.McpServerConfig
import com.pusios.pots.mcp.McpServerManager
import com.pusios.pots.mcp.McpServerStatus
import com.pusios.pots.mcp.McpTool
import com.pusios.pots.network.AllServicesFailedException
import com.pusios.pots.network.AnthropicInsufficientCreditsException
import com.pusios.pots.network.AnthropicInvalidApiKeyException
import com.pusios.pots.network.ConfiguredInstanceNotFoundException
import com.pusios.pots.network.ContextWindowExceededException
import com.pusios.pots.network.FileTooLargeException
import com.pusios.pots.network.GeminiInvalidApiKeyException
import com.pusios.pots.network.NoConfiguredServiceException
import com.pusios.pots.network.OpenAICompatibleBadRequestException
import com.pusios.pots.network.OpenAICompatibleContentModerationException
import com.pusios.pots.network.OpenAICompatibleEmptyResponseException
import com.pusios.pots.network.OpenAICompatibleGenericException
import com.pusios.pots.network.OpenAICompatibleInvalidApiKeyException
import com.pusios.pots.network.OpenAICompatibleModelNotFoundException
import com.pusios.pots.network.OpenAICompatibleQuotaExhaustedException
import com.pusios.pots.network.OpenAICompatibleRequestTooLargeException
import com.pusios.pots.network.Requests
import com.pusios.pots.network.ServiceCredentials
import com.pusios.pots.network.TokenUsage
import com.pusios.pots.network.UnsupportedFileTypeException
import com.pusios.pots.network.dtos.anthropic.extractText
import com.pusios.pots.network.dtos.gemini.extractText
import com.pusios.pots.network.dtos.openaicompatible.extractInlineToolCalls
import com.pusios.pots.network.dtos.openairesponses.OpenAIResponsesResponseDto
import com.pusios.pots.network.dtos.toTokenUsage
import com.pusios.pots.network.isRetryableProviderFailure
import com.pusios.pots.network.toUiError
import com.pusios.pots.network.tools.Tool
import com.pusios.pots.network.tools.ToolInfo
import com.pusios.pots.skills.RegistrySkillEntry
import com.pusios.pots.skills.SkillManager
import com.pusios.pots.skills.SkillManifest
import com.pusios.pots.sms.SmsPoller
import com.pusios.pots.sms.SmsReader
import com.pusios.pots.sms.SmsSendResult
import com.pusios.pots.sms.SmsSender
import com.pusios.pots.tools.NotificationListenerController
import com.pusios.pots.tools.PermissionController
import com.pusios.pots.tools.ToolActivation
import com.pusios.pots.tools.ToolDelivery
import com.pusios.pots.tools.ToolDeliveryMode
import com.pusios.pots.ui.chat.History
import com.pusios.pots.ui.chat.ToolCallInfo
import com.pusios.pots.ui.chat.toGeminiMessageDto
import com.pusios.pots.ui.potsik.PotsikLook
import com.pusios.pots.ui.settings.SettingsModel
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.mimeType
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.size
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.compose.resources.getString
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.default_soul
import pots.composeapp.generated.resources.local_tools_fallback_note
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val MIN_TOOL_DISPLAY_MS = 2000L
private const val MAX_HEARTBEAT_MESSAGES = 50
private const val ESTIMATED_CHARS_PER_TOKEN = 4
private const val MAX_SUMMARY_TRANSCRIPT_CHARS = 120_000

// Explicit allowlist of tools exposed to the on-device (LiteRT) model when the user has not
// configured a selection of their own. See [LocalToolSelection.DEFAULT_TOOL_IDS] for why this
// is a name list rather than a structural filter.
internal val LOCAL_TOOL_ALLOWLIST = LocalToolSelection.DEFAULT_TOOL_IDS

/**
 * The Responses API reports a failed turn inside a 200 body (`status: "failed"`), which would
 * otherwise read as an empty answer. Surface OpenAI's message instead of a generic empty-response
 * error.
 */
private fun OpenAIResponsesResponseDto.throwIfFailed(service: Service) {
    val message = error?.message ?: return
    throw OpenAICompatibleGenericException("${service.displayName}: $message")
}

private data class LoopChatResult(
    val textContent: String,
    val reasoningContent: String? = null,
    val isThinkingContent: Boolean = false,
    val toolCalls: List<ToolCallInfo>,
    /** Token accounting reported for this round, when the provider reports it. */
    val usage: TokenUsage? = null,
)

/** Final answer from a single assistant turn — text and (optionally) the reasoning trace
 * that produced it. Returned from [askWithService] so the caller can persist both. */
private data class AssistantTurn(
    val content: String,
    val reasoningContent: String? = null,
    /** Token accounting for the whole turn, summed over every provider round it took. */
    val usage: TokenUsage? = null,
    /** On-device models know their real window; remote turns fall back to the catalog. */
    val contextWindowTokens: Int? = null,
    /**
     * Human-readable note about a degraded turn (e.g. on-device tools dropped after the
     * runtime rejected them). Rendered under the reply so the user is never left wondering
     * why the model "did not use the tool".
     */
    val notice: String? = null,
)

private enum class BailoutReason { LIMIT_REACHED, REPEATING }

private fun bailoutPrompt(reason: BailoutReason): String = when (reason) {
    BailoutReason.LIMIT_REACHED -> "You have reached the tool call limit. Please respond with the best answer you have so far based on the information gathered."
    BailoutReason.REPEATING -> "You are repeating the same tool calls. Please respond with the best answer you have so far."
}

private interface ToolLoopStrategy {
    suspend fun chat(history: List<History>, systemPrompt: String?): LoopChatResult
    suspend fun bailout(history: List<History>, systemPrompt: String?, reason: BailoutReason): String

    /**
     * Context budget used to trim the request copy of the history each tool round. Providers that
     * send the history as-is (Gemini, Anthropic) declare their window here; the OpenAI-compatible
     * strategy trims the built message list inside [chat] instead and leaves this null.
     */
    val historyContextWindowTokens: Int? get() = null
}

class RemoteDataRepository(
    private val requests: Requests,
    private val appSettings: AppSettings,
    private val conversationStorage: ConversationStorage,
    private val toolExecutor: ToolExecutor,
    private val memoryStore: MemoryStore,
    private val taskStore: TaskStore,
    private val heartbeatManager: HeartbeatManager,
    private val emailStore: EmailStore,
    private val emailPoller: EmailPoller,
    private val smsStore: SmsStore,
    private val smsPoller: SmsPoller,
    private val smsReader: SmsReader,
    private val smsPermissionController: PermissionController,
    private val smsSendPermissionController: PermissionController,
    private val smsSender: SmsSender,
    private val smsDraftStore: SmsDraftStore,
    private val notificationStore: NotificationStore,
    private val notificationListenerController: NotificationListenerController,
    private val mcpServerManager: McpServerManager,
    private val skillManager: SkillManager,
    private val sandboxController: SandboxController,
    private val localInferenceEngine: LocalInferenceEngine? = null,
) : DataRepository {

    override val agentProfiles: StateFlow<List<AgentProfile>> = appSettings.agentProfilesFlow
    override val activeAgentProfileId: StateFlow<String> = appSettings.activeAgentProfileIdFlow

    private val prettyJson = Json { prettyPrint = true }

    /**
     * Returns the tools exposed to the on-device (LiteRT) model. Filtered by name against
     * [LocalToolSelection]: the user's explicit choice wins, and the built-in defaults only
     * stand in while the choice is unconfigured or no longer matches any known tool (a
     * renamed tool after an update). An explicit empty choice means "no tools" — that is
     * the whole point of [LocalToolSelection.configured]. Tools the user has disabled in
     * settings (e.g. shell command, which is gated behind `isToolEnabled("execute_shell_command")`)
     * won't appear in `getAvailableTools()` in the first place, so they're naturally excluded.
     *
     * MCP tools are opt-in per server: [LocalToolSelection.effective] hands none until the
     * owner ticks a server, and a selection whose servers are all gone yields none rather
     * than every connected server's tools. The final list is capped at
     * [LocalToolSelection.MAX_TOOLS] with the chat-priority tools kept first.
     */
    private fun getLocalSafeTools(): List<Tool> {
        val available = getAvailableTools()
        val mcpTools = available.filterIsInstance<McpTool>()
        val builtInTools = available.filterNot { it is McpTool }
        val selection = LocalToolSelection.effective(appSettings.getLocalToolSelection())

        val selectedBuiltIn = if (selection.toolIds.isEmpty()) {
            emptyList()
        } else {
            builtInTools.filter { tool -> selection.toolIds.any { id -> id.equals(tool.schema.name, ignoreCase = true) } }
                .ifEmpty {
                    // Guard against a stale/mismatched selection (every id unknown after a
                    // rename): keep the built-in defaults rather than silently handing the
                    // model no tools at all.
                    builtInTools.filter { it.schema.name in LOCAL_TOOL_ALLOWLIST }
                }
        }

        val selectedMcp = mcpTools.filter { it.serverId in selection.mcpServerIds }

        val candidates = selectedBuiltIn + selectedMcp
        val keptNames = LocalToolSelection.capToolNames(candidates.map { it.schema.name }).toSet()
        return candidates.filter { it.schema.name in keptNames }
    }

    /**
     * Whether the on-device model may be handed tools at all.
     *
     * Metadata first: the bundle's own `supportsFunctionCalling` decides, with the LFM ids
     * that do template-level tool calling as the only exception (see
     * [LocalToolSelection.TEMPLATE_LEVEL_TOOL_CALLING_MODEL_IDS]). A model whose chat template
     * has no tool section does not ignore tools — it fabricates their output, which is the
     * "local model answers with errors" report this gate exists for.
     */
    private suspend fun localModelDeclaresTools(modelId: String): Boolean {
        if (getLocalSafeTools().isEmpty()) return false
        val supportsFunctionCalling = localInferenceEngine?.modelCapabilities(modelId)?.supportsFunctionCalling
        return LocalToolSelection.modelSupportsToolCalling(supportsFunctionCalling, modelId)
    }

    /**
     * Context window in tokens for the on-device model [modelId]: the per-model value the user
     * set in the context slider, else the catalog default. Zero means "unknown" — the prompt and
     * history budgets then fall back to their own defaults instead of guessing a window.
     */
    private fun localModelContextTokens(modelId: String): Int {
        val stored = appSettings.getModelContextTokens(modelId)
        if (stored > 0) return stored
        return localInferenceEngine?.getAvailableModels()?.firstOrNull { it.id == modelId }?.defaultContextTokens ?: 0
    }

    // Per-instance model storage: instanceId -> models flow
    private val modelsByInstance: MutableMap<String, MutableStateFlow<List<SettingsModel>>> = mutableMapOf()

    /**
     * Tokens spent in the current conversation, accumulated over every request.
     *
     * A reply's footer shows its own request; the token dialog shows these running totals.
     * Loading a saved conversation seeds them from the last stored snapshot instead of
     * starting at zero, so reopening a chat does not make its session total jump backwards.
     */
    private var sessionTokenCount: Int = 0
    private var sessionPromptTokenCount: Int = 0
    private var sessionCompletionTokenCount: Int = 0
    private var lastRequestPromptTokens: Int = 0

    private fun seedSessionCounters(metrics: TurnMetrics?) {
        sessionTokenCount = metrics?.sessionTokens ?: 0
        sessionPromptTokenCount = metrics?.sessionPromptTokens ?: 0
        sessionCompletionTokenCount = metrics?.sessionCompletionTokens ?: 0
        lastRequestPromptTokens = metrics?.promptTokens ?: 0
    }

    /** Rebuilds the running totals from the newest reply still in the history. */
    private fun recalculateSessionCounters() {
        seedSessionCounters(chatHistory.value.lastOrNull { it.turnMetrics != null }?.turnMetrics)
    }

    /** Build credentials from per-instance settings */
    private fun instanceCredentials(
        instanceId: String,
        service: Service,
        modelIdOverride: String? = null,
    ): ServiceCredentials = ServiceCredentials(
        apiKey = appSettings.getInstanceApiKey(instanceId),
        modelId = modelIdOverride?.takeIf { it.isNotBlank() }
            ?: appSettings.getInstanceEffectiveModelId(instanceId).ifEmpty { appSettings.getSelectedModelId(service) },
        baseUrl = getInstanceBaseUrl(instanceId, service),
    )

    override val chatHistory: MutableStateFlow<List<History>> = MutableStateFlow(emptyList())

    private val _currentConversationId = MutableStateFlow<String?>(null)
    override val currentConversationId: StateFlow<String?> = _currentConversationId

    private val _fallbackStatus = MutableStateFlow<FallbackStatus?>(null)
    override val fallbackStatus: StateFlow<FallbackStatus?> = _fallbackStatus

    override val savedConversations: StateFlow<List<Conversation>> = conversationStorage.conversations

    override fun getConfiguredServiceInstances(): List<ServiceInstance> = appSettings.getConfiguredServiceInstances()
        .filter { Service.fromId(it.serviceId) != null }

    override fun addConfiguredService(serviceId: String): ServiceInstance {
        val instanceId = appSettings.generateInstanceId(serviceId)
        val instance = ServiceInstance(instanceId = instanceId, serviceId = serviceId)
        val current = appSettings.getConfiguredServiceInstances().toMutableList()
        current.add(instance)
        appSettings.setConfiguredServiceInstances(current)
        return instance
    }

    override fun removeConfiguredService(instanceId: String) {
        val current = appSettings.getConfiguredServiceInstances().toMutableList()
        current.removeAll { it.instanceId == instanceId }
        appSettings.setConfiguredServiceInstances(current)
        appSettings.removeInstanceSettings(instanceId)
        modelsByInstance.remove(instanceId)
    }

    override fun reorderConfiguredServices(orderedInstanceIds: List<String>) {
        val current = appSettings.getConfiguredServiceInstances()
        val byId = current.associateBy { it.instanceId }
        val reordered = orderedInstanceIds.mapNotNull { byId[it] }
        appSettings.setConfiguredServiceInstances(reordered)
    }

    override fun getServiceEntries(): List<ServiceEntry> = getConfiguredServiceInstances().mapNotNull { instance ->
        val service = Service.fromId(instance.serviceId) ?: return@mapNotNull null
        val modelId = appSettings.getInstanceEffectiveModelId(instance.instanceId).ifEmpty {
            appSettings.getSelectedModelId(service)
        }
        ServiceEntry(
            instanceId = instance.instanceId,
            serviceId = service.id,
            serviceName = service.displayName,
            modelId = modelId,
            icon = service.icon,
        )
    }

    override fun isOnboardingDone(): Boolean = appSettings.isOnboardingDone()

    override fun setOnboardingDone(done: Boolean) = appSettings.setOnboardingDone(done)

    override fun getOnboardingStep(): Int = appSettings.getOnboardingStep()

    override fun setOnboardingStep(step: Int) = appSettings.setOnboardingStep(step)

    override fun isOnboardingFinishedStep(): Boolean = appSettings.isOnboardingFinishedStep()

    override fun setOnboardingFinishedStep(finished: Boolean) = appSettings.setOnboardingFinishedStep(finished)

    // Per-instance settings
    override fun getInstanceApiKey(instanceId: String): String = appSettings.getInstanceApiKey(instanceId)

    override fun updateInstanceApiKey(instanceId: String, apiKey: String) {
        appSettings.setInstanceApiKey(instanceId, apiKey)
    }

    override fun getInstanceBaseUrl(instanceId: String, service: Service): String {
        val url = appSettings.getInstanceBaseUrl(instanceId)
        return url.ifBlank { if (service is Service.OpenAICompatible) Service.DEFAULT_OPENAI_COMPATIBLE_BASE_URL else "" }
    }

    override fun updateInstanceBaseUrl(instanceId: String, baseUrl: String) {
        appSettings.setInstanceBaseUrl(instanceId, baseUrl)
    }

    override fun getInstanceModels(instanceId: String, service: Service): StateFlow<List<SettingsModel>> = modelsByInstance.getOrPut(instanceId) {
        val selectedModelId = appSettings.getInstanceModelId(instanceId)
        val defaultSettingsModels = service.defaultModels.map {
            SettingsModel(
                id = it.id,
                subtitle = it.subtitle,
                descriptionRes = it.descriptionRes,
                isSelected = it.id == selectedModelId,
            )
        }
        val models = if (selectedModelId.isNotEmpty() && defaultSettingsModels.none { it.id == selectedModelId }) {
            listOf(
                SettingsModel(
                    id = selectedModelId,
                    subtitle = "",
                    isSelected = true,
                    isManualEntry = true,
                ),
            ) + defaultSettingsModels
        } else {
            defaultSettingsModels
        }
        MutableStateFlow(models)
    }

    override fun updateInstanceSelectedModel(instanceId: String, service: Service, modelId: String) {
        appSettings.setInstanceModelId(instanceId, modelId)
        modelsByInstance[instanceId]?.update { models ->
            ensureSelectedModelPresent(models, modelId)
        }
        // Free the previously-loaded on-device model as soon as the user picks a new one.
        // Deferring until the next chat would briefly hold both models' GPU buffers resident
        // and the driver's lazy reclaim can push us past LMK thresholds on mid-range devices.
        if (service.isOnDevice && localInferenceEngine?.currentModelId?.let { it != modelId } == true) {
            localInferenceEngine.releaseInBackground()
        }
    }

    override fun getInstanceUseCustomModel(instanceId: String): Boolean = appSettings.getInstanceUseCustomModel(instanceId)

    override fun updateInstanceUseCustomModel(instanceId: String, useCustom: Boolean) {
        if (useCustom && appSettings.getInstanceCustomModelId(instanceId).isBlank()) {
            // Prefill from list selection so the field is not empty when the checkbox is first enabled.
            val listModelId = appSettings.getInstanceModelId(instanceId)
            if (listModelId.isNotBlank()) {
                appSettings.setInstanceCustomModelId(instanceId, listModelId)
            }
        }
        appSettings.setInstanceUseCustomModel(instanceId, useCustom)
    }

    override fun getInstanceCustomModelId(instanceId: String): String = appSettings.getInstanceCustomModelId(instanceId)

    override fun updateInstanceCustomModelId(instanceId: String, modelId: String) {
        appSettings.setInstanceCustomModelId(instanceId, modelId)
    }

    override fun clearInstanceModels(instanceId: String, service: Service) {
        modelsByInstance[instanceId]?.update { emptyList() }
    }

    override suspend fun validateConnection(service: Service, instanceId: String) {
        if (service.isOnDevice) {
            fetchInstanceModels(service, instanceId)
            return
        }
        fetchInstanceModels(service, instanceId)
    }

    private suspend fun fetchInstanceModels(service: Service, instanceId: String) {
        when (service) {
            Service.LiteRT -> {
                val engine = localInferenceEngine ?: return
                val selectedModelId = appSettings.getInstanceModelId(instanceId)
                val downloaded = engine.getDownloadedModels()
                val models = downloaded.map {
                    SettingsModel(
                        id = it.id,
                        subtitle = "${it.displayName} (${formatFileSize(it.sizeBytes)})",
                        isSelected = it.id == selectedModelId,
                    )
                }
                updateModelsForInstance(instanceId, models, service)
            }

            else -> {
                if (service.modelsUrl != null) {
                    fetchModelsForInstance(service, instanceId) { creds, selectedModelId ->
                        val models = requests.getOpenAICompatibleModels(service, creds).getOrThrow().data
                        val pricing = if (service == Service.LlmRouter) requests.getLlmRouterPricing(creds).getOrNull()?.data else null
                        val catalog = fetchCatalogEnrichment(service)
                        mapOpenAICompatibleModels(models, service, selectedModelId, pricing, catalog)
                    }
                } else if (service.defaultModels.isNotEmpty()) {
                    val selectedModelId = appSettings.getInstanceModelId(instanceId)
                    val models = service.defaultModels.map {
                        SettingsModel(
                            id = it.id,
                            subtitle = it.subtitle,
                            descriptionRes = it.descriptionRes,
                            isSelected = it.id == selectedModelId,
                        )
                    }
                    updateModelsForInstance(instanceId, models, service)
                }
            }
        }
    }

    /**
     * Fetches [service]'s model list for [instanceId] and publishes it. [fetchAndMap] is the only
     * per-provider part: it issues the request and maps the response to [SettingsModel]s, given
     * the instance credentials and the currently selected model id.
     */
    private suspend fun fetchModelsForInstance(
        service: Service,
        instanceId: String,
        fetchAndMap: suspend (ServiceCredentials, String) -> List<SettingsModel>,
    ) {
        val creds = instanceCredentials(instanceId, service)
        val selectedModelId = appSettings.getInstanceModelId(instanceId)
        updateModelsForInstance(instanceId, fetchAndMap(creds, selectedModelId), service)
    }

    /**
     * Public per-provider catalogs that carry prices/caps the `/v1/models` endpoint lacks.
     * Both are fetched without a key and degrade silently to null on failure — the model cards
     * then keep their contour "—" tiles. PlusVibe's reserve gateway shares the main host's
     * catalog (the gw host's own /api/catalog is slow and unverified).
     */
    private suspend fun fetchCatalogEnrichment(service: Service): Map<String, ApiCatalogEnrichment>? = when (service) {
        Service.PlusVibe, Service.PlusVibeReserve ->
            requests.getPlusVibeCatalog().getOrNull()?.associate { it.apiModel to it.toEnrichment() }

        Service.AiTunnel ->
            requests.getAiTunnelCatalog().getOrNull()?.mapValues { it.value.toEnrichment() }

        else -> null
    }

    private fun updateModelsForInstance(instanceId: String, models: List<SettingsModel>, service: Service) {
        val selectedModelId = appSettings.getInstanceModelId(instanceId)
        val withSelected = ensureSelectedModelPresent(models, selectedModelId)
        val flow = modelsByInstance.getOrPut(instanceId) { MutableStateFlow(emptyList()) }
        flow.update { withSelected }
        // Only auto-pick when nothing is stored yet — never overwrite a custom / unlisted model id.
        if (withSelected.isNotEmpty() && withSelected.none { it.isSelected }) {
            val default = pickDefaultModel(withSelected, service)
            if (default != null) {
                appSettings.setInstanceModelId(instanceId, default.id)
                flow.update { m -> m.map { it.copy(isSelected = it.id == default.id) } }
            }
        }
    }

    private suspend fun askWithLocalEngine(
        messages: List<History>,
        systemPrompt: String?,
        instanceId: String,
        history: MutableStateFlow<List<History>> = chatHistory,
    ): AssistantTurn {
        val engine = localInferenceEngine
            ?: throw IllegalStateException("On-device inference not available on this platform")

        val modelId = appSettings.getInstanceModelId(instanceId)
        val downloadedModels = engine.getDownloadedModels()
        val model = downloadedModels.find { it.id == modelId }
            ?: downloadedModels.firstOrNull()
            ?: throw NoModelDownloadedException()

        val contextTokens = localModelContextTokens(model.id)

        val needsInit = engine.engineState.value != EngineState.READY || engine.currentModelId != model.id
        if (needsInit) {
            val statusEntry = History(
                role = History.Role.TOOL_EXECUTING,
                content = "",
                toolName = "Initializing ${model.displayName}",
                isStatusMessage = true,
            )
            history.update { it + statusEntry }
            try {
                engine.initialize(model, contextTokens)
            } finally {
                history.update { h -> h.filter { it.id != statusEntry.id } }
            }
        } else {
            engine.initialize(model, contextTokens)
        }

        // Callers pass either a CHAT_LOCAL system prompt (chat + silent paths) or null
        // (Splinterlands via `askSilentlyWithInstance`, where the caller owns the full
        // prompt shape). We hand whichever one through to the engine unchanged.
        // Native litert-lm `automaticToolCalling` owns the tool loop — our allowlisted
        // tools are passed once via [localToolDescriptionJson] and the engine drives them.
        //
        // A small model can get stuck re-issuing the same call (the native loop has no
        // repetition guard, only the 120 s timeout), so every identical attempt past
        // maxRepeatedCalls gets an error result — with the default of 3 the first three calls
        // run and the fourth is refused; the model then has nothing new to wait for and
        // answers with what it has. The cap follows the same tuning the remote loop uses.
        val maxRepeatedCalls = appSettings.getAgentTuning().maxRepeatedToolCalls
        val callCounts = mutableMapOf<String, Int>()
        val localTools: List<LocalTool> = if (localModelDeclaresTools(model.id)) {
            getLocalSafeTools().map { tool ->
                LocalTool(
                    name = tool.schema.name,
                    descriptionJsonString = localToolDescriptionJson(tool),
                    execute = { jsonArgs ->
                        val signature = "${tool.schema.name}:${jsonArgs.take(200)}"
                        val attempt = (callCounts[signature] ?: 0) + 1
                        callCounts[signature] = attempt
                        if (attempt > maxRepeatedCalls) {
                            toolErrorJson(
                                "Repeated call to '${tool.schema.name}' with the same arguments was blocked. " +
                                    "Do not call it again — answer with the information you already have.",
                            )
                        } else {
                            runLocalToolWithUiFeedback(tool.schema.name, jsonArgs, history)
                        }
                    },
                )
            }
        } else {
            emptyList()
        }

        // The executor refuses calls to tools this request did not carry; the on-device engine
        // has its own allowlist, so record it too or a tool the user selected would look "not
        // loaded" after a cloud turn in the same conversation.
        if (localTools.isNotEmpty()) {
            ToolActivation.recordDelivered(activeConversationId(), localTools.mapTo(mutableSetOf()) { it.name })
        }

        // The on-device window is real and small (4 096 tokens is the catalog default) and
        // litert-lm trims nothing for us: a request that does not fit fails the whole call with
        // an opaque INVALID_ARGUMENT. Cut the history down to the budget before the call; the
        // tool descriptions go into the same window, so their size comes out of that budget too.
        val trimmedMessages = trimLocalChatHistory(
            messages = messages,
            contextTokens = contextTokens,
            systemPromptChars = systemPrompt?.length ?: 0,
            toolSchemaChars = localTools.sumOf { it.descriptionJsonString.length },
        )

        val inferenceMessages = trimmedMessages.mapNotNull { msg ->
            when (msg.role) {
                History.Role.USER -> InferenceMessage(role = "user", content = msg.content, attachments = msg.attachments)
                History.Role.ASSISTANT -> InferenceMessage(role = "assistant", content = msg.content)
                else -> null
            }
        }

        var toolsDropped = false
        val result = try {
            engine.chat(messages = inferenceMessages, systemPrompt = systemPrompt, tools = localTools)
        } catch (e: RuntimeException) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (localTools.isEmpty()) {
                println("LiteRT: chat failed with no tools attached: ${e.message?.take(300)}")
                throw LocalInferenceException(e.message)
            }
            // litert-lm's strict ANTLR function-call parser sometimes rejects malformed
            // tool-call output from small Gemma models, throwing INVALID_ARGUMENT from JNI.
            // Retry once without tools so the user gets *some* answer rather than a hard
            // error in the UI. With an empty tool list, LiteRTInferenceEngine sets
            // automaticToolCalling = false, so the parser is bypassed entirely on the retry.
            println("LiteRT: tool-call parser failed (${e.message?.take(200)}). Retrying without tools.")
            toolsDropped = true
            try {
                engine.chat(messages = inferenceMessages, systemPrompt = systemPrompt, tools = emptyList())
            } catch (retryFailure: RuntimeException) {
                if (retryFailure is kotlinx.coroutines.CancellationException) throw retryFailure
                println("LiteRT: retry without tools failed: ${retryFailure.message?.take(300)}")
                throw LocalInferenceException(retryFailure.message)
            }
        }
        // An empty turn used to be stored as an empty bubble with no explanation.
        if (result.text.isBlank()) throw LocalEmptyResponseException()
        val usage = if (result.promptTokens > 0 || result.completionTokens > 0) {
            TokenUsage(
                promptTokens = result.promptTokens,
                completionTokens = result.completionTokens,
                totalTokens = result.promptTokens + result.completionTokens,
            )
        } else {
            null
        }
        return AssistantTurn(
            content = result.text,
            usage = usage,
            contextWindowTokens = contextTokens,
            notice = if (toolsDropped) getString(Res.string.local_tools_fallback_note) else null,
        )
    }

    /**
     * Cached OpenAPI/OpenAI-style JSON descriptions for local tools, keyed by tool name.
     * Schemas are static for allowlisted tools, so serializing them once per tool avoids
     * re-running the JSON builder on every message.
     */
    private val localToolDescriptionJsonCache = mutableMapOf<String, String>()

    /**
     * Returns the cached OpenAPI/OpenAI-style JSON description for [tool], building it on
     * first request. Shape mirrors `Tool.toRequestTool()` in `Requests.kt` without the
     * OpenAI `{type: "function", function: {…}}` wrapper, so litert-lm's `OpenApiTool`
     * adapter can forward it straight to the model. If a parameter has a `rawSchema`,
     * it's passed through verbatim — that preserves array/enum/nested-object shapes the
     * simple `{type, description}` form would lose.
     */
    private fun localToolDescriptionJson(tool: Tool): String = localToolDescriptionJsonCache.getOrPut(tool.schema.name) {
        buildJsonObject {
            put("name", tool.schema.name)
            put("description", tool.schema.description)
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    for ((paramName, param) in tool.schema.parameters) {
                        val raw = param.rawSchema
                        if (raw != null) {
                            put(paramName, raw)
                        } else {
                            putJsonObject(paramName) {
                                put("type", param.type)
                                put("description", param.description)
                            }
                        }
                    }
                }
                putJsonArray("required") {
                    tool.schema.parameters.filter { it.value.required }.keys.forEach { add(it) }
                }
            }
        }.toString()
    }

    /**
     * Runs a single tool invocation requested by the on-device engine, mirroring the UI
     * flow used by [executeToolCallsInParallel]: write the assistant tool-call row, show a
     * TOOL_EXECUTING indicator (with a 2 s minimum so it's visible), execute the tool, then
     * replace the indicator with a TOOL result row. Returns the raw result string for the
     * engine to feed back to the model.
     */
    private suspend fun runLocalToolWithUiFeedback(
        name: String,
        arguments: String,
        history: MutableStateFlow<List<History>>,
    ): String {
        val callId = "local-${Uuid.random()}"
        val executingId = Uuid.random().toString()
        val displayName = toolExecutor.getToolDisplayName(name)
        // Append the assistant tool-call row and the executing indicator in a single
        // StateFlow update so the UI doesn't flash twice before the tool even starts.
        history.update {
            it.toMutableList().apply {
                add(
                    History(
                        role = History.Role.ASSISTANT,
                        content = "",
                        toolCalls = persistentListOf(
                            ToolCallInfo(id = callId, name = name, arguments = arguments),
                        ),
                    ),
                )
                add(
                    History(
                        id = executingId,
                        role = History.Role.TOOL_EXECUTING,
                        content = name,
                        toolName = displayName,
                    ),
                )
            }
        }
        val startTime = Clock.System.now().toEpochMilliseconds()
        // Background runs must not leak shell commands into whatever chat is open.
        val conversationIdForTool = activeConversationId()
        try {
            val result = try {
                toolExecutor.executeTool(name, arguments, conversationIdForTool)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                toolErrorJson(e.message ?: "Tool execution failed")
            }
            val elapsed = Clock.System.now().toEpochMilliseconds() - startTime
            if (elapsed < MIN_TOOL_DISPLAY_MS) {
                delay(MIN_TOOL_DISPLAY_MS.milliseconds - elapsed.milliseconds)
            }
            history.update { h ->
                buildList(h.size) {
                    for (entry in h) {
                        if (entry.id != executingId) add(entry)
                    }
                    add(
                        History(
                            role = History.Role.TOOL,
                            content = result,
                            toolCallId = callId,
                            toolName = name,
                        ),
                    )
                }
            }
            return result
        } finally {
            // On cancellation the fused update above never ran — drop the stranded
            // indicator. On the success path this is a no-op (id already removed).
            history.update { h ->
                if (h.none { it.id == executingId }) h else h.filter { it.id != executingId }
            }
        }
    }

    private suspend fun askWithService(
        service: Service,
        messages: List<History>,
        systemPrompt: String?,
        instanceId: String,
        history: MutableStateFlow<List<History>> = chatHistory,
        modelIdOverride: String? = null,
    ): AssistantTurn = withContext(
        ModelExecutionElement(
            instanceId = instanceId,
            modelId = instanceCredentials(instanceId, service, modelIdOverride).modelId,
        ),
    ) {
        askWithServiceInContext(service, messages, systemPrompt, instanceId, history, modelIdOverride)
    }

    private suspend fun askWithServiceInContext(
        service: Service,
        messages: List<History>,
        systemPrompt: String?,
        instanceId: String,
        history: MutableStateFlow<List<History>>,
        modelIdOverride: String?,
    ): AssistantTurn {
        if (service.isOnDevice) {
            // No retry: local-inference failures are deterministic, and this path mutates
            // chat history through tool execution, so a replay could re-run tools.
            // Re-fetch the system prompt with the CHAT_LOCAL variant — the caller
            // (`ask()`/`askWithTools()`) pre-fetched a CHAT_REMOTE prompt, but on-device
            // needs the trimmed variant.
            val localPrompt = getActiveSystemPrompt(SystemPromptVariant.CHAT_LOCAL)
            return askWithLocalEngine(messages, localPrompt, instanceId, history)
        }

        val creds = instanceCredentials(instanceId, service, modelIdOverride)
        val tools = if (supportsTools(creds.modelId)) toolsForRequest() else emptyList()

        if (tools.isEmpty()) {
            return plainChat(service, creds, messages, systemPrompt, strictEmptyResponse = true)
        }

        return handleOpenAICompatibleChatWithTools(service, creds, messages, ::toolsForRequest, systemPrompt, history)
    }

    /**
     * The tools one remote request carries, plus the delivery record the executor gates on.
     *
     * Every enabled tool used to go on the wire, so a fresh chat paid ~10K tokens for schemas
     * before the first word. [ToolDelivery] keeps a small set eager and, above the threshold,
     * sends three meta-tools and the hottest few: the model pulls the rest in with
     * `find_tools` / `describe_tool`, and a described tool is delivered in full from then on.
     */
    private suspend fun toolsForRequest(): List<Tool> {
        val available = getAvailableTools()
        if (available.isEmpty()) return available
        val conversationId = activeConversationId()
        val plan = ToolDelivery.plan(available, ToolActivation.activatedNames(conversationId))
        ToolDelivery.log(plan)
        ToolActivation.recordDelivered(conversationId, plan.delivered.mapTo(mutableSetOf()) { it.schema.name })
        return plan.delivered
    }

    /**
     * The conversation a request belongs to: an explicit coroutine-context id (set by
     * askWithTools for heartbeat / scheduled runs) wins over the globally active chat id, so
     * background runs aren't attributed to whatever chat the user is viewing. Null before any
     * conversation exists. Used as the upstream session id for providers that require one.
     */
    private suspend fun activeConversationId(): String? = currentConversationIdOrNull() ?: _currentConversationId.value

    /**
     * One assistant turn with no tools declared, dispatched to whichever API [service] speaks.
     * The three providers differ only in message shape and text extraction, so every caller
     * needing a plain completion — the no-tools path above, the silent asks, and the
     * Gemini/Anthropic tool-loop bailouts — funnels through here.
     *
     * [retry] wraps the call in [retryApiCall]; silent callers skip it because they run on a
     * caller-supplied deadline. [strictEmptyResponse] turns a missing OpenAI-compatible message
     * or content into [OpenAICompatibleEmptyResponseException] — the visible chat path wants that
     * error surfaced, silent callers prefer empty text.
     */
    private suspend fun plainChat(
        service: Service,
        credentials: ServiceCredentials,
        messages: List<History>,
        systemPrompt: String?,
        requestTimeoutMs: Long? = null,
        retry: Boolean = true,
        strictEmptyResponse: Boolean = false,
    ): AssistantTurn {
        suspend fun <T> call(block: suspend () -> T): T = if (retry) retryApiCall(block) else block()
        val reasoningEffort = appSettings.getAgentTuning().reasoningEffort.apiValue

        return when (service) {
            else -> {
                // No tools on this request — strip any historic tool_calls so Groq's strict
                // validator doesn't see calls to tools we no longer declare.
                val openAIMessages = buildOpenAIMessages(service, messages, systemPrompt, credentials.modelId, declaredToolNames = emptySet())
                if (requiresResponsesApi(service, credentials.modelId, credentials.baseUrl)) {
                    val response = call {
                        requests.openAIResponses(
                            service,
                            credentials,
                            toResponsesInput(openAIMessages),
                            requestTimeoutMs = requestTimeoutMs,
                            reasoningEffort = reasoningEffort,
                        ).getOrThrow()
                    }
                    response.throwIfFailed(service)
                    val content = response.outputText
                    if (content == null && strictEmptyResponse) throw OpenAICompatibleEmptyResponseException()
                    return AssistantTurn(content.orEmpty(), response.reasoningSummary, response.usage.toTokenUsage())
                }
                val sessionId = activeConversationId()
                val response = call {
                    requests.openAICompatibleChat(
                        service,
                        credentials,
                        openAIMessages,
                        sessionId = sessionId,
                        requestTimeoutMs = requestTimeoutMs,
                        reasoningEffort = reasoningEffort,
                    ).getOrThrow()
                }
                val message = response.choices.firstOrNull()?.message
                val content = message?.effectiveContent
                if (content == null && strictEmptyResponse) throw OpenAICompatibleEmptyResponseException()
                AssistantTurn(content.orEmpty(), message?.reasoningTraceFor(content), response.usage.toTokenUsage())
            }
        }
    }

    private fun hasValidInstanceApiKey(instanceId: String, service: Service): Boolean {
        if (service.isOnDevice) return true
        if (!service.requiresApiKey && !service.supportsOptionalApiKey) return true
        if (service.requiresApiKey) return appSettings.getInstanceApiKey(instanceId).isNotBlank()
        return true // Optional API key services are always valid
    }

    private data class FallbackEntry(val instanceId: String, val service: Service)

    private fun getOrderedFallbackEntries(): List<FallbackEntry> {
        val instances = getConfiguredServiceInstances()
        val entries = instances.mapNotNull { instance ->
            val service = Service.fromId(instance.serviceId) ?: return@mapNotNull null
            FallbackEntry(instanceId = instance.instanceId, service = service)
        }
            .filter { !it.service.isOnDevice || localInferenceEngine != null }
        // On-device models are only tried as the primary service, never as a fallback
        // target — falling into one would silently start a heavy model load the user
        // didn't ask for (mirrors the guard that keeps on-device errors from silently
        // falling back to cloud services).
        return entries.filterIndexed { index, entry -> index == 0 || !entry.service.isOnDevice }
    }

    override suspend fun ask(question: String?, files: List<PlatformFile>, uiSubmission: UiSubmission?, activeSkillId: String?) {
        // The active skill (if any) is consumed for this single turn only — stored in a
        // field rather than a parameter on getActiveSystemPrompt so the existing internal
        // callers (heartbeat, askWithTools, etc.) don't all need a new parameter. The
        // skill's files already live in the sandbox at ~/skills/<id>/, so nothing is
        // materialized here.
        val resolvedSkillId = activeSkillId?.takeIf {
            skillManager.getSkill(it) != null && appSettings.isActiveProfileSkillEnabled(it)
        }
        pendingActiveSkillId = resolvedSkillId
        try {
            askInternal(question, files, uiSubmission)
        } finally {
            pendingActiveSkillId = null
            // The create-skill flow (and any other path that writes into ~/skills) must
            // be reflected in the installed-skills list without a restart. Rescan after
            // every turn so agent-authored skills show up in Settings → Tools → Skills.
            runCatching { skillManager.load() }
        }
    }

    private var pendingActiveSkillId: String? = null

    /** Built-in skill id; matches the bundled SKILL.md under composeResources. */
    private val createSkillId = "create-skill"

    private suspend fun askInternal(question: String?, files: List<PlatformFile>, uiSubmission: UiSubmission?) {
        // Allocate a conversation id immediately for fresh chats. Without this,
        // the very first tool call lands here with _currentConversationId.value
        // still null, so per-conversation routing (e.g. the sandbox shell)
        // falls through to a shared default — which both makes the new chat
        // invisible in the Terminal session picker and lets unrelated callers
        // collide on the same shell mutex. Persistence is deferred to the
        // existing saveCurrentConversation() flow that runs after the response.
        if (_currentConversationId.value == null) {
            setCurrentConversationId(Uuid.random().toString())
        }
        // Process every attached file: classify, compress/encode, and build an Attachment.
        // readBytes() is suspend, so this happens before the StateFlow.update block.
        val attachments = files.map { file ->
            val fileMimeType = file.mimeType()?.toString()
            val fileName = file.name

            val category = classifyFile(fileMimeType, fileName)
            if (category == FileCategory.UNSUPPORTED) throw UnsupportedFileTypeException()

            // Reject oversized files by stat size before readBytes(), which would otherwise
            // allocate a ByteArray large enough to OOM the process on multi-GB inputs.
            val rawSizeLimit = when (category) {
                FileCategory.TEXT -> MAX_TEXT_FILE_BYTES.toLong()
                FileCategory.PDF -> MAX_PDF_BYTES.toLong()
                FileCategory.IMAGE -> MAX_RAW_IMAGE_BYTES.toLong()
                FileCategory.UNSUPPORTED -> 0L
            }
            if (file.size() > rawSizeLimit) throw FileTooLargeException()

            val rawBytes = file.readBytes()

            when (category) {
                FileCategory.IMAGE -> {
                    val compressed = compressImageBytes(rawBytes, fileMimeType ?: "image/jpeg")
                    // compressImageBytes can fall back to the original bytes on failure or on
                    // platforms without compression — guard against Base64 OOM for oversized input.
                    if (compressed.size > MAX_IMAGE_BYTES) throw FileTooLargeException()
                    Attachment(
                        data = Base64.encode(compressed),
                        mimeType = "image/jpeg",
                        fileName = null,
                    )
                }

                FileCategory.TEXT -> Attachment(
                    data = Base64.encode(rawBytes),
                    mimeType = fileMimeType ?: "text/plain",
                    fileName = fileName,
                )

                FileCategory.PDF -> Attachment(
                    data = Base64.encode(rawBytes),
                    mimeType = "application/pdf",
                    fileName = fileName,
                )

                FileCategory.UNSUPPORTED -> throw UnsupportedFileTypeException()
            }
        }.toImmutableList()

        if (question != null) {
            chatHistory.update {
                it.toMutableList().apply {
                    add(
                        History(
                            role = History.Role.USER,
                            content = question,
                            attachments = attachments,
                            uiSubmission = uiSubmission,
                        ),
                    )
                }
            }
        }

        compactHistoryIfNeeded()

        val messages = chatHistory.value
        val systemPrompt = getActiveSystemPrompt()

        val fallbackEntries = getOrderedFallbackEntries().filter { hasValidInstanceApiKey(it.instanceId, it.service) }

        // Nothing to route to (no services, or none with a valid key). Say exactly
        // that instead of falling through to a misleading "empty response" below:
        // the chat UI turns this into the provider-suggestions panel (spec §5.4).
        if (fallbackEntries.isEmpty()) throw NoConfiguredServiceException()

        val historyChars = messages.sumOf { it.content.length } + (systemPrompt?.length ?: 0)

        var lastException: Exception? = null
        var fallbackServiceName: String? = null

        try {
            for ((index, entry) in fallbackEntries.withIndex()) {
                // Skip fallback services whose context window is too small for the current history
                // On-device models handle their own context limits, so skip this check for them
                if (!entry.service.isOnDevice) {
                    val creds = instanceCredentials(entry.instanceId, entry.service)
                    val entryWindowChars = ModelCatalog.estimateContextWindow(creds.modelId) * ESTIMATED_CHARS_PER_TOKEN
                    if (historyChars > entryWindowChars) {
                        lastException = ContextWindowExceededException()
                        _fallbackStatus.value = FallbackStatus(
                            serviceName = entry.service.displayName,
                            errorReason = ContextWindowExceededException().toUiError(),
                            nextServiceName = fallbackEntries.getOrNull(index + 1)?.service?.displayName,
                        )
                        continue
                    }
                }

                // No retry wrapper here: each network call retries inside askWithService.
                // Retrying the whole call would re-enter the tool loop against a chat
                // history already mutated by the failed attempt.
                val requestStartedAt = Clock.System.now()
                val turn = try {
                    askWithService(entry.service, messages, systemPrompt, entry.instanceId)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    // On-device services should not silently fall back — surface the error
                    if (entry.service.isOnDevice) throw e
                    lastException = e
                    _fallbackStatus.value = FallbackStatus(
                        serviceName = entry.service.displayName,
                        errorReason = e.toUiError(),
                        nextServiceName = fallbackEntries.getOrNull(index + 1)?.service?.displayName,
                    )
                    continue
                }
                if (index > 0) {
                    fallbackServiceName = entry.service.displayName
                }
                // The duration covers the provider call only, not the surrounding bookkeeping.
                val durationMs = (Clock.System.now() - requestStartedAt).inWholeMilliseconds
                // Session totals accumulate across the conversation; the reply footer uses
                // the per-request fields, while the token dialog shows these running totals.
                val promptTokens = turn.usage?.promptTokens ?: 0
                val completionTokens = turn.usage?.completionTokens ?: 0
                val totalTokens = turn.usage?.totalTokens?.takeIf { it > 0 } ?: (promptTokens + completionTokens)
                // The API reports the full prompt (including the whole history) in every response,
                // so advancing the running prompt total by the whole value double-counts the
                // context. Raise it only by the growth over the previous request instead; the
                // completion count keeps accumulating normally.
                val promptDelta = (promptTokens - lastRequestPromptTokens).coerceAtLeast(0)
                if (promptTokens > lastRequestPromptTokens) lastRequestPromptTokens = promptTokens
                sessionPromptTokenCount += promptDelta
                sessionCompletionTokenCount += completionTokens
                sessionTokenCount = sessionPromptTokenCount + sessionCompletionTokenCount
                val turnMetrics = TurnMetrics(
                    sessionTokens = sessionTokenCount,
                    sessionPromptTokens = sessionPromptTokenCount,
                    sessionCompletionTokens = sessionCompletionTokenCount,
                    contextWindowTokens = turn.contextWindowTokens ?: ModelCatalog.estimateContextWindow(
                        instanceCredentials(entry.instanceId, entry.service).modelId,
                    ),
                    durationMs = durationMs,
                    promptTokens = promptTokens,
                    completionTokens = completionTokens,
                    totalTokens = promptTokens + completionTokens,
                )
                chatHistory.update {
                    it.toMutableList().apply {
                        add(
                            History(
                                role = History.Role.ASSISTANT,
                                content = turn.content,
                                reasoningContent = turn.reasoningContent,
                                fallbackServiceName = fallbackServiceName,
                                localFallbackNote = turn.notice,
                                turnMetrics = turnMetrics,
                            ),
                        )
                    }
                }
                saveCurrentConversation()
                return
            }

            throw if (fallbackEntries.size > 1 && lastException != null) {
                AllServicesFailedException()
            } else {
                lastException ?: OpenAICompatibleEmptyResponseException()
            }
        } finally {
            _fallbackStatus.value = null
        }
    }

    private suspend fun handleOpenAICompatibleChatWithTools(
        service: Service,
        credentials: ServiceCredentials,
        @Suppress("UNUSED_PARAMETER") messages: List<History>,
        toolsProvider: suspend () -> List<Tool>,
        systemPrompt: String? = null,
        history: MutableStateFlow<List<History>> = chatHistory,
    ): AssistantTurn {
        val contextWindowTokens = ModelCatalog.estimateContextWindow(credentials.modelId)
        val reasoningEffort = appSettings.getAgentTuning().reasoningEffort.apiValue
        // GPT-5.6 and friends reject function tools on chat completions; the same messages are
        // translated to Responses API items instead. Everything before the wire call — prompt
        // assembly, tool-call pairing, context trimming — is shared.
        val useResponsesApi = requiresResponsesApi(service, credentials.modelId, credentials.baseUrl)
        val strategy = object : ToolLoopStrategy {
            override suspend fun chat(history: List<History>, systemPrompt: String?): LoopChatResult {
                // Re-resolve the tool set on every round. `describe_tool` activates a tool in the
                // middle of a turn, and the sanitizer strips a call to a tool the request does not
                // declare — so the newly activated tool has to reach the very next request.
                val tools = toolsProvider()
                val declaredToolNames = tools.map { it.schema.name }.toSet()
                val msgs = trimMessagesForContext(buildOpenAIMessages(service, history, systemPrompt, credentials.modelId, declaredToolNames), contextWindowTokens)
                if (useResponsesApi) {
                    val response = retryApiCall {
                        requests.openAIResponses(
                            service,
                            credentials,
                            toResponsesInput(msgs),
                            tools,
                            reasoningEffort = reasoningEffort,
                        ).getOrThrow()
                    }
                    response.throwIfFailed(service)
                    val text = response.outputText
                    val calls = response.functionCalls.map { fc ->
                        ToolCallInfo(
                            id = fc.callId ?: Uuid.random().toString(),
                            name = fc.name.orEmpty(),
                            arguments = fc.arguments ?: "{}",
                        )
                    }
                    if (text == null && calls.isEmpty()) throw OpenAICompatibleEmptyResponseException()
                    return LoopChatResult(
                        textContent = text.orEmpty(),
                        reasoningContent = response.reasoningSummary,
                        toolCalls = calls,
                        usage = response.usage.toTokenUsage(),
                    )
                }
                val sessionId = activeConversationId()
                val response = retryApiCall {
                    requests.openAICompatibleChat(
                        service,
                        credentials,
                        msgs,
                        tools,
                        sessionId = sessionId,
                        reasoningEffort = reasoningEffort,
                    ).getOrThrow()
                }
                val message = response.choices.firstOrNull()?.message ?: throw OpenAICompatibleEmptyResponseException()
                var calls = message.toolCalls.orEmpty().map { tc ->
                    ToolCallInfo(id = tc.id, name = tc.function.name, arguments = tc.function.arguments)
                }
                var textContent = message.effectiveContent ?: ""
                if (calls.isEmpty() && textContent.contains("<tool_call>")) {
                    val extracted = extractInlineToolCalls(textContent, tools)
                    if (extracted.calls.isNotEmpty()) {
                        textContent = extracted.cleanedText
                        calls = extracted.calls.map {
                            ToolCallInfo(
                                id = "inline-${Uuid.random()}",
                                name = it.name,
                                arguments = it.arguments,
                            )
                        }
                    }
                }
                return LoopChatResult(
                    textContent = textContent,
                    reasoningContent = message.reasoningTraceFor(textContent),
                    isThinkingContent = message.isContentFromReasoning,
                    toolCalls = calls,
                    usage = response.usage.toTokenUsage(),
                )
            }

            override suspend fun bailout(history: List<History>, systemPrompt: String?, reason: BailoutReason): String {
                // Bailout sends no tools — strip historic tool_calls to satisfy strict validators.
                val msgs = trimMessagesForContext(buildOpenAIMessages(service, history, systemPrompt, credentials.modelId, declaredToolNames = emptySet()), contextWindowTokens)
                return makeFinalCallWithoutTools(service, credentials, msgs, reason, useResponsesApi)
            }
        }
        return runToolLoop(strategy, systemPrompt, history)
    }

    private suspend fun runToolLoop(
        strategy: ToolLoopStrategy,
        systemPrompt: String?,
        history: MutableStateFlow<List<History>>,
    ): AssistantTurn {
        val tuning = appSettings.getAgentTuning()
        var iteration = 0
        val recentSignatures = mutableListOf<String>()
        val toolCallTimestamps = mutableListOf<Long>()
        // A turn can take several provider rounds (one per tool call). Every round bills
        // its own completion tokens, but the prompt it bills is the full context of that
        // round — accumulating all of them double-counts the context. Keep the latest
        // prompt size for context accounting and sum the completions.
        var lastPromptTokens = 0
        var completionTokens = 0
        var totalTokens = 0
        var sawUsage = false
        while (true) {
            iteration++
            // Compaction runs inside the loop, after tool results have been appended. When it
            // fires, the turn keeps going with the compacted history — no extra user message.
            compactHistoryIfNeeded(tuning = tuning, historyFlow = history)
            val visible = history.value.filter { it.role != History.Role.TOOL_EXECUTING }.let { full ->
                // Trim a request copy only — the history flow is the visible, persisted conversation.
                strategy.historyContextWindowTokens
                    ?.let { trimHistoryForContext(full, systemPrompt?.length ?: 0, it).ifEmpty { full } }
                    ?: full
            }
            if (iteration > tuning.maxToolIterations) {
                return AssistantTurn(strategy.bailout(visible, systemPrompt, BailoutReason.LIMIT_REACHED))
            }
            val result = strategy.chat(visible, systemPrompt)
            result.usage?.let { usage ->
                sawUsage = true
                lastPromptTokens = usage.promptTokens.takeIf { it > 0 } ?: lastPromptTokens
                completionTokens += usage.completionTokens
                totalTokens += usage.totalTokens
            }
            if (result.toolCalls.isEmpty()) {
                // For thinking-only turns, the reasoning text already became the content via
                // `isContentFromReasoning`, so don't surface it again as a reasoning trace.
                val reasoning = result.reasoningContent?.takeIf { !result.isThinkingContent }
                return AssistantTurn(
                    result.textContent,
                    reasoning,
                    usage = if (sawUsage) TokenUsage(lastPromptTokens, completionTokens, lastPromptTokens + completionTokens) else null,
                )
            }

            val signatures = result.toolCalls.map { "${it.name}:${it.arguments.hashCode()}" }
            if (isRepeatingToolCalls(recentSignatures, signatures, tuning.maxRepeatedToolCalls)) {
                return AssistantTurn(strategy.bailout(visible, systemPrompt, BailoutReason.REPEATING))
            }
            recentSignatures.addAll(signatures)

            history.update {
                it.toMutableList().apply {
                    add(
                        History(
                            role = History.Role.ASSISTANT,
                            content = result.textContent,
                            isThinking = result.isThinkingContent,
                            toolCalls = result.toolCalls.toImmutableList(),
                            reasoningContent = result.reasoningContent,
                        ),
                    )
                }
            }

            val toolResults = executeToolCallsInParallel(
                result.toolCalls.map { Triple(it.id, it.name, it.arguments) },
                history,
                tuning,
                toolCallTimestamps,
            )

            history.update { h ->
                buildList(h.size + toolResults.size) {
                    for (entry in h) {
                        if (entry.role != History.Role.TOOL_EXECUTING) add(entry)
                    }
                    for ((callId, name, content) in toolResults) {
                        add(
                            History(
                                role = History.Role.TOOL,
                                content = content,
                                toolCallId = callId,
                                toolName = name,
                            ),
                        )
                    }
                }
            }
        }
    }

    /**
     * Detects if the current batch of tool calls is repeating a recent pattern.
     */
    private fun isRepeatingToolCalls(
        recentSignatures: List<String>,
        currentSignatures: List<String>,
        repeatLimit: Int,
    ): Boolean {
        if (currentSignatures.isEmpty()) return false
        // Count how many consecutive times the same signature set appeared at the tail
        val batchSize = currentSignatures.size
        var consecutiveCount = 0
        var i = recentSignatures.size - batchSize
        while (i >= 0) {
            val slice = recentSignatures.subList(i, i + batchSize)
            if (slice == currentSignatures) {
                consecutiveCount++
                i -= batchSize
            } else {
                break
            }
        }
        // +1 for the current batch that's about to be executed
        return consecutiveCount + 1 >= repeatLimit
    }

    /**
     * Makes a final OpenAI-compatible API call without tools, asking the model to summarize.
     */
    private suspend fun makeFinalCallWithoutTools(
        service: Service,
        credentials: ServiceCredentials,
        messages: List<com.pusios.pots.network.dtos.openaicompatible.OpenAICompatibleChatRequestDto.Message>,
        reason: BailoutReason,
        useResponsesApi: Boolean = false,
    ): String {
        val reasoningEffort = appSettings.getAgentTuning().reasoningEffort.apiValue
        val bailoutMessages = messages.toMutableList().apply {
            add(
                com.pusios.pots.network.dtos.openaicompatible.OpenAICompatibleChatRequestDto.Message(
                    role = "user",
                    content = JsonPrimitive(bailoutPrompt(reason)),
                ),
            )
        }
        if (useResponsesApi) {
            val response = retryApiCall {
                requests.openAIResponses(
                    service,
                    credentials,
                    toResponsesInput(bailoutMessages),
                    reasoningEffort = reasoningEffort,
                ).getOrThrow()
            }
            response.throwIfFailed(service)
            return response.outputText.orEmpty()
        }
        val sessionId = activeConversationId()
        val response = retryApiCall {
            requests.openAICompatibleChat(
                service,
                credentials,
                bailoutMessages,
                sessionId = sessionId,
                reasoningEffort = reasoningEffort,
            ).getOrThrow()
        }
        return response.choices.firstOrNull()?.message?.effectiveContent ?: ""
    }

    /**
     * Executes tool calls in parallel, showing TOOL_EXECUTING indicators in [history] — the run's
     * own flow, so background runs (heartbeat, scheduled tasks) don't leak rows into the open chat.
     * Returns a list of (callId, toolName, result).
     */
    private suspend fun executeToolCallsInParallel(
        toolCalls: List<Triple<String, String, String>>,
        history: MutableStateFlow<List<History>>,
        tuning: AgentTuning,
        toolCallTimestamps: MutableList<Long>,
    ): List<Triple<String, String, String>> {
        // Add all TOOL_EXECUTING indicators first
        val executingIds = toolCalls.map { Uuid.random().toString() }
        for ((index, toolCall) in toolCalls.withIndex()) {
            val (_, name, _) = toolCall
            val toolDisplayName = toolExecutor.getToolDisplayName(name)
            history.update {
                it.toMutableList().apply {
                    add(
                        History(
                            id = executingIds[index],
                            role = History.Role.TOOL_EXECUTING,
                            content = name,
                            toolName = toolDisplayName,
                        ),
                    )
                }
            }
        }

        // Execute all tools concurrently, ensuring indicators show for at least 2 seconds.
        // Snapshot the conversation id once so all parallel tool calls in this batch
        // see a stable value even if the user switches conversations mid-flight.
        val conversationIdSnapshot = activeConversationId()
        val startTime = Clock.System.now().toEpochMilliseconds()
        try {
            val batchSize = if (tuning.toolCallsPerMinute > 0) {
                minOf(tuning.maxParallelToolCalls, tuning.toolCallsPerMinute)
            } else {
                tuning.maxParallelToolCalls
            }.coerceAtLeast(1)
            val results = mutableListOf<Triple<String, String, String>>()
            // A batch runs in parallel, so `describe_tool` and the call it was meant to unlock can
            // execute in either order. That is fine: a refused call activates its tool as well, so
            // the tool ends up enabled either way and the next round declares it.
            for (batch in toolCalls.chunked(batchSize)) {
                awaitToolCallBudget(batch.size, tuning.toolCallsPerMinute, toolCallTimestamps)
                results += coroutineScope {
                    batch.map { (callId, name, arguments) ->
                        async {
                            val result = toolExecutor.executeTool(name, arguments, conversationIdSnapshot)
                            Triple(callId, name, result)
                        }
                    }.awaitAll()
                }
            }
            val elapsed = Clock.System.now().toEpochMilliseconds() - startTime
            if (elapsed < MIN_TOOL_DISPLAY_MS) {
                delay((MIN_TOOL_DISPLAY_MS - elapsed).milliseconds)
            }
            return results
        } finally {
            // Remove all TOOL_EXECUTING indicators — also on cancellation, so stopping a
            // run doesn't strand spinner rows in the chat. Non-suspending, safe in finally.
            history.update { h ->
                h.filter { it.id !in executingIds }
            }
        }
    }

    /**
     * Enforces the optional rolling per-minute tool-call budget. Calls are reserved before they
     * run so parallel batches cannot overshoot it.
     */
    private suspend fun awaitToolCallBudget(count: Int, perMinute: Int, timestamps: MutableList<Long>) {
        if (perMinute <= 0) return
        while (true) {
            val now = Clock.System.now().toEpochMilliseconds()
            timestamps.removeAll { now - it >= 60_000L }
            if (timestamps.size + count <= perMinute) {
                repeat(count) { timestamps.add(now) }
                return
            }
            val oldest = timestamps.firstOrNull() ?: break
            val waitMs = (60_000L - (now - oldest)).coerceAtLeast(100L)
            delay(waitMs.milliseconds)
        }
    }

    /**
     * Failures that a retry a second later cannot fix: exhausted credit, rejected credentials, and
     * requests the provider refused as malformed, too large or disallowed. Retrying these only
     * delays the fallback chain (~3 s per service). Unknown/generic errors stay retryable.
     *
     * Delegates to [isRetryableProviderFailure] so the chat retry loop and the heartbeat's
     * scheduler-level retries cannot drift apart.
     */
    private fun isNonRetryableException(e: Exception): Boolean = !e.isRetryableProviderFailure()

    /**
     * Retries an API call with simple exponential backoff.
     */
    private suspend fun <T> retryApiCall(block: suspend () -> T): T {
        var lastException: Exception? = null
        val maxRetries = appSettings.getAgentTuning().maxApiRetries
        for (attempt in 0..maxRetries) {
            try {
                return block()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (isNonRetryableException(e)) throw e
                lastException = e
                if (attempt < maxRetries) {
                    delay((attempt + 1).seconds)
                }
            }
        }
        throw lastException!!
    }

    private fun estimateMessageChars(msg: com.pusios.pots.network.dtos.openaicompatible.OpenAICompatibleChatRequestDto.Message): Int {
        val contentChars = when (val content = msg.content) {
            is JsonArray -> {
                // Vision messages: only count text parts, not base64 image data
                content.sumOf { element ->
                    val obj = element as? JsonObject
                    val type = (obj?.get("type") as? JsonPrimitive)?.content
                    if (type == "text") {
                        (obj["text"] as? JsonPrimitive)?.content?.length ?: 0
                    } else {
                        100 // Fixed small cost for image references
                    }
                }
            }

            is JsonPrimitive -> content.content.length

            else -> content?.toString()?.length ?: 0
        }
        return contentChars + msg.role.length
    }

    /**
     * Trims messages to fit within the estimated context window by dropping oldest messages
     * (keeping the system prompt and most recent messages).
     */
    private fun trimMessagesForContext(
        messages: List<com.pusios.pots.network.dtos.openaicompatible.OpenAICompatibleChatRequestDto.Message>,
        contextWindowTokens: Int = ModelCatalog.DEFAULT_CONTEXT_WINDOW_TOKENS,
    ): List<com.pusios.pots.network.dtos.openaicompatible.OpenAICompatibleChatRequestDto.Message> {
        val maxChars = contextWindowTokens * ESTIMATED_CHARS_PER_TOKEN
        val totalChars = messages.sumOf { estimateMessageChars(it) }
        if (totalChars <= maxChars) return messages

        // Keep system prompt (first message if role is "system") and trim from oldest non-system
        val systemMessages = messages.takeWhile { it.role == "system" }
        val nonSystemMessages = messages.drop(systemMessages.size)

        val systemChars = systemMessages.sumOf { estimateMessageChars(it) }
        val availableChars = maxChars - systemChars

        // Group each assistant tool-call turn together with the tool responses that follow it so
        // trimming never strands one without the other. Strict OpenAI-compatible providers (e.g.
        // strict DeepSeek endpoints) reject an assistant `tool_calls` message that isn't followed
        // by its tool responses, and a `tool` message without a preceding `tool_calls`.
        val groups = mutableListOf<List<com.pusios.pots.network.dtos.openaicompatible.OpenAICompatibleChatRequestDto.Message>>()
        var index = 0
        while (index < nonSystemMessages.size) {
            val msg = nonSystemMessages[index]
            if (msg.role == "assistant" && !msg.tool_calls.isNullOrEmpty()) {
                var end = index + 1
                while (end < nonSystemMessages.size && nonSystemMessages[end].role == "tool") {
                    end++
                }
                groups.add(nonSystemMessages.subList(index, end).toList())
                index = end
            } else {
                groups.add(listOf(msg))
                index++
            }
        }

        // Keep whole groups from the end until we exceed the budget.
        val kept = mutableListOf<com.pusios.pots.network.dtos.openaicompatible.OpenAICompatibleChatRequestDto.Message>()
        var usedChars = 0
        for (group in groups.asReversed()) {
            val groupChars = group.sumOf { estimateMessageChars(it) }
            if (usedChars + groupChars > availableChars) break
            kept.addAll(0, group)
            usedChars += groupChars
        }

        return systemMessages + kept
    }

    /**
     * Trims History entries to fit within the estimated context window by dropping oldest messages
     * (keeping the most recent). Used by Gemini and Anthropic tool loops where the system prompt
     * is sent separately (not as a message).
     */
    private fun trimHistoryForContext(
        history: List<History>,
        systemPromptChars: Int = 0,
        contextWindowTokens: Int = ModelCatalog.DEFAULT_CONTEXT_WINDOW_TOKENS,
    ): List<History> {
        val maxChars = contextWindowTokens * ESTIMATED_CHARS_PER_TOKEN
        val totalChars = history.sumOf { it.content.length } + systemPromptChars
        if (totalChars <= maxChars) return history

        val availableChars = maxChars - systemPromptChars

        // Keep messages from the end until we exceed the budget
        val kept = mutableListOf<History>()
        var usedChars = 0
        for (msg in history.reversed()) {
            val msgChars = msg.content.length
            if (usedChars + msgChars > availableChars) break
            kept.add(0, msg)
            usedChars += msgChars
        }

        // Dropping from the front can cut a tool round in half; a tool result whose assistant
        // tool call was trimmed away is an orphan that strict providers reject.
        return kept.dropWhile { it.role == History.Role.TOOL }
    }

    /**
     * Compacts a history flow by summarizing older messages via an LLM call when the history
     * exceeds the configured share of the context window. Keeps recent exchanges verbatim and
     * replaces older ones with a single summary. The target share selects how much recent history
     * stays verbatim. If summarization fails, drops older turns only when the history no longer
     * fits the context window; cancellation propagates without touching history.
     */
    private suspend fun compactHistoryIfNeeded(
        tuning: AgentTuning = appSettings.getAgentTuning(),
        historyFlow: MutableStateFlow<List<History>> = chatHistory,
    ) {
        if (!tuning.autoCompact) return
        // Use the primary service's context window for compaction decisions.
        val firstInstance = getConfiguredServiceInstances().firstOrNull() ?: return
        val service = Service.fromId(firstInstance.serviceId) ?: return
        val modelId = appSettings.getSelectedModelId(service)
        val contextWindowTokens = ModelCatalog.estimateContextWindow(modelId)

        val history = historyFlow.value.filter { it.role != History.Role.TOOL_EXECUTING }
        val systemPromptChars = getActiveSystemPrompt()?.length ?: 0
        val totalChars = history.sumOf { it.content.length } + systemPromptChars
        val maxChars = contextWindowTokens * ESTIMATED_CHARS_PER_TOKEN
        val thresholdChars = (maxChars.toLong() * tuning.compactThresholdPercent / 100).toInt()
        if (totalChars <= thresholdChars) return

        val userIndices = history.mapIndexedNotNull { index, h ->
            if (h.role == History.Role.USER) index else null
        }
        if (userIndices.size <= tuning.compactKeepRecentTurns) return

        // Choose the earliest user turn whose suffix still fits the compacted target. Starting on
        // a user turn keeps every tool-call group in the retained suffix intact.
        val targetChars = (maxChars.toLong() * tuning.compactTargetPercent / 100).toInt()
        val lastAllowedOrdinal = userIndices.size - tuning.compactKeepRecentTurns
        var cutoffOrdinal = lastAllowedOrdinal
        for (ordinal in 0 until lastAllowedOrdinal) {
            val suffixChars = history.subList(userIndices[ordinal], history.size)
                .sumOf { it.content.length }
            if (suffixChars <= targetChars) {
                cutoffOrdinal = ordinal
                break
            }
        }
        val cutoffIndex = userIndices[cutoffOrdinal]
        val olderMessages = history.subList(0, cutoffIndex)
        val recentMessages = history.subList(cutoffIndex, history.size)
        if (olderMessages.isEmpty()) return

        val transcript = buildString {
            for (msg in olderMessages) {
                when (msg.role) {
                    History.Role.USER -> appendLine("User: ${msg.content}")

                    History.Role.ASSISTANT -> appendLine("Assistant: ${msg.content}")

                    History.Role.TOOL -> appendLine(
                        "Tool result${msg.toolName?.let { " ($it)" }.orEmpty()}: ${msg.content.take(2_000)}",
                    )

                    History.Role.TOOL_EXECUTING -> Unit
                }
                if (length >= MAX_SUMMARY_TRANSCRIPT_CHARS) break
            }
        }.take(MAX_SUMMARY_TRANSCRIPT_CHARS)

        val summaryBudgetChars = (targetChars / 4).coerceIn(1_000, 16_000)
        val summaryPrompt = "Summarize this conversation concisely, preserving key facts, decisions, " +
            "unfinished steps, and any information the assistant would need to continue. " +
            "Keep the summary under $summaryBudgetChars characters:\n\n$transcript"

        val summary = try {
            askSilentlyWithInstance(firstInstance.instanceId, summaryPrompt).trim()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // Summarization failed (offline, rate limit, …). While the history still fits the
            // context window, keep every turn — a transient error must not erase the conversation.
            // Only when it no longer fits at all, drop the older turns so the chat can continue.
            if (totalChars > maxChars) historyFlow.value = recentMessages
            return
        }
        if (summary.isEmpty()) {
            if (totalChars > maxChars) historyFlow.value = recentMessages
            return
        }

        val summaryEntry = History(
            role = History.Role.ASSISTANT,
            content = "[Conversation summary: ${summary.take(summaryBudgetChars)}]",
        )

        historyFlow.value = listOf(summaryEntry) + recentMessages
    }

    private fun trimToRecentExchanges(history: List<History>, maxExchanges: Int): List<History> {
        val userIndices = history.mapIndexedNotNull { index, h ->
            if (h.role == History.Role.USER) index else null
        }
        if (userIndices.size <= maxExchanges) return history
        val cutoffIndex = userIndices[userIndices.size - maxExchanges]
        return history.subList(cutoffIndex, history.size)
    }

    private suspend fun saveCurrentConversation() {
        val history = trimToRecentExchanges(chatHistory.value, 20)
        if (history.isEmpty()) return

        val now = Clock.System.now().toEpochMilliseconds()
        val conversationId = _currentConversationId.value ?: Uuid.random().toString().also {
            setCurrentConversationId(it)
        }

        val existingConversation = savedConversations.value.find { it.id == conversationId }

        val title = existingConversation?.title?.ifEmpty { null }
            ?: deriveTitle(history)
        val conversation = Conversation(
            id = conversationId,
            messages = history
                .filter { it.role != History.Role.TOOL_EXECUTING }
                .map { h ->
                    Conversation.Message(
                        id = h.id,
                        role = when (h.role) {
                            History.Role.USER -> "user"
                            History.Role.ASSISTANT -> "assistant"
                            History.Role.TOOL -> "tool"
                            History.Role.TOOL_EXECUTING -> "tool" // Should not happen due to filter
                        },
                        content = h.content,
                        attachments = h.attachments,
                        uiSubmission = h.uiSubmission,
                        isThinking = h.isThinking,
                        reasoningContent = h.reasoningContent,
                        turnMetrics = h.turnMetrics,
                    )
                },
            createdAt = existingConversation?.createdAt ?: now,
            updatedAt = now,
            title = title,
            type = existingConversation?.type ?: if (interactiveModeFlag) Conversation.TYPE_INTERACTIVE else Conversation.TYPE_CHAT,
        )

        conversationStorage.saveConversation(conversation)
    }

    override fun clearHistory() {
        chatHistory.update {
            emptyList()
        }
    }

    override fun supportedFileExtensions(): List<String> {
        val service = currentService() ?: return supportedFileExtensions - imageExtensions
        if (service.isOnDevice) {
            val allowsImages = localInferenceEngine?.getAvailableModels()?.firstOrNull { it.id == currentModelId() }?.supportsImages == true ||
                com.pusios.pots.inference.MODEL_CATALOG.firstOrNull { it.id == currentModelId() }?.supportsImages == true
            val extensionSet = if (allowsImages) supportedFileExtensions.toSet() else (supportedFileExtensions - imageExtensions)
            return extensionSet.toList()
        }
        // Images are offered only when both the service and the active model accept them —
        // mixed services (e.g. Z.AI) pair text-only models with multimodal ones.
        val imagesSupported = service.supportsImages && modelSupportsImages(currentModelId())
        val base = if (imagesSupported) supportedFileExtensions else supportedFileExtensions - imageExtensions
        return if (service.supportsPdf) base + "pdf" else base
    }

    override fun currentService(): Service? {
        val instances = getConfiguredServiceInstances()
        return instances.firstOrNull()?.let { Service.fromId(it.serviceId) }
    }

    /** Model id of the active instance, blank when nothing is configured yet. */
    private fun currentModelId(): String {
        val instance = getConfiguredServiceInstances().firstOrNull() ?: return ""
        val service = Service.fromId(instance.serviceId) ?: return ""
        return appSettings.getInstanceEffectiveModelId(instance.instanceId).ifEmpty { appSettings.getSelectedModelId(service) }
    }

    private fun setCurrentConversationId(id: String?) {
        _currentConversationId.value = id
        appSettings.setCurrentConversationId(id)
    }

    // Conversation management
    override fun loadConversations() {
        conversationStorage.loadConversations()
    }

    override fun loadConversation(id: String) {
        val conversation = savedConversations.value.find { it.id == id } ?: return

        setCurrentConversationId(id)
        chatHistory.value = conversation.messages.map { m ->
            // Prefer the modern `attachments` field. Fall back to the legacy single-file
            // fields for conversations saved before multi-attachment support.
            val attachments = when {
                m.attachments.isNotEmpty() -> m.attachments.toImmutableList()

                m.data != null && m.mimeType != null ->
                    persistentListOf(Attachment(data = m.data, mimeType = m.mimeType, fileName = m.fileName))

                else -> persistentListOf()
            }
            History(
                id = m.id,
                role = when (m.role) {
                    "user" -> History.Role.USER
                    "tool" -> History.Role.TOOL
                    else -> History.Role.ASSISTANT
                },
                content = m.content,
                attachments = attachments,
                uiSubmission = m.uiSubmission,
                isThinking = m.isThinking,
                reasoningContent = m.reasoningContent,
                turnMetrics = m.turnMetrics,
            )
        }
        recalculateSessionCounters()
    }

    override suspend fun deleteConversation(id: String) {
        if (_currentConversationId.value == id) {
            setCurrentConversationId(null)
            chatHistory.value = emptyList()
        }
        conversationStorage.deleteConversation(id)
        // Drop the per-conversation shell session so a future conversation reusing
        // this id (very unlikely — random uuids) doesn't inherit stale state, and
        // memory is freed.
        sandboxController.closeSession(id)
    }

    override fun regenerate() {
        chatHistory.update { history ->
            val lastUserIndex = history.indexOfLast { it.role == History.Role.USER }
            if (lastUserIndex >= 0) {
                history.subList(0, lastUserIndex + 1)
            } else {
                history
            }
        }
        recalculateSessionCounters()
    }

    override fun startNewChat() {
        setCurrentConversationId(null)
        chatHistory.value = emptyList()
        sessionTokenCount = 0
        sessionPromptTokenCount = 0
        sessionCompletionTokenCount = 0
        lastRequestPromptTokens = 0
    }

    override fun popLastExchange() {
        chatHistory.update { history ->
            val lastUserIndex = history.indexOfLast { it.role == History.Role.USER }
            if (lastUserIndex >= 0) history.take(lastUserIndex) else history
        }
        recalculateSessionCounters()
    }

    override fun truncateFrom(messageId: String) {
        chatHistory.update { history ->
            val index = history.indexOfFirst { it.id == messageId }
            if (index >= 0) history.take(index) else history
        }
        recalculateSessionCounters()
    }

    override fun restoreCurrentConversation() {
        // One-time migration for existing users: pin the latest conversation as the new
        // "current" pointer so the upgrade is non-disruptive.
        if (!appSettings.isCurrentConversationMigrated()) {
            val latest = savedConversations.value.maxByOrNull { it.updatedAt }
            if (latest != null) {
                loadConversation(latest.id)
            }
            appSettings.markCurrentConversationMigrated()
            return
        }

        // Already-loaded guard (covers re-entry from refreshSettings)
        val currentId = _currentConversationId.value
        if (currentId != null && chatHistory.value.isNotEmpty() &&
            savedConversations.value.any { it.id == currentId }
        ) {
            return
        }

        val persistedId = appSettings.getCurrentConversationId()
        if (persistedId != null && savedConversations.value.any { it.id == persistedId }) {
            loadConversation(persistedId)
        }
        // else: null id or stale id → leave history empty (this is the new-empty-chat state)
    }

    // Tool management
    override fun getLocalToolSelection(): LocalToolSelection = appSettings.getLocalToolSelection()

    override fun setLocalToolSelection(selection: LocalToolSelection) {
        appSettings.setLocalToolSelection(selection)
    }

    override fun getToolDefinitions(): List<ToolInfo> = getPlatformToolDefinitions()
        .filter { it.userToggleable }
        .map { it.copy(isEnabled = appSettings.isToolEnabled(it.id, defaultEnabled = it.isEnabled)) }

    override fun setToolEnabled(toolId: String, enabled: Boolean) {
        appSettings.setToolEnabled(toolId, enabled)
    }

    // MCP servers
    override fun getMcpServers(): List<McpServerConfig> = mcpServerManager.getServers()

    override suspend fun addMcpServer(name: String, url: String, headers: Map<String, String>): McpServerConfig = mcpServerManager.addServer(name, url, headers)

    override suspend fun removeMcpServer(serverId: String) {
        mcpServerManager.removeServer(serverId)
    }

    override suspend fun setMcpServerEnabled(serverId: String, enabled: Boolean) {
        mcpServerManager.setServerEnabled(serverId, enabled)
    }

    override suspend fun connectMcpServer(serverId: String): Result<List<ToolInfo>> {
        val result = mcpServerManager.connect(serverId)
        return result.map { mcpServerManager.getToolsForServer(serverId) }
    }

    override fun getMcpToolsForServer(serverId: String): List<ToolInfo> = mcpServerManager.getToolsForServer(serverId)

    override fun isMcpServerConnected(serverId: String): Boolean = mcpServerManager.isConnected(serverId)

    override suspend fun connectEnabledMcpServers() {
        mcpServerManager.connectEnabledServers()
    }

    override fun getMcpServerStatuses(): Map<String, McpServerStatus> = mcpServerManager.statuses.value

    override fun observeMcpStatuses(): Flow<Map<String, McpServerStatus>> = mcpServerManager.statuses

    override fun observeMcpConfirmation(): Flow<McpConfirmRequest?> = mcpServerManager.gate.pending

    override fun respondMcpConfirmation(requestId: String, allowed: Boolean) {
        mcpServerManager.gate.respond(requestId, allowed)
    }

    override fun hasMcpToken(serverId: String): Boolean = mcpServerManager.hasStoredToken(serverId)

    override suspend fun authorizeMcpServer(serverId: String): Result<String> = mcpServerManager.beginAuthorization(serverId)

    override fun getMcpPendingAuthorizationUrl(serverId: String): String? = mcpServerManager.pendingAuthorizationUrl(serverId)

    override suspend fun setMcpManualToken(serverId: String, token: String) {
        mcpServerManager.setManualToken(serverId, token)
    }

    override suspend fun clearMcpToken(serverId: String) {
        mcpServerManager.clearToken(serverId)
    }

    override fun getAgentProfiles(): List<AgentProfile> = appSettings.getAgentProfiles()

    override fun getActiveAgentProfile(): AgentProfile = appSettings.getActiveAgentProfile()

    override fun createAgentProfile(name: String): AgentProfile? = appSettings.createAgentProfile(name)

    override fun renameAgentProfile(id: String, name: String): Boolean = appSettings.renameAgentProfile(id, name)

    override fun deleteAgentProfile(id: String): Boolean {
        val wasActive = appSettings.getActiveAgentProfileId() == id
        val deleted = appSettings.deleteAgentProfile(id)
        if (deleted && wasActive) {
            mcpServerManager.closeDisabledProfileServers()
            startNewChat()
        }
        return deleted
    }

    override suspend fun activateAgentProfile(id: String): Boolean {
        if (appSettings.getActiveAgentProfileId() == id) return true
        if (!appSettings.activateAgentProfile(id)) return false
        mcpServerManager.closeDisabledProfileServers()
        startNewChat()
        mcpServerManager.connectEnabledServers()
        return true
    }

    override fun setActiveProfileSkillIds(ids: Set<String>) {
        appSettings.setActiveProfileSkillIds(ids)
    }

    override fun setAgentProfileSkillIds(id: String, ids: Set<String>) {
        appSettings.setAgentProfileSkillIds(id, ids)
    }

    // Skills
    override fun getInstalledSkills(): List<SkillManifest> = skillManager.getInstalled()

    override suspend fun uninstallSkill(id: String) {
        skillManager.uninstall(id)
    }

    override suspend fun browseSkillMarketplaces(): Result<List<RegistrySkillEntry>> = skillManager.browseMarketplaces()

    override suspend fun installBrowsedSkill(entry: RegistrySkillEntry): Result<SkillManifest> = skillManager.installFromRegistryEntry(entry)

    override suspend fun installGitHubSkill(owner: String, repo: String, ref: String, path: String): Result<SkillManifest> = skillManager.installFromGitHub(owner, repo, ref, path)

    // Soul (system prompt)
    override fun getAgentTuning(): AgentTuning = appSettings.getAgentTuning()

    override fun setAgentTuning(tuning: AgentTuning) {
        appSettings.setAgentTuning(tuning)
    }

    override fun getSoulText(): String = appSettings.getSoulText()

    override fun setSoulText(text: String) {
        appSettings.setSoulText(text)
    }

    override suspend fun getActiveSystemPrompt(variant: SystemPromptVariant): String? {
        val soul = appSettings.getSoulText().ifEmpty { getString(Res.string.default_soul) }
        val memoryEnabled = appSettings.isMemoryEnabled()
        val schedulingEnabled = appSettings.isSchedulingEnabled()

        val memoryInstructions = if (memoryEnabled) {
            appSettings.getMemoryInstructions().ifEmpty { null }
        } else {
            null
        }

        val memories = if (memoryEnabled) memoryStore.getAllMemories() else emptyList()
        val byCategory = memories.groupBy { it.category }

        val tasksSplit = if (schedulingEnabled) taskStore.getPendingTasksPartitioned() else PendingTaskPartition(emptyList(), emptyList())
        val pendingTasks = tasksSplit.scheduled
        val heartbeatAdditions = tasksSplit.heartbeatAdditions

        // Surface connected email accounts so the AI knows they exist in regular chat,
        // not just during heartbeats. Gated on the email toggle: if the user has email
        // off, the AI shouldn't reference the accounts. Whether the section actually
        // renders is decided by the prompt builder on the tool set (check_email).
        val emailAccounts = if (appSettings.isEmailEnabled()) {
            emailStore.getAccounts().map { account ->
                val state = emailStore.getSyncState(account.id)
                EmailAccountSummary(
                    email = account.email,
                    unreadCount = state.unreadCount,
                    lastSyncEpochMs = state.lastSyncEpochMs,
                    lastError = state.lastError,
                )
            }
        } else {
            emptyList()
        }

        val service = currentService()
        // On-device services store the active model ID per-instance, not globally, so
        // `getSelectedModelId` comes back blank for LiteRT. Fall back to the first
        // configured on-device instance's model ID in that case.
        val modelId = if (service == null) {
            ""
        } else {
            appSettings.getSelectedModelId(service).ifBlank {
                if (service.isOnDevice) {
                    getConfiguredServiceInstances()
                        .firstOrNull { Service.fromId(it.serviceId)?.isOnDevice == true }
                        ?.let { appSettings.getInstanceModelId(it.instanceId) }
                        .orEmpty()
                } else {
                    ""
                }
            }
        }
        val now = Clock.System.now()
        val timeZone = TimeZone.currentSystemDefault()
        val localDateTime = now.toLocalDateTime(timeZone)
        val offset = timeZone.offsetAt(now)
        val runtime = ChatPromptRuntimeContext(
            nowLocalIsoWithOffset = "$localDateTime$offset",
            timeZoneId = timeZone.id,
            nowUtcIsoString = now.toString(),
            platform = currentPlatform.displayName,
            modelId = modelId,
            providerName = service?.displayName.orEmpty(),
        )

        val isLimited = !supportsTools(modelId)
        val uiMode = when {
            interactiveModeFlag -> ChatPromptUiMode.INTERACTIVE_UI
            appSettings.isDynamicUiEnabled() && !isLimited -> ChatPromptUiMode.DYNAMIC_UI
            else -> ChatPromptUiMode.NONE
        }

        // Feature sections in the prompt are gated on the tools the request will
        // actually carry, so mirror the exact tool set here: remote uses the full set
        // (when the model supports tools), local uses the user's selection (when the
        // model can declare tools at all).
        val remoteTools = if (variant == SystemPromptVariant.CHAT_REMOTE && !isLimited) getAvailableTools() else emptyList()
        val toolNames: Set<String> = when (variant) {
            SystemPromptVariant.CHAT_REMOTE -> remoteTools.mapTo(mutableSetOf()) { it.schema.name }
            SystemPromptVariant.CHAT_LOCAL -> if (localModelDeclaresTools(modelId)) getLocalSafeTools().mapTo(mutableSetOf()) { it.schema.name } else emptySet()
        }
        val hasTools = toolNames.isNotEmpty()
        // Gating stays on the full set on purpose: a feature section (vault, ADB, media) describes
        // a capability the model can still reach through the catalogue, so dropping it would make
        // the prompt lie. What lazy delivery cuts is the schemas, replaced by this compact index.
        val toolCatalogue = if (remoteTools.isNotEmpty()) {
            val plan = ToolDelivery.plan(remoteTools, ToolActivation.activatedNames(activeConversationId()))
            if (plan.mode == ToolDeliveryMode.LAZY) plan.catalog.promptBlock() else null
        } else {
            null
        }

        val activeSkill = pendingActiveSkillId?.let { skillManager.getSkill(it) }

        val prompt = buildChatSystemPrompt(
            variant = variant,
            soul = soul,
            hasTools = hasTools,
            toolNames = toolNames,
            toolCatalogue = toolCatalogue,
            memoryEnabled = memoryEnabled,
            schedulingEnabled = schedulingEnabled,
            memoryInstructions = memoryInstructions,
            generalMemories = byCategory[MemoryCategory.GENERAL].orEmpty(),
            preferenceMemories = byCategory[MemoryCategory.PREFERENCE].orEmpty(),
            learningMemories = byCategory[MemoryCategory.LEARNING].orEmpty(),
            errorMemories = byCategory[MemoryCategory.ERROR].orEmpty(),
            pendingTasks = pendingTasks,
            heartbeatAdditions = heartbeatAdditions,
            emailAccounts = emailAccounts,
            runtime = runtime,
            uiMode = uiMode,
            activeSkill = activeSkill,
            // The on-device prompt has to fit the model's own window: litert-lm rejects the
            // whole turn when it does not, and there is no provider to fall back to.
            localPromptBudgetChars = if (variant == SystemPromptVariant.CHAT_LOCAL) {
                localPromptBudgetCharsFor(localModelContextTokens(modelId))
            } else {
                LOCAL_PROMPT_BUDGET_CHARS
            },
        )
        val authoring = if (uiMode == ChatPromptUiMode.INTERACTIVE_UI && activeSkill?.id != "mini-app-authoring") {
            skillManager.getSkill("mini-app-authoring")
        } else {
            null
        }
        return buildString {
            append(prompt)
            if (authoring != null) {
                append("\n\n## Mini-app authoring\n")
                append(authoring.body.trim())
            }
        }.ifEmpty { null }
    }

    override fun isDynamicUiEnabled(): Boolean = appSettings.isDynamicUiEnabled()

    override fun setDynamicUiEnabled(enabled: Boolean) {
        appSettings.setDynamicUiEnabled(enabled)
    }

    override fun getThemeMode(): ThemeMode = appSettings.getThemeMode()
    override fun getAppLanguage(): com.pusios.pots.data.AppLanguage = appSettings.getAppLanguage()

    override fun getAnimatedBackground() = appSettings.getAnimatedBackground()

    override fun setAnimatedBackground(kind: com.pusios.pots.ui.background.AnimatedBackground) {
        appSettings.setAnimatedBackground(kind)
    }

    override fun setThemeMode(mode: ThemeMode) {
        appSettings.setThemeMode(mode)
    }
    override fun setAppLanguage(mode: com.pusios.pots.data.AppLanguage) {
        appSettings.setAppLanguage(mode)
    }

    override fun getTtsEngine(): com.pusios.pots.data.TtsEngine = appSettings.getTtsEngine()
    override fun setTtsEngine(engine: com.pusios.pots.data.TtsEngine) = appSettings.setTtsEngine(engine)
    override fun getTtsSystemVoice(): String = appSettings.getTtsSystemVoice()
    override fun setTtsSystemVoice(voiceName: String) = appSettings.setTtsSystemVoice(voiceName)
    override fun getTtsPiperVoice(): String = appSettings.getTtsPiperVoice()
    override fun setTtsPiperVoice(voiceId: String) = appSettings.setTtsPiperVoice(voiceId)
    override fun getTtsRate(): Float = appSettings.getTtsRate()
    override fun setTtsRate(rate: Float) = appSettings.setTtsRate(rate)
    override fun getTtsPitch(): Float = appSettings.getTtsPitch()
    override fun setTtsPitch(pitch: Float) = appSettings.setTtsPitch(pitch)

    override fun getSttEngine(): com.pusios.pots.data.SttEngine = appSettings.getSttEngine()
    override fun setSttEngine(engine: com.pusios.pots.data.SttEngine) = appSettings.setSttEngine(engine)
    override fun getSttVoskModel(): String = appSettings.getSttVoskModel()
    override fun setSttVoskModel(modelId: String) = appSettings.setSttVoskModel(modelId)
    override fun getSttNeuralModel(): String = appSettings.getSttNeuralModel()
    override fun setSttNeuralModel(modelId: String) = appSettings.setSttNeuralModel(modelId)

    override val potsikLookFlow: StateFlow<PotsikLook> = appSettings.potsikLookFlow

    override fun getPotsikLook(): PotsikLook = appSettings.getPotsikLook()

    override fun setPotsikLook(look: PotsikLook) {
        appSettings.setPotsikLook(look)
    }

    private var interactiveModeFlag = appSettings.getCurrentInteractiveMode()

    override fun setInteractiveMode(enabled: Boolean) {
        interactiveModeFlag = enabled
        appSettings.setCurrentInteractiveMode(enabled)
    }

    override fun isInteractiveModeActive(): Boolean = interactiveModeFlag

    override fun seedInteractiveSession(screenJson: String, appName: String) {
        val now = Clock.System.now().toEpochMilliseconds()
        val conversationId = _currentConversationId.value ?: Uuid.random().toString().also {
            setCurrentConversationId(it)
        }
        chatHistory.value = listOf(
            History(
                role = History.Role.USER,
                content = "Edit the mini-app \"$appName\". The screen below is the current version; change it when I ask.",
            ),
            History(
                role = History.Role.ASSISTANT,
                content = "```pots-ui\n$screenJson\n```",
            ),
        )
        conversationStorage.saveConversation(
            Conversation(
                id = conversationId,
                messages = chatHistory.value.map { h ->
                    Conversation.Message(
                        id = h.id,
                        role = if (h.role == History.Role.USER) "user" else "assistant",
                        content = h.content,
                    )
                },
                createdAt = now,
                updatedAt = now,
                type = Conversation.TYPE_INTERACTIVE,
            ),
        )
    }

    override fun isMemoryEnabled(): Boolean = appSettings.isMemoryEnabled()

    override fun setMemoryEnabled(enabled: Boolean) {
        appSettings.setMemoryEnabled(enabled)
    }

    override fun getMemories(): List<MemoryEntry> = memoryStore.getAllMemories()

    override suspend fun deleteMemory(key: String) {
        memoryStore.forget(key)
    }

    override suspend fun updateMemoryContent(key: String, content: String) {
        memoryStore.updateContent(key, content)
    }

    override fun isSchedulingEnabled(): Boolean = appSettings.isSchedulingEnabled()

    override fun setSchedulingEnabled(enabled: Boolean) {
        appSettings.setSchedulingEnabled(enabled)
    }

    override fun getScheduledTasks(): List<ScheduledTask> = taskStore.getAllTasks()

    override val scheduledTasksFlow: StateFlow<List<ScheduledTask>> get() = taskStore.tasksFlow

    override suspend fun cancelScheduledTask(id: String) {
        taskStore.removeTask(id)
    }

    override fun isDaemonEnabled(): Boolean = appSettings.isDaemonEnabled()

    override fun setDaemonEnabled(enabled: Boolean) {
        appSettings.setDaemonEnabled(enabled)
    }

    override fun isSandboxEnabled(): Boolean = appSettings.isSandboxEnabled()

    override fun setSandboxEnabled(enabled: Boolean) {
        appSettings.setSandboxEnabled(enabled)
    }

    override fun getSandboxDistro(): LinuxDistro = appSettings.getSandboxDistro()

    override fun setSandboxDistro(distro: LinuxDistro) {
        appSettings.setSandboxDistro(distro)
    }

    override fun getPotsBuildLaunchAgent(): String? = appSettings.getPotsBuildLaunchAgent()

    override fun setPotsBuildLaunchAgent(agentId: String?) {
        appSettings.setPotsBuildLaunchAgent(agentId)
    }

    override fun getWebPanelPreferences(panelId: String): WebPanelPreferences = appSettings.getWebPanelPreferences(panelId)

    override fun setWebPanelPreferences(panelId: String, prefs: WebPanelPreferences) {
        appSettings.setWebPanelPreferences(panelId, prefs)
    }

    override fun getHeartbeatConfig(): HeartbeatConfig = heartbeatManager.getConfig()

    override fun setHeartbeatEnabled(enabled: Boolean) {
        val config = heartbeatManager.getConfig()
        heartbeatManager.saveConfig(config.copy(enabled = enabled))
    }

    override fun setHeartbeatIntervalMinutes(minutes: Int) {
        val config = heartbeatManager.getConfig()
        heartbeatManager.saveConfig(config.copy(intervalMinutes = minutes))
    }

    override fun setHeartbeatActiveHours(start: Int, end: Int) {
        val config = heartbeatManager.getConfig()
        heartbeatManager.saveConfig(config.copy(activeHoursStart = start, activeHoursEnd = end))
    }

    override fun getHeartbeatPrompt(): String = appSettings.getHeartbeatPrompt()

    override fun setHeartbeatPrompt(text: String) {
        appSettings.setHeartbeatPrompt(text)
    }

    override fun getHeartbeatLog(): List<HeartbeatLogEntry> = heartbeatManager.getHeartbeatLog()

    override fun getHeartbeatInstanceId(): String? = heartbeatManager.getConfig().heartbeatInstanceId

    override fun setHeartbeatInstanceId(instanceId: String?) {
        val config = heartbeatManager.getConfig()
        heartbeatManager.saveConfig(config.copy(heartbeatInstanceId = instanceId))
    }

    override fun getHeartbeatFallbackInstanceId(): String? = heartbeatManager.getConfig().heartbeatFallbackInstanceId

    override fun setHeartbeatFallbackInstanceId(instanceId: String?) {
        val config = heartbeatManager.getConfig()
        heartbeatManager.saveConfig(config.copy(heartbeatFallbackInstanceId = instanceId))
    }

    override fun isEmailEnabled(): Boolean = appSettings.isEmailEnabled()

    override fun setEmailEnabled(enabled: Boolean) {
        appSettings.setEmailEnabled(enabled)
    }

    override fun getEmailAccounts(): List<EmailAccount> = emailStore.getAccounts()

    override suspend fun removeEmailAccount(id: String) {
        emailStore.removeAccount(id)
    }

    override fun getEmailPollIntervalMinutes(): Int = appSettings.getEmailPollIntervalMinutes()

    override fun getPendingEmailCount(): Int = emailStore.getPending().size

    override fun getEmailSyncStates(): Map<String, EmailSyncState> = emailStore.getAllSyncStates()

    override suspend fun pollEmailAccount(accountId: String) {
        val account = emailStore.getAccount(accountId) ?: return
        emailPoller.poll(account)
    }

    override fun setEmailPollIntervalMinutes(minutes: Int) {
        appSettings.setEmailPollIntervalMinutes(minutes)
    }

    override fun isSmsEnabled(): Boolean = appSettings.isSmsEnabled()

    override fun setSmsEnabled(enabled: Boolean) {
        appSettings.setSmsEnabled(enabled)
    }

    override fun getSmsPollIntervalMinutes(): Int = appSettings.getSmsPollIntervalMinutes()

    override fun setSmsPollIntervalMinutes(minutes: Int) {
        appSettings.setSmsPollIntervalMinutes(minutes)
    }

    override fun getPendingSmsCount(): Int = smsStore.getPending().size

    override fun getSmsSyncState(): SmsSyncState = smsStore.getSyncState()

    override fun hasSmsPermission(): Boolean = smsReader.hasPermission()

    override suspend fun requestSmsPermission(): Boolean = smsPermissionController.requestPermission()

    override suspend fun pollSms() {
        smsPoller.poll()
    }

    override fun isSmsSendEnabled(): Boolean = appSettings.isSmsSendEnabled()

    override fun setSmsSendEnabled(enabled: Boolean) {
        appSettings.setSmsSendEnabled(enabled)
    }

    override fun hasSmsSendPermission(): Boolean = smsSender.hasPermission()

    override suspend fun requestSmsSendPermission(): Boolean = smsSendPermissionController.requestPermission()

    override val smsDrafts: StateFlow<List<SmsDraft>> = smsDraftStore.drafts

    // Flips the draft to SENDING, delegates to SmsSender, then updates to SENT/FAILED.
    // Explicit user-triggered (never AI-triggered) — the banner is the gate.
    override suspend fun sendSmsDraft(draftId: String): Boolean {
        val draft = smsDraftStore.getDraft(draftId) ?: return false
        if (draft.status != SmsDraftStatus.PENDING) return false
        smsDraftStore.updateStatus(draftId, SmsDraftStatus.SENDING)
        return when (val result = smsSender.send(draft.address, draft.body)) {
            is SmsSendResult.Success -> {
                smsDraftStore.updateStatus(draftId, SmsDraftStatus.SENT)
                true
            }

            is SmsSendResult.Failure -> {
                smsDraftStore.updateStatus(draftId, SmsDraftStatus.FAILED, result.message)
                false
            }
        }
    }

    override suspend fun discardSmsDraft(draftId: String) {
        smsDraftStore.removeDraft(draftId)
    }

    override fun isNotificationsEnabled(): Boolean = appSettings.isNotificationsEnabled()

    override fun setNotificationsEnabled(enabled: Boolean) {
        appSettings.setNotificationsEnabled(enabled)
    }

    override fun isNotificationListenerAccessGranted(): Boolean = notificationListenerController.isAccessGranted()

    override fun openNotificationListenerSettings() {
        notificationListenerController.openAccessSettings()
    }

    override fun getPendingNotificationCount(): Int = notificationStore.getPending().size

    override fun getNotificationSyncState(): NotificationSyncState = notificationStore.getSyncState()

    override suspend fun clearPendingNotifications() {
        notificationStore.clearPending()
    }

    override fun getUiScale(): Float = appSettings.getUiScale()

    override fun setUiScale(scale: Float) {
        appSettings.setUiScale(scale)
    }

    override fun exportSettingsToJson(sections: Set<ImportSection>): String {
        val toolIds = getPlatformToolDefinitions().map { it.id }
        val jsonObject = appSettings.exportToJson(toolIds, sections, conversationStorage.conversations.value)
        return prettyJson.encodeToString(JsonObject.serializer(), jsonObject)
    }

    override fun getExportPreview(): Map<ImportSection, String?> {
        val toolIds = getPlatformToolDefinitions().map { it.id }
        val jsonObject = appSettings.exportToJson(toolIds, conversations = conversationStorage.conversations.value)
        return detectExportableSections(jsonObject)
    }

    override fun importSettingsFromJson(json: String, sections: Set<ImportSection>, replace: Boolean): Int {
        val jsonObject = SharedJson.parseToJsonElement(json).jsonObject
        val toolIds = getPlatformToolDefinitions().map { it.id }
        return appSettings.importFromJson(jsonObject, toolIds, sections, replace)
    }

    override suspend fun askWithTools(
        prompt: String,
        instanceId: String?,
        conversationIdOverride: String?,
        modelIdOverride: String?,
    ): String {
        val targetInstance = resolveAskInstance(getConfiguredServiceInstances(), instanceId)
        val service = Service.fromId(targetInstance.serviceId)
            ?: throw NoConfiguredServiceException("unknown service ${targetInstance.serviceId}")
        val targetInstanceId = targetInstance.instanceId
        val messages = listOf(History(role = History.Role.USER, content = prompt))
        val systemPrompt = getActiveSystemPrompt()
        // Use a local history to avoid polluting the current conversation's chatHistory
        val localHistory = MutableStateFlow(messages)
        // Every askWithTools run is headless (heartbeat / scheduled task): tools that need
        // the user on screen — the vault request form — must refuse immediately.
        val runContext = if (conversationIdOverride != null) {
            // Bind tool calls to that conversation's sandbox session via the coroutine
            // context — otherwise tool dispatch would inherit `_currentConversationId`
            // (the chat the user is viewing), routing the run's shell commands into
            // that chat's persistent bash session.
            ConversationIdElement(conversationIdOverride) + HeadlessRunElement()
        } else {
            HeadlessRunElement()
        }
        return withContext(runContext) {
            askWithService(service, messages, systemPrompt, targetInstanceId, localHistory, modelIdOverride).content
        }
    }

    override suspend fun askSilently(question: String): String {
        val target = getOrderedFallbackEntries().firstOrNull() ?: return ""
        val service = target.service
        val instanceId = target.instanceId
        val messages = listOf(History(role = History.Role.USER, content = question))

        if (service.isOnDevice) {
            // Throwaway history — we don't want tool-execution rows leaking into the
            // visible chatHistory for a "silent" call. LOCAL variant of the system
            // prompt so small on-device models get the right section set.
            val localPrompt = getActiveSystemPrompt(SystemPromptVariant.CHAT_LOCAL)
            return askWithLocalEngine(messages, localPrompt, instanceId, MutableStateFlow(messages)).content
        }

        val systemPrompt = getActiveSystemPrompt()
        val creds = instanceCredentials(instanceId, service)

        return plainChat(service, creds, messages, systemPrompt, retry = false).content
    }

    override suspend fun askSilentlyWithInstance(instanceId: String, prompt: String, timeoutMs: Long): String {
        val instance = getConfiguredServiceInstances().find { it.instanceId == instanceId }
            ?: return askSilently(prompt)
        val service = Service.fromId(instance.serviceId) ?: return ""
        val messages = listOf(History(role = History.Role.USER, content = prompt))

        if (service.isOnDevice) {
            return askWithLocalEngine(messages, null, instanceId, MutableStateFlow(messages)).content
        }

        val creds = instanceCredentials(instanceId, service)

        return plainChat(
            service = service,
            credentials = creds,
            messages = messages,
            systemPrompt = null,
            requestTimeoutMs = timeoutMs.takeIf { it > 0 },
            retry = false,
        ).content
    }

    private val _hasUnreadHeartbeat = MutableStateFlow(false)
    override val hasUnreadHeartbeat: StateFlow<Boolean> = _hasUnreadHeartbeat

    override fun clearUnreadHeartbeat() {
        _hasUnreadHeartbeat.value = false
    }

    private val _openHeartbeatRequested = MutableStateFlow(false)
    override val openHeartbeatRequested: StateFlow<Boolean> = _openHeartbeatRequested

    override fun requestOpenHeartbeat() {
        _openHeartbeatRequested.value = true
    }

    override fun consumeOpenHeartbeatRequest() {
        _openHeartbeatRequested.value = false
    }

    private val _openAssistRequested = MutableStateFlow(false)
    override val openAssistRequested: StateFlow<Boolean> = _openAssistRequested

    override fun requestOpenAssist() {
        _openAssistRequested.value = true
    }

    override fun consumeOpenAssistRequest() {
        _openAssistRequested.value = false
    }

    private val _pendingShareText = MutableStateFlow<String?>(null)
    override val pendingShareText: StateFlow<String?> = _pendingShareText

    override fun requestOpenShare(text: String) {
        _pendingShareText.value = text
    }

    override fun consumeOpenShareRequest() {
        _pendingShareText.value = null
    }

    override suspend fun addAssistantMessage(content: String) {
        val now = Clock.System.now().toEpochMilliseconds()

        val existing = savedConversations.value.find { it.type == Conversation.TYPE_HEARTBEAT }
        val heartbeatId = existing?.id ?: getOrCreateHeartbeatConversationId()

        val newMessage = Conversation.Message(
            id = Uuid.random().toString(),
            role = "assistant",
            content = content,
        )

        val messages = ((existing?.messages ?: emptyList()) + newMessage).takeLast(MAX_HEARTBEAT_MESSAGES)

        val conversation = Conversation(
            id = heartbeatId,
            messages = messages,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            type = Conversation.TYPE_HEARTBEAT,
        )

        _hasUnreadHeartbeat.value = true
        conversationStorage.saveConversation(conversation)
    }

    override suspend fun getOrCreateHeartbeatConversationId(): String {
        val existing = savedConversations.value.find { it.type == Conversation.TYPE_HEARTBEAT }
        if (existing != null) return existing.id
        val now = Clock.System.now().toEpochMilliseconds()
        val id = Uuid.random().toString()
        conversationStorage.saveConversation(
            Conversation(
                id = id,
                messages = emptyList(),
                createdAt = now,
                updatedAt = now,
                type = Conversation.TYPE_HEARTBEAT,
            ),
        )
        return id
    }

    private fun deriveTitle(history: List<History>): String {
        val firstUserMessage = history.firstOrNull { it.role == History.Role.USER }?.content ?: return ""
        return if (firstUserMessage.length <= 50) {
            firstUserMessage
        } else {
            val truncated = firstUserMessage.take(50)
            val lastSpace = truncated.lastIndexOf(' ')
            if (lastSpace > 20) truncated.substring(0, lastSpace) + "..." else truncated + "..."
        }
    }

    // On-device inference (LiteRT)

    override fun isLocalInferenceAvailable(): Boolean = localInferenceEngine != null
    override fun getLocalInferenceEngine(): com.pusios.pots.inference.LocalInferenceEngine? = localInferenceEngine

    override fun getLocalEngineState(): StateFlow<EngineState>? = localInferenceEngine?.engineState

    override fun getLocalDownloadingModelId(): StateFlow<String?>? = localInferenceEngine?.downloadingModelId

    override fun getLocalDownloadProgress(): StateFlow<Float?>? = localInferenceEngine?.downloadProgress

    override fun getLocalDownloadError(): StateFlow<DownloadError?>? = localInferenceEngine?.downloadError

    override fun getLocalDownloadedModels(): List<DownloadedModel> = localInferenceEngine?.getDownloadedModels() ?: emptyList()

    override fun getLocalAvailableModels(): List<LocalModel> = localInferenceEngine?.getAvailableModels() ?: emptyList()

    override fun getLocalImportedModels(): List<LocalModel> = localInferenceEngine?.getImportedLocalModels() ?: emptyList()

    override fun getLocalFreeSpaceBytes(): Long = localInferenceEngine?.getFreeSpaceBytes() ?: 0L

    override fun getTotalDeviceMemoryBytes(): Long = getTotalMemoryBytes()

    override fun getModelContextTokens(modelId: String): Int = appSettings.getModelContextTokens(modelId)

    override fun setModelContextTokens(modelId: String, contextTokens: Int) {
        appSettings.setModelContextTokens(modelId, contextTokens)
    }

    override suspend fun releaseLocalEngine() {
        localInferenceEngine?.release()
    }

    override fun getLocalImportingFileName(): StateFlow<String?>? = localInferenceEngine?.importingFileName

    override fun getLocalImportProgress(): StateFlow<Float?>? = localInferenceEngine?.importProgress

    override fun getLocalImportError(): StateFlow<ModelImportError?>? = localInferenceEngine?.importError

    override fun startLocalModelDownload(model: LocalModel) {
        localInferenceEngine?.startDownload(model)
    }

    override fun cancelLocalModelDownload() {
        localInferenceEngine?.cancelDownload()
    }

    override suspend fun importLocalModel(source: PlatformFile): ModelImportResult {
        val engine = localInferenceEngine
            ?: return ModelImportResult.Failure(ModelImportError.COPY_FAILED, "On-device inference not available")
        return engine.importModel(source)
    }

    override fun cancelLocalModelImport() {
        localInferenceEngine?.cancelImport()
    }

    override suspend fun deleteLocalModel(modelId: String) {
        localInferenceEngine?.deleteModel(modelId)
    }
}

/**
 * Which configured instance a background run (heartbeat, scheduled task) must use.
 *
 * An explicit pin has to resolve: reporting on "the first remote" because the chosen provider
 * was deleted or re-created under a new id is worse than failing, since the owner would be
 * looking at the wrong model's behaviour. "First remote, then on-device" only answers the
 * question "no preference expressed". An empty catalogue is an error too — it used to be an
 * empty string, which the scheduler recorded as a successful run that did nothing.
 */
internal fun resolveAskInstance(instances: List<ServiceInstance>, instanceId: String?): ServiceInstance {
    if (instances.isEmpty()) throw NoConfiguredServiceException("no configured service instances")
    if (instanceId == null) {
        return instances.firstOrNull { Service.fromId(it.serviceId)?.isOnDevice == false } ?: instances.first()
    }
    return instances.find { it.instanceId == instanceId } ?: throw ConfiguredInstanceNotFoundException(instanceId)
}

/**
 * Picks the model a service lands on when nothing is stored yet: the service's curated
 * default first, then the everyday model from the [RecommendedModels] table, then the
 * kimi-k2.5 heuristic, then the first entry. Never overwrites a saved pick — callers
 * only invoke this when no model is selected yet.
 */
internal fun pickDefaultModel(models: List<SettingsModel>, service: Service): SettingsModel? {
    val defaultModel = service.defaultModel
    if (defaultModel != null) {
        models.firstOrNull { it.id == defaultModel }?.let { return it }
    }
    RecommendedModels.everydayModelId(service)?.let { everyday ->
        models.firstOrNull { it.id == everyday }?.let { return it }
    }
    return models.firstOrNull { it.id.contains("kimi-k2.5", ignoreCase = true) }
        ?: models.firstOrNull()
}
