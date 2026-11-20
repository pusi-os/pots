package com.pusios.pots.ui.build

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.pusios.pots.build.WebPanelState
import com.pusios.pots.build.WebPanelStatus
import com.pusios.pots.build.WebPanels
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.pots_build_web_panel_log
import pots.composeapp.generated.resources.pots_build_web_panel_log_refresh
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The log section of a panel row: folded away by default, read only when the
 * owner asks for it — or when a start fails, which is exactly when it is needed.
 *
 * The counter stands in for the file read: composing the card must never touch it.
 */
@OptIn(ExperimentalTestApi::class)
class WebPanelsCardLogTest {

    private val log = "starting opencode\nlistening on 4096"

    private fun header(): String = runBlocking { getString(Res.string.pots_build_web_panel_log) }

    private fun refresh(): String = runBlocking { getString(Res.string.pots_build_web_panel_log_refresh) }

    @Test
    fun `the section is folded and the log is not read until it is opened`() = runComposeUiTest {
        var reads = 0
        val header = header()

        setContent {
            MaterialTheme {
                WebPanelsCard(
                    panels = persistentMapOf("opencode" to WebPanelState("opencode", WebPanelStatus.NotRunning)),
                    onStart = {},
                    onStop = {},
                    onOpen = {},
                    onSetAutostart = { _, _ -> },
                    onPassword = { null },
                    onLog = {
                        reads++
                        log
                    },
                )
            }
        }

        waitForIdle()
        onNodeWithText(header).assertExists()
        onNodeWithText(log).assertDoesNotExist()
        assertEquals(0, reads, "the log is read on the tap, not while composing")
    }

    @Test
    fun `opening the section reads the log and shows it as it is`() = runComposeUiTest {
        var reads = 0
        val header = header()

        setContent {
            MaterialTheme {
                WebPanelsCard(
                    panels = persistentMapOf("opencode" to WebPanelState("opencode", WebPanelStatus.NotRunning)),
                    onStart = {},
                    onStop = {},
                    onOpen = {},
                    onSetAutostart = { _, _ -> },
                    onPassword = { null },
                    onLog = {
                        reads++
                        log
                    },
                )
            }
        }

        onNodeWithText(header).performClick()

        waitUntil { reads == 1 }
        onNodeWithText(log).assertExists()
    }

    @Test
    fun `the refresh button reads the log again`() = runComposeUiTest {
        var reads = 0
        val header = header()
        val refresh = refresh()

        setContent {
            MaterialTheme {
                WebPanelsCard(
                    panels = persistentMapOf("opencode" to WebPanelState("opencode", WebPanelStatus.NotRunning)),
                    onStart = {},
                    onStop = {},
                    onOpen = {},
                    onSetAutostart = { _, _ -> },
                    onPassword = { null },
                    onLog = {
                        reads++
                        log
                    },
                )
            }
        }

        onNodeWithText(header).performClick()
        waitUntil { reads == 1 }

        onNodeWithText(refresh).performClick()
        waitUntil { reads == 2 }
    }

    @Test
    fun `a failed start opens the section and shows the log without a tap`() = runComposeUiTest {
        var reads = 0

        setContent {
            MaterialTheme {
                WebPanelsCard(
                    panels = persistentMapOf(
                        "opencode" to WebPanelState("opencode", WebPanelStatus.Failed("opencode did not start")),
                    ),
                    onStart = {},
                    onStop = {},
                    onOpen = {},
                    onSetAutostart = { _, _ -> },
                    onPassword = { null },
                    onLog = {
                        reads++
                        log
                    },
                )
            }
        }

        waitUntil { reads == 1 }
        onNodeWithText(log).assertExists()
    }

    @Test
    fun `a status refresh does not fold the open section`() = runComposeUiTest {
        var reads = 0
        var status by mutableStateOf<WebPanelStatus>(WebPanelStatus.NotRunning)
        val header = header()

        setContent {
            MaterialTheme {
                WebPanelsCard(
                    panels = persistentMapOf("opencode" to WebPanelState("opencode", status)),
                    onStart = {},
                    onStop = {},
                    onOpen = {},
                    onSetAutostart = { _, _ -> },
                    onPassword = { null },
                    onLog = {
                        reads++
                        log
                    },
                )
            }
        }

        onNodeWithText(header).performClick()
        waitUntil { reads == 1 }

        // The row is polled: a new status must not shut a section the owner opened.
        status = WebPanelStatus.Starting
        waitForIdle()

        onNodeWithText(log).assertExists()
        assertEquals(1, reads, "a status change is not a reason to read the log again")
    }

    @Test
    fun `the panels card still names the agent it belongs to`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                WebPanelsCard(
                    panels = persistentMapOf("opencode" to WebPanelState("opencode", WebPanelStatus.NotRunning)),
                    onStart = {},
                    onStop = {},
                    onOpen = {},
                    onSetAutostart = { _, _ -> },
                    onPassword = { null },
                    onLog = { "" },
                )
            }
        }

        onNodeWithText(WebPanels.get(WebPanels.OPENCODE)!!.title).assertExists()
    }
}
