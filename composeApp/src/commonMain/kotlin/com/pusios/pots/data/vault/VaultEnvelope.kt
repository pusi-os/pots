// Encrypted vault: file envelope (§4.3 of the vault research report). The file
// is JSON containing only crypto parameters and ciphertext — salt/iterations
// live in the header so files with older KDF parameters still open, IVs are
// stored alongside their ciphertext, and the AAD strings bind the format so a
// downgraded or swapped header breaks the tag.

package com.pusios.pots.data.vault

import com.pusios.pots.data.SharedJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object VaultFormat {
    const val FORMAT = "pots-vault"
    const val VERSION = 1
    const val KDF_ID = "pbkdf2-hmac-sha256"
    const val KDF_ITERATIONS = 600_000
    const val SALT_BYTES = 16
    const val IV_BYTES = 12
    const val KEY_BYTES = 32
    const val KEK_AAD = "pots-vault-kek-v1"
    const val PAYLOAD_AAD = "pots-vault-v1"
    const val FILE_NAME = "vault.pots"
}

/** Everything the caller asked [buildEnvelope] to produce: the envelope plus the raw vault key. */
class SealedVault(val envelope: VaultEnvelope, val vaultKey: ByteArray)

@Serializable
data class VaultEnvelope(
    val format: String = VaultFormat.FORMAT,
    val version: Int = VaultFormat.VERSION,
    val kdf: VaultKdfParams,
    // AES-GCM(vaultKey wrapped by the master-password-derived KEK).
    val wrap: VaultCipherBlock,
    // Optional future device slot (Android Keystore); never set in v1.
    val device: VaultDeviceSlot? = null,
    // AES-GCM(vaultKey) over the JSON document.
    val payload: VaultCipherBlock,
)

@Serializable
data class VaultKdfParams(
    val id: String,
    val iters: Int,
    // Base64.
    val salt: String,
)

@Serializable
data class VaultCipherBlock(
    // Base64 IV, stored next to its ciphertext.
    val iv: String,
    // Base64 ciphertext+tag.
    val data: String,
)

@Serializable
data class VaultDeviceSlot(
    val alias: String,
    val iv: String,
    val data: String,
)

// ---- Errors surfaced to UI, one per situation the screen must name. ----

sealed class VaultException(message: String) : Exception(message)

/** iOS / web: the vault has no platform implementation in v1. */
class VaultUnsupportedPlatformException : VaultException("Vault is not supported on this platform")

/** There is no vault file yet — the setup flow must run first. */
class VaultNotCreatedException : VaultException("No vault file exists yet")

class VaultAlreadyExistsException : VaultException("A vault file already exists")

/** The file is not a parseable vault envelope (truncated, foreign, rewritten). */
class VaultCorruptedException(detail: String) : VaultException("Vault file is corrupted: $detail")

/** The file was written by a newer app version. */
class VaultUnsupportedVersionException(val version: Int) :
    VaultException("Vault format version $version is not supported")

/** The master password is wrong (tag check failed at key unwrap). */
class VaultWrongPasswordException : VaultException("Wrong master password")

/** The payload does not match its tag: the file was altered or corrupted on disk. */
class VaultTamperedException : VaultException("Vault payload failed integrity check")

/** Accessing vault contents while the session is locked (or timed out). */
class VaultLockedException : VaultException("The vault session is locked")

// ---- Pure envelope logic (crypto primitives come from the engine). ----

// The envelope spells out its magic fields (format/version) so the file on disk
// is self-describing; item defaults stay omitted inside the payload.
private val envelopeJson = Json(from = SharedJson) { encodeDefaults = true }

fun parseEnvelope(bytes: ByteArray): VaultEnvelope {
    val envelope = try {
        envelopeJson.decodeFromString<VaultEnvelope>(bytes.decodeToString())
    } catch (_: Exception) {
        throw VaultCorruptedException("not a valid envelope")
    }
    if (envelope.format != VaultFormat.FORMAT) {
        throw VaultCorruptedException("unknown format '${envelope.format}'")
    }
    if (envelope.version != VaultFormat.VERSION) {
        throw VaultUnsupportedVersionException(envelope.version)
    }
    if (envelope.kdf.id != VaultFormat.KDF_ID) {
        throw VaultCorruptedException("unsupported kdf '${envelope.kdf.id}'")
    }
    if (envelope.kdf.iters < 1) {
        throw VaultCorruptedException("bad kdf iterations ${envelope.kdf.iters}")
    }
    return envelope
}

fun deriveKek(engine: VaultCryptoEngine, masterPassword: String, kdf: VaultKdfParams): ByteArray {
    val salt = engine.decodeBase64(kdf.salt) ?: throw VaultCorruptedException("bad kdf salt")
    return engine.pbkdf2HmacSha256(masterPassword.toCharArray(), salt, kdf.iters, VaultFormat.KEY_BYTES)
}

