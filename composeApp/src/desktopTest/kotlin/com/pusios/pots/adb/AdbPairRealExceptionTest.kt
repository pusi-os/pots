package com.pusios.pots.adb

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLProtocolException
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The pairing path mapped against the *real* exception types the transport throws.
 *
 * A mistyped six-digit code never reaches the ADB protocol: the code seeds SPAKE2, both sides derive
 * a PSK from it, and a mismatch only shows up as `SSLSocket.startHandshake()` failing inside
 * `libadb-android`. Its `AdbAuthenticationFailedException` is thrown from the connect path instead,
 * so [AdbPairResult.InvalidCode] has to come from the SSL types — an implementation that only looks
 * for the library exception fails the first test below.
 */
class AdbPairRealExceptionTest {

    @Test
    fun `a rejected pairing handshake is a wrong code`() {
        assertEquals(AdbPairResult.InvalidCode, adbPairResultOf(SSLHandshakeException("Handshake failed")))
        assertEquals(AdbPairResult.InvalidCode, adbPairResultOf(SSLException("handshake failed")))
        assertEquals(AdbPairResult.InvalidCode, adbPairResultOf(SSLProtocolException("bad record mac")))
    }

    @Test
    fun `a handshake failure wrapped by the library is still a wrong code`() {
        val wrapped = IOException("Unable to initialize pairing cipher").apply {
            initCause(SSLHandshakeException("Handshake failed"))
        }
        assertEquals(AdbPairResult.InvalidCode, adbPairResultOf(wrapped))
    }

    @Test
    fun `a closed pairing port is TargetNotFound`() {
        assertEquals(AdbPairResult.TargetNotFound, adbPairResultOf(ConnectException("Connection refused")))
    }

    @Test
    fun `a silent pairing peer is a Timeout`() {
        assertEquals(AdbPairResult.Timeout, adbPairResultOf(SocketTimeoutException("Read timed out")))
    }
}
