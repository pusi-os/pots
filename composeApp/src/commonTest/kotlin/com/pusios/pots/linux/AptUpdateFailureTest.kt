package com.pusios.pots.linux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AptUpdateFailureTest {

    /** The line from the report: a phone 2d 21h behind the mirror's Release file. */
    private val notValidYet = """
        Get:1 http://deb.debian.org/debian-security bookworm-security InRelease [48.0 kB]
        Reading package lists...
        E: Release file for http://deb.debian.org/debian-security/dists/bookworm-security/InRelease is not valid yet (invalid for 2d 21h 19min 43s). Updates for this repository will not be applied.
        E: Some index files failed to download. They have been ignored, or old ones used instead.
    """.trimIndent()

    @Test
    fun `a release file from the future is the device clock`() {
        val failure = AptPackageManager.classifyFailure(notValidYet)

        assertTrue(failure is InstallFailure.ClockSkew, "expected ClockSkew, got $failure")
        assertEquals(
            "Release file for http://deb.debian.org/debian-security/dists/bookworm-security/InRelease " +
                "is not valid yet (invalid for 2d 21h 19min 43s). Updates for this repository will not be applied.",
            failure.detail,
        )
    }

    @Test
    fun `a release file past its window is expired metadata`() {
        val output = """
            E: Release file for http://deb.debian.org/debian/dists/bookworm/InRelease is expired (invalid since 3d 2h 1min 2s). Updates for this repository will not be applied.
        """.trimIndent()

        val failure = AptPackageManager.classifyFailure(output)

        assertTrue(failure is InstallFailure.ExpiredMetadata, "expected ExpiredMetadata, got $failure")
        assertTrue(failure.detail.startsWith("Release file for http://deb.debian.org/debian/dists/bookworm/InRelease"))
    }

    @Test
    fun `an index that never arrived is a network failure`() {
        val output = """
            W: Failed to fetch http://deb.debian.org/debian/dists/bookworm/InRelease  Temporary failure resolving 'deb.debian.org'
            E: Some index files failed to download. They have been ignored, or old ones used instead.
        """.trimIndent()

        val failure = AptPackageManager.classifyFailure(output)

        assertTrue(failure is InstallFailure.Network, "expected Network, got $failure")
        assertTrue(failure.detail.contains("Temporary failure resolving"))
    }

    @Test
    fun `a mirror without a release file is a mirror failure`() {
        val noRelease = """
            E: The repository 'http://deb.debian.org/debian bookworm Release' does not have a Release file.
        """.trimIndent()
        val notFound = """
            E: Failed to fetch http://deb.debian.org/debian/dists/bookworm/InRelease  404  Not Found [IP: 151.101.2.132 80]
        """.trimIndent()

        assertTrue(AptPackageManager.classifyFailure(noRelease) is InstallFailure.Mirror)
        assertTrue(AptPackageManager.classifyFailure(notFound) is InstallFailure.Mirror)
    }

    @Test
    fun `anything else keeps apt's last error line`() {
        val output = """
            Reading package lists...
            E: Could not open lock file /var/lib/apt/lists/lock - open (13: Permission denied)
        """.trimIndent()

        assertEquals(
            InstallFailure.Other("Could not open lock file /var/lib/apt/lists/lock - open (13: Permission denied)"),
            AptPackageManager.classifyFailure(output),
        )
    }

    @Test
    fun `only the date markers ask for a retry without the date checks`() {
        assertTrue(AptPackageManager.isDateCheckFailure(notValidYet))
        assertTrue(AptPackageManager.isDateCheckFailure("E: Release file for http://x/InRelease is expired (invalid since 1d)"))
        assertTrue(AptPackageManager.isDateCheckFailure("E: Release file for http://x/InRelease is not valid yet (invalid for 3h)"))

        assertFalse(AptPackageManager.isDateCheckFailure("Temporary failure resolving 'deb.debian.org'"))
        assertFalse(AptPackageManager.isDateCheckFailure("E: The repository 'http://x bookworm Release' does not have a Release file."))
    }

    @Test
    fun `the retry turns both date checks off and the plain update keeps them on`() {
        assertEquals("apt-get update -y", AptPackageManager.updateCommand)
        assertEquals(
            "apt-get update -y -o Acquire::Check-Date=false -o Acquire::Check-Valid-Until=false",
            AptPackageManager.updateWithoutDateCheckCommand,
        )
        assertFalse(AptPackageManager.updateCommand.contains("Check-Date"))
    }

    @Test
    fun `the measured clock offset reads as days, hours and minutes`() {
        val twoDaysBehind = -(2 * 86_400L + 21 * 3_600L + 19 * 60L)

        assertEquals("-2d 21h 19m", AptPackageManager.formatClockOffset(twoDaysBehind))
        assertEquals("+1h 5m", AptPackageManager.formatClockOffset(3_900))
        assertEquals("+45m", AptPackageManager.formatClockOffset(2_700))
        assertEquals("+0m", AptPackageManager.formatClockOffset(0))
    }

    @Test
    fun `a day of drift is where the clock stops being plausible`() {
        assertEquals(86_400L, AptPackageManager.clockSkewToleranceSeconds)
    }

    @Test
    fun `a classified failure still has one technical line for the log`() {
        val failure = AptPackageManager.classifyFailure(notValidYet)

        assertTrue(failure.summary.startsWith("the device clock is off by Release file for"))
        assertFalse(failure.summary.contains("E: "))
    }

    @Test
    fun `a measured clock wins over whatever apt printed after the retry`() {
        // The retry can fail for its own reason (a mirror that went away); the
        // device clock is still the thing the user has to fix.
        val failure = AptPackageManager.indexFailure(
            firstOutput = notValidYet,
            retryOutput = "W: Failed to fetch http://deb.debian.org/debian/InRelease  Temporary failure resolving 'deb.debian.org'",
            clockOffsetSeconds = -(2 * 86_400L + 21 * 3_600L + 19 * 60L),
        )

        assertEquals(InstallFailure.ClockSkew("-2d 21h 19m"), failure)
    }

    @Test
    fun `without a measurement the output names the reason`() {
        val fromDate = AptPackageManager.indexFailure(notValidYet, notValidYet, clockOffsetSeconds = null)
        assertTrue(fromDate is InstallFailure.ClockSkew, "expected ClockSkew, got $fromDate")
        assertTrue(fromDate.detail.contains("is not valid yet"))

        val fromRetry = AptPackageManager.indexFailure(
            firstOutput = notValidYet,
            retryOutput = "E: Failed to fetch http://deb.debian.org/debian/dists/bookworm/InRelease  404  Not Found [IP: 1.2.3.4 80]",
            clockOffsetSeconds = null,
        )
        // The first run's date error is checked before the retry's mirror error:
        // it is the failure that made the retry necessary.
        assertTrue(fromRetry is InstallFailure.ClockSkew, "expected ClockSkew, got $fromRetry")
    }

    @Test
    fun `a small offset is not a clock problem`() {
        val failure = AptPackageManager.indexFailure(notValidYet, notValidYet, clockOffsetSeconds = 90)

        assertTrue(failure is InstallFailure.ClockSkew)
        assertTrue(failure.detail.contains("is not valid yet"), "expected apt's line, got ${failure.detail}")
    }
}
