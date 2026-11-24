package com.pusios.pots.ui.chat

import androidx.compose.runtime.Immutable
import com.pusios.pots.data.ProviderBalance
import com.pusios.pots.ui.settings.SettingsModel
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
    /**
     * The provider the wizard is currently working on, set when the user moves past the picker
     * and derived from the configured instances on a resume. Null falls back to the default
     * (PlusVibe) in the UI.
     */
    val serviceId: String? = null,
    val isChecking: Boolean = false,
    val error: StringResource? = null,
    val showLocalLimits: Boolean = false,
    /** The add-service call succeeded: the field shows the green «Добавлено» state until «Готово». */
    val isKeyAdded: Boolean = false,
    /** Catalog of the just-added provider, loaded right after the key validated. */
    val modelOptions: List<SettingsModel> = emptyList(),
    /** Model picked for the new provider; the onboarding default when the user did not choose. */
    val selectedModelId: String? = null,
    /** Prepaid balance read right after the key validated; null when the provider exposes none. */
    val balance: ProviderBalance? = null,
    /** True while the catalog and balance load after a successful key save. */
    val isLoadingDetails: Boolean = false,
)
