package com.pusios.pots.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The empty chat picks its content from the onboarding phase and whether a service exists.
 * A configured service means the chat can answer, so the farewell card («модель не подключена»)
 * must not be shown anymore.
 */
class EmptyChatSlotTest {

    @Test
    fun skippedWithAConfiguredServiceShowsTheStartSurface() {
        assertEquals(
            EmptyChatSlot.StartSurface,
            emptyChatSlot(OnboardingPhase.Skipped, hasConfiguredService = true),
        )
    }

    @Test
    fun skippedWithoutAnyServiceStillExplainsTheModelIsMissing() {
        assertEquals(
            EmptyChatSlot.FarewellCard,
            emptyChatSlot(OnboardingPhase.Skipped, hasConfiguredService = false),
        )
    }

    @Test
    fun abortedWithAConfiguredServiceShowsTheStartSurface() {
        assertEquals(
            EmptyChatSlot.StartSurface,
            emptyChatSlot(OnboardingPhase.Aborted, hasConfiguredService = true),
        )
    }

    @Test
    fun abortedWithoutAnyServiceStillExplainsTheModelIsMissing() {
        assertEquals(
            EmptyChatSlot.FarewellCard,
            emptyChatSlot(OnboardingPhase.Aborted, hasConfiguredService = false),
        )
    }

    @Test
    fun anActiveWizardIsNotReplacedByAConfiguredService() {
        assertEquals(
            EmptyChatSlot.Wizard,
            emptyChatSlot(OnboardingPhase.Active, hasConfiguredService = true),
        )
    }

    @Test
    fun aFinishedOnboardingStillShowsTheSuccessCard() {
        assertEquals(
            EmptyChatSlot.SuccessCard,
            emptyChatSlot(OnboardingPhase.Finished, hasConfiguredService = true),
        )
        assertEquals(
            EmptyChatSlot.SuccessCard,
            emptyChatSlot(OnboardingPhase.Finished, hasConfiguredService = false),
        )
    }

    @Test
    fun hiddenOnboardingLeavesThePlainStartSurface() {
        assertEquals(
            EmptyChatSlot.StartSurface,
            emptyChatSlot(OnboardingPhase.Hidden, hasConfiguredService = false),
        )
    }
}
