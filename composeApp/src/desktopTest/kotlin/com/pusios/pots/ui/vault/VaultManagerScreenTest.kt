// Vault manager UI tests (card 2/5): real temp-dir vault sessions with real
// JVM crypto, driven through the screens. Vault files are written with 1k KDF
// iterations so unlock is fast; saves hop to Dispatchers.Default, so
// assertions wait for the resulting UI instead of racing it. Editor fields
// are typed into editable nodes in composition order, with the save button's
// enabled state and node counts as checkpoints. Each flow step wraps its
// failures in its own exception class so the CI console names the step that
// hung (Gradle prints exception types of failed tests, not messages).

package com.pusios.pots.ui.vault

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.waitUntilDoesNotExist
import com.pusios.pots.data.vault.JvmVaultCryptoEngine
import com.pusios.pots.data.vault.JvmVaultFileAccess
import com.pusios.pots.data.vault.VaultDocument
import com.pusios.pots.data.vault.VaultField
import com.pusios.pots.data.vault.VaultFieldKind
import com.pusios.pots.data.vault.VaultItem
import com.pusios.pots.data.vault.VaultItemKind
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
import pots.composeapp.generated.resources.vault_editor_add_field
import pots.composeapp.generated.resources.vault_editor_new_title
import pots.composeapp.generated.resources.vault_error_wrong_password
import pots.composeapp.generated.resources.vault_list_empty
import pots.composeapp.generated.resources.vault_password_mismatch
import pots.composeapp.generated.resources.vault_setup_create
import pots.composeapp.generated.resources.vault_toggle_show
import pots.composeapp.generated.resources.vault_unlock_button

/** One exception class per flow step: Gradle logs exception types of failed tests. */
private class VaultStep00StateException : IllegalStateException("state not unlocked")
private class VaultStep01FrameException : IllegalStateException("no frame")
private class VaultStep01ProbeException : IllegalStateException("state flipped")
private class VaultStep01FabException : IllegalStateException("fab missing")
private class VaultStep02EditorException : IllegalStateException("editor")
private class VaultStep03NameException : IllegalStateException("name")
private class VaultStep04FieldException : IllegalStateException("custom field")
private class VaultStep05SaveException : IllegalStateException("save")
private class VaultStep06DetailException : IllegalStateException("detail")
private class VaultStep07DeleteException : IllegalStateException("delete")

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
        val model = VaultViewModel(session, CoroutineScope(Dispatchers.Default))
        runBlocking { model.refresh() }
        if (model.state.value !is VaultUiState.Unlocked) {
            throw VaultStep00StateException()
        }
        val add = runBlocking { getString(Res.string.vault_add_item) }
        val save = runBlocking { getString(Res.string.vault_common_save) }
        val delete = runBlocking { getString(Res.string.vault_detail_delete) }
        val deleteYes = runBlocking { getString(Res.string.vault_delete_confirm_yes) }
        val addField = runBlocking { getString(Res.string.vault_editor_add_field) }
        val newTitle = runBlocking { getString(Res.string.vault_editor_new_title) }
        val emptyList = runBlocking { getString(Res.string.vault_list_empty) }

        fun unlockedItems(): List<VaultItem> =
            (model.state.value as? VaultUiState.Unlocked)?.items ?: emptyList()

        setContent {
            MaterialTheme {
                val state by model.state.collectAsState()
                if (state is VaultUiState.Unlocked) {
                    VaultUnlockedContent(model, state as VaultUiState.Unlocked)
                } else {
                    Text("VAULT-PROBE-NOT-UNLOCKED")
                }
            }
        }

        try {
            // 01: the frame is rendered as one of the three possible screens.
            waitUntilAtLeastOneExists(
                hasText(add).or(hasText(emptyList)).or(hasText("VAULT-PROBE-NOT-UNLOCKED")),
            )
        } catch (t: Throwable) {
            throw VaultStep01FrameException()
        }
        if (onAllNodesWithText("VAULT-PROBE-NOT-UNLOCKED").fetchSemanticsNodes().isNotEmpty()) {
            throw VaultStep01ProbeException()
        }
        // 01b: the FAB text does not merge into the merged semantics tree on desktop,
        // so the FAB is located by its test tag instead.
        if (onAllNodesWithTag(ADD_FAB_TAG).fetchSemanticsNodes().isEmpty()) {
            throw VaultStep01FabException()
        }
        try {
            // 02: the FAB opens the editor.
            onNodeWithTag(ADD_FAB_TAG).performClick()
            waitUntilAtLeastOneExists(hasText(newTitle))
        } catch (t: Throwable) {
            throw VaultStep02EditorException()
        }
        try {
            // 03: the name lands in the first editable; the save button enables.
            onAllNodes(hasSetTextAction())[0].performTextInput("GitHub")
            waitUntil {
                val node = onAllNodesWithText(save).fetchSemanticsNodes().firstOrNull()
                node != null && !node.config.contains(SemanticsProperties.Disabled)
            }
            // The login template trio and its password value.
            onAllNodes(hasSetTextAction())[1].performTextInput("https://github.com")
            onAllNodes(hasSetTextAction())[2].performTextInput("alice")
            onAllNodes(hasSetTextAction())[8].performTextInput("s3cret-42")
        } catch (t: Throwable) {
            throw VaultStep03NameException()
        }
        try {
            // 04: the custom field row appears (11 editables before, 13 after).
            // The button sits below the visible window: scroll it into view first.
            onNode(hasScrollAction()).performScrollToNode(hasText(addField))
            onNodeWithText(addField).performClick()
            waitUntil { onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size >= 13 }
            onAllNodes(hasSetTextAction())[9].performTextInput("API key")
            onAllNodes(hasSetTextAction())[10].performTextInput("tok-123")
        } catch (t: Throwable) {
            throw VaultStep04FieldException()
        }
        try {
            // 05: save writes the card through the session.
            onNode(hasScrollAction()).performScrollToNode(hasText(save))
            onNodeWithText(save).performClick()
            waitUntil { unlockedItems().any { it.name == "GitHub" } }
            waitUntilAtLeastOneExists(hasText("GitHub"))
        } catch (t: Throwable) {
            throw VaultStep05SaveException()
        }
        try {
            // 06: the detail shows the plain custom field but not the secret.
            onNodeWithText("GitHub").performClick()
            waitUntilAtLeastOneExists(hasText("tok-123"))
            onNodeWithText("s3cret-42").assertDoesNotExist()
        } catch (t: Throwable) {
            throw VaultStep06DetailException()
        }
        try {
            // 07: delete via the dialog; the list is empty again.
            onNodeWithText(delete).performClick()
            onNodeWithText(deleteYes).performClick()
            waitUntil { unlockedItems().isEmpty() }
            waitUntilAtLeastOneExists(hasText(emptyList))
            waitUntilDoesNotExist(hasText("GitHub"))
        } catch (t: Throwable) {
            throw VaultStep07DeleteException()
        }
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
