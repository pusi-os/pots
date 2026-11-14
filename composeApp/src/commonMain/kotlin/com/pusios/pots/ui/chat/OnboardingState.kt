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
)
