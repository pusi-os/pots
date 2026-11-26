package com.pusios.pots.ui.build

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.pusios.pots.build.WebPanelOpen
import com.pusios.pots.build.WebPanelState
import com.pusios.pots.build.WebPanelStatus
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.pots_build_web_panel_open_in_browser
import kotlin.test.Test

/**
 * The row goes from "start" to its address and the open button when the state
 * turns running, with nothing rebuilt around it.
 *
 * That is how a start and a refresh reach the screen: the runtime replaces the
 * published map, the screen recomposes from it, and the row the owner is looking
 * at changes where it stands.
 */
@OptIn(ExperimentalTestApi::class)
class WebPanelsCardRunningTest {

    private fun open(): String = runBlocking { getString(Res.string.pots_build_web_panel_open_in_browser) }

    @Test
    fun `the row gains the address and the open button when the state turns running`() = runComposeUiTest {
        var status by mutableStateOf<WebPanelStatus>(WebPanelStatus.NotRunning)

        setContent {
            MaterialTheme {
                WebPanelsCard(
                    panels = persistentMapOf("hermes" to WebPanelState("hermes", status)),
                    onStart = {},
                    onStop = {},
                    onOpen = {},
                    onSetAutostart = { _, _ -> },
                    onPassword = { null },
                    onLog = { "" },
                )
            }
        }

        waitForIdle()
        onNodeWithText(ADDRESS).assertDoesNotExist()
        onNodeWithContentDescription(open()).assertDoesNotExist()

        status = WebPanelStatus.Running(ADDRESS, WebPanelOpen.DASHBOARD_SIGN_IN)
        waitForIdle()

        onNodeWithText(ADDRESS).assertExists()
        onNodeWithContentDescription(open()).assertExists()
    }
}

/** The address the Hermes row shows: the phone's own, at the panel's port. */
private const val ADDRESS = "http://192.168.1.50:9120"
