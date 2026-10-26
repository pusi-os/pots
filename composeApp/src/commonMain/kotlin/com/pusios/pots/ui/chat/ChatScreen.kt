@file:OptIn(
    ExperimentalFoundationApi::class,
)

package com.pusios.pots.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Alignment.Companion.BottomCenter
import androidx.compose.ui.Alignment.Companion.CenterEnd
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pusios.pots.BackIcon
import com.pusios.pots.TerminalLine
import com.pusios.pots.data.AgentProfile
import com.pusios.pots.data.Service
import com.pusios.pots.data.supportsAgenticFlows
import com.pusios.pots.getBackgroundDispatcher
import com.pusios.pots.handsfree.HandsFreeController
import com.pusios.pots.handsfree.HandsFreeStatus
import com.pusios.pots.handsfree.createSpeechToText
import com.pusios.pots.onDragAndDropEventDropped
import com.pusios.pots.permissionQualifier
import com.pusios.pots.ui.background.screenBackground
import com.pusios.pots.ui.build.PotsBuildScreen
import com.pusios.pots.ui.chat.composables.AgentProfileHandoffOverlay
import com.pusios.pots.ui.chat.composables.AgentProfilesToolbar
import com.pusios.pots.ui.chat.composables.BotMessage
import com.pusios.pots.ui.chat.composables.ChatHistorySheet
import com.pusios.pots.ui.chat.composables.CircleIconButton
import com.pusios.pots.ui.chat.composables.EmptyState
import com.pusios.pots.ui.chat.composables.ErrorMessage
import com.pusios.pots.ui.chat.composables.FreeProviderSuggestionsPanel
import com.pusios.pots.ui.chat.composables.HeartbeatBanner
import com.pusios.pots.ui.chat.composables.PendingSmsBanners
import com.pusios.pots.ui.chat.composables.QuestionInput
import com.pusios.pots.ui.chat.composables.ServiceSelector
import com.pusios.pots.ui.chat.composables.TokenStatsDialog
import com.pusios.pots.ui.chat.composables.TopBar
import com.pusios.pots.ui.chat.composables.TrailingIcon
import com.pusios.pots.ui.chat.composables.UserMessage
import com.pusios.pots.ui.chat.composables.WaitingResponseRow
import com.pusios.pots.ui.chat.composables.uiErrorText
import com.pusios.pots.ui.components.VerticalScrollbarForList
import com.pusios.pots.ui.components.animatedGradientBorder
import com.pusios.pots.ui.dynamicui.FrozenSubmission
import com.pusios.pots.ui.dynamicui.LocalMiniAppRuntime
import com.pusios.pots.ui.dynamicui.PotsUiRenderer
import com.pusios.pots.ui.dynamicui.toSpeakableText
import com.pusios.pots.ui.handCursor
import com.pusios.pots.ui.markdown.PotsUiBlock
import com.pusios.pots.ui.markdown.parseMarkdown
import com.pusios.pots.ui.miniapp.MiniAppRunnerScreen
import com.pusios.pots.ui.miniapp.MiniAppSession
import com.pusios.pots.ui.miniapp.MiniAppsGlassToolbar
import com.pusios.pots.ui.miniapp.MiniAppsSheet
import com.pusios.pots.ui.potsik.LocalPotsikStage
import com.pusios.pots.ui.potsik.PotsikAnchor
import com.pusios.pots.ui.potsik.PotsikAnchorKind
import com.pusios.pots.ui.potsik.PotsikOverlay
import com.pusios.pots.ui.potsik.PotsikPhase
import com.pusios.pots.ui.potsik.PotsikStage
import com.pusios.pots.ui.sandbox.SandboxTabsContent
import com.pusios.pots.ui.settings.SandboxUiState
import com.pusios.pots.ui.settings.SandboxViewModel
import com.pusios.pots.ui.tts.sayChunked
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch
import nl.marc_apps.tts.TextToSpeechInstance
import nl.marc_apps.tts.errors.TextToSpeechSynthesisInterruptedError
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.fallback_answered_by
import pots.composeapp.generated.resources.fallback_service_failed
import pots.composeapp.generated.resources.fallback_trying_next
import pots.composeapp.generated.resources.ic_miniapp_library
import pots.composeapp.generated.resources.ic_miniapp_new
import pots.composeapp.generated.resources.ic_miniapp_save
import pots.composeapp.generated.resources.ic_stop
import pots.composeapp.generated.resources.interactive_back_content_description
import pots.composeapp.generated.resources.interactive_exit_content_description
import pots.composeapp.generated.resources.interactive_title
import pots.composeapp.generated.resources.interactive_ui_parsing_failed
import pots.composeapp.generated.resources.interactive_welcome_subtitle
import pots.composeapp.generated.resources.interactive_welcome_title
import pots.composeapp.generated.resources.mini_app_cancel
import pots.composeapp.generated.resources.mini_app_description_label
import pots.composeapp.generated.resources.mini_app_icon_label
import pots.composeapp.generated.resources.mini_app_library_content_description
import pots.composeapp.generated.resources.mini_app_name_label
import pots.composeapp.generated.resources.mini_app_new_content_description
import pots.composeapp.generated.resources.mini_app_save
import pots.composeapp.generated.resources.mini_app_save_content_description
import pots.composeapp.generated.resources.mini_app_save_title
import pots.composeapp.generated.resources.scroll_to_bottom_content_description

@Composable
fun ChatScreen(
    viewModel: ChatViewModel = koinViewModel(),
    textToSpeech: TextToSpeechInstance?,
    onNavigateToSettings: () -> Unit,
    isSandboxAvailable: Boolean = false,
    isPotsBuildAvailable: Boolean = false,
    navigationTabBar: (@Composable () -> Unit)? = null,
) {
    val uiState by viewModel.state.collectAsStateWithLifecycle()
    val potsikStage: PotsikStage = koinInject()

    ChatScreenContent(
        uiState = uiState,
        textToSpeech = textToSpeech,
        onNavigateToSettings = onNavigateToSettings,
        isSandboxAvailable = isSandboxAvailable,
        isPotsBuildAvailable = isPotsBuildAvailable,
        navigationTabBar = navigationTabBar,
        potsikStage = potsikStage,
    )
}

