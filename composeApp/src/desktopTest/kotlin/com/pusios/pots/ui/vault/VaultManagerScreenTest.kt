// Vault manager UI tests (card 2/5): real temp-dir vault sessions with real
// JVM crypto, driven through the screens. Vault files are written with 1k KDF
// iterations so unlock is fast; saves hop to Dispatchers.Default, so
// assertions wait for the resulting UI instead of racing it. Editor fields
// are typed into editable nodes in composition order, with the save button's
// enabled state as a checkpoint after the name lands.

package com.pusios.pots.ui.vault

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.waitUntilDoesNotExist
import androidx.compose.ui.semantics.SemanticsProperties
import com.pusios.pots.data.vault.VaultDocument
import com.pusios.pots.data.vault.VaultField
import com.pusios.pots.data.vault.VaultFieldKind
import com.pusios.pots.data.vault.VaultItem
import com.pusios.pots.data.vault.VaultItemKind
import com.pusios.pots.data.vault.JvmVaultCryptoEngine
import com.pusios.pots.data.vault.JvmVaultFileAccess
import com.pusios.pots.data.vault.VaultSession
import com.pusios.pots.data.vault.VaultStore
import com.pusios.pots.data.vault.buildEnvelope
import com.pusios.pots.data.vault.envelopeBytes
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
import pots.composeapp.generated.resources.vault_add_item
import pots.composeapp.generated.resources.vault_common_save
import pots.composeapp.generated.resources.vault_delete_confirm_yes
import pots.composeapp.generated.resources.vault_detail_delete
import pots.composeapp.generated.resources.vault_error_wrong_password
import pots.composeapp.generated.resources.vault_list_empty
import pots.composeapp.generated.resources.vault_password_mismatch
import pots.composeapp.generated.resources.vault_setup_create
import pots.composeapp.generated.resources.vault_toggle_show
import pots.composeapp.generated.resources.vault_unlock_button

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

    /** Write a vault file directly with cheap KDF parameters; unlock reads them from the header. */
    private fun seededVaultFile(dir: File, password: String) {
        val sealed = buildEnvelope(JvmVaultCryptoEngine(), VaultDocument(), password, iters = 1_000)
        assertTrue(JvmVaultFileAccess(dir).writeAtomic(envelopeBytes(sealed.envelope)))
    }

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
        onNodeWithText(create).assertExists()

        onAllNodes(hasSetTextAction())[1].performTextReplacement("master-1")
        onNodeWithText(create).performClick()
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
    fun `unlock flow wrong password keeps the screen with the error then the right one opens the list`() = runComposeUiTest {
        val dir = tempDir()
        seededVaultFile(dir, "master-1")
        val session = newSession(dir)
        val unlockButton = runBlocking { getString(Res.string.vault_unlock_button) }
        val wrongPassword = runBlocking { getString(Res.string.vault_error_wrong_password) }
        val emptyList = runBlocking { getString(Res.string.vault_list_empty) }

        setContent {
            MaterialTheme {
                VaultManagerScreen(session)
            }
        }

        onNode(hasSetTextAction()).performTextInput("nope")
        onNodeWithText(unlockButton).performClick()
        waitUntilAtLeastOneExists(hasText(wrongPassword))

        onNode(hasSetTextAction()).performTextReplacement("master-1")
        onNodeWithText(unlockButton).performClick()
        waitUntilAtLeastOneExists(hasText(emptyList))
    }

    @Test
    fun `manager flow create card with custom field and delete it`() = runComposeUiTest {
        val dir = tempDir()
        seededVaultFile(dir, "master-1")
        val session = newSession(dir)
        runBlocking { session.unlock("master-1") }
        val add = runBlocking { getString(Res.string.vault_add_item) }
        val save = runBlocking { getString(Res.string.vault_common_save) }
        val delete = runBlocking { getString(Res.string.vault_detail_delete) }
        val deleteYes = runBlocking { getString(Res.string.vault_delete_confirm_yes) }
        val emptyList = runBlocking { getString(Res.string.vault_list_empty) }

        setContent {
            MaterialTheme {
                VaultManagerScreen(session)
            }
        }
        waitUntilAtLeastOneExists(hasText(add))

        // New card: the first editable is the name; the save button's enabled
        // state proves the text landed in the name field before anything else.
        onNodeWithText(add).performClick()
        waitUntilAtLeastOneExists(hasText(save))
        onAllNodes(hasSetTextAction())[0].performTextInput("GitHub")
        waitUntil {
            val node = onAllNodesWithText(save).fetchSemanticsNodes().firstOrNull()
            node != null && !node.config.contains(SemanticsProperties.Disabled)
        }
        // Template trio: url and username of the login draft.
        onAllNodes(hasSetTextAction())[1].performTextInput("https://github.com")
        onAllNodes(hasSetTextAction())[2].performTextInput("alice")
        // The template's password value is the last editable before notes.
        onAllNodes(hasSetTextAction())[8].performTextInput("s3cret-42")
        // A custom field: appended row, label then value.
        onNodeWithText(runBlocking { getString(Res.string.vault_editor_add_field) }).performClick()
        waitUntil { onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size >= 11 }
        onAllNodes(hasSetTextAction())[9].performTextInput("API key")
        onAllNodes(hasSetTextAction())[10].performTextInput("tok-123")
        onNodeWithText(save).performClick()

        // Back on the list with the new card; open the detail.
        waitUntilAtLeastOneExists(hasText("GitHub"))
        onNodeWithText("GitHub").performClick()

        // Secrets stay masked in the detail view; the plain custom field shows.
        waitUntilAtLeastOneExists(hasText("tok-123"))
        onNodeWithText("s3cret-42").assertDoesNotExist()

        // Delete via the confirmation dialog; its confirm button text is distinct.
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
