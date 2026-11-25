package com.pusios.pots.adb

import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `adbkey.pub` blob is checked against the AOSP `android_pubkey.h` layout field by field — the
 * same fields a desktop `adb` would read back — rather than against our own writer.
 */
class AdbPublicKeyEncoderTest {
    private val keyPair: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val publicKey: RSAPublicKey = keyPair.public as RSAPublicKey
    private val twoPow32: BigInteger = BigInteger.ONE.shiftLeft(32)

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun readBigEndianFromLittleEndian(bytes: ByteArray): BigInteger = BigInteger(1, bytes.reversedArray())

    @Test
    fun `the blob has the AOSP layout`() {
        val blob = AdbPublicKeyEncoder.encode(publicKey)

        assertEquals(524, blob.size)
        assertEquals(64, readInt(blob, 0))

        val modulus = readBigEndianFromLittleEndian(blob.copyOfRange(8, 8 + 256))
        assertEquals(publicKey.modulus, modulus)

        // n0inv is -1 / modulus[0] mod 2^32, so modulus[0] * n0inv + 1 must vanish mod 2^32.
        val n0 = publicKey.modulus.and(twoPow32.subtract(BigInteger.ONE))
        val n0inv = readBigEndianFromLittleEndian(blob.copyOfRange(4, 8))
        assertEquals(BigInteger.ZERO, n0.multiply(n0inv).add(BigInteger.ONE).mod(twoPow32))

        val r = BigInteger.ONE.shiftLeft(2048)
        assertEquals(
            r.multiply(r).mod(publicKey.modulus),
            readBigEndianFromLittleEndian(blob.copyOfRange(8 + 256, 8 + 512)),
        )

        assertEquals(65537, readInt(blob, 8 + 512))
    }

    @Test
    fun `the pubkey line is base64 plus the user name`() {
        val line = AdbPublicKeyEncoder.encodeWithName(publicKey, "pots@android")

        assertTrue(line.endsWith(" pots@android"), "unexpected trailer: $line")
        assertEquals(524, Base64.getDecoder().decode(line.substringBefore(' ')).size)
    }
}
