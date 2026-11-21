package com.pusios.pots.ui.build

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
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
import pots.composeapp.generated.resources.pots_build_web_panel_credentials
import pots.composeapp.generated.resources.pots_build_web_panel_open_in_browser
import pots.composeapp.generated.resources.pots_build_web_panel_password_show
import pots.composeapp.generated.resources.pots_build_web_panel_username
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The sign-in details of a panel row: folded away by default, present at every
 * status, and readable before the panel has ever been started.
 *
 * The password getter stands in for the runtime's: composing must never call it,
 * and the value it hands over must reach the screen only on the Show tap.
 */
@OptIn(ExperimentalTestApi::class)
class WebPanelsCardCredentialsTest {

    private val password = "Xq7Rz2LmNp4Tv8WkHc3J"

    private fun header(): String = runBlocking { getString(Res.string.pots_build_web_panel_credentials) }

    private fun showPassword(): String = runBlocking { getString(Res.string.pots_build_web_panel_password_show) }

    private fun openInBrowser(): String = runBlocking { getString(Res.string.pots_build_web_panel_open_in_browser) }

    private fun login(username: String): String =
        runBlocking { getString(Res.string.pots_build_web_panel_username, username) }

    /** The "Login:" part alone, whatever the locale calls it. */
    private fun loginPrefix(): String =
        runBlocking { getString(Res.string.pots_build_web_panel_username, "@@") }.substringBefore("@@")

    @Test
    fun `the section is folded and the password is not read until it is opened`() = runComposeUiTest {
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
                    onPassword = {
                        reads++
                        password
                    },
                    onLog = { "" },
                )
            }
        }

        waitForIdle()
        onNodeWithText(header).assertExists()
        onNodeWithText(showPassword()).assertDoesNotExist()
        assertEquals(0, reads, "the password is read on the tap, not while composing")
    }

    @Test
    fun `a panel that never started shows its password after Show`() = runComposeUiTest {
        val header = header()

        setContent {
            MaterialTheme {
                WebPanelsCard(
                    panels = persistentMapOf("opencode" to WebPanelState("opencode", WebPanelStatus.NotRunning)),
                    onStart = {},
                    onStop = {},
                    onOpen = {},
                    onSetAutostart = { _, _ -> },
                    onPassword = { password },
                    onLog = { "" },
                )
            }
        }

        onNodeWithText(header).performClick()
        waitForIdle()
        onNodeWithText(showPassword()).assertExists()
        onNodeWithText(password).assertDoesNotExist()

        onNodeWithText(showPassword()).performClick()

        waitForIdle()
        onNodeWithText(password).assertExists()
    }

    @Test
    fun `the section is on the row at every status, and folded`() = runComposeUiTest {
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
                    onPassword = { password },
                    onLog = { "" },
                )
            }
        }

        val statuses = listOf(
            WebPanelStatus.NotRunning,
            WebPanelStatus.Starting,
            WebPanelStatus.Running("http://127.0.0.1:4096"),
            WebPanelStatus.Failed("opencode did not start"),
        )
        for (next in statuses) {
            status = next
            waitForIdle()
            onNodeWithText(header).assertExists()
            onNodeWithText(showPassword()).assertDoesNotExist()
        }
    }

    @Test
    fun `the login is shown for the panel that asks for one`() = runComposeUiTest {
        val header = header()

        setContent {
            MaterialTheme {
                WebPanelsCard(
                    panels = persistentMapOf("hermes" to WebPanelState("hermes", WebPanelStatus.NotRunning)),
                    onStart = {},
                    onStop = {},
                    onOpen = {},
                    onSetAutostart = { _, _ -> },
                    onPassword = { password },
                    onLog = { "" },
                )
            }
        }

        onNodeWithText(header).performClick()

        waitForIdle()
        onNodeWithText(login(WebPanels.HERMES_USER)).assertExists()
    }

    @Test
    fun `pi-web gets no login line, because its form asks for the password alone`() = runComposeUiTest {
        val header = header()

        setContent {
            MaterialTheme {
                WebPanelsCard(
                    panels = persistentMapOf("pi" to WebPanelState("pi", WebPanelStatus.NotRunning)),
                    onStart = {},
                    onStop = {},
                    onOpen = {},
                    onSetAutostart = { _, _ -> },
                    onPassword = { password },
                    onLog = { "" },
                )
            }
        }

        onNodeWithText(header).performClick()

        waitForIdle()
        onAllNodes(hasText(loginPrefix(), substring = true)).assertCountEquals(0)
        // The password is still there: the row is the one place the account lives.
        onNodeWithText(showPassword()).assertExists()
    }

    @Test
    fun `the address is opened by a button of its own, and stays visible as text`() = runComposeUiTest {
        var opened = 0
        val url = "http://192.168.1.5:9120"

        setContent {
            MaterialTheme {
                WebPanelsCard(
                    panels = persistentMapOf("hermes" to WebPanelState("hermes", WebPanelStatus.Running(url))),
                    onStart = {},
                    onStop = {},
                    onOpen = { opened++ },
                    onSetAutostart = { _, _ -> },
                    onPassword = { password },
                    onLog = { "" },
                )
            }
        }

        onNodeWithText(url).assertExists()
        val button = onNodeWithContentDescription(openInBrowser())
        button.assertHasClickAction()
        button.performClick()

        waitForIdle()
        assertEquals(1, opened)
    }
}
