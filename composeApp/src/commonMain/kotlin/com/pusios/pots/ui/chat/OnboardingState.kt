package com.pusios.pots.ui.chat

import androidx.compose.runtime.Immutable
import org.jetbrains.compose.resources.StringResource

/**
 * Lifecycle of the first-launch setup wizard that lives in the empty-chat slot.
 *
 * - [Hidden]: nothing onboarding-related is rendered (done with services, dismissed, or not applicable).
 * - [Active]: the wizard card is on screen at [OnboardingState.step].
 * - [Skipped]: the user pressed «Пропустить» — final card with the resume button.
 * - [Aborted]: the user left before success — final card with the resume button.
 * - [Finished]: success (cloud key verified or local model chosen) — success card.
 */
enum class OnboardingPhase { Hidden, Active, Skipped, Aborted, Finished }

/**
 * What fills the empty chat: the wizard, its farewell card, or the plain start surface.
 * Kept apart from [ChatScreen] so the choice is testable without Compose.
 */
internal enum class EmptyChatSlot { Wizard, FarewellCard, SuccessCard, StartSurface }

/**
 * Picks the empty-chat content. A configured service ([hasConfiguredService]) means the chat can
 * answer, so the «model is not connected» farewell card would be a lie — the user gets the plain
 * start surface instead. An explicitly started wizard ([OnboardingPhase.Active]) always wins.
 */
internal fun emptyChatSlot(
    phase: OnboardingPhase,
    hasConfiguredService: Boolean,
): EmptyChatSlot = when (phase) {
    OnboardingPhase.Active -> EmptyChatSlot.Wizard

    OnboardingPhase.Skipped, OnboardingPhase.Aborted ->
        if (hasConfiguredService) EmptyChatSlot.StartSurface else EmptyChatSlot.FarewellCard

    OnboardingPhase.Finished -> EmptyChatSlot.SuccessCard

    else -> EmptyChatSlot.StartSurface
}

/**
 * The wizard slice of [ChatUiState]. The phase decides which card the empty-chat slot renders;
 * everything else is step-local detail. Persistence lives in AppSettings (KEY_ONBOARDING_*),
 * this object only mirrors it for the UI.
 */
@Immutable
data class OnboardingState(
    val phase: OnboardingPhase = OnboardingPhase.Hidden,
    val step: Int = 1,
    val isChecking: Boolean = false,
    val error: StringResource? = null,
    val showLocalLimits: Boolean = false,
    /** The add-service call succeeded: the field shows the green «Добавлено» state until «Готово». */
    val isKeyAdded: Boolean = false,
    /**
     * Seed for the guide section's open state. The open/close toggling itself lives in the
     * composable; the field exists so a screenshot test can compose the expanded section
     * through the public state, without a Koin container or an interaction.
     */
    val isGuideOpen: Boolean = false,
)
