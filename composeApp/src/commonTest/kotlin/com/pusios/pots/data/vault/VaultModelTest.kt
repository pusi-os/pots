// Vault model tests: raw-string kinds stay forward compatible and the envelope
// parser refuses foreign/corrupted/newer files. Crypto-backed scenarios live in
// desktopTest (they need the JVM engine and a real temp file).

package com.pusios.pots.data.vault

import com.pusios.pots.data.SharedJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VaultModelTest {

    @Test
    fun `unknown item type degrades to a plain card but keeps the raw string`() {
        val item = VaultItem(id = "1", type = "future_thing", name = "Mystery")
        assertNull(VaultItemKind.fromId("future_thing"))
        assertNull(item.kind)
        assertEquals("future_thing", item.type)
    }

    @Test
    fun `unknown field type reads as text and is never treated as secret`() {
        val field = VaultField(key = "k", label = "K", type = "future_kind", value = "v")
        assertEquals(VaultFieldKind.TEXT, field.kind)
        assertFalse(field.isSecret)
    }

    @Test
    fun `known field kinds keep their masking`() {
        assertTrue(VaultField(key = "p", label = "P", type = "secret").isSecret)
        assertTrue(VaultField(key = "k", label = "K", type = "secret_multiline").isSecret)
        assertFalse(VaultField(key = "t", label = "T", type = "text").isSecret)
    }

    @Test
    fun `document json survives unknown extra keys for forward compatibility`() {
        val json = """{"items":[],"writtenByNewerVersion":{"whatever":true}}"""
        val document = SharedJson.decodeFromString(VaultDocument.serializer(), json)
        assertEquals(emptyList(), document.items)
    }

    @Test
    fun `envelope roundtrip and rejection paths`() {
        val envelope = VaultEnvelope(
            kdf = VaultKdfParams(id = VaultFormat.KDF_ID, iters = 600_000, salt = "AAAA"),
            wrap = VaultCipherBlock(iv = "AAAA", data = "BBBB"),
            payload = VaultCipherBlock(iv = "AAAA", data = "BBBB"),
        )
        val bytes = envelopeBytes(envelope)
        assertEquals(envelope, parseEnvelope(bytes))

        assertFailsWith<VaultCorruptedException> { parseEnvelope("definitely not json".encodeToByteArray()) }
        assertFailsWith<VaultUnsupportedVersionException> {
            parseEnvelope(envelopeBytes(envelope.copy(version = 2)))
        }
        assertFailsWith<VaultCorruptedException> {
            parseEnvelope(envelopeBytes(envelope.copy(format = "something-else")))
        }
        assertFailsWith<VaultCorruptedException> {
            parseEnvelope(envelopeBytes(envelope.copy(kdf = envelope.kdf.copy(id = "argon2id"))))
        }
    }
}