@Composable
fun ChatScreenContent(
    uiState: ChatUiState,
    textToSpeech: TextToSpeechInstance? = null,
    onNavigateToSettings: () -> Unit = {},
    isSandboxAvailable: Boolean = false,
    isPotsBuildAvailable: Boolean = false,
    navigationTabBar: (@Composable () -> Unit)? = null,
    initialSandboxOpen: Boolean = false,
    previewSandboxState: SandboxUiState? = null,
    previewSandboxLines: ImmutableList<TerminalLine> = persistentListOf(),
    potsikStage: PotsikStage? = null,
) {
    // Pots Build is a transient surface, not a conversation — unlike interactive
    // mode it needs no persistence beyond surviving configuration changes.
    var isPotsBuildOpen by rememberSaveable { mutableStateOf(false) }
    // A saved app can run over the chat without becoming a navigation destination.
    var runningMiniAppId by rememberSaveable { mutableStateOf<String?>(null) }
    var profileHandoff by remember { mutableStateOf<Pair<AgentProfile, AgentProfile>?>(null) }

    // The character's stage. The app hands in the one the agent's tool writes
    // to; a preview that has no app behind it gets its own.
    val stage = potsikStage ?: remember { PotsikStage() }

    // Where he was left is the chat's business, so it is written back into
    // the look the settings own — but only when the placement mode says a
    // move is worth keeping.
    val currentLook by rememberUpdatedState(uiState.potsikLook)
    DisposableEffect(stage) {
        stage.onMoved = { x, y ->
            uiState.actions.setPotsikLook(currentLook.copy(pinnedX = x, pinnedY = y))
        }
        onDispose { stage.onMoved = null }
    }

    // The hands-free voice loop. The recognizer is created once and released with the
    // screen; the controller owns the listen → speak cycle.
    val speechToText = remember { createSpeechToText() }
    val handsFreeScope = rememberCoroutineScope()
    val handsFree = remember(textToSpeech, speechToText) {
        HandsFreeController(
            scope = handsFreeScope,
            textToSpeech = textToSpeech,
            speechToText = speechToText,
            onSubmit = { question -> uiState.actions.ask(question) },
        )
    }
    DisposableEffect(speechToText) {
        onDispose {
            speechToText?.cancel()
            speechToText?.release()
        }
    }

    // Shared text opens a new chat; leave Pots Build so the composer is visible.
    LaunchedEffect(uiState.composerPrefill) {
        if (uiState.composerPrefill != null && isPotsBuildOpen) {
            isPotsBuildOpen = false
        }
    }

    // Потсик, one copy, drawn over the whole app: the chat, the mini-app
    // runner, the build screen. The stage holds where he is, so the agent's
    // tool and the user's own dragging both move the same character.
    val potsikPhase = when {
        uiState.isLoading -> PotsikPhase.WORKING
        uiState.isSpeaking -> PotsikPhase.SPEAKING
        uiState.error != null -> PotsikPhase.ERROR
        else -> PotsikPhase.IDLE
    }
    val runningTool = rememberExecutingTools(uiState.history).tools.lastOrNull()?.first

    CompositionLocalProvider(LocalPotsikStage provides stage) {
        Box(Modifier.fillMaxSize()) {
            when {
                runningMiniAppId != null -> MiniAppRunnerScreen(
                    appId = runningMiniAppId!!,
                    onExit = { runningMiniAppId = null },
                    onEdit = { id ->
                        runningMiniAppId = null
                        uiState.actions.editMiniApp(id)
                    },
                    onRunAutomation = { id, automationId ->
                        runningMiniAppId = null
                        uiState.actions.runMiniAppAutomation(id, automationId)
                    },
                    onRunAgent = { id, prompt ->
                        runningMiniAppId = null
                        uiState.actions.runMiniAppPrompt(id, prompt)
                    },
                )

                uiState.isInteractiveMode && !uiState.isRestoring -> InteractiveModeScreen(
                    uiState = uiState,
                    onOpenMiniApp = { runningMiniAppId = it },
                )

                isPotsBuildOpen -> PotsBuildScreen(onExit = { isPotsBuildOpen = false })

                else -> ChatModeScreen(
                    uiState = uiState,
                    textToSpeech = textToSpeech,
                    handsFree = handsFree,
                    onNavigateToSettings = onNavigateToSettings,
                    isSandboxAvailable = isSandboxAvailable,
                    onOpenPotsBuild = { isPotsBuildOpen = true }.takeIf { isPotsBuildAvailable },
                    navigationTabBar = navigationTabBar,
                    initialSandboxOpen = initialSandboxOpen,
                    previewSandboxState = previewSandboxState,
                    previewSandboxLines = previewSandboxLines,
                    onOpenMiniApp = { runningMiniAppId = it },
                    onSelectAgentProfile = { incoming ->
                        if (incoming.id == uiState.activeAgentProfileId || profileHandoff != null) return@ChatModeScreen
                        val outgoing = uiState.agentProfiles.firstOrNull { it.id == uiState.activeAgentProfileId }
                        if (outgoing == null || (!outgoing.potsikLook.isVisible && !incoming.potsikLook.isVisible)) {
                            uiState.actions.activateAgentProfile(incoming.id)
                        } else {
                            profileHandoff = outgoing to incoming
                        }
                    },
                )
            }

            // The character, drawn over everything. The anchors the layout sets
            // say where he belongs; with none of them on screen he stands in
            // the corner, which is what the sections outside the chat want.
            PotsikOverlay(
                stage = stage,
                look = if (profileHandoff == null) uiState.potsikLook else uiState.potsikLook.copy(isVisible = false),
                phase = potsikPhase,
                toolName = runningTool,
            )
            profileHandoff?.let { (outgoing, incoming) ->
                AgentProfileHandoffOverlay(
                    outgoing = outgoing,
                    incoming = incoming,
                    onActivateIncoming = {
                        stage.clearPinned()
                        uiState.actions.activateAgentProfile(incoming.id)
                    },
                    onFinished = { profileHandoff = null },
                )
            }
        }
    }
}

// --- Interactive Mode ---

