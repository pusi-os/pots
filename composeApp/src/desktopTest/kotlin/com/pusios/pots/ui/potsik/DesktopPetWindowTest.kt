package com.pusios.pots.ui.potsik

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One character at a time.
 *
 * The PixelTag rule: the floating desktop window and the in-app surface are
 * mutually exclusive — two surfaces drawing the character at once doubled
 * him and stuttered both. While the system window shows him on the desktop,
 * the in-app overlay must stand down, and pick up again the moment the
 * window hides.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopPetWindowTest {

    @Test
    fun `the in-app overlay draws nothing while the desktop window is showing`() = runComposeUiTest {
        mainClock.autoAdvance = false
        DesktopPetWindow.setShowing(true)
        try {
            setContent {
                MaterialTheme {
                    PotsikOverlay(
                        stage = PotsikStage(clock = { mainClock.currentTime }),
                        look = PotsikLook(),
                        phase = PotsikPhase.IDLE,
                    )
                }
            }
            mainClock.advanceTimeBy(SETTLE)
            var applies = 0
            val subscription = Snapshot.registerApplyObserver { changes, _ -> applies += changes.size }
            mainClock.advanceTimeBy(WINDOW)
            subscription.dispose()
            assertEquals(
                0,
                applies,
                "the in-app character must stand down while the desktop window shows him " +
                    "($applies snapshot applies in ${WINDOW}ms)",
            )
        } finally {
            DesktopPetWindow.setShowing(false)
        }
    }

    @Test
    fun `the in-app overlay breathes again once the desktop window hides`() = runComposeUiTest {
        mainClock.autoAdvance = false
        DesktopPetWindow.setShowing(true)
        try {
            setContent {
                MaterialTheme {
                    PotsikOverlay(
                        stage = PotsikStage(clock = { mainClock.currentTime }),
                        look = PotsikLook(),
                        phase = PotsikPhase.IDLE,
                    )
                }
            }
            mainClock.advanceTimeBy(SETTLE)
        } finally {
            DesktopPetWindow.setShowing(false)
        }
        mainClock.advanceTimeBy(SETTLE)
        var applies = 0
        val subscription = Snapshot.registerApplyObserver { changes, _ -> applies += changes.size }
        mainClock.advanceTimeBy(WINDOW)
        subscription.dispose()
        assertTrue(
            applies > 0,
            "coming back from the desktop the in-app character must resume his own clock",
        )
    }

    private companion object {
        const val SETTLE = 1_000L
        const val WINDOW = 4_000L
    }
}
