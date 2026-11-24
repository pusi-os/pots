@file:OptIn(ExperimentalVoiceApi::class)

package com.pusios.pots.screenshots

import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.pusios.pots.data.Service
import com.pusios.pots.ui.DarkColorScheme
import com.pusios.pots.ui.LightColorScheme
import com.pusios.pots.ui.Theme
import com.pusios.pots.ui.chat.ChatScreenContent
import com.pusios.pots.ui.chat.OnboardingPhase
import com.pusios.pots.ui.chat.OnboardingState
import com.pusios.pots.ui.dynamicui.LocalPreviewImages
import com.pusios.pots.ui.settings.SettingsScreenContent
import com.pusios.pots.ui.vault.VaultUnlockScreen
import nl.marc_apps.tts.experimental.ExperimentalVoiceApi
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.setResourceReaderAndroidContext
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.onboarding_error_network

@OptIn(ExperimentalResourceApi::class)
class ScreenshotTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_9A.copy(softButtons = false),
        showSystemUi = true,
        maxPercentDifference = 0.1,
    )

    private val previewImages = mutableMapOf<String, ImageBitmap>()

    @Before
    fun setup() {
        setResourceReaderAndroidContext(paparazzi.context)
        loadPreviewImage("resource://orc_survival.png", "/orc_survival.png")
        loadPreviewImage("resource://cacio_e_pepe.png", "/cacio_e_pepe.png")
    }

    private fun loadPreviewImage(key: String, resourcePath: String) {
        val bitmap = BitmapFactory.decodeStream(javaClass.getResourceAsStream(resourcePath)) ?: return
        previewImages[key] = bitmap.asImageBitmap()
    }

    fun Paparazzi.snap(
        colorScheme: ColorScheme,
        content: @Composable () -> Unit,
    ) {
        val theme = if (colorScheme == DarkColorScheme) {
            "android:Theme.Material.NoActionBar"
        } else {
            "android:Theme.Material.Light.NoActionBar"
        }
        unsafeUpdateConfig(theme = theme)

        snapshot {
            CompositionLocalProvider(
                LocalInspectionMode provides true,
                LocalPreviewImages provides previewImages,
            ) {
                Theme(colorScheme = colorScheme) {
                    content()
                }
            }
        }
    }

    @Test
    fun chatEmptyState_light() {
        paparazzi.snap(LightColorScheme) {
            ChatScreenContent(
                uiState = ScreenshotTestData.chatEmptyState,
                FakeTextToSpeechInstance(),
            )
        }
    }

    @Test
    fun chatWithMessages_dark() {
        paparazzi.snap(DarkColorScheme) {
            ChatScreenContent(
                uiState = ScreenshotTestData.chatWithMessages,
                FakeTextToSpeechInstance(),
            )
        }
    }

    @Test
    fun chatSecondRequestRunning_light() {
        paparazzi.snap(LightColorScheme) {
            ChatScreenContent(
                uiState = ScreenshotTestData.chatSecondRequestRunning,
            )
        }
    }

    @Test
    fun chatWithDynamicUi_light() {
        paparazzi.snap(LightColorScheme) {
            ChatScreenContent(
                uiState = ScreenshotTestData.chatWithDynamicUi,
                FakeTextToSpeechInstance(),
            )
        }
    }

    @Test
    fun settingsAgent_dark() {
        paparazzi.snap(DarkColorScheme) {
            SettingsScreenContent(
                uiState = ScreenshotTestData.settingsAgent,
                sandboxState = ScreenshotTestData.sandboxState,
            )
        }
    }

    @Test
    fun settingsFree_dark() {
        paparazzi.snap(DarkColorScheme) {
            SettingsScreenContent(
                uiState = ScreenshotTestData.freeConnected,
                sandboxState = ScreenshotTestData.sandboxState,
            )
        }
    }

    @Test
    fun settingsTools_light() {
        paparazzi.snap(LightColorScheme) {
            SettingsScreenContent(
                uiState = ScreenshotTestData.settingsTools,
                sandboxState = ScreenshotTestData.sandboxState,
            )
        }
    }

    @Test
    fun settingsVault_dark() {
        paparazzi.snap(DarkColorScheme) {
            SettingsScreenContent(
                uiState = ScreenshotTestData.settingsVault,
                sandboxState = ScreenshotTestData.sandboxState,
                // The real lock screen: the slot composes without a session or
                // a Koin container, which paparazzi does not provide.
                vaultContent = { VaultUnlockScreen(error = null, onUnlock = {}) },
            )
        }
    }

    @Test
    fun settingsSandbox_dark() {
        paparazzi.snap(DarkColorScheme) {
            ChatScreenContent(
                uiState = ScreenshotTestData.chatEmptyState,
                isSandboxAvailable = true,
                initialSandboxOpen = true,
                previewSandboxState = ScreenshotTestData.sandboxState,
                previewSandboxLines = ScreenshotTestData.sandboxTerminalLines,
            )
        }
    }

    // ---- onboarding wizard steps (docs/design/onboarding; 3.20.0 step flow) ----

    private fun onboardingState(
        error: org.jetbrains.compose.resources.StringResource? = null,
        isChecking: Boolean = false,
        isKeyAdded: Boolean = false,
        step: Int = 1,
    ) = OnboardingState(
        phase = OnboardingPhase.Active,
        step = step,
        serviceId = if (step >= 2) Service.PlusVibe.id else null,
        error = error,
        isChecking = isChecking,
        isKeyAdded = isKeyAdded,
    )

    @Test
    fun onboardingProviderPicker_dark() {
        paparazzi.snap(DarkColorScheme) {
            ChatScreenContent(uiState = ScreenshotTestData.chatOnboarding(onboardingState()))
        }
    }

    @Test
    fun onboardingProviderGuide_dark() {
        paparazzi.snap(DarkColorScheme) {
            ChatScreenContent(uiState = ScreenshotTestData.chatOnboarding(onboardingState(step = 2)))
        }
    }

    @Test
    fun onboardingProviderGuide_narrow_dark() {
        paparazzi.snap(DarkColorScheme) {
            Box(Modifier.width(300.dp)) {
                ChatScreenContent(uiState = ScreenshotTestData.chatOnboarding(onboardingState(step = 2)))
            }
        }
    }

    @Test
    fun onboardingKeyChecking_dark() {
        paparazzi.snap(DarkColorScheme) {
            ChatScreenContent(uiState = ScreenshotTestData.chatOnboarding(onboardingState(step = 3, isChecking = true)))
        }
    }

    @Test
    fun onboardingKeyNetworkError_light() {
        paparazzi.snap(LightColorScheme) {
            ChatScreenContent(
                uiState = ScreenshotTestData.chatOnboarding(onboardingState(step = 3, error = Res.string.onboarding_error_network)),
            )
        }
    }

    @Test
    fun onboardingKeyAdded_dark() {
        paparazzi.snap(DarkColorScheme) {
            ChatScreenContent(uiState = ScreenshotTestData.chatOnboarding(onboardingState(step = 3, isKeyAdded = true)))
        }
    }
}
