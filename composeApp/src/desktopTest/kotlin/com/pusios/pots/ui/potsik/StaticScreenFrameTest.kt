package com.pusios.pots.ui.potsik

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.pusios.pots.ui.chat.ChatActions
import com.pusios.pots.ui.chat.OnboardingPhase
import com.pusios.pots.ui.chat.OnboardingState
import com.pusios.pots.ui.chat.composables.EmptyState
import com.pusios.pots.ui.chat.composables.OnboardingWizardCard
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A static screen must not keep asking the frame clock for more frames.
 *
 * The app used to redraw itself forever on a screen nobody touched — the
 * character's clock and the hands-free button's pulse both ran on every frame
 * for as long as they were composed, which is what heated the phone and kept
 * the desktop busy.
 *
 * The measurement is the state the animations write: every frame an animation
 * runs, it changes something, and the change lands in the global snapshot. So
 * the test settles the screen on a manually driven clock, then advances two
 * seconds of frames and counts the snapshot applies. A screen at rest writes
 * nothing; a screen that animates forever writes on every single frame.
 */
@OptIn(ExperimentalTestApi::class)
class StaticScreenFrameTest {

    @Test
    fun `the onboarding wizard card is static at rest`() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            MaterialTheme {
                OnboardingWizardCard(
                    state = OnboardingState(phase = OnboardingPhase.Active, step = 1),
                    actions = noopChatActions(),
                )
            }
        }
        assertStaticAtRest("the onboarding wizard card")
    }

    @Test
    fun `the character overlay is static at rest`() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            MaterialTheme {
                PotsikOverlay(
                    stage = PotsikStage(),
                    look = PotsikLook(),
                    phase = PotsikPhase.IDLE,
                )
            }
        }
        assertStaticAtRest("the character overlay")
    }

    @Test
    fun `the empty chat screen is static at rest`() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalPotsikStage provides PotsikStage()) {
                    EmptyState(
                        modifier = Modifier,
                        onStartHandsFree = {},
                        onOpenMedia = {},
                    )
                }
            }
        }
        assertStaticAtRest("the empty chat screen")
    }

    @Test
    fun `the standalone character is static at rest`() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            MaterialTheme {
                PotsikAvatar(
                    look = PotsikLook(),
                    phase = PotsikPhase.IDLE,
                    size = 40.dp,
                )
            }
        }
        assertStaticAtRest("the standalone character")
    }
}

/** A screen at rest writes nothing; anything above a handful is an animation that never ends. */
private const val APPLY_BUDGET = 5

/** How long the screen gets to compose, lay out and draw its first frames. */
private const val SETTLE_MS = 1_000L

/** The measured window of pumped frames — a minute of them would be ~3750 applies. */
private const val WINDOW_MS = 2_000L

/** Settles the screen, then counts the snapshot applies a [WINDOW_MS] window of frames produces. */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.assertStaticAtRest(what: String) {
    mainClock.advanceTimeBy(SETTLE_MS)
    var applies = 0
    val subscription = Snapshot.registerApplyObserver { changes, _ -> applies += changes.size }
    mainClock.advanceTimeBy(WINDOW_MS)
    subscription.dispose()
    assertTrue(
        applies <= APPLY_BUDGET,
        "$what keeps writing state on every frame ($applies snapshot applies in ${WINDOW_MS}ms of frames) — " +
            "a screen at rest must not request new frames",
    )
}

/** Every action does nothing: the tests never interact, they only watch the frames. */
private fun noopChatActions() = ChatActions(
    ask = {},
    toggleSpeechOutput = {},
    retry = {},
    clearHistory = {},
    setIsSpeaking = { _, _ -> },
    addFile = {},
    removeFile = {},
    startNewChat = {},
    regenerate = {},
    cancel = {},
    selectService = {},
    activateAgentProfile = {},
    loadConversation = {},
    deleteConversation = {},
    clearUnreadHeartbeat = {},
    clearSnackbar = {},
    undoDeleteConversation = {},
    submitUiCallback = { _, _ -> },
    resubmit = { _, _, _ -> },
    enterInteractiveMode = {},
    exitInteractiveMode = {},
    goBackInteractiveMode = {},
    sendSmsDraft = {},
    discardSmsDraft = {},
    consumeComposerPrefill = {},
    setPotsikLook = {},
    setSpeechOutputEnabled = {},
    newMiniAppSession = {},
    saveMiniApp = { _, _, _, _ -> },
    editMiniApp = {},
    runMiniAppAutomation = { _, _ -> },
    runMiniAppPrompt = { _, _ -> },
    onboardingOpenSite = {},
    onboardingSaveKey = {},
    onboardingTopUp = {},
    onboardingCheck = {},
    onboardingSkip = {},
    onboardingResume = {},
    onboardingChooseLocal = {},
    onboardingDismiss = {},
)
