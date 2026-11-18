package com.pusios.pots.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.pusios.pots.assistant.AssistantAvailability
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.settings_assistant_assign
import pots.composeapp.generated.resources.settings_assistant_held
import pots.composeapp.generated.resources.settings_assistant_needs_android
import pots.composeapp.generated.resources.settings_assistant_not_supported
import pots.composeapp.generated.resources.settings_assistant_open_settings
import pots.composeapp.generated.resources.settings_assistant_title
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The General-tab card that sends the user to default-apps settings.
 * ROLE_ASSISTANT is not requestable, so the only working action is opening
 * the system screen; the button is dead on platforms / API levels that
 * cannot hold the role.
 */
@OptIn(ExperimentalTestApi::class)
class AssistantSettingsSectionTest {

    @Test
    fun `an available unassigned role shows the assign button and opens settings`() = runComposeUiTest {
        val title = runBlocking { getString(Res.string.settings_assistant_title) }
        val assign = runBlocking { getString(Res.string.settings_assistant_assign) }
        var opens = 0

        setContent {
            MaterialTheme {
                AssistantSettingsSection(
                    availability = AssistantAvailability.Available(isHeld = false),
                    onOpenDefaultApps = { opens++ },
                )
            }
        }

        onNodeWithText(title).assertExists()
        onNodeWithText(assign).assertExists().assertIsEnabled().performClick()
        assertEquals(1, opens)
    }

    @Test
    fun `when Pots is already the assistant the status says so and settings still open`() = runComposeUiTest {
        val held = runBlocking { getString(Res.string.settings_assistant_held) }
        val open = runBlocking { getString(Res.string.settings_assistant_open_settings) }
        var opens = 0

        setContent {
            MaterialTheme {
                AssistantSettingsSection(
                    availability = AssistantAvailability.Available(isHeld = true),
                    onOpenDefaultApps = { opens++ },
                )
            }
        }

        onNodeWithText(held).assertExists()
        onNodeWithText(open).assertExists().assertIsEnabled().performClick()
        assertEquals(1, opens)
    }

    @Test
    fun `older Android shows a disabled control and the required version`() = runComposeUiTest {
        val reason = runBlocking { getString(Res.string.settings_assistant_needs_android, "10") }
        val assign = runBlocking { getString(Res.string.settings_assistant_assign) }
        var opens = 0

        setContent {
            MaterialTheme {
                AssistantSettingsSection(
                    availability = AssistantAvailability.NeedsNewerAndroid("10"),
                    onOpenDefaultApps = { opens++ },
                )
            }
        }

        onNodeWithText(reason).assertExists()
        onNodeWithText(assign).assertIsNotEnabled()
        assertEquals(0, opens)
    }

    @Test
    fun `desktop hides the assign action entirely`() = runComposeUiTest {
        val reason = runBlocking { getString(Res.string.settings_assistant_not_supported) }
        val assign = runBlocking { getString(Res.string.settings_assistant_assign) }

        setContent {
            MaterialTheme {
                AssistantSettingsSection(
                    availability = AssistantAvailability.NotOnThisPlatform,
                    onOpenDefaultApps = {},
                )
            }
        }

        onNodeWithText(reason).assertExists()
        onNodeWithText(assign).assertDoesNotExist()
    }
}
