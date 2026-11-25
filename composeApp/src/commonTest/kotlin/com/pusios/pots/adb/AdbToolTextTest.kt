// Tests for the two text helpers: secret masking in the confirmation summary, and the 8 KB output
// tail with its marker.

package com.pusios.pots.adb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdbToolTextTest {

    @Test
    fun masksValuesAfterCredentialLikeKeys() {
        val masked = maskAdbSecrets("curl -H token=SECRET-1 -d password: hunter2 --api_key=abc")
        assertFalse(masked.contains("SECRET-1"))
        assertFalse(masked.contains("hunter2"))
        assertFalse(masked.contains("abc"))
        assertTrue(masked.contains("token=***"))
        assertTrue(masked.contains("password: ***"))
        assertTrue(masked.contains("api_key=***"))
    }

    @Test
    fun keepsOrdinaryCommandsReadable() {
        val command = "getprop ro.product.model"
        assertEquals(command, maskAdbSecrets(command))
    }

    @Test
    fun shortOutputIsReturnedUnchanged() {
        val output = "uid=2000(shell) gid=2000(shell)"
        assertEquals(output, truncateAdbShellOutput(output))
        assertFalse(truncateAdbShellOutput(output).contains("truncated"))
    }

    @Test
    fun longOutputKeepsTheTailAndSaysSo() {
        val output = "A".repeat(20_000) + "END"
        val truncated = truncateAdbShellOutput(output, limitBytes = 8 * 1024)
        assertTrue(truncated.startsWith("[output truncated: showing the last 8 KB of 19 KB]"))
        assertTrue(truncated.endsWith("END"))
    }

    @Test
    fun truncationDoesNotSplitAUtf8Character() {
        // 3-byte characters: cutting at an arbitrary byte offset would otherwise decode to U+FFFD.
        val output = "ж".repeat(4_000)
        val truncated = truncateAdbShellOutput(output, limitBytes = 4_000)
        assertFalse(truncated.contains('\uFFFD'))
        assertTrue(truncated.endsWith("ж"))
    }
}