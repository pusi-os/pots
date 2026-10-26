package com.pusios.pots.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pusios.pots.data.Conversation
import com.pusios.pots.data.DataRepository
import com.pusios.pots.data.FreeMode
import com.pusios.pots.data.ScheduledTask
import com.pusios.pots.data.Service
import com.pusios.pots.data.ServiceEntry
import com.pusios.pots.data.TaskScheduler
import com.pusios.pots.data.TaskStore
import com.pusios.pots.data.TaskTrigger
import com.pusios.pots.data.UiSubmission
import com.pusios.pots.data.getAvailableServiceEntries
import com.pusios.pots.data.miniapp.MiniAppDraft
import com.pusios.pots.data.miniapp.MiniAppRepository
import com.pusios.pots.data.miniapp.MiniAppScreen
import com.pusios.pots.data.miniapp.MiniAppSummary
import com.pusios.pots.getBackgroundDispatcher
import com.pusios.pots.network.UiError
import com.pusios.pots.network.shouldShowFreeProviderSuggestions
import com.pusios.pots.network.toUiError
import com.pusios.pots.tools.AppPermission
import com.pusios.pots.tools.PermissionController
import com.pusios.pots.tools.isLocalNetworkUrl
import com.pusios.pots.ui.markdown.PotsUiBlock
import com.pusios.pots.ui.markdown.PotsUiError
import com.pusios.pots.ui.markdown.parseMarkdown
import com.pusios.pots.ui.potsik.PotsikLook
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.extension
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.jetbrains.compose.resources.getString
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.conversation_untitled
import pots.composeapp.generated.resources.error_local_network_permission
import pots.composeapp.generated.resources.error_unsupported_file_type
import pots.composeapp.generated.resources.litert_no_model_warning
import pots.composeapp.generated.resources.mini_app_nothing_to_save
import pots.composeapp.generated.resources.mini_app_saved
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class, kotlin.time.ExperimentalTime::class)
class ChatViewModel(
    private val dataRepository: DataRepository,
    private val taskScheduler: TaskScheduler,
    private val backgroundDispatcher: CoroutineContext = getBackgroundDispatcher(),
    private val localNetworkPermissionController: PermissionController = PermissionController(AppPermission.LOCAL_NETWORK),
    private val miniAppRepository: MiniAppRepository? = null,
    private val taskStore: TaskStore? = null,
) : ViewModel() {

    private val actions = ChatActions(
        ask = ::ask,
        retry = ::retry,
        toggleSpeechOutput = ::toggleSpeechOutput,
        clearHistory = ::clearHistory,
        setIsSpeaking = ::setIsSpeaking,
        addFile = ::addFile,
        removeFile = ::removeFile,
        startNewChat = { startNewChat() },
        regenerate = ::regenerate,
        cancel = ::cancel,
        selectService = ::selectService,
        loadConversation = ::loadConversation,
        deleteConversation = ::deleteConversation,
        clearUnreadHeartbeat = ::clearUnreadHeartbeat,
        clearSnackbar = ::clearSnackbar,
        undoDeleteConversation = ::undoDeleteConversation,
        submitUiCallback = ::submitUiCallback,
        resubmit = ::resubmit,
        enterInteractiveMode = ::enterInteractiveMode,
        exitInteractiveMode = ::exitInteractiveMode,
        goBackInteractiveMode = ::goBackInteractiveMode,
        sendSmsDraft = ::sendSmsDraft,
        discardSmsDraft = ::discardSmsDraft,
        consumeComposerPrefill = ::consumeComposerPrefill,
        setPotsikLook = ::setPotsikLook,
        setSpeechOutputEnabled = ::setSpeechOutputEnabled,
        newMiniAppSession = ::newMiniAppSession,
        saveMiniApp = ::saveMiniApp,
        editMiniApp = ::editMiniApp,
        runMiniAppAutomation = ::runMiniAppAutomation,
        runMiniAppPrompt = ::runMiniAppPrompt,
    )
    private var currentJob: Job? = null
    private var pendingConversationDeleteJob: Job? = null
    private val _state = MutableStateFlow(
        ChatUiState(
            actions = actions,
            showPrivacyInfo = dataRepository.isUsingSharedKey(),
            potsikLook = dataRepository.getPotsikLook(),
        ),
    )

    init {
        updateAvailableServices()

        // Keep restoreCurrentConversation off the main thread; see issue #197 (large persisted
        // tool outputs caused ANRs when JSON-decoded synchronously during VM construction).
        // ChatScreen gates the interactive-mode branch on !isRestoring to avoid a flash.
        viewModelScope.launch(backgroundDispatcher) {
            dataRepository.loadConversations()
            // Share and assist both want a fresh chat. Skip restore so a slow
            // load cannot clobber startNewChat() when Pots is cold-started from
            // ACTION_SEND or ACTION_ASSIST.
            val skipRestore = dataRepository.pendingShareText.value != null ||
                dataRepository.openAssistRequested.value
            if (!skipRestore) {
                dataRepository.restoreCurrentConversation()
                presetInteractiveModeForCurrentConversation()
            }
            _state.update { it.copy(isRestoring = false) }
        }

        viewModelScope.launch(backgroundDispatcher) {
            dataRepository.connectEnabledMcpServers()
        }
        viewModelScope.launch {
            dataRepository.fallbackStatus.collect { status ->
                _state.update { it.copy(fallbackStatus = status) }
            }
        }
        // The character's look can change from the settings while the chat is
        // open, so it is followed rather than read once.
        viewModelScope.launch {
            dataRepository.potsikLookFlow.collect { look ->
                _state.update { it.copy(potsikLook = look) }
            }
        }
        taskScheduler.isLoadingCheck = { _state.value.isLoading }
        taskScheduler.start()

        viewModelScope.launch {
            dataRepository.smsDrafts.collect { drafts ->
                _state.update { it.copy(smsDrafts = drafts.toImmutableList()) }
            }
        }

        viewModelScope.launch {
            dataRepository.openHeartbeatRequested
                .filter { it }
                .collect {
                    val heartbeatId = dataRepository.savedConversations.value
                        .firstOrNull { it.type == Conversation.TYPE_HEARTBEAT }?.id
                    if (heartbeatId != null) {
                        loadConversation(heartbeatId)
                        clearUnreadHeartbeat()
                    }
                    dataRepository.consumeOpenHeartbeatRequest()
                }
        }

        viewModelScope.launch {
            dataRepository.openAssistRequested
                .filter { it }
                .collect {
                    startNewChat()
                    dataRepository.consumeOpenAssistRequest()
                }
        }

        viewModelScope.launch {
            dataRepository.pendingShareText
                .filterNotNull()
                .collect { text ->
                    startNewChat(composerPrefill = text)
                    dataRepository.consumeOpenShareRequest()
                }
        }
    }

    private val miniAppsFlow = miniAppRepository?.apps ?: flowOf(emptyList())
    private val miniAppToolbarIdsFlow = miniAppRepository?.toolbarIds ?: flowOf(null)

    val state = combine(
        combine(
            _state,
            dataRepository.chatHistory,
            dataRepository.savedConversations,
            dataRepository.currentConversationId,
            dataRepository.hasUnreadHeartbeat,
        ) { state, history, conversations, conversationId, hasUnreadHeartbeat ->
            val summaries = conversations
                .sortedByDescending { it.updatedAt }
                .map {
                    val isHeartbeat = it.type == Conversation.TYPE_HEARTBEAT
                    val isInteractive = it.type == Conversation.TYPE_INTERACTIVE
                    ConversationSummary(
                        id = it.id,
                        title = if (isHeartbeat) "" else it.title.ifEmpty { getString(Res.string.conversation_untitled) },
                        updatedAt = it.updatedAt,
                        isHeartbeat = isHeartbeat,
                        isInteractive = isInteractive,
                    )
                }
            state.copy(
                history = history.toImmutableList(),
                supportedFileExtensions = dataRepository.supportedFileExtensions().toImmutableList(),
                savedConversations = summaries.toImmutableList(),
                currentConversationId = conversationId,
                hasUnreadHeartbeat = hasUnreadHeartbeat,
                installedSkills = dataRepository.getInstalledSkills().toImmutableList(),
            )
        },
        miniAppsFlow,
        miniAppToolbarIdsFlow,
    ) { state, apps, toolbarIds ->
        val summaries = apps.map { MiniAppSummary.of(it) }.toImmutableList()
        val pinned = if (toolbarIds == null) {
            summaries.filter { it.visible }
        } else {
            val byId = summaries.associateBy { it.id }
            toolbarIds.mapNotNull { byId[it] }
        }
        state.copy(miniApps = summaries, pinnedMiniApps = pinned.toImmutableList())
    }.distinctUntilChanged().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = _state.value,
    )

    private fun submitUiCallback(event: String, data: Map<String, String>) {
        val message = if (data.isNotEmpty()) {
            val formattedData = data.entries.joinToString(", ") { "${it.key}: ${it.value}" }
            "Responded with: $formattedData"
        } else {
            "Pressed: $event"
        }
        val lastAssistant = dataRepository.chatHistory.value.lastRenderedAssistant()
        val submission = lastAssistant?.let {
            UiSubmission(sourceContent = it.content, values = data, pressedEvent = event)
        }
        askInternal(message, submission)
    }

    private fun ask(question: String?) {
        askInternal(question, null)
    }

    private fun askInternal(question: String?, uiSubmission: UiSubmission?) {
        // Prevent concurrent requests
        if (_state.value.isLoading) return

        // Capture files before launching coroutine to avoid race with files being cleared
        val files = _state.value.files

        val (strippedQuestion, activeSkillId) = parseSkillInvocation(question)

        currentJob = viewModelScope.launch(backgroundDispatcher) {
            _state.update {
                it.copy(
                    isLoading = true,
                    error = null,
                    showFreeProviderSuggestions = false,
                    files = persistentListOf(),
                )
            }
            // Android 17+ blocks LAN traffic without the local network permission — without
            // asking first, requests to self-hosted servers silently never leave the device.
            if (!ensureLocalNetworkPermission()) {
                _state.update {
                    it.copy(
                        error = UiError.Resource(Res.string.error_local_network_permission),
                        showFreeProviderSuggestions = false,
                        isLoading = false,
                    )
                }
                return@launch
            }
            try {
                dataRepository.ask(strippedQuestion, files, uiSubmission, activeSkillId)

                // Auto-retry in interactive mode if the response has no valid pots-ui
                if (_state.value.isInteractiveMode) {
                    retryIfNoValidPotsUi()
                }

                _state.update {
                    it.copy(isLoading = false)
                }
            } catch (exception: Exception) {
                // CancellationException must be re-thrown to properly propagate coroutine cancellation
                if (exception is CancellationException) throw exception

                val showUpsell = shouldShowFreeProviderSuggestions(
                    noConfiguredServices = dataRepository.getConfiguredServiceInstances().isEmpty(),
                    exception = exception,
                )
                _state.update {
                    it.copy(
                        error = exception.toUiError(),
                        showFreeProviderSuggestions = showUpsell,
                        isLoading = false,
                    )
                }
            }
        }
    }

    /**
     * True unless the active service points at a local network host and the user
     * denied the local network permission. Cheap no-op on non-Android platforms.
     */
    private suspend fun ensureLocalNetworkPermission(): Boolean {
        val instance = dataRepository.getConfiguredServiceInstances().firstOrNull() ?: return true
        val baseUrl = dataRepository.getInstanceBaseUrl(instance.instanceId, Service.fromId(instance.serviceId))
        if (!isLocalNetworkUrl(baseUrl)) return true
        return localNetworkPermissionController.requestPermission()
    }

    private suspend fun retryIfNoValidPotsUi(maxRetries: Int = 2) {
        repeat(maxRetries) {
            currentCoroutineContext().ensureActive()
            val lastAssistant = dataRepository.chatHistory.value.lastRenderedAssistant() ?: return

            val blocks = parseMarkdown(lastAssistant.content).blocks
            val hasValidUi = blocks.any { it is PotsUiBlock }
            if (hasValidUi) return

            // Build error feedback for the AI
            val errorBlock = blocks.filterIsInstance<PotsUiError>().firstOrNull()
            val errorDetail = if (errorBlock != null) {
                "JSON parse error in: ${errorBlock.rawJson.take(200)}"
            } else {
                "No pots-ui code fence found in your response."
            }
            val retryMessage = "[SYSTEM] Your previous response failed to render as interactive UI. $errorDetail " +
                "Remember: respond with ONLY a single ```pots-ui code fence containing valid JSON. No text outside the fence."

            dataRepository.ask(retryMessage, emptyList())
        }
    }

    private fun setSpeechOutputEnabled(enabled: Boolean) {
        _state.update { it.copy(isSpeechOutputEnabled = enabled) }
    }

    private fun newMiniAppSession() {
        currentJob?.cancel()
        currentJob = null
        dataRepository.startNewChat()
        dataRepository.setInteractiveMode(true)
        _state.update {
            it.copy(
                isInteractiveMode = true,
                isLoading = false,
                error = null,
                showFreeProviderSuggestions = false,
                miniAppDraftId = Uuid.random().toString(),
                editingMiniAppId = null,
                editingMiniAppName = null,
                editingMiniAppDescription = null,
                editingMiniAppIcon = null,
            )
        }
    }

    private fun saveMiniApp(name: String, description: String, icon: String) {
        val repository = miniAppRepository ?: return
        val screens = collectScreens()
        val state = _state.value
        val id = state.editingMiniAppId ?: state.miniAppDraftId ?: Uuid.random().toString()
        val existing = repository.get(id)
        val meta = repository.draftMeta(id)
        val entryPoint = meta.entryPoint ?: existing?.entryPoint
        if (screens.isEmpty() && entryPoint == null) {
            _state.update { it.copy(snackbarMessage = Res.string.mini_app_nothing_to_save) }
            return
        }
        viewModelScope.launch(backgroundDispatcher) {
            val app = repository.saveDraft(
                MiniAppDraft(
                    id = id,
                    name = name,
                    description = description.ifBlank { existing?.description.orEmpty() },
                    icon = icon.ifBlank { existing?.icon ?: "🧩" },
                    screens = screens,
                    sourceConversationId = dataRepository.currentConversationId.value ?: id,
                    message = if (state.editingMiniAppId == null) "Created" else "Saved from the editor",
                    kind = if (entryPoint != null) meta.kind else existing?.kind ?: com.pusios.pots.data.miniapp.MiniAppKind.NATIVE,
                    entryPoint = entryPoint,
                ),
            )
            syncAutomations(app)
            _state.update {
                it.copy(
                    miniAppDraftId = app.id,
                    editingMiniAppId = app.id,
                    editingMiniAppName = app.name,
                    editingMiniAppDescription = app.description,
                    editingMiniAppIcon = app.icon,
                    snackbarMessage = Res.string.mini_app_saved,
                )
            }
        }
    }

    private suspend fun syncAutomations(app: com.pusios.pots.data.miniapp.MiniApp) {
        val store = taskStore ?: return
        if (!dataRepository.isSchedulingEnabled()) return
        val prefix = "Mini-app ${app.id}:"
        store.getAllTasks()
            .filter { it.description.startsWith(prefix) }
            .forEach { store.removeTask(it.id) }
        app.automations.forEach { automation ->
            val cron = automation.cron?.takeIf { it.isNotBlank() } ?: return@forEach
            store.addTask(
                description = "$prefix ${automation.title}",
                prompt = automationPrompt(app, automation),
                scheduledAtEpochMs = 0L,
                cron = cron,
                trigger = TaskTrigger.CRON,
            )
        }
    }

    private fun automationPrompt(
        app: com.pusios.pots.data.miniapp.MiniApp,
        automation: com.pusios.pots.data.miniapp.MiniAppAutomation,
    ): String = buildString {
        append("Mini-app automation for \"").append(app.name).append("\" (id: ").append(app.id).append(").\n")
        append("Task: ").append(automation.prompt).append("\n")
        if (app.collections.isNotEmpty()) {
            append("Collections: ").append(app.collections.joinToString { it.name }).append(".\n")
        }
        append("Use mini_app_data with app_id=\"").append(app.id).append("\" to read or write this app's records. ")
        append("Report the outcome briefly; the result is shown in the heartbeat conversation.")
    }

    private fun runMiniAppAutomation(appId: String, automationId: String) {
        val repository = miniAppRepository ?: return
        val app = repository.get(appId) ?: return
        val automation = app.automations.firstOrNull { it.id == automationId } ?: return
        runMiniAppPrompt(appId, "Run the automation \"${automation.title}\": ${automation.prompt}")
    }

    private fun runMiniAppPrompt(appId: String, prompt: String) {
        val repository = miniAppRepository ?: return
        viewModelScope.launch(backgroundDispatcher) {
            val app = repository.get(appId) ?: return@launch
            val screen = app.latestScreen?.potsUiJson.orEmpty()
            val records = repository.allRecords(appId)
                .groupBy { it.collection }
                .entries
                .joinToString("\n") { (collection, rows) ->
                    "$collection: " + rows.take(50).joinToString(", ") { it.value.toString() }
                }
            currentJob?.cancel()
            currentJob = null
            dataRepository.startNewChat()
            dataRepository.setInteractiveMode(true)
            val seeded = buildString {
                append("You are working on the saved mini-app \"").append(app.name).append("\" (id: ").append(app.id).append(").\n")
                if (app.description.isNotBlank()) append("Description: ").append(app.description).append("\n")
                if (app.collections.isNotEmpty()) {
                    append("Data collections: ").append(app.collections.joinToString { it.name }).append("\n")
                }
                if (records.isNotBlank()) {
                    append("Current records (bounded):\n").append(records).append("\n")
                }
                append("Current screen:\n```pots-ui\n").append(screen).append("\n```\n")
                append("Request: ").append(prompt).append("\n")
                append("Respond with a complete updated pots-ui screen.")
            }
            _state.update {
                it.copy(
                    isInteractiveMode = true,
                    isLoading = false,
                    error = null,
                    showFreeProviderSuggestions = false,
                    miniAppDraftId = app.id,
                    editingMiniAppId = app.id,
                    editingMiniAppName = app.name,
                    editingMiniAppDescription = app.description,
                    editingMiniAppIcon = app.icon,
                )
            }
            askInternal(seeded, null)
        }
    }

    /**
     * A web app has no pots-ui screen to seed the editor with; this summary is
     * the handle the agent needs to see which app and files it is working on.
     */
    private fun webSummaryScreen(app: com.pusios.pots.data.miniapp.MiniApp): String = buildJsonObject {
        put("type", "column")
        putJsonArray("children") {
            add(
                buildJsonObject {
                    put("type", "text")
                    put("value", "Web mini-app: ${app.name}")
                    put("style", "headline")
                },
            )
            add(
                buildJsonObject {
                    put("type", "text")
                    put("value", "Entry point: ${app.entryPoint ?: "web/index.html"}")
                    put("style", "caption")
                },
            )
            add(
                buildJsonObject {
                    put("type", "text")
                    put(
                        "value",
                        "App id: ${app.id}. Use mini_app_data with app_id to list/read/write this app's files. Files: " +
                            app.files.joinToString { it.path }.ifBlank { "none yet" },
                    )
                    put("style", "caption")
                },
            )
        }
    }.toString()

    /**
     * Every distinct pots-ui screen the session produced, in order. The last one
     * is the final screen the user sees; earlier ones stay as navigable history.
     */
    private fun collectScreens(): List<MiniAppScreen> {
        val result = mutableListOf<MiniAppScreen>()
        val now = Clock.System.now().toEpochMilliseconds()
        for (message in dataRepository.chatHistory.value) {
            if (message.role != History.Role.ASSISTANT || message.isThinking || message.content.isEmpty()) continue
            val blocks = parseMarkdown(message.content).blocks.filterIsInstance<PotsUiBlock>()
            for (block in blocks) {
                if (result.any { it.potsUiJson == block.rawJson }) continue
                result += MiniAppScreen(
                    id = Uuid.random().toString(),
                    title = "Screen ${result.size + 1}",
                    potsUiJson = block.rawJson,
                    createdAt = now,
                )
            }
        }
        return result
    }

    private fun editMiniApp(id: String) {
        val repository = miniAppRepository ?: return
        currentJob?.cancel()
        currentJob = null
        viewModelScope.launch(backgroundDispatcher) {
            val app = repository.get(id) ?: return@launch
            val screen = app.latestScreen ?: MiniAppScreen(
                id = "web-summary",
                title = app.name,
                potsUiJson = webSummaryScreen(app),
            )
            dataRepository.startNewChat()
            dataRepository.setInteractiveMode(true)
            dataRepository.seedInteractiveSession(screen.potsUiJson, app.name)
            _state.update {
                it.copy(
                    isInteractiveMode = true,
                    isLoading = false,
                    error = null,
                    showFreeProviderSuggestions = false,
                    miniAppDraftId = app.id,
                    editingMiniAppId = app.id,
                    editingMiniAppName = app.name,
                    editingMiniAppDescription = app.description,
                    editingMiniAppIcon = app.icon,
                )
            }
        }
    }

    private fun clearHistory() {
        dataRepository.clearHistory()
        _state.update {
            it.copy(error = null, showFreeProviderSuggestions = false)
        }
    }

    /**
     * If [text] begins with `/<skill-id>`, look up the skill among the currently-
     * installed-and-enabled skills and return its id alongside the verbatim user
     * text. The text is sent unchanged so the conversation visibly reflects what
     * the user typed; the skill's instructions in the system prompt tell the model
     * how to parse the args after the slash command. Falls through with null skill
     * id when no match — slash commands are opt-in.
     */
    private fun parseSkillInvocation(text: String?): Pair<String?, String?> {
        if (text == null) return null to null
        val trimmed = text.trimStart()
        if (!trimmed.startsWith('/')) return text to null
        val firstSpace = trimmed.indexOfFirst { it.isWhitespace() }
        val rawId = if (firstSpace < 0) trimmed.substring(1) else trimmed.substring(1, firstSpace)
        if (rawId.isEmpty()) return text to null
        val skill = dataRepository.getInstalledSkills().firstOrNull { it.id.equals(rawId, ignoreCase = true) }
            ?: return text to null
        return text to skill.id
    }

    private fun setIsSpeaking(isSpeaking: Boolean, contentId: String) {
        _state.update {
            it.copy(
                isSpeaking = isSpeaking,
                isSpeakingContentId = if (isSpeaking) {
                    contentId
                } else {
                    it.isSpeakingContentId
                },
            )
        }
    }

    private fun addFile(file: PlatformFile) {
        val ext = file.extension.lowercase()
        val supported = dataRepository.supportedFileExtensions()
        if (ext.isEmpty() || ext !in supported) {
            _state.update {
                it.copy(snackbarMessage = Res.string.error_unsupported_file_type)
            }
            return
        }
        _state.update {
            it.copy(files = (it.files + file).toImmutableList())
        }
    }

    private fun removeFile(file: PlatformFile) {
        _state.update {
            it.copy(files = it.files.filterNot { f -> f == file }.toImmutableList())
        }
    }

    private fun clearSnackbar() {
        _state.update {
            it.copy(snackbarMessage = null)
        }
    }

    private fun retry() {
        ask(null)
    }

    private fun toggleSpeechOutput() {
        _state.update {
            it.copy(
                isSpeechOutputEnabled = !it.isSpeechOutputEnabled,
            )
        }
    }

    private fun cancel() {
        currentJob?.cancel()
        currentJob = null
        _state.update {
            it.copy(isLoading = false)
        }
    }

    private fun selectService(instanceId: String) {
        val freeMode = FREE_MODE_INSTANCE_IDS[instanceId]
        if (freeMode != null) {
            dataRepository.setFreeMode(freeMode)
            dataRepository.setFreeServicePrimary(true)
            updateAvailableServices()
            return
        }

        dataRepository.setFreeServicePrimary(false)
        val instances = dataRepository.getConfiguredServiceInstances()
        val currentIds = instances.map { it.instanceId }
        if (instanceId !in currentIds) return
        val reordered = listOf(instanceId) + currentIds.filter { it != instanceId }
        dataRepository.reorderConfiguredServices(reordered)
        updateAvailableServices()
    }

    private fun updateAvailableServices() {
        val entries = dataRepository.getAvailableServiceEntries().toImmutableList()

        val primaryService = entries.firstOrNull()?.let { Service.fromId(it.serviceId) }
        val warning = if (primaryService?.isOnDevice == true && dataRepository.getLocalDownloadedModels().isEmpty()) {
            Res.string.litert_no_model_warning
        } else {
            null
        }
        _state.update { it.copy(availableServices = entries, warning = warning, showPrivacyInfo = dataRepository.isUsingSharedKey()) }
    }

    companion object {
        private val FREE_MODE_INSTANCE_IDS = FreeMode.entries.associateBy { it.instanceId }
    }

    private fun regenerate() {
        dataRepository.regenerate()
        ask(null)
    }

    private fun loadConversation(id: String) {
        currentJob?.cancel()
        currentJob = null
        val conversation = dataRepository.savedConversations.value.find { it.id == id }
        val isInteractive = conversation?.type == Conversation.TYPE_INTERACTIVE
        dataRepository.setInteractiveMode(isInteractive)
        dataRepository.loadConversation(id)
        _state.update {
            it.copy(
                error = null,
                showFreeProviderSuggestions = false,
                isInteractiveMode = isInteractive,
                isLoading = false,
                composerPrefill = null,
            )
        }
    }

    private fun deleteConversation(id: String) {
        commitPendingConversationDeletion()
        _state.update { it.copy(pendingConversationDeletion = id) }
        pendingConversationDeleteJob = viewModelScope.launch(backgroundDispatcher) {
            delay(4.seconds)
            dataRepository.deleteConversation(id)
            _state.update { it.copy(pendingConversationDeletion = null) }
        }
    }

    private fun undoDeleteConversation() {
        pendingConversationDeleteJob?.cancel()
        pendingConversationDeleteJob = null
        _state.update { it.copy(pendingConversationDeletion = null) }
    }

    private fun commitPendingConversationDeletion() {
        pendingConversationDeleteJob?.cancel()
        pendingConversationDeleteJob = null
        val pendingId = _state.value.pendingConversationDeletion ?: return
        _state.update { it.copy(pendingConversationDeletion = null) }
        viewModelScope.launch(backgroundDispatcher) {
            dataRepository.deleteConversation(pendingId)
        }
    }

    override fun onCleared() {
        commitPendingConversationDeletion()
        // The scheduler lives longer than this ViewModel (it's a singleton driving the
        // Android foreground service). Reset the predicate so the daemon path keeps
        // running without a stale reference to a dead state flow. The foreground-visible
        // signal (`appInForeground`) is tracked separately via `ProcessLifecycleOwner`
        // on Android — ViewModel lifecycle is too narrow (survives backgrounding).
        taskScheduler.isLoadingCheck = { false }
        super.onCleared()
    }

    private fun clearUnreadHeartbeat() {
        dataRepository.clearUnreadHeartbeat()
    }

    private fun sendSmsDraft(draftId: String) {
        viewModelScope.launch(backgroundDispatcher) {
            dataRepository.sendSmsDraft(draftId)
        }
    }

    private fun discardSmsDraft(draftId: String) {
        viewModelScope.launch(backgroundDispatcher) {
            dataRepository.discardSmsDraft(draftId)
        }
    }

    private fun startNewChat(composerPrefill: String? = null) {
        currentJob?.cancel()
        currentJob = null
        dataRepository.startNewChat()
        dataRepository.setInteractiveMode(false)
        _state.update {
            it.copy(
                error = null,
                showFreeProviderSuggestions = false,
                isInteractiveMode = false,
                isLoading = false,
                composerPrefill = composerPrefill,
                miniAppDraftId = null,
                editingMiniAppId = null,
                editingMiniAppName = null,
                editingMiniAppDescription = null,
                editingMiniAppIcon = null,
            )
        }
    }

    /**
     * Remembers where the character was put.
     *
     * The settings screen owns his appearance, but where the user dropped
     * him is a chat thing, so it is written through the same store.
     */
    private fun setPotsikLook(look: PotsikLook) {
        dataRepository.setPotsikLook(look)
    }

    private fun consumeComposerPrefill() {
        _state.update { it.copy(composerPrefill = null) }
    }

    private fun enterInteractiveMode() {
        dataRepository.startNewChat()
        dataRepository.setInteractiveMode(true)
        _state.update {
            it.copy(
                isInteractiveMode = true,
                error = null,
                showFreeProviderSuggestions = false,
                miniAppDraftId = Uuid.random().toString(),
                editingMiniAppId = null,
                editingMiniAppName = null,
                editingMiniAppDescription = null,
                editingMiniAppIcon = null,
            )
        }
    }

    private fun exitInteractiveMode() {
        currentJob?.cancel()
        currentJob = null
        dataRepository.startNewChat()
        dataRepository.setInteractiveMode(false)
        _state.update {
            it.copy(
                isInteractiveMode = false,
                isLoading = false,
                error = null,
                showFreeProviderSuggestions = false,
                miniAppDraftId = null,
                editingMiniAppId = null,
                editingMiniAppName = null,
                editingMiniAppDescription = null,
                editingMiniAppIcon = null,
            )
        }
    }

    private fun resubmit(messageId: String, event: String, data: Map<String, String>) {
        if (_state.value.isLoading) return
        dataRepository.truncateFrom(messageId)
        submitUiCallback(event, data)
    }

    private fun goBackInteractiveMode() {
        val userCount = dataRepository.chatHistory.value.count { it.role == History.Role.USER }
        if (userCount <= 1) {
            // Go back to initial prompt — clear history but stay in interactive mode
            dataRepository.clearHistory()
        } else {
            dataRepository.popLastExchange()
        }
    }

    fun refreshSettings() {
        updateAvailableServices()
        viewModelScope.launch(backgroundDispatcher) {
            dataRepository.restoreCurrentConversation()
            presetInteractiveModeForCurrentConversation()
        }
    }

    /**
     * Resolves the interactive mode flag from the currently-loaded conversation, or — when
     * there is no loaded conversation (new empty chat) — falls back to the persisted flag.
     */
    private fun presetInteractiveModeForCurrentConversation() {
        val currentId = dataRepository.currentConversationId.value
        val conversation = dataRepository.savedConversations.value.find { it.id == currentId }
        val isInteractive = if (conversation != null) {
            conversation.type == Conversation.TYPE_INTERACTIVE
        } else {
            dataRepository.isInteractiveModeActive()
        }
        dataRepository.setInteractiveMode(isInteractive)
        _state.update { it.copy(isInteractiveMode = isInteractive) }
    }
}
