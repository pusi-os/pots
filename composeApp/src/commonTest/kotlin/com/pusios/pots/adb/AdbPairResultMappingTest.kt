package com.pusios.pots.adb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pairing screen has to tell a typo apart from a revoked key, so the mapping from the transport's
 * exceptions to [AdbPairResult] is tested here rather than left to the one place that sees them.
 *
 * The exceptions are declared locally with the same simple names as the library's and the JDK's: the
 * classifier is deliberately name-based (it lives in commonMain, where the real types do not exist),
 * and this is what proves the names it looks for are the ones the transport actually throws.
 */
class AdbPairResultMappingTest {

    private class AdbAuthenticationFailedException : RuntimeException()

    private class AdbPairingRequiredException(message: String) : Exception(message)

    private class InterruptedException : Exception()

    private class ConnectException : Exception()

    private class SocketTimeoutException : Exception()

    @Test
    fun `wrong pairing code maps to InvalidCode`() {
        assertEquals(AdbPairResult.InvalidCode, adbPairResultOf(AdbAuthenticationFailedException()))
    }

    @Test
    fun `revoked key maps to PairingRequiredAgain`() {
        assertEquals(AdbPairResult.PairingRequiredAgain, adbPairResultOf(AdbPairingRequiredException("pair again")))
    }

    @Test
    fun `closed pairing window maps to TargetNotFound`() {
        assertEquals(AdbPairResult.TargetNotFound, adbPairResultOf(ConnectException()))
    }

    @Test
    fun `deadline maps to Timeout`() {
        assertEquals(AdbPairResult.Timeout, adbPairResultOf(InterruptedException()))
        assertEquals(AdbPairResult.Timeout, adbPairResultOf(SocketTimeoutException()))
    }

    @Test
    fun `anything else maps to IoError with the message`() {
        val result = adbPairResultOf(IllegalStateException("socket exploded"))
        assertTrue(result is AdbPairResult.IoError)
        assertEquals("socket exploded", result.message)
    }

    @Test
    fun `the cause chain is walked`() {
        val wrapped = RuntimeException("wrapper", ConnectException())
        assertEquals(AdbPairResult.TargetNotFound, adbPairResultOf(wrapped))
        assertEquals(AdbErrorKind.UNREACHABLE, adbErrorKindOf(wrapped))
    }

    @Test
    fun `kinds are classified for the shell path too`() {
        assertEquals(AdbErrorKind.AUTHENTICATION_FAILED, adbErrorKindOf(AdbAuthenticationFailedException()))
        assertEquals(AdbErrorKind.PAIRING_REQUIRED, adbErrorKindOf(AdbPairingRequiredException("pair")))
        assertEquals(AdbErrorKind.TIMEOUT, adbErrorKindOf(InterruptedException()))
        assertEquals(AdbErrorKind.IO, adbErrorKindOf(IllegalStateException("boom")))
    }

    @Test
    fun `an AdbException keeps its kind through the wrapper`() {
        val original = AdbException(AdbErrorKind.TIMEOUT, "slow device")
        assertEquals(AdbErrorKind.TIMEOUT, adbExceptionOf(original).kind)
        assertEquals(AdbErrorKind.IO, adbExceptionOf(IllegalArgumentException("bad")).kind)
    }
}
