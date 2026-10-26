package com.pusios.pots.ui.chat

import androidx.compose.runtime.Immutable
import com.pusios.pots.ui.potsik.PotsikLook
import io.github.vinceglb.filekit.PlatformFile

@Immutable
data class ChatActions(
    val ask: (String) -> Unit,
    val toggleSpeechOutput: () -> Unit,
    val retry: () -> Unit,
    val clearHistory: () -> Unit,
    val setIsSpeaking: (Boolean, String) -> Unit,
    val addFile: (PlatformFile) -> Unit,
    val removeFile: (PlatformFile) -> Unit,
    val startNewChat: () -> Unit,
    val regenerate: () -> Unit,
    val cancel: () -> Unit,
    val selectService: (String) -> Unit,
    val activateAgentProfile: (String) -> Unit,
    val loadConversation: (String) -> Unit,
    val deleteConversation: (String) -> Unit,
    val clearUnreadHeartbeat: () -> Unit,
    val clearSnackbar: () -> Unit,
    val undoDeleteConversation: () -> Unit,
    val submitUiCallback: (event: String, data: Map<String, String>) -> Unit,
    val resubmit: (messageId: String, event: String, data: Map<String, String>) -> Unit,
    val enterInteractiveMode: () -> Unit,
    val exitInteractiveMode: () -> Unit,
    val goBackInteractiveMode: () -> Unit,
    val sendSmsDraft: (String) -> Unit,
    val discardSmsDraft: (String) -> Unit,
    val consumeComposerPrefill: () -> Unit,
    val setPotsikLook: (PotsikLook) -> Unit,
    /** Sets speech output explicitly, so hands-free and the toggle agree. */
    val setSpeechOutputEnabled: (Boolean) -> Unit,
    /** Starts a fresh interactive session for a new mini-app. */
    val newMiniAppSession: () -> Unit,
    /** Snapshots the current interactive screens into a saved mini-app. */
    val saveMiniApp: (name: String, description: String, icon: String) -> Unit,
    /** Reopens a saved mini-app in the interactive editor. */
    val editMiniApp: (String) -> Unit,
    /** Runs one declared automation of a saved app in a fresh interactive session. */
    val runMiniAppAutomation: (appId: String, automationId: String) -> Unit,
    /** Hands an arbitrary request about a saved app to the agent. */
    val runMiniAppPrompt: (appId: String, prompt: String) -> Unit,
)