/** Wrap the random vault key under a KEK derived from the master password. */
fun wrapVaultKey(engine: VaultCryptoEngine, kek: ByteArray, vaultKey: ByteArray): VaultCipherBlock {
    val iv = engine.randomBytes(VaultFormat.IV_BYTES)
    val data = engine.aesGcmEncrypt(kek, iv, VaultFormat.KEK_AAD.encodeToByteArray(), vaultKey)
    return VaultCipherBlock(iv = engine.encodeBase64(iv), data = engine.encodeBase64(data))
}

/** Unwrap the vault key; a bad tag means the master password is wrong. */
fun unwrapVaultKey(engine: VaultCryptoEngine, kek: ByteArray, block: VaultCipherBlock): ByteArray {
    val iv = engine.decodeBase64(block.iv) ?: throw VaultCorruptedException("bad wrap iv")
    val data = engine.decodeBase64(block.data) ?: throw VaultCorruptedException("bad wrap data")
    return try {
        engine.aesGcmDecrypt(kek, iv, VaultFormat.KEK_AAD.encodeToByteArray(), data)
    } catch (_: VaultCryptoInvalidTagException) {
        throw VaultWrongPasswordException()
    }
}

fun sealPayload(engine: VaultCryptoEngine, vaultKey: ByteArray, document: VaultDocument): VaultCipherBlock {
    val iv = engine.randomBytes(VaultFormat.IV_BYTES)
    val plaintext = SharedJson.encodeToString(VaultDocument.serializer(), document).encodeToByteArray()
    val data = engine.aesGcmEncrypt(vaultKey, iv, VaultFormat.PAYLOAD_AAD.encodeToByteArray(), plaintext)
    return VaultCipherBlock(iv = engine.encodeBase64(iv), data = engine.encodeBase64(data))
}

fun openPayload(engine: VaultCryptoEngine, vaultKey: ByteArray, block: VaultCipherBlock): VaultDocument {
    val iv = engine.decodeBase64(block.iv) ?: throw VaultCorruptedException("bad payload iv")
    val data = engine.decodeBase64(block.data) ?: throw VaultCorruptedException("bad payload data")
    val plaintext = try {
        engine.aesGcmDecrypt(vaultKey, iv, VaultFormat.PAYLOAD_AAD.encodeToByteArray(), data)
    } catch (_: VaultCryptoInvalidTagException) {
        throw VaultTamperedException()
    }
    return try {
        SharedJson.decodeFromString(VaultDocument.serializer(), plaintext.decodeToString())
    } catch (_: Exception) {
        // The tag verified, so the bytes are ours — a parse failure means a writer bug.
        throw VaultCorruptedException("payload did not decode")
    }
}

/**
 * Build a fresh envelope: random salt, random vault key, KDF under the master
 * password. [kdfId]/[iters] are parameters only tests override (files carry
 * their own parameters, so files written with other values still open).
 */
fun buildEnvelope(
    engine: VaultCryptoEngine,
    document: VaultDocument,
    masterPassword: String,
    kdfId: String = VaultFormat.KDF_ID,
    iters: Int = VaultFormat.KDF_ITERATIONS,
): SealedVault {
    val vaultKey = engine.randomBytes(VaultFormat.KEY_BYTES)
    val salt = engine.randomBytes(VaultFormat.SALT_BYTES)
    val kdf = VaultKdfParams(id = kdfId, iters = iters, salt = engine.encodeBase64(salt))
    val kek = deriveKek(engine, masterPassword, kdf)
    val envelope = VaultEnvelope(
        kdf = kdf,
        wrap = wrapVaultKey(engine, kek, vaultKey),
        payload = sealPayload(engine, vaultKey, document),
    )
    return SealedVault(envelope, vaultKey)
}

/** Re-wrap the vault key under a new master password; the payload stays byte-identical. */
fun reWrapEnvelope(
    engine: VaultCryptoEngine,
    envelope: VaultEnvelope,
    vaultKey: ByteArray,
    newMasterPassword: String,
): VaultEnvelope {
    val salt = engine.randomBytes(VaultFormat.SALT_BYTES)
    val kdf = envelope.kdf.copy(salt = engine.encodeBase64(salt))
    val kek = deriveKek(engine, newMasterPassword, kdf)
    return envelope.copy(
        kdf = kdf,
        wrap = wrapVaultKey(engine, kek, vaultKey),
        // payload untouched: changing the master password never re-encrypts the contents.
        payload = envelope.payload,
    )
}

fun envelopeBytes(envelope: VaultEnvelope): ByteArray =
    envelopeJson.encodeToString(VaultEnvelope.serializer(), envelope).encodeToByteArray()
