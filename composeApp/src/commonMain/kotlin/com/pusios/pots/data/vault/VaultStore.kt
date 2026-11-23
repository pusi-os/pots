// Encrypted vault: disk layer. Knows how to read, atomically replace and
// quarantine the vault file; all crypto lives in [VaultSession].

package com.pusios.pots.data.vault

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class VaultStore(
    private val files: VaultFileAccess?,
    val engine: VaultCryptoEngine?,
) {
    /** False on platforms without a vault implementation (iOS / web in v1). */
    val isSupported: Boolean
        get() = files != null && engine != null

    fun exists(): Boolean = files?.exists() ?: false

    /**
     * Parse the current file; null when there is no file yet. Throws
     * [VaultCorruptedException] / [VaultUnsupportedVersionException] on a bad file.
     */
    fun readEnvelope(): VaultEnvelope? {
        val bytes = files?.read() ?: return null
        return parseEnvelope(bytes)
    }

    /** Create the vault file with an empty document; refuses to overwrite. */
    suspend fun create(masterPassword: String): SealedVault {
        val engine = engine ?: throw VaultUnsupportedPlatformException()
        val files = files ?: throw VaultUnsupportedPlatformException()
        if (files.exists()) throw VaultAlreadyExistsException()
        return withContext(Dispatchers.Default) {
            val sealed = buildEnvelope(engine, VaultDocument(), masterPassword)
            writeOrThrow(files, sealed.envelope)
            sealed
        }
    }

    /** Atomically replace the file; callers must have sealed the payload themselves. */
    suspend fun save(envelope: VaultEnvelope) {
        val files = files ?: throw VaultUnsupportedPlatformException()
        withContext(Dispatchers.Default) {
            writeOrThrow(files, envelope)
        }
    }

    /** Move an unreadable file aside; returns the new name or null. */
    fun quarantineCorrupted(): String? = files?.quarantineUnreadable()

    private fun writeOrThrow(files: VaultFileAccess, envelope: VaultEnvelope) {
        if (!files.writeAtomic(envelopeBytes(envelope))) {
            throw VaultCorruptedException("could not write the vault file")
        }
    }
}
