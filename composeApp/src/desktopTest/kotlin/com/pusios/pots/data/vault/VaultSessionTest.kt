// Vault session integration tests: real files in a temp directory, real JVM
// crypto. These cover the card's acceptance criteria — roundtrip, ciphertext
// only on disk, wrong password, tamper, truncation, version refusal, password
// change re-wrap, forward-compatible unknown types, auto-lock, and the 200-card
// unlock budget.

package com.pusios.pots.data.vault

import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.TimeMark
import kotlin.time.TimeSource

@OptIn(ExperimentalTime::class)
class VaultSessionTest {

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

    private fun tempDir(): File = Files.createTempDirectory("vault-test").toFile()

    private fun newSession(
        dir: File,
        autoLockTimeout: Duration = 5.minutes,
        clock: TimeSource = TimeSource.Monotonic,
    ): VaultSession = VaultSession(
        store = VaultStore(JvmVaultFileAccess(dir), JvmVaultCryptoEngine()),
        autoLockTimeout = autoLockTimeout,
        clock = clock,
    )

    private fun fileBytes(dir: File): ByteArray = JvmVaultFileAccess(dir).read().let {
        assertNotNull(it)
        it
    }

    private fun rewriteEnvelope(dir: File, transform: (VaultEnvelope) -> VaultEnvelope) {
        val access = JvmVaultFileAccess(dir)
        val envelope = parseEnvelope(access.read()!!)
        assertTrue(access.writeAtomic(envelopeBytes(transform(envelope))))
    }

    @Test
    fun `roundtrip with templates custom fields and values survives lock and unlock`() = runTest {
        val dir = tempDir()
        val session = newSession(dir)
        session.createVault("master-1")

        val login = session.upsert(
            VaultTemplates.draft("login", name = "GitHub", url = "https://github.com", tags = listOf("dev"))!!,
            values = mapOf("username" to "alice", "password" to "s3cret-42", "api_hint" to "beta"),
        )
        session.upsert(VaultTemplates.draft("ssh_key", name = "Home server")!!, values = mapOf("private_key" to "-----BEGIN OPENSSH PRIVATE KEY-----"))

        session.lock()
        assertTrue(session.locked.value)
        assertNull(session.document())

        assertEquals(VaultUnlockResult.Success, session.unlock("master-1"))
        val document = assertNotNull(session.document())
        assertEquals(2, document.items.size)

        val restored = document.items.first { it.id == login.id }
        assertEquals("GitHub", restored.name)
        assertEquals("alice", restored.username)
        assertEquals("s3cret-42", restored.fields.first { it.key == "password" }.value)
        // A value not in the template draft is appended as a TEXT field.
        val extra = restored.fields.first { it.key == "api_hint" }
        assertEquals("beta", extra.value)
        assertEquals(VaultFieldKind.TEXT, extra.kind)
        assertEquals(4, restored.fields.size)
        assertEquals(VaultItemKind.SSH_KEY, document.items.first { it.name == "Home server" }.kind)
    }

    @Test
    fun `vault file contains ciphertext only`() = runTest {
        val dir = tempDir()
        val session = newSession(dir)
        session.createVault("master-1")
        session.upsert(VaultTemplates.draft("login", name = "GitHub", username = "alice")!!, values = mapOf("password" to "s3cret-42"))

        val text = String(fileBytes(dir), Charsets.UTF_8)
        assertFalse(text.contains("s3cret-42"))
        assertFalse(text.contains("alice"))
        assertFalse(text.contains("GitHub"))
        assertTrue(text.contains("\"format\":\"pots-vault\""))
    }

    @Test
    fun `wrong password refuses without crashing and the correct one still opens`() = runTest {
        val dir = tempDir()
        val session = newSession(dir)
        session.createVault("master-1")
        session.upsert(VaultTemplates.draft("note", name = "Secret note")!!)
        session.lock()

        assertEquals(VaultUnlockResult.WrongPassword, session.unlock("nope"))
        assertTrue(session.locked.value)
        assertNull(session.document())
        assertEquals(VaultUnlockResult.Success, session.unlock("master-1"))
        assertEquals(1, session.document()?.items?.size)
    }

    @Test
    fun `tampered payload is refused with a distinct result`() = runTest {
        val dir = tempDir()
        val session = newSession(dir)
        session.createVault("master-1")
        session.upsert(VaultTemplates.draft("note", name = "Secret note")!!)
        session.lock()

        rewriteEnvelope(dir) { envelope ->
            val block = envelope.payload
            val data = assertNotNull(Base64.getDecoder().decode(block.data))
            data[data.size / 2] = (data[data.size / 2].toInt() xor 1).toByte()
            envelope.copy(payload = block.copy(data = Base64.getEncoder().encodeToString(data)))
        }

        assertEquals(VaultUnlockResult.Tampered, session.unlock("master-1"))
        assertTrue(session.locked.value)
        // The tampered file is not silently overwritten.
        assertEquals(VaultUnlockResult.Tampered, session.unlock("master-1"))
    }

