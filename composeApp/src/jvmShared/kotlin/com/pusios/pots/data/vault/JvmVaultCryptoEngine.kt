// Vault crypto for the JVM targets (android + desktop): JCA PBKDF2 and
// AES-256-GCM, SecureRandom for all randomness. One fresh Cipher per call —
// Cipher instances are not thread-safe.

package com.pusios.pots.data.vault

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class JvmVaultCryptoEngine : VaultCryptoEngine {
    private val random = SecureRandom()

    override fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    override fun encodeBase64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    override fun decodeBase64(value: String): ByteArray? = try {
        Base64.getDecoder().decode(value)
    } catch (_: IllegalArgumentException) {
        null
    }

    override fun pbkdf2HmacSha256(password: CharArray, salt: ByteArray, iterations: Int, keyLengthBytes: Int): ByteArray {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password, salt, iterations, keyLengthBytes * 8)
        return try {
            factory.generateSecret(spec).encoded
        } finally {
            // Best-effort: drop the password copy the spec holds.
            spec.clearPassword()
        }
    }

    override fun aesGcmEncrypt(key: ByteArray, iv: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    override fun aesGcmDecrypt(key: ByteArray, iv: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(aad)
        return try {
            cipher.doFinal(ciphertext)
        } catch (_: AEADBadTagException) {
            throw VaultCryptoInvalidTagException()
        }
    }

    private companion object {
        const val GCM_TAG_BITS = 128
    }
}