@Composable
private fun InteractiveModeScreen(
    uiState: ChatUiState,
    onOpenMiniApp: (String) -> Unit = {},
) {
    val potsikStage = LocalPotsikStage.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Intercept system back to exit interactive mode instead of closing the app
    com.pusios.pots.PlatformBackHandler(enabled = true) {
        uiState.actions.exitInteractiveMode()
    }

    val hasAssistantResponse = remember(uiState.history) {
        uiState.history.any { it.role == History.Role.ASSISTANT }
    }
    // Interactive mode drives a tool-calling loop and emits pots-ui JSON, so the
    // switcher only lists services/models capable of agentic flows.
    val interactiveServices = remember(uiState.availableServices) {
        uiState.availableServices
            .filter { supportsAgenticFlows(it.serviceId, it.modelId) }
            .toImmutableList()
    }
    var inputExpanded by remember { mutableStateOf(true) }
    val isPreview = LocalInspectionMode.current
    val miniAppRepository: com.pusios.pots.data.miniapp.MiniAppRepository? = if (!isPreview) koinInject() else null
    val draftId = uiState.miniAppDraftId ?: uiState.currentConversationId
    val draftSession = remember(draftId, miniAppRepository) {
        draftId?.let { id -> miniAppRepository?.let { MiniAppSession(id, it) } }
    }
    var showSaveDialog by remember { mutableStateOf(false) }
    var showLibrary by remember { mutableStateOf(false) }
    LaunchedEffect(hasAssistantResponse, uiState.history.size) {
        if (hasAssistantResponse) inputExpanded = false
    }
    val showFullInput = inputExpanded && !uiState.isLoading
    var questionInputText by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }

    Box(
        Modifier
            .fillMaxSize()
            .screenBackground()
            .navigationBarsPadding()
            .statusBarsPadding()
            .imePadding(),
    ) {
        Column(Modifier.fillMaxSize()) {
            // Top bar with back and close
            Box(
                Modifier.onGloballyPositioned { coords ->
                    runCatching { potsikStage.setTopInset(coords.boundsInWindow().bottom - potsikStage.areaOrigin.value.y) }
                },
            ) {
                InteractiveModeTopBar(
                    onBack = uiState.actions.goBackInteractiveMode,
                    onExit = uiState.actions.exitInteractiveMode,
                    onNew = uiState.actions.newMiniAppSession,
                    onSave = { showSaveDialog = true },
                    onOpenLibrary = { showLibrary = true },
                    isLoading = uiState.isLoading,
                    showBack = hasAssistantResponse,
                )
            }

            Box(
                modifier = Modifier.fillMaxSize(),
            ) {
                // Content area fills remaining space
                if (!hasAssistantResponse && !uiState.isLoading) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        // The character is drawn once, over the whole chat,
                        // so this is only the place he belongs — the overlay
                        // reads the anchor and puts him there.
                        PotsikAnchor(
                            kind = PotsikAnchorKind.EMPTY,
                            size = 132.dp,
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = stringResource(Res.string.interactive_welcome_title),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Text(
                            text = stringResource(Res.string.interactive_welcome_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                } else {
                    CompositionLocalProvider(LocalMiniAppRuntime provides draftSession) {
                        SelectionContainer {
                            InteractiveModeContent(
                                uiState = uiState,
                                modifier = Modifier.fillMaxSize(),
                                bottomPadding = 88.dp,
                            )
                        }
                    }
                }

                // Full QuestionInput stays in the column flow
                if (showFullInput) {
                    QuestionInput(
                        modifier = Modifier.align(Alignment.BottomEnd),
                        files = uiState.files,
                        addFile = uiState.actions.addFile,
                        removeFile = uiState.actions.removeFile,
                        ask = {
                            inputExpanded = false
                            uiState.actions.ask(it)
                        },
                        supportedFileExtensions = uiState.supportedFileExtensions,
                        textState = questionInputText,
                        onTextStateChange = { questionInputText = it },
                        isLoading = uiState.isLoading,
                        cancel = uiState.actions.cancel,
                        availableServices = interactiveServices,
                        onSelectService = uiState.actions.selectService,
                        installedSkills = uiState.installedSkills,
                    )
                }
            }
        }

        // Animated gradient frame signals the AI is working, mirroring the
        // "Start Interactive UI" button's border on the empty state.
        if (uiState.isLoading) {
            Box(
                Modifier
                    .matchParentSize()
                    .animatedGradientBorder(cornerRadius = 28.dp),
            )
        }

        // Collapsed pill floats over content at the bottom-end
        if (!showFullInput) {
            val gradientBrush = com.pusios.pots.ui.gradientBrush
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .height(56.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(28.dp))
                    .border(
                        BorderStroke(2.dp, gradientBrush),
                        RoundedCornerShape(28.dp),
                    )
                    .handCursor()
                    .padding(horizontal = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (uiState.isLoading) {
                    TrailingIcon(
                        icon = Res.drawable.ic_stop,
                        onClick = { uiState.actions.cancel() },
                        isPulsing = true,
                    )
                } else {
                    CircleIconButton(
                        icon = Icons.Default.Edit,
                        onClick = { inputExpanded = true },
                    )
                }
                if (interactiveServices.size > 1) {
                    ServiceSelector(
                        services = interactiveServices,
                        onSelectService = uiState.actions.selectService,
                    )
                }
            }
        }

        // The working tile, exactly as the chat shows it: the character
        // stands in it while the request runs, and the engine plays its
        // random working playlist.
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .align(BottomCenter)
                    .padding(bottom = 88.dp),
            ) {
                WaitingResponseRow(
                    executingTools = remember { kotlinx.collections.immutable.persistentListOf() },
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(BottomCenter).padding(bottom = 80.dp),
        ) { data ->
            Snackbar(snackbarData = data)
        }
    }

    if (showSaveDialog) {
        MiniAppSaveDialog(
            initialName = uiState.editingMiniAppName ?: "",
            initialDescription = uiState.editingMiniAppDescription ?: "",
            initialIcon = uiState.editingMiniAppIcon ?: "🧩",
            onDismiss = { showSaveDialog = false },
            onSave = { name, description, icon ->
                showSaveDialog = false
                uiState.actions.saveMiniApp(name, description, icon)
            },
        )
    }

    if (showLibrary) {
        MiniAppsSheet(
            onDismiss = { showLibrary = false },
            onOpen = { id ->
                showLibrary = false
                onOpenMiniApp(id)
            },
            onEdit = { id ->
                showLibrary = false
                uiState.actions.editMiniApp(id)
            },
        )
    }
}

@Composable
private fun MiniAppSaveDialog(
    initialName: String,
    initialDescription: String,
    initialIcon: String,
    onDismiss: () -> Unit,
    onSave: (name: String, description: String, icon: String) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName.ifBlank { "My mini-app" }) }
    var description by remember(initialDescription) { mutableStateOf(initialDescription) }
    var icon by remember(initialIcon) { mutableStateOf(initialIcon) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.mini_app_save_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(Res.string.mini_app_name_label)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(Res.string.mini_app_description_label)) },
                    minLines = 2,
                )
                OutlinedTextField(
                    value = icon,
                    onValueChange = { icon = it.take(4) },
                    label = { Text(stringResource(Res.string.mini_app_icon_label)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, description, icon) }) {
                Text(stringResource(Res.string.mini_app_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.mini_app_cancel)) }
        },
    )
}

