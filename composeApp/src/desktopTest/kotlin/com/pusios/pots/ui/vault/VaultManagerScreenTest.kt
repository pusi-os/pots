// Vault manager UI tests (card 2/5): real temp-dir vault sessions with real
// JVM crypto, driven through the screens. Field values are typed into editable
// nodes in composition order; saves hop to Dispatchers.Default, so assertions
// wait for the resulting UI instead of racing it.

package com.pusios.pots.ui.vault

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.waitUntilDoesNotExist
import com.pusios.pots.data.vault.VaultField
import com.pusios.pots.data.vault.VaultFieldKind
import com.pusios.pots.data.vault.VaultItem
import com.pusios.pots.data.vault.VaultItemKind
import com.pusios.pots.data.vault.VaultSession
import com.pusios.pots.data.vault.VaultStore
import com.pusios.pots.data.vault.JvmVaultCryptoEngine
import com.pusios.pots.data.vault.JvmVaultFileAccess
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import pots.composeapp.generated.resources.Res

@OptIn(ExperimentalTestApi::class, ExperimentalTime::class)
class VaultManagerScreenTest {

    private class ManualClock(var elapsedMillis: Long = 0) : TimeSource {
        private var markedAt: Long = 0

        private inner class ManualMark : TimeMark {
            override fun elapsedNow(): Duration = (elapsedMillis - markedAt).milliseconds
        }

        override fun markNow(): TimeMark {
            markedAt = elapsedMillis
            return ManualMark()
        }
    }

    private fun tempDir(): File = Files.createTempDirectory("vault-ui-test").toFile()

    private fun newSession(
        dir: File,
        autoLockTimeout: Duration = Duration.INFINITE,
        clock: TimeSource = TimeSource.Monotonic,
    ): VaultSession = VaultSession(
        store = VaultStore(JvmVaultFileAccess(dir), JvmVaultCryptoEngine()),
        autoLockTimeout = autoLockTimeout,
        clock = clock,
    )

    @Test
    fun `setup screen requires matching passwords before creating`() = runComposeUiTest {
        var created: String? = null
        val mismatch = runBlocking { getString(Res.string.vault_password_mismatch) }
        val create = runBlocking { getString(Res.string.vault_setup_create) }

        setContent {
            MaterialTheme {
                VaultSetupScreen(onSetup = { created = it })
            }
        }

        onAllNodes(hasSetTextAction())[0].performTextInput("master-1")
        onAllNodes(hasSetTextAction())[1].performTextInput("other")
        onNodeWithText(mismatch).assertExists()
        onNodeWithText(create).assertIsNotEnabled()

        onAllNodes(hasSetTextAction())[1].performTextReplacement("master-1")
        onNodeWithText(create).assertIsEnabled().performClick()
        waitUntil { created == "master-1" }
    }

    @Test
    fun `unlock screen shows the wrong password error`() = runComposeUiTest {
        val error = runBlocking { getString(Res.string.vault_error_wrong_password) }

        setContent {
            MaterialTheme {
                VaultUnlockScreen(error = VaultUnlockError.WRONG_PASSWORD, onUnlock = {})
            }
        }

        onNodeWithText(error).assertExists()
    }

    @Test
    fun `detail screen masks secrets and reveals them on the eye`() = runComposeUiTest {
        val show = runBlocking { getString(Res.string.vault_toggle_show) }
        val item = VaultItem(
            id = "1",
            type = VaultItemKind.LOGIN.id,
            name = "GitHub",
            username = "alice",
            fields = listOf(
                VaultField(key = "password", label = "Password", type = VaultFieldKind.SECRET.id, value = "s3cret-42"),
            ),
        )

        setContent {
            MaterialTheme {
                VaultDetailScreen(item = item, onBack = {}, onEdit = {}, onDelete = {})
            }
        }

        // The secret value is never in the text while masked.
        onNodeWithText("s3cret-42").assertDoesNotExist()
        onNodeWithText("••••••••").assertExists()

        onNodeWithContentDescription(show).performClick()
        onNodeWithText("s3cret-42").assertExists()
    }

    @Test
    fun `manager flow unlock wrong then right create card with custom field and delete`() = runComposeUiTest {
        val dir = tempDir()
        val session = newSession(dir)
        runBlocking {
            session.createVault("master-1")
            session.lock()
        }
        val unlockButton = runBlocking { getString(Res.string.vault_unlock_button) }
        val wrongPassword = runBlocking { getString(Res.string.vault_error_wrong_password) }
        val add = runBlocking { getString(Res.string.vault_add_item) }
        val addField = runBlocking { getString(Res.string.vault_editor_add_field) }
        val save = runBlocking { getString(Res.string.vault_common_save) }
        val delete = runBlocking { getString(Res.string.vault_detail_delete) }
        val deleteYes = runBlocking { getString(Res.string.vault_delete_confirm_yes) }
        val emptyList = runBlocking { getString(Res.string.vault_list_empty) }

        setContent {
            MaterialTheme {
                VaultManagerScreen(session)
            }
        }

        // Wrong password: distinct error, still locked.
        onNode(hasSetTextAction()).performTextInput("nope")
        onNodeWithText(unlockButton).performClick()
        waitUntilAtLeastOneExists(hasText(wrongPassword))

        // Correct password: the list opens.
        onNode(hasSetTextAction()).performTextReplacement("master-1")
        onNodeWithText(unlockButton).performClick()
        waitUntilAtLeastOneExists(hasText(add))
        waitUntilAtLeastOneExists(hasText(emptyList))

        // New card: the first editable is the name field.
        onNodeWithText(add).performClick()
        onAllNodes(hasSetTextAction())[0].performTextInput("GitHub")
        // The two top-level inputs: URL and username of the login template.
        onAllNodes(hasSetTextAction())[1].performTextInput("https://github.com")
        onAllNodes(hasSetTextAction())[2].performTextInput("alice")
        // The template's password field value is the last editable before notes.
        onAllNodes(hasSetTextAction())[8].performTextInput("s3cret-42")
        // A custom field: appended row, label then value.
        onNodeWithText(addField).performClick()
        onAllNodes(hasSetTextAction())[9].performTextInput("API key")
        onAllNodes(hasSetTextAction())[10].performTextInput("tok-123")
        onNodeWithText(save).performClick()

        // Back on the list with the new card; open the detail.
        waitUntilAtLeastOneExists(hasText("GitHub"))
        onNodeWithText("GitHub").performClick()

        // Secrets stay masked in the detail view; the plain custom field shows.
        waitUntilAtLeastOneExists(hasText("tok-123"))
        onNodeWithText("s3cret-42").assertDoesNotExist()

        // Delete via the confirmation dialog.
        onNodeWithText(delete).performClick()
        onNodeWithText(deleteYes).performClick()
        waitUntilAtLeastOneExists(hasText(emptyList))
        waitUntilDoesNotExist(hasText("GitHub"))
    }

    @Test
    fun `model returns to the locked screen on session auto lock`() {
        val dir = tempDir()
        val clock = ManualClock()
        val session = newSession(dir, autoLockTimeout = 100.milliseconds, clock = clock)
        val model = VaultViewModel(session, CoroutineScope(Dispatchers.Default))
        runBlocking {
            model.setup("master-1")
            assertTrue(model.state.value is VaultUiState.Unlocked)
            clock.elapsedMillis = 500
            model.setQuery("github")
        }
        assertTrue(model.state.value is VaultUiState.Locked)
    }
}
