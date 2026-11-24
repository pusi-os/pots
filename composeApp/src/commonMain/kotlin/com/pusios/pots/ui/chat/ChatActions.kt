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
    /** Clears [ChatUiState.startHandsFreeRequested] after the voice loop has been started. */
    val consumeStartHandsFree: () -> Unit,
    val setPotsikLook: (PotsikLook) -> Unit,
    /** Sets speech output explicitly, so hands-free and the toggle agree. */
    val setSpeechOutputEnabled: (Boolean) -> Unit,
    /** Starts a fresh interactive session for a new mini-app. */
    val newMiniAppSession: () -> Unit,
    /** Snapshots the current interactive screens into a saved mini-app. */
    val saveMiniApp: (name: String, description: String, icon: String, pinToToolbar: Boolean) -> Unit,
    /** Reopens a saved mini-app in the interactive editor. */
    val editMiniApp: (String) -> Unit,
    /** Runs one declared automation of a saved app in a fresh interactive session. */
    val runMiniAppAutomation: (appId: String, automationId: String) -> Unit,
    /** Hands an arbitrary request about a saved app to the agent. */
    val runMiniAppPrompt: (appId: String, prompt: String) -> Unit,
    /** Validates the key at the picked provider and adds the service (Settings → Services add+key mechanism); a mapped error lands under the field. */
    val onboardingAddKey: (serviceId: String, apiKey: String) -> Unit,
    /** Provider switched or wizard restarted: a check verdict (error/added) belongs to its provider (03-key-states §3). */
    val onboardingClearKeyState: () -> Unit,
    /** «Готово» after a successful add: closes the wizard the same way the old check success did. */
    val onboardingConfirmKey: () -> Unit,
    /** «Далее» on any wizard step: persists and opens the next card (carries the picked provider). */
    val onboardingGoToStep: (step: Int, serviceId: String?) -> Unit,
    /** Model picked in the onboarding dropdown: persists it on the just-added instance. */
    val onboardingSelectModel: (modelId: String) -> Unit,
    /** «Пропустить» — parks the wizard behind the final card. */
    val onboardingSkip: () -> Unit,
    /** Reopens the wizard from the final card at the persisted step. */
    val onboardingResume: () -> Unit,
    /** Local-model branch: first call reveals limits, second confirms LiteRT. */
    val onboardingChooseLocal: () -> Unit,
    /** Hides the final card (close icon); the wizard stays interruptible. */
    val onboardingDismiss: () -> Unit,
)
