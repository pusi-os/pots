// Vault form bridge tests (card 2/5, review round 2): the tool-facing contract
// around a locked vault. The tool waits in [VaultFormBridge.request]; what it
// hears when the user cancels depends on the vault state — a plain refusal
// while unlocked, `vault_locked` while the unlock step is cancelled (delta
// §6.1), and `vault_locked` when a submit raced an auto-lock.

package com.pusios.pots.data.vault

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VaultFormBridgeTest {

    private fun tempDir(): File = Files.createTempDirectory("vault-bridge-test").toFile()

    /** Write a vault file directly with cheap KDF parameters; unlock reads them from the header. */
    private fun seededVaultFile(dir: File, password: String) {
        val sealed = buildEnvelope(JvmVaultCryptoEngine(), VaultDocument(), password, iters = 1_000)
        assertTrue(JvmVaultFileAccess(dir).writeAtomic(envelopeBytes(sealed.envelope)))
    }

    private fun newSession(dir: File): VaultSession = VaultSession(store = VaultStore(JvmVaultFileAccess(dir), JvmVaultCryptoEngine()))

    /** An existing vault whose master password is known; the session is open. */
    private suspend fun unlockedSession(): VaultSession {
        val dir = tempDir()
        seededVaultFile(dir, "master-1")
        return newSession(dir).also { it.unlock("master-1") }
    }

    /** An existing vault; the session starts closed, as after an auto-lock. */
    private fun lockedSession(): VaultSession {
        val dir = tempDir()
        seededVaultFile(dir, "master-1")
        return newSession(dir)
    }

    private fun form(id: String) = VaultFormRequest(
        id = id,
        mode = "create",
        reason = "I need the token to set up CI in your repository",
        fields = listOf(VaultFormFieldSpec(key = "password", label = "Password", input = VaultFieldInput.SECRET)),
    )

    @Test
    fun `cancel while unlocked answers the tool with cancelled`() = runTest {
        val session = unlockedSession()
        val bridge = VaultFormBridge(session)
        var outcome: VaultFormOutcome? = null
        val job = launch { outcome = bridge.request(form("f1")) }
        runCurrent()

        bridge.cancel("f1")
        job.join()
        assertEquals(VaultFormOutcome.Cancelled, outcome)
    }

    @Test
    fun `cancelling the unlock step answers the tool with vault_locked`() = runTest {
        val session = lockedSession()
        val bridge = VaultFormBridge(session)
        var outcome: VaultFormOutcome? = null
        val job = launch { outcome = bridge.request(form("f1")) }
        runCurrent()
        assertTrue(session.locked.value)

        // The user cancels the sheet while the unlock step is showing.
        bridge.cancel("f1")
        job.join()
        assertEquals(VaultFormOutcome.Locked, outcome)
    }

    @Test
    fun `submit into a locked vault answers the tool with vault_locked and drops the form`() = runTest {
        val session = lockedSession()
        val bridge = VaultFormBridge(session)
        var outcome: VaultFormOutcome? = null
        val job = launch { outcome = bridge.request(form("f1")) }
        runCurrent()

        val submitted = bridge.submit("f1", conversationId = null, values = mapOf("password" to "s3cret-42"))
        job.join()
        advanceUntilIdle()
        assertEquals(VaultFormOutcome.Locked, submitted)
        assertEquals(VaultFormOutcome.Locked, outcome)
        // The sheet is gone: a closed tool call must leave no stale form.
        assertEquals(null, bridge.pending.value)
    }
}