    @Test
    fun `truncated file is refused as corrupted`() = runTest {
        val dir = tempDir()
        val session = newSession(dir)
        session.createVault("master-1")
        session.lock()

        val bytes = fileBytes(dir)
        assertTrue(JvmVaultFileAccess(dir).writeAtomic(bytes.copyOfRange(0, bytes.size / 2)))

        assertEquals(VaultUnlockResult.Corrupted, session.unlock("master-1"))
    }

    @Test
    fun `file from a newer format version is refused`() = runTest {
        val dir = tempDir()
        val session = newSession(dir)
        session.createVault("master-1")
        session.lock()

        rewriteEnvelope(dir) { it.copy(version = 2) }

        assertEquals(VaultUnlockResult.UnsupportedVersion(2), session.unlock("master-1"))
    }

    @Test
    fun `file written with other kdf parameters opens`() = runTest {
        val dir = tempDir()
        val engine = JvmVaultCryptoEngine()
        val document = VaultDocument(items = listOf(VaultItem(id = "x", name = "Legacy")))
        val sealed = buildEnvelope(engine, document, "legacy-pass", iters = 1_000)
        assertTrue(JvmVaultFileAccess(dir).writeAtomic(envelopeBytes(sealed.envelope)))

        val session = newSession(dir)
        assertEquals(VaultUnlockResult.Success, session.unlock("legacy-pass"))
        assertEquals("Legacy", session.document()?.items?.first()?.name)
    }

    @Test
    fun `password change re-wraps the key and leaves the payload identical`() = runTest {
        val dir = tempDir()
        val session = newSession(dir)
        session.createVault("master-1")
        session.upsert(VaultTemplates.draft("note", name = "Note")!!)

        val envelopeBefore = parseEnvelope(fileBytes(dir))
        val payloadBefore = envelopeBefore.payload
        assertTrue(session.changeMasterPassword("wrong-old", "new-pass") == false)
        assertTrue(session.changeMasterPassword("master-1", "new-pass"))

        val envelopeAfter = parseEnvelope(fileBytes(dir))
        assertEquals(payloadBefore.data, envelopeAfter.payload.data)
        assertEquals(payloadBefore.iv, envelopeAfter.payload.iv)
        // Fresh salt and fresh wrap block under the new password.
        assertNotEquals(envelopeBefore.kdf.salt, envelopeAfter.kdf.salt)
        assertNotEquals(envelopeBefore.wrap.data, envelopeAfter.wrap.data)

        session.lock()
        assertEquals(VaultUnlockResult.WrongPassword, session.unlock("master-1"))
        assertEquals(VaultUnlockResult.Success, session.unlock("new-pass"))
        assertEquals(1, session.document()?.items?.size)
    }

    @Test
    fun `unknown types survive reopening and rewrites`() = runTest {
        val dir = tempDir()
        val session = newSession(dir)
        session.createVault("master-1")
        val mystery = session.upsert(
            VaultItem(
                id = "",
                type = "future_thing",
                name = "Mystery",
                fields = listOf(VaultField(key = "k", label = "K", type = "future_kind", value = "v")),
            ),
        )
        session.upsert(VaultTemplates.draft("login", name = "GitHub")!!, values = mapOf("password" to "p"))
        session.lock()
        assertEquals(VaultUnlockResult.Success, session.unlock("master-1"))

        val document = assertNotNull(session.document())
        val restored = document.items.first { it.id == mystery.id }
        assertNull(restored.kind)
        assertEquals(VaultFieldKind.TEXT, restored.fields.first().kind)
        assertEquals("v", restored.fields.first().value)

        // Rewriting the vault (upsert a neighbour) must not normalize the raw types.
        session.upsert(VaultTemplates.draft("card", name = "Bank")!!)
        session.lock()
        assertEquals(VaultUnlockResult.Success, session.unlock("master-1"))
        val afterRewrite = assertNotNull(session.document()).items.first { it.id == mystery.id }
        assertEquals("future_thing", afterRewrite.type)
        assertEquals("future_kind", afterRewrite.fields.first().type)
    }

    @Test
    fun `session locks by timeout and by lock call`() = runTest {
        val dir = tempDir()
        val clock = ManualClock()
        val session = newSession(dir, autoLockTimeout = 100.milliseconds, clock = clock)
        session.createVault("master-1")

        clock.elapsedMillis = 50
        assertNotNull(session.withKey { it })
        assertEquals(VaultUnlockResult.Success, session.unlock("master-1"))

        clock.elapsedMillis = 500
        assertFailsWith<VaultLockedException> { session.withKey { it } }
        assertNull(session.document())
        assertTrue(session.locked.value)

        assertEquals(VaultUnlockResult.Success, session.unlock("master-1"))
        assertNotNull(session.withKey { it })
        session.lock()
        assertFailsWith<VaultLockedException> { session.withKey { it } }
    }

