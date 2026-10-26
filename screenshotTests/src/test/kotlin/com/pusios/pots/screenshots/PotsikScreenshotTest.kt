package com.pusios.pots.screenshots

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.pusios.pots.ui.DarkColorScheme
import com.pusios.pots.ui.potsik.PotsikAction
import com.pusios.pots.ui.potsik.PotsikLook
import com.pusios.pots.ui.potsik.PotsikOutfit
import com.pusios.pots.ui.potsik.PotsikPosePreview
import com.pusios.pots.ui.settings.SettingsScreenContent
import com.pusios.pots.ui.settings.SettingsTab
import com.pusios.pots.ui.settings.SettingsUiState
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.setResourceReaderAndroidContext
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Golden images of the character itself.
 *
 * The character is drawn entirely in code, so these are the only way a change
 * to a single curve shows up in a diff.
 */
@OptIn(ExperimentalResourceApi::class)
class PotsikScreenshotTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_9A.copy(softButtons = false),
        showSystemUi = false,
        maxPercentDifference = 0.1,
    )

    @Before
    fun setup() {
        setResourceReaderAndroidContext(paparazzi.context)
    }

    private fun Paparazzi.snapPotsik(content: @Composable () -> Unit) {
        unsafeUpdateConfig(theme = "android:Theme.Material.NoActionBar")
        snapshot(name = javaClass.simpleName, composable = content)
    }

    @Test
    fun `potsik outfits`() {
        paparazzi.snapPotsik {
            Surface(color = DarkColorScheme.background) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    PotsikOutfit.entries.chunked(4).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            row.forEach { outfit ->
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    PotsikPosePreview(
                                        look = PotsikLook.Default.copy(outfit = outfit),
                                        action = PotsikAction.IDLE_BREATHE,
                                        timeMs = 400f,
                                        size = 88.dp,
                                    )
                                    Text(outfit.name, color = DarkColorScheme.onBackground, fontSize = 9.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `potsik settings tab`() {
        paparazzi.snapPotsik {
            Surface(color = DarkColorScheme.background) {
                SettingsScreenContent(
                    uiState = SettingsUiState(currentTab = SettingsTab.Potsiki),
                )
            }
        }
    }

    @Test
    fun `potsik animations`() {
        paparazzi.snapPotsik {
            Surface(color = DarkColorScheme.background) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PotsikAction.entries.chunked(6).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            row.forEach { action ->
                                PotsikPosePreview(
                                    look = PotsikLook.Default,
                                    action = action,
                                    timeMs = 700f,
                                    size = 92.dp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
