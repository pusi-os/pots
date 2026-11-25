package com.pusios.pots.adb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pairing screen has to tell a typo apart from a closed window, so the mapping from the
 * transport's exceptions to [AdbPairResult] is tested here.
 *
 * The doubles are declared locally with the same simple names as the library's and the JDK's,
 * because the classifier is name-based and lives in commonMain, where the real types do not exist.
 * That proves the matcher, not the names: the names the pair path must recognise are pinned by the
 * desktop test `AdbPairRealExceptionTest`, which uses the JDK's own `javax.net.ssl` types.
 */
class AdbPairResultMappingTest {

    private class AdbAuthenticationFailedException : RuntimeException()

    private class AdbPairingRequiredException(message: String) : Exception(message)

    private class InterruptedException : Exception()

    private class ConnectException : Exception()

    private class SocketTimeoutException : Exception()

    @Test
    fun `a rejected key on connect maps to InvalidCode`() {
        assertEquals(AdbPairResult.InvalidCode, adbPairResultOf(AdbAuthenticationFailedException()))
    }

    @Test
    fun `pairing required is not an outcome of pair and stays an IoError`() {
        val result = adbPairResultOf(AdbPairingRequiredException("pair again"))
        assertTrue(result is AdbPairResult.IoError)
        assertEquals("pair again", result.message)
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
