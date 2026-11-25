package com.pusios.pots.adb

/**
 * The `adbkey` file layout: a PKCS#8 private key followed by the self-signed certificate that
 * carries the matching public key.
 *
 * This is exactly what a desktop `~/.android/adbkey` holds, so the file stays legible to the ADB
 * tooling (and to a future backend that shells out to the `adb` binary) while keeping the
 * certificate next to the key it signs — the two must never drift apart, or every device would see
 * a new identity on each launch and ask to be authorized again.
 */
object AdbKeyPem {
    private const val PRIVATE_KEY_HEADER = "-----BEGIN PRIVATE KEY-----"
    private const val PRIVATE_KEY_FOOTER = "-----END PRIVATE KEY-----"
    private const val CERTIFICATE_HEADER = "-----BEGIN CERTIFICATE-----"
    private const val CERTIFICATE_FOOTER = "-----END CERTIFICATE-----"
    private const val LINE_WIDTH = 64

    /** The base64 bodies of the two PEM blocks, newlines stripped. */
    data class Parts(val privateKeyBase64: String, val certificateBase64: String)

    /** Renders [privateKeyBase64] and [certificateBase64] as the two PEM blocks of an `adbkey`. */
    fun format(privateKeyBase64: String, certificateBase64: String): String = buildString {
        appendPemBlock(this, PRIVATE_KEY_HEADER, PRIVATE_KEY_FOOTER, privateKeyBase64)
        append('\n')
        appendPemBlock(this, CERTIFICATE_HEADER, CERTIFICATE_FOOTER, certificateBase64)
    }

    /** Reads back [format], or null when either block is missing or empty. */
    fun split(text: String): Parts? {
        val privateKey = extractBody(text, PRIVATE_KEY_HEADER, PRIVATE_KEY_FOOTER) ?: return null
        val certificate = extractBody(text, CERTIFICATE_HEADER, CERTIFICATE_FOOTER) ?: return null
        return Parts(privateKey, certificate)
    }

    private fun appendPemBlock(target: StringBuilder, header: String, footer: String, body: String) {
        target.append(header).append('\n')
        body.chunked(LINE_WIDTH).forEach { target.append(it).append('\n') }
        target.append(footer).append('\n')
    }

    private fun extractBody(text: String, header: String, footer: String): String? {
        val start = text.indexOf(header)
        if (start < 0) return null
        val bodyStart = start + header.length
        val end = text.indexOf(footer, bodyStart)
        if (end < 0) return null
        val body = text.substring(bodyStart, end).filterNot { it.isWhitespace() }
        return body.ifEmpty { null }
    }
}
