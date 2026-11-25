package com.pusios.pots.adb

import java.math.BigInteger
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/**
 * Writes the `adbkey.pub` blob: the 524-byte `RSAPublicKey` struct from AOSP `android_pubkey.h`,
 * base64'd onto one line followed by `name`.
 *
 * We need our own encoder because the one inside libadb-android (`AndroidPubkey`) is package-private.
 * The struct is a Montgomery-friendly form of the key, not the usual `SubjectPublicKeyInfo`:
 *
 * ```
 * uint32 len            modulus size in 32-bit words (64 for RSA-2048)
 * uint32 n0inv          -1 / modulus[0] mod 2^32
 * uint8  modulus[256]   little-endian
 * uint8  rr[256]        R^2 mod modulus, R = 2^2048, little-endian
 * uint32 exponent       little-endian
 * ```
 *
 * The file is a byproduct for interoperability (desktop adb, and the future bundled-binary backend);
 * nothing in the app reads it back, so it must not be able to break key generation — [encode] only
 * rejects keys that are not RSA-2048-sized.
 */
object AdbPublicKeyEncoder {
    const val MODULUS_BYTES = 256
    const val MODULUS_WORDS = MODULUS_BYTES / 4
    const val ENCODED_SIZE = 4 + 4 + MODULUS_BYTES + MODULUS_BYTES + 4

    private val TWO_POW_32: BigInteger = BigInteger.ONE.shiftLeft(32)
    private val UINT32_MASK: BigInteger = TWO_POW_32.subtract(BigInteger.ONE)

    /** The binary blob adb calls an `RSAPublicKey`. */
    fun encode(publicKey: RSAPublicKey): ByteArray {
        val modulus = publicKey.modulus
        val exponent = publicKey.publicExponent
        require(modulus.bitLength() <= MODULUS_BYTES * 8) { "only RSA-2048 host keys are supported" }
        require(exponent.bitLength() <= 32) { "RSA exponent does not fit the 32-bit field" }

        val output = ByteArray(ENCODED_SIZE)
        writeInt(output, 0, MODULUS_WORDS)
        writeInt(output, 4, n0inv(modulus))
        copyLittleEndian(modulus, output, 8)
        val r = BigInteger.ONE.shiftLeft(MODULUS_BYTES * 8)
        copyLittleEndian(r.multiply(r).mod(modulus), output, 8 + MODULUS_BYTES)
        writeInt(output, 8 + 2 * MODULUS_BYTES, exponent.toInt())
        return output
    }

    /** The one-line form of an `adbkey.pub`: base64 of [encode], a space, then the user name. */
    fun encodeWithName(publicKey: RSAPublicKey, name: String): String = Base64.getEncoder().encodeToString(encode(publicKey)) + " " + name

    /** `-1 / modulus[0] mod 2^32` — the Montgomery constant stored beside the modulus. */
    private fun n0inv(modulus: BigInteger): Int {
        val n0 = modulus.and(UINT32_MASK)
        val inverse = n0.modInverse(TWO_POW_32)
        return TWO_POW_32.subtract(inverse).mod(TWO_POW_32).toInt()
    }

    /** Writes [value] into [target] at [offset], least significant byte first, zero-padded to 256. */
    private fun copyLittleEndian(value: BigInteger, target: ByteArray, offset: Int) {
        // toByteArray() drops leading zero bytes and may prepend a sign byte; both are harmless
        // because the destination is already zeroed and we write from the least significant end.
        val bigEndian = value.toByteArray()
        val length = minOf(bigEndian.size, MODULUS_BYTES)
        for (index in 0 until length) {
            target[offset + index] = bigEndian[bigEndian.size - 1 - index]
        }
    }

    private fun writeInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        target[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }
}
