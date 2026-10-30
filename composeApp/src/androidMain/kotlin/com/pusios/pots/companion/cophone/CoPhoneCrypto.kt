package com.pusios.pots.companion.cophone

import java.math.BigInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * CoPhone v2 key schedule (BROMESH-SPEC v1.3 §13), byte-for-byte identical to
 * the firmware's `MeshCrypto` helpers. X25519 is the RFC 7748 Montgomery
 * ladder over `BigInteger` — validated against an independent implementation
 * (Python `cryptography`) on 500 random pairs; HKDF/HMAC are the standard
 * RFC 5869 / 2104 over `HmacSHA256`.
 *
 * The labels below are part of every key: never change one without a version
 * bump, or the cube and the phone derive different keys and the SAS diverges.
 */
object CoPhoneCrypto {
    private val P: BigInteger = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19))
    private val A24 = BigInteger.valueOf(121665)
    private val MASK255: BigInteger = BigInteger.TWO.pow(255).subtract(BigInteger.ONE)

    private const val PAIR_INFO = "bromesh-cophone-v2"
    private const val SAS_INFO = "cophone-sas"
    private const val RESP_INFO = "cophone-resp"
    private const val OK_INFO = "cophone-ok"
    private const val CONFIRM_LABEL = "cophone-confirm"

    // ---- X25519 -----------------------------------------------------------

    /** mypublic = scalar * basepoint (basepoint u = 9). */
    fun publicKey(scalar: ByteArray): ByteArray = x25519(scalar, ByteArray(32).also { it[0] = 9 })

    /** shared = scalar * u (u is a point, not a scalar). */
    fun x25519(scalar: ByteArray, u: ByteArray): ByteArray {
        val k = scalar.copyOf()
        k[0] = (k[0].toInt() and 248).toByte()
        k[31] = (k[31].toInt() and 127 or 64).toByte()
        val kInt = BigInteger(1, k.reversedArray())
        val x1 = BigInteger(1, u.reversedArray()).and(MASK255)
        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = x1
        var z3 = BigInteger.ONE
        var swap = 0
        for (t in 254 downTo 0) {
            val kt = kInt.shiftRight(t).and(BigInteger.ONE).toInt()
            swap = swap xor kt
            if (swap == 1) {
                val tx = x2
                x2 = x3
                x3 = tx
                val tz = z2
                z2 = z3
                z3 = tz
            }
            swap = kt
            val a = x2.add(z2).mod(P)
            val aa = a.multiply(a).mod(P)
            val b = x2.subtract(z2).mod(P)
            val bb = b.multiply(b).mod(P)
            val e = aa.subtract(bb).mod(P)
            val c = x3.add(z3).mod(P)
            val d = x3.subtract(z3).mod(P)
            val da = d.multiply(a).mod(P)
            val cb = c.multiply(b).mod(P)
            x3 = da.add(cb).mod(P)
            x3 = x3.multiply(x3).mod(P)
            z3 = da.subtract(cb).mod(P)
            z3 = z3.multiply(z3).mod(P)
            z3 = z3.multiply(x1).mod(P)
            x2 = aa.multiply(bb).mod(P)
            z2 = e.multiply(aa.add(A24.multiply(e))).mod(P)
        }
        if (swap == 1) {
            val tx = x2
            x2 = x3
            x3 = tx
            val tz = z2
            z2 = z3
            z3 = tz
        }
        val res = x2.multiply(z2.modPow(P.subtract(BigInteger.TWO), P)).mod(P)
        return toLE32(res)
    }

    private fun toLE32(v: BigInteger): ByteArray {
        val out = ByteArray(32)
        val be = v.mod(BigInteger.TWO.pow(256)).toByteArray()
        var i = 0
        for (j in be.indices.reversed()) {
            if (i >= 32) break
            out[i++] = be[j]
        }
        return out
    }

    /** A fresh 32-byte X25519 scalar from a secure RNG. */
    fun randomScalar(): ByteArray {
        val s = ByteArray(32)
        java.security.SecureRandom().nextBytes(s)
        return s
    }

    // ---- HMAC / HKDF ------------------------------------------------------

    fun hmacSha256(key: ByteArray, msg: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(msg)
    }

    fun hkdfSha256(ikm: ByteArray, salt: ByteArray?, info: ByteArray, outLen: Int): ByteArray {
        val prk = if (salt != null && salt.isNotEmpty()) {
            hmacSha256(salt, ikm)
        } else {
            hmacSha256(ByteArray(32), ikm)
        }
        val out = ByteArray(outLen)
        var t = ByteArray(0)
        var done = 0
        var ctr = 1
        while (done < outLen) {
            val msg = t + info + byteArrayOf(ctr.toByte())
            ctr++
            t = hmacSha256(prk, msg)
            val take = minOf(t.size, outLen - done)
            System.arraycopy(t, 0, out, done, take)
            done += take
        }
        return out
    }

    // ---- CoPhone key schedule --------------------------------------------

    /**
     * pairKey = HKDF(shared, salt = cubeNodeId(8) || phoneId(6), info = PAIR_INFO).
     * Both IDs are stable: the cube's BLE address and the phone's rotate
     * (RPA), the nodeIds do not — a MAC in the salt is why SAS codes used to
     * diverge mid-pairing.
     */
    fun pairKey(shared: ByteArray, cubeNodeId: ByteArray, phoneId: ByteArray): ByteArray {
        require(shared.size == 32 && cubeNodeId.size == 8 && phoneId.size == 6)
        val salt = cubeNodeId + phoneId
        return hkdfSha256(shared, salt, PAIR_INFO.toByteArray(Charsets.UTF_8), 32)
    }

    /** Six-digit SAS from HKDF(pairKey, "cophone-sas", 4), big-endian mod 1e6. */
    fun sas(pairKey: ByteArray): Int {
        val h = hkdfSha256(pairKey, null, SAS_INFO.toByteArray(Charsets.UTF_8), 4)
        val v = ((h[0].toInt() and 0xFF) shl 24) or ((h[1].toInt() and 0xFF) shl 16) or
            ((h[2].toInt() and 0xFF) shl 8) or (h[3].toInt() and 0xFF)
        return v % 1_000_000
    }

    fun respKey(pairKey: ByteArray): ByteArray = hkdfSha256(pairKey, null, RESP_INFO.toByteArray(Charsets.UTF_8), 16)

    fun okKey(pairKey: ByteArray): ByteArray = hkdfSha256(pairKey, null, OK_INFO.toByteArray(Charsets.UTF_8), 16)

    /** hmac = HMAC(pairKey, "cophone-confirm" || cubeNodeId || phoneId)[0:8]. */
    fun acceptHmac(pairKey: ByteArray, cubeNodeId: ByteArray, phoneId: ByteArray): ByteArray {
        require(cubeNodeId.size == 8 && phoneId.size == 6)
        val msg = CONFIRM_LABEL.toByteArray(Charsets.UTF_8) + cubeNodeId + phoneId
        return hmacSha256(pairKey, msg).copyOfRange(0, 8)
    }

    /**
     * Код, напечатанный на кубике, как доказательство присутствия:
     * HMAC(pairKey, "cophone-confirm" || cubeNodeId || phoneId || code)[0:4].
     * Кубик сверяет и код, и HMAC, поэтому подсмотренный в эфире CONFIRM
     * бесполезен без pairKey, а подобранный код без HMAC — тоже.
     */
    fun confirmHmac(
        pairKey: ByteArray,
        cubeNodeId: ByteArray,
        phoneId: ByteArray,
        code: String,
    ): ByteArray {
        require(cubeNodeId.size == 8 && phoneId.size == 6)
        val msg = CONFIRM_LABEL.toByteArray(Charsets.UTF_8) + cubeNodeId + phoneId +
            code.toByteArray(Charsets.UTF_8)
        return hmacSha256(pairKey, msg).copyOfRange(0, 4)
    }

    /** Reverse a 6-byte address between display order and BLE air order. */
    fun reverse6(b: ByteArray): ByteArray = ByteArray(6) { b[5 - it] }
}
