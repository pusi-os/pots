package com.pusios.pots.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.pusios.pots.data.LocalToolSelection
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.settings_local_model_description
import pots.composeapp.generated.resources.settings_local_model_title
import kotlin.test.Test

/**
 * The «Локальная модель» block on the Tools tab is collapsed by default and expands
 * on a header click. It composes without a Koin container — everything is slot
 * parameters, which is why this test can render it bare.
 */
@OptIn(ExperimentalTestApi::class)
class LocalModelCollapsibleSectionTest {

    @Test
    fun `collapsed by default`() = runComposeUiTest {
        val title = runBlocking { getString(Res.string.settings_local_model_title) }
        val description = runBlocking { getString(Res.string.settings_local_model_description) }

        setContent {
            MaterialTheme {
                LocalModelCollapsibleSection(
                    tools = persistentListOf(),
                    mcpServers = persistentListOf(),
                    selection = LocalToolSelection(),
                    isConfigured = false,
                    onToggleTool = { _, _ -> },
                    onToggleMcpServer = { _, _ -> },
                    onReset = {},
                )
            }
        }

        onNodeWithText(title).assertExists()
        onNodeWithText(description).assertDoesNotExist()
    }

    @Test
    fun `header click expands the tool selection`() = runComposeUiTest {
        val title = runBlocking { getString(Res.string.settings_local_model_title) }
        val description = runBlocking { getString(Res.string.settings_local_model_description) }

        setContent {
            MaterialTheme {
                LocalModelCollapsibleSection(
                    tools = persistentListOf(),
                    mcpServers = persistentListOf(),
                    selection = LocalToolSelection(),
                    isConfigured = false,
                    onToggleTool = { _, _ -> },
                    onToggleMcpServer = { _, _ -> },
                    onReset = {},
                )
            }
        }

        onNodeWithText(title).performClick()
        waitForIdle()
        onNodeWithText(description).assertExists()
    }
}