    @Test
    fun `refreshLockState sees the auto-lock expiry without any other session access`() = runTest {
        val dir = tempDir()
        val clock = ManualClock()
        val session = newSession(dir, autoLockTimeout = 100.milliseconds, clock = clock)
        session.createVault("master-1")

        // Idle past the timeout with NO other call on the session: the locked
        // StateFlow is lazy — it still publishes `false` — which is exactly
        // what the sheet's old `locked.value` gate tripped over (review
        // round 2, item 1).
        clock.elapsedMillis = 500
        assertFalse(session.locked.value)

        // The eager check sees the real state and locks the session.
        assertTrue(session.refreshLockState())
        assertTrue(session.locked.value)
        assertNull(session.document())
        assertFailsWith<VaultLockedException> { session.withKey { it } }

        // Unlocking again works, and a fresh check on a live session stays
        // unlocked: checking is not activity, so it must not expire the timer.
        assertEquals(VaultUnlockResult.Success, session.unlock("master-1"))
        clock.elapsedMillis = 520
        assertFalse(session.refreshLockState())
        assertNotNull(session.withKey { it })
    }

    @Test
    fun `upsert values path does not require serialized fields and search and delete work`() = runTest {
        val dir = tempDir()
        val session = newSession(dir)
        session.createVault("master-1")

        session.upsert(VaultTemplates.draft("login", name = "GitHub", url = "https://github.com")!!, values = mapOf("password" to "p1"))
        session.upsert(VaultTemplates.draft("card", name = "Bank", tags = listOf("money"))!!, values = mapOf("number" to "4111"))

        assertEquals(listOf("GitHub"), session.search("github").map { it.name })
        assertEquals(listOf("Bank"), session.search(kind = VaultItemKind.CARD).map { it.name })
        assertEquals(listOf("Bank"), session.search(tag = "money").map { it.name })
        assertEquals(2, session.search().size)

        val id = session.search(kind = VaultItemKind.LOGIN).single().id
        assertTrue(session.delete(id))
        assertFalse(session.delete(id))
        assertEquals(1, session.document()?.items?.size)
    }

    @Test
    fun `not created unsupported and double-create paths behave`() = runTest {
        val dir = tempDir()
        val session = newSession(dir)
        assertEquals(VaultUnlockResult.NotCreated, session.unlock("master-1"))
        session.createVault("master-1")
        assertFailsWith<VaultAlreadyExistsException> { session.createVault("master-2") }

        val unsupported = VaultSession(VaultStore(files = null, engine = null))
        assertFalse(unsupported.isSupported)
        assertEquals(VaultUnlockResult.NotSupported, unsupported.unlock("x"))
        assertFailsWith<VaultUnsupportedPlatformException> { unsupported.createVault("x") }
    }

    @Test
    fun `quarantine moves a corrupted file aside instead of deleting it`() {
        val dir = tempDir()
        val access = JvmVaultFileAccess(dir)
        assertTrue(access.writeAtomic("garbage".encodeToByteArray()))
        val name = assertNotNull(access.quarantineUnreadable())
        assertTrue(name.startsWith("vault.pots.unreadable-"))
        assertFalse(access.exists())
        assertTrue(File(dir, name).isFile)
    }

    @Test
    fun `unlock of two hundred cards stays within the one second budget`() = runTest {
        val dir = tempDir()
        val session = newSession(dir)
        session.createVault("master-1")
        repeat(200) { index ->
            session.upsert(VaultTemplates.draft("login", name = "Service $index")!!, values = mapOf("password" to "p-$index"))
        }
        session.lock()

        val started = TimeSource.Monotonic.markNow()
        assertEquals(VaultUnlockResult.Success, session.unlock("master-1"))
        val elapsed = started.elapsedNow()
        println("Vault unlock of 200 cards took $elapsed")
        assertTrue(elapsed < 1000.milliseconds, "unlock took $elapsed, budget is 1 s")
        assertEquals(200, session.document()?.items?.size)
    }

    @Test
    fun `unlockWithKey opens the session with the raw vault key`() = runTest {
        val dir = tempDir()
        val session = newSession(dir)
        session.createVault("master-1")
        session.upsert(VaultTemplates.draft("login", name = "GitHub")!!, values = mapOf("password" to "s3cret-42"))
        var captured: ByteArray? = null
        session.withKey { captured = it.copyOf() }
        session.lock()
        assertTrue(session.locked.value)

        val key = assertNotNull(captured)
        assertEquals(VaultUnlockResult.Success, session.unlockWithKey(key))
        assertFalse(session.locked.value)
        assertEquals("GitHub", session.document()?.items?.single()?.name)
    }

    @Test
    fun `unlockWithKey with a stale key reports tampered and stays locked`() = runTest {
        val dir = tempDir()
        val session = newSession(dir)
        session.createVault("master-1")
        session.upsert(VaultTemplates.draft("note", name = "Secret note")!!)
        session.lock()

        assertEquals(VaultUnlockResult.Tampered, session.unlockWithKey(ByteArray(32)))
        assertTrue(session.locked.value)
        assertNull(session.document())
        // The vault file is untouched: the master password still opens it.
        assertEquals(VaultUnlockResult.Success, session.unlock("master-1"))
    }
}
