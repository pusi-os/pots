// Vault export/import tests (card 5/5): real files, real JVM crypto. These
// cover the card's acceptance criteria — roundtrip restores cards and custom
// fields, a tampered/corrupted/wrong-password file is refused with the vault
// untouched, and the exported file contains no plaintext secrets.

package com.pusios.pots.data.vault

import com.pusios.pots.data.AppSettings
import com.pusios.pots.data.exportToJson
import com.russhwolf.settings.MapSettings
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class VaultBackupTest {

    private fun tempDir(): File = Files.createTempDirectory("vault-backup-test").toFile()

    private fun newSession(dir: File): VaultSession = VaultSession(
        store = VaultStore(JvmVaultFileAccess(dir), JvmVaultCryptoEngine()),
    )

    private fun tamperPayload(bytes: ByteArray): ByteArray {
        val envelope = parseEnvelope(bytes)
        val data = assertNotNull(Base64.getDecoder().decode(envelope.payload.data))
        data[data.size / 2] = (data[data.size / 2].toInt() xor 1).toByte()
        return envelopeBytes(envelope.copy(payload = envelope.payload.copy(data = Base64.getEncoder().encodeToString(data))))
    }

    @Test
    fun `export then import replace restores all cards and custom fields`() = runTest {
        val sourceDir = tempDir()
        val source = newSession(sourceDir)
        source.createVault("master-1")
        val login = source.upsert(
            VaultTemplates.draft("login", name = "GitHub", url = "https://github.com", tags = listOf("dev"))!!,
            values = mapOf("username" to "alice", "password" to "s3cret-42", "api_hint" to "beta"),
        )
        source.upsert(
            VaultTemplates.draft("ssh_key", name = "Home server")!!,
            values = mapOf("private_key" to "-----BEGIN OPENSSH PRIVATE KEY-----"),
        )
        val exportBytes = source.exportVault("export-pass")

        val targetDir = tempDir()
        val target = newSession(targetDir)
        target.createVault("target-pass")
        target.upsert(VaultTemplates.draft("note", name = "Old")!!)

        val result = target.importVault(exportBytes, "export-pass", VaultImportMode.REPLACE)
        assertEquals(VaultImportResult.Success(2), result)

        val document = assertNotNull(target.document())
        assertEquals(2, document.items.size)
        val restored = document.items.first { it.id == login.id }
        assertEquals("GitHub", restored.name)
        assertEquals("https://github.com", restored.url)
        assertEquals("alice", restored.username)
        assertEquals("s3cret-42", restored.fields.first { it.key == "password" }.value)
        val extra = restored.fields.first { it.key == "api_hint" }
        assertEquals("beta", extra.value)
        assertEquals(VaultFieldKind.TEXT, extra.kind)
        assertTrue(login.createdAt > 0)
        assertEquals(login.createdAt, restored.createdAt)
        assertEquals(
            "-----BEGIN OPENSSH PRIVATE KEY-----",
            document.items.first { it.name == "Home server" }.fields.first { it.key == "private_key" }.value,
        )
        assertTrue(document.items.none { it.name == "Old" })
    }

    @Test
    fun `import refuses tampered corrupted wrong password and version mismatch files`() = runTest {
        val source = newSession(tempDir())
        source.createVault("master-1")
        source.upsert(VaultTemplates.draft("note", name = "Note")!!, values = mapOf("body" to "secret-body"))
        val bytes = source.exportVault("export-pass")

        val target = newSession(tempDir())
        target.createVault("target-pass")
        target.upsert(VaultTemplates.draft("login", name = "Old")!!)

        assertEquals(
            VaultImportResult.Tampered,
            target.importVault(tamperPayload(bytes), "export-pass", VaultImportMode.REPLACE),
        )
        assertEquals(
            VaultImportResult.WrongPassword,
            target.importVault(bytes, "wrong-pass", VaultImportMode.REPLACE),
        )
        assertEquals(
            VaultImportResult.Corrupted,
            target.importVault("not a vault".encodeToByteArray(), "export-pass", VaultImportMode.REPLACE),
        )
        val versioned = parseEnvelope(bytes).copy(version = 2)
        assertEquals(
            VaultImportResult.UnsupportedVersion(2),
            target.importVault(envelopeBytes(versioned), "export-pass", VaultImportMode.REPLACE),
        )

        // Every refusal leaves the current vault untouched.
        assertEquals(1, target.document()?.items?.size)
        assertEquals("Old", target.document()?.items?.first()?.name)
    }

    @Test
    fun `exported backup file contains ciphertext only`() = runTest {
        val source = newSession(tempDir())
        source.createVault("master-1")
        source.upsert(
            VaultTemplates.draft("login", name = "GitHub", username = "alice")!!,
            values = mapOf("password" to "s3cret-42"),
        )
        source.upsert(
            VaultTemplates.draft("ssh_key", name = "Home server")!!,
            values = mapOf("private_key" to "-----BEGIN OPENSSH PRIVATE KEY-----"),
        )

        val text = String(source.exportVault("export-pass"), Charsets.UTF_8)
        assertFalse(text.contains("s3cret-42"))
        assertFalse(text.contains("alice"))
        assertFalse(text.contains("GitHub"))
        assertFalse(text.contains("Home server"))
        assertFalse(text.contains("OPENSSH"))
        assertTrue(text.contains("\"format\":\"pots-vault\""))
    }

    @Test
    fun `import merge keeps unrelated cards and replaces conflicting ids`() = runTest {
        val engine = JvmVaultCryptoEngine()
        val importedDocument = VaultDocument(
            items = listOf(
                VaultItem(
                    id = "gh",
                    type = VaultItemKind.LOGIN.id,
                    name = "GitHub",
                    username = "alice",
                    fields = listOf(
                        VaultField(key = "password", label = "Password", type = VaultFieldKind.SECRET.id, value = "new-pass"),
                    ),
                ),
                VaultItem(id = "gl", type = VaultItemKind.LOGIN.id, name = "GitLab"),
            ),
        )
        val bytes = envelopeBytes(buildEnvelope(engine, importedDocument, "export-pass").envelope)

        val target = newSession(tempDir())
        target.createVault("target-pass")
        // Same id as the imported card: merge must let the import win.
        target.upsert(
            VaultItem(
                id = "gh",
                type = VaultItemKind.LOGIN.id,
                name = "GitHub",
                username = "bob",
                fields = listOf(
                    VaultField(key = "password", label = "Password", type = VaultFieldKind.SECRET.id, value = "old-pass"),
                ),
            ),
        )
        target.upsert(VaultTemplates.draft("card", name = "Bank")!!)

        val result = target.importVault(bytes, "export-pass", VaultImportMode.MERGE)
        assertEquals(VaultImportResult.Success(2), result)

        val document = assertNotNull(target.document())
        assertEquals(3, document.items.size)
        // The imported card won the conflicting id and brought its own values.
        val github = document.items.first { it.name == "GitHub" }
        assertEquals("gh", github.id)
        assertEquals("alice", github.username)
        assertEquals("new-pass", github.fields.first { it.key == "password" }.value)
        // Unrelated current cards survive the merge; the replaced card is gone.
        assertTrue(document.items.any { it.name == "Bank" })
        assertTrue(document.items.none { it.id == "gh" && it.username == "bob" })
    }

    @Test
    fun `import while locked is refused and leaves the vault untouched`() = runTest {
        val source = newSession(tempDir())
        source.createVault("master-1")
        source.upsert(VaultTemplates.draft("note", name = "Note")!!)
        val bytes = source.exportVault("export-pass")

        val target = newSession(tempDir())
        target.createVault("target-pass")
        target.upsert(VaultTemplates.draft("login", name = "Old")!!)
        target.lock()

        assertEquals(VaultImportResult.Locked, target.importVault(bytes, "export-pass", VaultImportMode.REPLACE))
        assertEquals(VaultUnlockResult.Success, target.unlock("target-pass"))
        assertEquals(listOf("Old"), target.document()?.items?.map { it.name })
    }

    @Test
    fun `export requires an unlocked supported session`() = runTest {
        val session = newSession(tempDir())
        // A session that never unlocked is locked, and export needs plaintext.
        assertFailsWith<VaultLockedException> { session.exportVault("pass") }
        session.createVault("master-1")
        assertNotNull(session.exportVault("pass"))
        session.lock()
        assertFailsWith<VaultLockedException> { session.exportVault("pass") }

        val unsupported = VaultSession(VaultStore(files = null, engine = null))
        assertFailsWith<VaultUnsupportedPlatformException> { unsupported.exportVault("pass") }
        assertIs<VaultImportResult.NotSupported>(unsupported.importVault("x".encodeToByteArray(), "pass", VaultImportMode.MERGE))
    }

    @Test
    fun `settings export never contains vault data`() = runTest {
        val session = newSession(tempDir())
        session.createVault("master-1")
        session.upsert(
            VaultTemplates.draft("login", name = "GitHub")!!,
            values = mapOf("password" to "s3cret-42"),
        )

        val json = AppSettings(MapSettings()).exportToJson(emptyList())
        val text = json.toString()
        assertFalse(text.contains("s3cret-42"))
        assertFalse(text.contains("pots-vault"))
        assertFalse(text.contains("GitHub"))
    }
}