@Composable
private fun InteractiveModeTopBar(
    onBack: () -> Unit,
    onExit: () -> Unit,
    onNew: () -> Unit,
    onSave: () -> Unit,
    onOpenLibrary: () -> Unit,
    isLoading: Boolean,
    showBack: Boolean,
) {
    val iconColor = MaterialTheme.colorScheme.onSurface

    Row(
        modifier = Modifier
            .fillMaxWidth(),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        if (showBack) {
            IconButton(
                onClick = onBack,
                enabled = !isLoading,
                modifier = Modifier.handCursor(),
            ) {
                Icon(
                    BackIcon,
                    contentDescription = stringResource(Res.string.interactive_back_content_description),
                    tint = iconColor,
                )
            }
        } else {
            // Placeholder to keep close button aligned right
            Spacer(Modifier.size(48.dp))
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = stringResource(Res.string.interactive_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.weight(1f))
        // The studio controls, immediately left of the close button.
        IconButton(onClick = onNew, modifier = Modifier.handCursor()) {
            Icon(
                painter = painterResource(Res.drawable.ic_miniapp_new),
                contentDescription = stringResource(Res.string.mini_app_new_content_description),
                tint = iconColor,
            )
        }
        IconButton(onClick = onSave, modifier = Modifier.handCursor()) {
            Icon(
                painter = painterResource(Res.drawable.ic_miniapp_save),
                contentDescription = stringResource(Res.string.mini_app_save_content_description),
                tint = iconColor,
            )
        }
        IconButton(onClick = onOpenLibrary, modifier = Modifier.handCursor()) {
            Icon(
                painter = painterResource(Res.drawable.ic_miniapp_library),
                contentDescription = stringResource(Res.string.mini_app_library_content_description),
                tint = iconColor,
            )
        }
        IconButton(
            onClick = onExit,
            modifier = Modifier.handCursor(),
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = stringResource(Res.string.interactive_exit_content_description),
                tint = iconColor,
            )
        }
    }
}

