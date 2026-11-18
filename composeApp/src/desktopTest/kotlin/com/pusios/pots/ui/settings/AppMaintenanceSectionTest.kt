package com.pusios.pots.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.pusios.pots.update.DownloadProgress
import com.pusios.pots.update.UpdateError
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.settings_restart_onboarding
import pots.composeapp.generated.resources.settings_update_app
import pots.composeapp.generated.resources.settings_update_error_not_enough_space
import pots.composeapp.generated.resources.settings_update_retry
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The App card under PotsGame, rendered on the desktop target: both buttons have to be on
 * screen, clickable, and the update button must go dark while a transfer is running.
 */
@OptIn(ExperimentalTestApi::class)
class AppMaintenanceSectionTest {

    @Test
    fun `both actions render and reach their callbacks`() = runComposeUiTest {
        val onboardingLabel = runBlocking { getString(Res.string.settings_restart_onboarding) }
        val updateLabel = runBlocking { getString(Res.string.settings_update_app) }
        var onboardingClicks = 0
        var updateClicks = 0

        setContent {
            MaterialTheme {
                AppMaintenanceSection(
                    update = AppUpdateUiState(),
                    onRestartOnboarding = { onboardingClicks++ },
                    onUpdateApp = { updateClicks++ },
                    onCancelUpdate = {},
                )
            }
        }

        onNodeWithText(onboardingLabel).assertExists().performClick()
        onNodeWithText(updateLabel).assertExists().assertIsEnabled().performClick()
        assertEquals(1, onboardingClicks)
        assertEquals(1, updateClicks)
    }

    @Test
    fun `a failed update shows the reason and a retry button`() = runComposeUiTest {
        val errorLabel = runBlocking { getString(Res.string.settings_update_error_not_enough_space) }
        val retryLabel = runBlocking { getString(Res.string.settings_update_retry) }
        var retries = 0

        setContent {
            MaterialTheme {
                AppMaintenanceSection(
                    update = AppUpdateUiState(phase = UpdatePhase.Failed, error = UpdateError.NotEnoughSpace),
                    onRestartOnboarding = {},
                    onUpdateApp = { retries++ },
                    onCancelUpdate = {},
                )
            }
        }

        onNodeWithText(errorLabel).assertExists()
        onNodeWithText(retryLabel).assertExists().performClick()
        assertEquals(1, retries)
    }

    @Test
    fun `the update button is disabled while the transfer runs`() = runComposeUiTest {
        val updateLabel = runBlocking { getString(Res.string.settings_update_app) }

        setContent {
            MaterialTheme {
                AppMaintenanceSection(
                    update = AppUpdateUiState(
                        phase = UpdatePhase.Downloading,
                        progress = DownloadProgress(bytesReceived = 512, totalBytes = 1024, bytesPerSecond = 256),
                    ),
                    onRestartOnboarding = {},
                    onUpdateApp = {},
                    onCancelUpdate = {},
                )
            }
        }

        onNodeWithText(updateLabel).assertIsNotEnabled()
    }
}
