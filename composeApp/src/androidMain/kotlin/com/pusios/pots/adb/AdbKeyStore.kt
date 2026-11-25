package com.pusios.pots.adb

import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * The host key Pots presents to every ADB daemon, stored in `filesDir/adb/`.
 *
 * `adbkey` holds the PKCS#8 private key followed by its self-signed certificate, and `adbkey.pub`
 * holds the same public key in the `android_pubkey.h` form — the same pair of files a desktop
 * `~/.android` keeps, so the identity can be moved or inspected with familiar tools. The key is
 * generated once, on first use, and reused forever: a device that authorized Pots remembers the key,
 * and regenerating it would silently turn every device into an unauthorized one.
 *
 * The directory is app-private and excluded from backups (see the backup rules); wrapping the key in
 * the Android Keystore is deliberately out of scope for this release.
 */
class AdbKeyStore(private val directory: File) {
    private val keyFile = File(directory, "adbkey")
    private val publicKeyFile = File(directory, "adbkey.pub")
    private val lock = Any()

    private var privateKeyDer: ByteArray? = null
    private var certificateDer: ByteArray? = null
    private var publicKeyLine: String? = null

    /**
     * A fresh [PrivateKey] on every call.
     *
     * `AbsAdbConnectionManager.close()` destroys the key it was handed, and a destroyed key is a
     * broken key: handing out a new instance each time keeps the stored identity immune to that.
     */
    fun privateKey(): PrivateKey = synchronized(lock) {
        ensureLoaded()
        KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(privateKeyDer!!))
    }

    /** The certificate that carries the public half of [privateKey], re-parsed per call. */
    fun certificate(): Certificate = synchronized(lock) {
        ensureLoaded()
        CertificateFactory.getInstance("X.509").generateCertificate(certificateDer!!.inputStream())
    }

    /** The `adbkey.pub` line, e.g. for showing the key fingerprint in the settings screen. */
    fun publicKeyLine(): String = synchronized(lock) {
        ensureLoaded()
        publicKeyLine!!
    }

    private fun ensureLoaded() {
        if (privateKeyDer != null) return
        val stored = readStoredKey()
        if (stored != null) {
            privateKeyDer = stored.first
            certificateDer = stored.second
            publicKeyLine = readStoredPublicKeyLine() ?: encodePublicKeyLine()
            if (!publicKeyFile.isFile) publicKeyFile.writeText(publicKeyLine!!)
            return
        }
        generate()
    }

    private fun readStoredKey(): Pair<ByteArray, ByteArray>? {
        if (!keyFile.isFile) return null
        val parts = runCatching { AdbKeyPem.split(keyFile.readText()) }.getOrNull() ?: return null
        val privateKey = runCatching { Base64.getDecoder().decode(parts.privateKeyBase64) }.getOrNull() ?: return null
        val certificate = runCatching { Base64.getDecoder().decode(parts.certificateBase64) }.getOrNull() ?: return null
        return if (matches(privateKey, certificate)) privateKey to certificate else null
    }

    private fun readStoredPublicKeyLine(): String? =
        publicKeyFile.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }

    /** True when [certificateDer] really carries the public half of [privateKeyDer]. */
    private fun matches(privateKeyDer: ByteArray, certificateDer: ByteArray): Boolean = runCatching {
        val privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(privateKeyDer))
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(certificateDer.inputStream())
        privateKey is RSAPrivateCrtKey &&
            certificate is X509Certificate &&
            certificate.publicKey.let { it is RSAPublicKey && it.modulus == privateKey.modulus }
    }.getOrDefault(false)

    private fun generate() {
        directory.mkdirs()
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(KEY_SIZE_BITS)
        val keyPair = generator.generateKeyPair()
        val certificate = SelfSignedCertificate.create(keyPair)

        privateKeyDer = keyPair.private.encoded
        certificateDer = certificate
        publicKeyLine = AdbPublicKeyEncoder.encodeWithName(keyPair.public as RSAPublicKey, PUBLIC_KEY_NAME)

        keyFile.writeText(
            AdbKeyPem.format(
                Base64.getEncoder().encodeToString(privateKeyDer!!),
                Base64.getEncoder().encodeToString(certificateDer!!),
            ),
        )
        publicKeyFile.writeText(publicKeyLine!!)
    }

    private fun encodePublicKeyLine(): String {
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(certificateDer!!.inputStream())
        return AdbPublicKeyEncoder.encodeWithName(certificate.publicKey as RSAPublicKey, PUBLIC_KEY_NAME)
    }

    private companion object {
        const val KEY_SIZE_BITS = 2048
        const val PUBLIC_KEY_NAME = "pots@android"
    }
}