@Composable
private fun InteractiveModeContent(
    uiState: ChatUiState,
    modifier: Modifier = Modifier,
    bottomPadding: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val lastAssistant = remember(uiState.history) { uiState.history.lastRenderedAssistant() }

    Box(modifier.fillMaxWidth()) {
        if (uiState.isLoading && lastAssistant == null) {
            // First load — show centered loading
            Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                WaitingResponseRow(
                    executingTools = remember { kotlinx.collections.immutable.persistentListOf() },
                )
            }
        } else if (lastAssistant != null) {
            val contentId = lastAssistant.id
            AnimatedContent(
                targetState = contentId,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                modifier = Modifier.fillMaxSize(),
            ) { _ ->
                val blocks = remember(lastAssistant.content) { parseMarkdown(lastAssistant.content).blocks }
                val uiBlocks = blocks.filterIsInstance<PotsUiBlock>()

                if (uiBlocks.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp + bottomPadding),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (block in uiBlocks) {
                            PotsUiRenderer(
                                node = block.node,
                                isInteractive = !uiState.isLoading,
                                onCallback = { event, data ->
                                    uiState.actions.submitUiCallback(event, data)
                                },
                                wrapInCard = false,
                            )
                        }
                    }
                } else if (uiState.error == null) {
                    // AI responded with no valid pots-ui AND there's no API error underneath —
                    // this is a genuine parse failure (retries exhausted). When an API error is
                    // set, the ErrorMessage overlay below takes over with the correct message.
                    Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        Column(horizontalAlignment = CenterHorizontally) {
                            Text(
                                text = stringResource(Res.string.interactive_ui_parsing_failed),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        // Error state
        uiState.error?.let { error ->
            Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                if (uiState.showFreeProviderSuggestions) {
                    FreeProviderSuggestionsPanel(
                        error = error,
                        retry = uiState.actions.retry,
                    )
                } else {
                    ErrorMessage(error = error, retry = uiState.actions.retry)
                }
            }
        }
    }
}

// --- Regular Chat Mode ---

@Composable
private fun ChatModeScreen(
    uiState: ChatUiState,
    textToSpeech: TextToSpeechInstance?,
    handsFree: HandsFreeController,
    onNavigateToSettings: () -> Unit,
    isSandboxAvailable: Boolean,
    onOpenPotsBuild: (() -> Unit)?,
    navigationTabBar: (@Composable () -> Unit)?,
    initialSandboxOpen: Boolean = false,
    previewSandboxState: SandboxUiState? = null,
    previewSandboxLines: ImmutableList<TerminalLine> = persistentListOf(),
    onOpenMiniApp: (String) -> Unit = {},
    onSelectAgentProfile: (AgentProfile) -> Unit = {},
) {
    val potsikStage = LocalPotsikStage.current
    var showHistorySheet by remember { mutableStateOf(false) }
    var showAgentProfilesToolbar by remember { mutableStateOf(false) }
    var showMiniAppsToolbar by rememberSaveable { mutableStateOf(false) }
    var showMiniAppsSheet by remember { mutableStateOf(false) }
    var showTokenStats by remember { mutableStateOf(false) }
    var isSandboxOpen by rememberSaveable { mutableStateOf(initialSandboxOpen) }
    // Hoisted here so the draft survives toggling the sandbox/terminal view, which
    // removes QuestionInput from composition and would otherwise drop the text.
    var questionInputText by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }
    val keyboardController = LocalSoftwareKeyboardController.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Speak every new assistant reply while the hands-free loop is running. The effect is
    // keyed on the message id, so a reply is spoken exactly once.
    val handsFreeStatus by handsFree.status.collectAsStateWithLifecycle()
    val lastAssistantMessage = uiState.history.lastRenderedAssistant()
    LaunchedEffect(lastAssistantMessage?.id) {
        val assistant = lastAssistantMessage ?: return@LaunchedEffect
        if (handsFree.isActive) {
            val lastUser = uiState.history.lastOrNull { it.role == History.Role.USER }?.content?.lowercase().orEmpty()
            val goodbye = listOf(
                "bye", "goodbye", "disconnect", "hang up", "hangup", "stop",
                "пока", "отключ", "заверш", "стоп", "до свидания",
            )
            if (goodbye.any { lastUser.contains(it) }) {
                handsFree.requestStopAfterCurrentReply()
            }
        }
        handsFree.onAssistantReply(assistant.id, assistant.content)
    }

    // Hands-free always starts with speech on, and the loop follows the toggle
    // afterwards — including turning it off in the middle of a sentence.
    LaunchedEffect(handsFreeStatus) {
        if (handsFreeStatus != HandsFreeStatus.Idle && !uiState.isSpeechOutputEnabled) {
            uiState.actions.setSpeechOutputEnabled(true)
        }
    }
    LaunchedEffect(uiState.isSpeechOutputEnabled) {
        handsFree.setSpeechOutputEnabled(uiState.isSpeechOutputEnabled)
    }

    LaunchedEffect(uiState.composerPrefill) {
        val prefill = uiState.composerPrefill ?: return@LaunchedEffect
        questionInputText = TextFieldValue(
            text = prefill,
            selection = TextRange(prefill.length),
        )
        if (isSandboxOpen) isSandboxOpen = false
        uiState.actions.consumeComposerPrefill()
    }

    // When the active conversation changes (e.g. user starts a new chat from the
    // top bar or taps the heartbeat banner), collapse the sandbox view so the
    // user lands on the chat they just opened. Tracking the previous id avoids
    // firing on the initial composition — important when returning from Settings,
    // where rememberSaveable has just restored isSandboxOpen.
    var lastConversationId by remember { mutableStateOf(uiState.currentConversationId) }
    LaunchedEffect(uiState.currentConversationId) {
        if (lastConversationId != uiState.currentConversationId) {
            if (isSandboxOpen) isSandboxOpen = false
            lastConversationId = uiState.currentConversationId
        }
    }

    LaunchedEffect(uiState.snackbarMessage) {
        val resource = uiState.snackbarMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(getString(resource))
        uiState.actions.clearSnackbar()
    }

    val filteredConversations = remember(uiState.savedConversations, uiState.pendingConversationDeletion) {
        val pendingId = uiState.pendingConversationDeletion
        if (pendingId != null) uiState.savedConversations.filter { it.id != pendingId }.toImmutableList() else uiState.savedConversations
    }

    val historyState = rememberUpdatedState(uiState.history)
    val isShellExecuting by remember {
        derivedStateOf {
            historyState.value.any { it.role == History.Role.TOOL_EXECUTING && it.content == "execute_shell_command" }
        }
    }

    // Потсик: one copy, drawn over the chat. The stage holds where he is, so
    // the agent's tool and the user's own dragging both move the same
    // character.
    val executingTools = rememberExecutingTools(uiState.history)
    var thinkingExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.isLoading) {
        if (!uiState.isLoading) thinkingExpanded = false
    }
    val fallbackStatusText = uiState.fallbackStatus?.let { status ->
        val failed = stringResource(Res.string.fallback_service_failed, status.serviceName, uiErrorText(status.errorReason))
        val next = status.nextServiceName?.let { stringResource(Res.string.fallback_trying_next, it) }
        if (next != null) "$failed\n$next" else failed
    }
    Box(Modifier.fillMaxSize().screenBackground().navigationBarsPadding().statusBarsPadding().imePadding()) {
        Column(Modifier.fillMaxSize()) {
            Box(
                Modifier.onGloballyPositioned { coords ->
                    runCatching { potsikStage.setTopInset(coords.boundsInWindow().bottom - potsikStage.areaOrigin.value.y) }
                },
            ) {
                TopBar(
                    textToSpeech = textToSpeech,
                    isSpeechOutputEnabled = uiState.isSpeechOutputEnabled,
                    isSpeaking = uiState.isSpeaking,
                    actions = uiState.actions,
                    isChatHistoryEmpty = uiState.history.isEmpty(),
                    hasSavedConversations = filteredConversations.any { it.id != uiState.currentConversationId },
                    onNavigateToSettings = onNavigateToSettings,
                    isSandboxAvailable = isSandboxAvailable,
                    isSandboxOpen = isSandboxOpen,
                    isShellExecuting = isShellExecuting,
                    onToggleSandbox = { isSandboxOpen = !isSandboxOpen },
                    onShowHistory = {
                        keyboardController?.hide()
                        showHistorySheet = true
                    },
                    navigationTabBar = navigationTabBar,
                    handsFreeStatus = handsFreeStatus.takeIf { handsFree.isSupported },
                    onToggleHandsFree = {
                        when (handsFreeStatus) {
                            // While the agent talks, the button interrupts it instead of stopping
                            // the mode — that is the barge-in.
                            HandsFreeStatus.Speaking -> handsFree.interrupt()

                            HandsFreeStatus.Error -> handsFree.resume()

                            else -> {
                                // The loop owns the voice while it runs: silence any
                                // regular reply already being read out before the
                                // microphone opens.
                                textToSpeech?.stop()
                                uiState.actions.setIsSpeaking(false, "")
                                handsFree.toggle()
                            }
                        }
                    },
                    miniAppsOpen = showMiniAppsToolbar,
                    onToggleMiniApps = { showMiniAppsToolbar = !showMiniAppsToolbar },
                    profilesOpen = showAgentProfilesToolbar,
                    onToggleProfiles = { showAgentProfilesToolbar = !showAgentProfilesToolbar },
                )
            }

            AgentProfilesToolbar(
                visible = showAgentProfilesToolbar,
                profiles = uiState.agentProfiles,
                activeProfileId = uiState.activeAgentProfileId,
                onSelectProfile = { profile ->
                    showAgentProfilesToolbar = false
                    onSelectAgentProfile(profile)
                },
            )

            MiniAppsGlassToolbar(
                visible = showMiniAppsToolbar,
                apps = uiState.miniApps,
                pinned = uiState.pinnedMiniApps,
                onOpenApp = { id ->
                    showMiniAppsToolbar = false
                    onOpenMiniApp(id)
                },
                onOpenLibrary = { showMiniAppsSheet = true },
                onNewApp = {
                    showMiniAppsToolbar = false
                    uiState.actions.newMiniAppSession()
                },
            )

            HeartbeatBanner(
                visible = uiState.hasUnreadHeartbeat,
                onTap = {
                    uiState.heartbeatConversationId?.let { uiState.actions.loadConversation(it) }
                    uiState.actions.clearUnreadHeartbeat()
                    isSandboxOpen = false
                },
                onDismiss = {
                    uiState.actions.clearUnreadHeartbeat()
                },
            )

            PendingSmsBanners(
                drafts = uiState.smsDrafts,
                onSend = uiState.actions.sendSmsDraft,
                onDiscard = uiState.actions.discardSmsDraft,
            )

            uiState.warning?.let { warning ->
                Text(
                    text = stringResource(warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            if (isSandboxOpen) {
                val isPreview = LocalInspectionMode.current
                val sandboxViewModel = if (!isPreview) koinViewModel<SandboxViewModel>() else null
                val liveState = sandboxViewModel?.state?.collectAsStateWithLifecycle()?.value
                val sandboxState = liveState ?: previewSandboxState ?: SandboxUiState()
                SandboxTabsContent(
                    sandboxState = sandboxState,
                    onSetupSandbox = sandboxViewModel?.let { { it.onSetupSandbox() } } ?: {},
                    onCancelSandbox = sandboxViewModel?.let { { it.onCancelSandbox() } } ?: {},
                    previewLines = previewSandboxLines,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )
            } else {
                Box(Modifier.weight(1f)) {
                    var isDropping by remember {
                        mutableStateOf(false)
                    }
                    val addFile by rememberUpdatedState(uiState.actions.addFile)
                    val canAcceptDrop by rememberUpdatedState(uiState.supportedFileExtensions.isNotEmpty())
                    val shouldStartDragAndDrop = remember { { _: DragAndDropEvent -> canAcceptDrop } }
                    val dropTarget = remember {
                        object : DragAndDropTarget {
                            override fun onEntered(event: DragAndDropEvent) {
                                super.onEntered(event)
                                isDropping = true
                            }
                            override fun onExited(event: DragAndDropEvent) {
                                super.onExited(event)
                                isDropping = false
                            }
                            override fun onDrop(event: DragAndDropEvent): Boolean {
                                val file = onDragAndDropEventDropped(event)
                                if (file != null) addFile(file)
                                isDropping = false
                                return file != null
                            }
                        }
                    }
                    Column(
                        Modifier
                            .fillMaxSize()
                            .blur(radius = if (isDropping) 4.dp else 0.dp)
                            .dragAndDropTarget(
                                shouldStartDragAndDrop = shouldStartDragAndDrop,
                                target = dropTarget,
                            ),
                    ) {
                        if (uiState.history.isEmpty()) {
                            // Interactive UI mode isn't offered on on-device LiteRT: the pots-ui
                            // component schema is too large for small Gemma models to coherently
                            // attend to, and even the minimal variant we tried was unreliable.
                            val primaryIsOnDevice = uiState.availableServices
                                .firstOrNull()
                                ?.let { Service.fromId(it.serviceId).isOnDevice } == true
                            EmptyState(
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                onStartInteractiveMode = uiState.actions.enterInteractiveMode
                                    .takeUnless { primaryIsOnDevice },
                                onOpenPotsBuild = onOpenPotsBuild,
                            )
                        } else {
                            val listState = rememberLazyListState()
                            val componentScope = rememberCoroutineScope()

                            LaunchedEffect(uiState.history.size) {
                                // Capture history at effect start to prevent race conditions
                                val history = uiState.history
                                if (history.isNotEmpty()) {
                                    listState.requestScrollToItem(history.lastIndex)
                                    val lastMessage = history.last()
                                    if (uiState.isSpeechOutputEnabled && handsFreeStatus == HandsFreeStatus.Idle && lastMessage.role == History.Role.ASSISTANT) {
                                        componentScope.launch(getBackgroundDispatcher()) {
                                            textToSpeech?.stop()
                                            uiState.actions.setIsSpeaking(true, lastMessage.id)
                                            try {
                                                textToSpeech?.sayChunked(lastMessage.content.toSpeakableText())
                                            } catch (_: TextToSpeechSynthesisInterruptedError) {
                                                // Speech was interrupted by user
                                            } catch (_: Exception) {
                                                // Handle TTS errors gracefully (service failure, audio issues, etc.)
                                            } finally {
                                                uiState.actions.setIsSpeaking(false, lastMessage.id)
                                            }
                                        }
                                    }
                                }
                            }

                            val lastAssistantId = remember(uiState.history) { uiState.history.lastRenderedAssistant()?.id }
                            // Pair every user submission with its originating assistant so the pots-ui
                            // renders once (on the assistant side) with a frozen snapshot — never as a
                            // separate user-side card. pressedEvent + values persist across the loading
                            // transition; isPending is only set for the latest in-flight submission.
                            val pairings = remember(uiState.history, uiState.isLoading) {
                                val history = uiState.history
                                val lastUserIdx = history.indexOfLast { it.role == History.Role.USER }
                                val frozen = mutableMapOf<String, FrozenSubmission>()
                                val userIdByAssistant = mutableMapOf<String, String>()
                                for ((i, h) in history.withIndex()) {
                                    if (h.role != History.Role.USER) continue
                                    val sub = h.uiSubmission ?: continue
                                    val originId = (i - 1 downTo 0).firstNotNullOfOrNull { j ->
                                        history[j].takeIf {
                                            it.role == History.Role.ASSISTANT &&
                                                it.content.isNotEmpty() && !it.isThinking &&
                                                it.content == sub.sourceContent
                                        }?.id
                                    } ?: (i - 1 downTo 0).firstNotNullOfOrNull { j ->
                                        history[j].takeIf {
                                            it.role == History.Role.ASSISTANT &&
                                                it.content.isNotEmpty() && !it.isThinking
                                        }?.id
                                    } ?: continue
                                    frozen[originId] = FrozenSubmission(
                                        values = sub.values,
                                        pressedEvent = sub.pressedEvent,
                                        isPending = uiState.isLoading && i == lastUserIdx,
                                    )
                                    userIdByAssistant[originId] = h.id
                                }
                                frozen.toMap() to userIdByAssistant.toMap()
                            }
                            val frozenByAssistantId = pairings.first
                            val userIdByAssistantId = pairings.second
                            val executingToolsState = executingTools

                            // Group every reasoning segment in a response (intermediate tool-call /
                            // thinking-only turns plus the final answer's own reasoning) under the
                            // answer-bearing assistant message, so each response shows a single
                            // collapsible "Thinking" section instead of N standalone ones.
                            val (reasoningSegmentsByAssistantId, suppressedThinkingIds) = remember(uiState.history) {
                                val byAnswerId = mutableMapOf<String, ImmutableList<String>>()
                                val suppressed = mutableSetOf<String>()
                                val pending = mutableListOf<String>()
                                val pendingThinkingIds = mutableListOf<String>()
                                for (entry in uiState.history) {
                                    when {
                                        entry.role == History.Role.USER -> {
                                            pending.clear()
                                            pendingThinkingIds.clear()
                                        }

                                        entry.role == History.Role.ASSISTANT &&
                                            entry.isThinking &&
                                            entry.content.isNotEmpty() -> {
                                            pending.add(entry.content)
                                            pendingThinkingIds.add(entry.id)
                                        }

                                        entry.role == History.Role.ASSISTANT &&
                                            !entry.isThinking &&
                                            entry.content.isNotEmpty() -> {
                                            val combined = buildList {
                                                addAll(pending)
                                                entry.reasoningContent?.takeIf { it.isNotBlank() }?.let { add(it) }
                                            }
                                            if (combined.isNotEmpty()) byAnswerId[entry.id] = combined.toImmutableList()
                                            suppressed.addAll(pendingThinkingIds)
                                            pending.clear()
                                            pendingThinkingIds.clear()
                                        }

                                        entry.role == History.Role.ASSISTANT &&
                                            entry.toolCalls != null -> {
                                            // Assistant turn with tool calls but no answer text yet —
                                            // capture its reasoning, attach to the eventual answer.
                                            entry.reasoningContent
                                                ?.takeIf { it.isNotBlank() }
                                                ?.let { pending.add(it) }
                                        }
                                    }
                                }
                                // In-flight: the user is still waiting for the answer but earlier
                                // thinking turns are already in history. Collapse them into the most
                                // recent thinking entry so the user sees ONE growing Thinking section
                                // instead of a separate bubble per tool-loop iteration.
                                if (pendingThinkingIds.isNotEmpty()) {
                                    val lastId = pendingThinkingIds.last()
                                    byAnswerId[lastId] = pending.toImmutableList()
                                    for (i in 0 until pendingThinkingIds.size - 1) {
                                        suppressed.add(pendingThinkingIds[i])
                                    }
                                }
                                byAnswerId to suppressed
                            }

                            val showScrollToBottom by remember {
                                derivedStateOf {
                                    val lastVisibleItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()
                                    lastVisibleItem != null && lastVisibleItem.index < listState.layoutInfo.totalItemsCount - 1
                                }
                            }

                            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    state = listState,
                                    horizontalAlignment = CenterHorizontally,
                                ) {
                                    items(uiState.history, key = { it.id }, contentType = { it.role }) { history ->
                                        when (history.role) {
                                            History.Role.USER -> {
                                                // Submissions are shown by the paired assistant's frozen pots-ui card
                                                // above; the "Responded with: …" text bubble would be redundant.
                                                if (history.uiSubmission == null) {
                                                    UserMessage(
                                                        message = history.content,
                                                        attachments = history.attachments,
                                                    )
                                                }
                                            }

                                            History.Role.ASSISTANT -> {
                                                if (history.content.isNotEmpty() && !history.isThinking) {
                                                    val isLastAssistant = history.id == lastAssistantId
                                                    val frozen = frozenByAssistantId[history.id]
                                                    val pairedUserId = userIdByAssistantId[history.id]
                                                    BotMessage(
                                                        message = history.content,
                                                        textToSpeech = textToSpeech,
                                                        isSpeaking = uiState.isSpeaking && uiState.isSpeakingContentId == history.id,
                                                        setIsSpeaking = {
                                                            uiState.actions.setIsSpeaking(it, history.id)
                                                        },
                                                        onRegenerate = if (isLastAssistant) uiState.actions.regenerate else null,
                                                        isInteractive = isLastAssistant && !uiState.isLoading && frozen == null,
                                                        onUiCallback = { event, data ->
                                                            uiState.actions.submitUiCallback(event, data)
                                                        },
                                                        frozen = frozen,
                                                        onResubmit = if (pairedUserId != null && !uiState.isLoading) {
                                                            { event, data -> uiState.actions.resubmit(pairedUserId, event, data) }
                                                        } else {
                                                            null
                                                        },
                                                        reasoningSegments = reasoningSegmentsByAssistantId[history.id] ?: persistentListOf(),
                                                        thinkingExpanded = thinkingExpanded,
                                                        onThinkingExpandedChange = { thinkingExpanded = it },
                                                        turnMetrics = history.turnMetrics,
                                                        onTokenCounterClick = { showTokenStats = true },
                                                    )
                                                    if (history.fallbackServiceName != null) {
                                                        androidx.compose.material3.Text(
                                                            text = stringResource(Res.string.fallback_answered_by, history.fallbackServiceName),
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
                                                        )
                                                    }
                                                } else if (history.isThinking &&
                                                    history.content.isNotEmpty() &&
                                                    history.id !in suppressedThinkingIds
                                                ) {
                                                    // Thinking-only turn still in flight — render as a standalone
                                                    // reasoning bubble. The precomputation above has already gathered
                                                    // every earlier thinking segment in this cycle under this id.
                                                    BotMessage(
                                                        message = "",
                                                        textToSpeech = null,
                                                        isSpeaking = false,
                                                        setIsSpeaking = {},
                                                        reasoningSegments = reasoningSegmentsByAssistantId[history.id]
                                                            ?: persistentListOf(history.content),
                                                        thinkingExpanded = thinkingExpanded,
                                                        onThinkingExpandedChange = { thinkingExpanded = it },
                                                    )
                                                }
                                            }

                                            History.Role.TOOL_EXECUTING -> {
                                                // Rendered in WaitingResponseRow below
                                            }

                                            History.Role.TOOL -> {
                                                // Don't show completed tool results in UI
                                            }
                                        }
                                    }
                                    // The working widget is not an item of the list: it is a fixed
                                    // band at the bottom of the chat (see WorkingArea below), so
                                    // that the character in it is never scrolled away.
                                    uiState.error?.let { error ->
                                        item(key = "error") {
                                            if (uiState.showFreeProviderSuggestions) {
                                                FreeProviderSuggestionsPanel(
                                                    error = error,
                                                    retry = uiState.actions.retry,
                                                )
                                            } else {
                                                ErrorMessage(error = error, retry = uiState.actions.retry)
                                            }
                                        }
                                    }
                                }

                                VerticalScrollbarForList(
                                    listState = listState,
                                    modifier = Modifier.align(CenterEnd).fillMaxHeight(),
                                )

                                androidx.compose.animation.AnimatedVisibility(
                                    visible = showScrollToBottom,
                                    modifier = Modifier.align(BottomCenter).padding(bottom = 8.dp),
                                    enter = fadeIn() + scaleIn(),
                                    exit = fadeOut() + scaleOut(),
                                ) {
                                    SmallFloatingActionButton(
                                        modifier = Modifier
                                            .handCursor(),
                                        onClick = {
                                            componentScope.launch {
                                                val totalItems = listState.layoutInfo.totalItemsCount
                                                if (totalItems > 0) {
                                                    listState.animateScrollToItem(totalItems - 1)
                                                }
                                            }
                                        },
                                    ) {
                                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(Res.string.scroll_to_bottom_content_description))
                                    }
                                }
                            }
                        }

                        // The working band: a transparent strip under the
                        // messages with the character in it and the state
                        // under him. It does not scroll with the messages, so
                        // the character is always in it — on the first request
                        // and on the tenth.
                        if (uiState.isLoading) {
                            WaitingResponseRow(
                                executingTools = executingTools.tools,
                                isStatusOnly = executingTools.isStatusOnly,
                                statusText = fallbackStatusText,
                            )
                        }
                    }
                }
            }

            if (!isSandboxOpen) {
                QuestionInput(
                    files = uiState.files,
                    addFile = uiState.actions.addFile,
                    removeFile = uiState.actions.removeFile,
                    ask = uiState.actions.ask,
                    supportedFileExtensions = uiState.supportedFileExtensions,
                    textState = questionInputText,
                    onTextStateChange = { questionInputText = it },
                    isLoading = uiState.isLoading,
                    cancel = uiState.actions.cancel,
                    availableServices = uiState.availableServices,
                    onSelectService = uiState.actions.selectService,
                    installedSkills = uiState.installedSkills,
                )
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(BottomCenter).padding(bottom = 80.dp),
        ) { data ->
            Snackbar(snackbarData = data)
        }
    }

    if (showMiniAppsSheet) {
        MiniAppsSheet(
            onDismiss = { showMiniAppsSheet = false },
            onOpen = { id ->
                showMiniAppsSheet = false
                onOpenMiniApp(id)
            },
            onEdit = { id ->
                showMiniAppsSheet = false
                uiState.actions.editMiniApp(id)
            },
        )
    }

    if (showHistorySheet) {
        ChatHistorySheet(
            conversations = filteredConversations,
            currentConversationId = uiState.currentConversationId,
            pendingConversationDeletion = uiState.pendingConversationDeletion,
            actions = uiState.actions,
            onDismiss = { showHistorySheet = false },
            onConversationSelected = { isSandboxOpen = false },
        )
    }

    if (showTokenStats) {
        val sessionMetrics = remember(uiState.history) {
            uiState.history.mapNotNull { it.turnMetrics }
        }
        val contextWindow = sessionMetrics.lastOrNull()?.contextWindowTokens ?: 0
        TokenStatsDialog(
            turnMetrics = sessionMetrics,
            contextWindowTokens = contextWindow,
            onDismiss = { showTokenStats = false },
        )
    }
}

private data class ExecutingToolsState(
    val tools: ImmutableList<Pair<String, String>>,
    val isStatusOnly: Boolean,
)

@Composable
private fun rememberExecutingTools(history: ImmutableList<History>): ExecutingToolsState {
    // Wrap the history parameter in State so derivedStateOf can observe it, then
    // only recompute (and only emit) when the executing-tools subset actually changes.
    // Streaming tokens mutate `history` on every frame but rarely change this derived slice.
    val historyState = rememberUpdatedState(history)
    val state by remember {
        derivedStateOf {
            val executing = historyState.value.filter { it.role == History.Role.TOOL_EXECUTING }
            ExecutingToolsState(
                tools = executing.map { it.id to (it.toolName ?: "tool") }.toImmutableList(),
                isStatusOnly = executing.any { it.isStatusMessage },
            )
        }
    }
    return state
}
