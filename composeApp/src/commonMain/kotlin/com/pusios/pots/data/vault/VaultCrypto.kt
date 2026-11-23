// Encrypted vault: crypto and file-access seams. Android and desktop share the
// JVM implementations in `jvmShared`; iOS and web return null (the vault is
// unavailable there in v1 — master-password crypto is JCA-only for now).

package com.pusios.pots.data.vault

/**
 * Platform seam for the primitives the vault needs. Implementations must use a
 * cryptographically strong random source and standard AEAD/KDF primitives only.
 */
interface VaultCryptoEngine {
    fun randomBytes(size: Int): ByteArray

    fun encodeBase64(bytes: ByteArray): String

    /** Returns null when [value] is not valid Base64. */
    fun decodeBase64(value: String): ByteArray?

    /** PBKDF2-HMAC-SHA256; [keyLengthBytes] is the desired output length. */
    fun pbkdf2HmacSha256(password: CharArray, salt: ByteArray, iterations: Int, keyLengthBytes: Int): ByteArray

    /** AES-256-GCM with additional authenticated data. */
    fun aesGcmEncrypt(key: ByteArray, iv: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray

    /** AES-256-GCM verify-and-decrypt; throws [VaultCryptoInvalidTagException] on a bad tag. */
    fun aesGcmDecrypt(key: ByteArray, iv: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray
}

/** Vault file IO seam; implementations must write atomically. */
interface VaultFileAccess {
    fun exists(): Boolean

    fun read(): ByteArray?

    /** Replace the whole file with [bytes]; readers never see a partial write. */
    fun writeAtomic(bytes: ByteArray): Boolean

    fun delete(): Boolean

    /** Rename the current file aside (e.g. `vault.pots.unreadable-…`); returns the new name or null. */
    fun quarantineUnreadable(): String?
}

/** AES-256-GCM refused the ciphertext: wrong password at unwrap, or tampering. */
class VaultCryptoInvalidTagException : Exception("AES-GCM tag check failed")
