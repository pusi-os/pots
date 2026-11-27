package com.pusios.pots.linux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AptUpdateFailureTest {

    /**
     * apt's own line, verbatim — libapt-pkg's format string is
     * `Release file for %s is not valid yet (invalid for another %s)`. This is
     * a phone 2d 21h 19min 43s behind the mirror's Release file.
     */
    private val notValidYet = """
        Get:1 http://deb.debian.org/debian-security bookworm-security InRelease [48.0 kB]
        Reading package lists...
        E: Release file for http://deb.debian.org/debian-security/dists/bookworm-security/InRelease is not valid yet (invalid for another 2d 21h 19min 43s). Updates for this repository will not be applied.
        E: Some index files failed to download. They have been ignored, or old ones used instead.
    """.trimIndent()

    @Test
    fun `a release file from the future is the device clock`() {
        val failure = AptPackageManager.classifyFailure(notValidYet)

        assertTrue(failure is InstallFailure.ClockSkew, "expected ClockSkew, got $failure")
        assertEquals(
            "Release file for http://deb.debian.org/debian-security/dists/bookworm-security/InRelease " +
                "is not valid yet (invalid for another 2d 21h 19min 43s). Updates for this repository will not be applied.",
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
    fun `apt's own gap is the measured clock skew`() {
        // The mirror's Date is 2d 21h 19min 43s ahead of the phone, so the phone
        // is behind by exactly that much. This is the only measurement that does
        // not read the phone's own clock twice.
        val expected = -(2 * 86_400L + 21 * 3_600L + 19 * 60L + 43L)

        assertEquals(expected, AptPackageManager.clockSkewSeconds(notValidYet))
    }

    @Test
    fun `the gap parses with leading units dropped`() {
        assertEquals(
            -43L,
            AptPackageManager.clockSkewSeconds("E: Release file for http://x/InRelease is not valid yet (invalid for another 43s)"),
        )
        assertEquals(
            -(3 * 3_600L + 19 * 60L + 43L),
            AptPackageManager.clockSkewSeconds(
                "E: Release file for http://x/InRelease is not valid yet (invalid for another 3h 19min 43s)",
            ),
        )
    }

    @Test
    fun `only a future-dated release carries a gap`() {
        // `invalid since` is the opposite direction: the index has run out of
        // time, which is not the device clock being behind.
        assertNull(
            AptPackageManager.clockSkewSeconds(
                "E: Release file for http://x/InRelease is expired (invalid since 3d 2h 1min 2s)",
            ),
        )
        assertNull(AptPackageManager.clockSkewSeconds("Temporary failure resolving 'deb.debian.org'"))
        assertNull(AptPackageManager.clockSkewSeconds("E: Release file for http://x/InRelease is not valid yet"))
    }

    @Test
    fun `only a date failure asks for the retry with the date checks off`() {
        assertEquals(AptPackageManager.updateWithoutDateCheckCommand, AptPackageManager.retryCommandFor(notValidYet))
        assertEquals(
            AptPackageManager.updateWithoutDateCheckCommand,
            AptPackageManager.retryCommandFor(
                "E: Release file for http://x/InRelease is expired (invalid since 1d 2h)",
            ),
        )

        assertNull(
            AptPackageManager.retryCommandFor(
                "E: Failed to fetch http://x/dists/bookworm/InRelease  404  Not Found [IP: 1.2.3.4 80]",
            ),
        )
        assertNull(
            AptPackageManager.retryCommandFor(
                "W: Failed to fetch http://x/dists/bookworm/InRelease  Temporary failure resolving 'x'",
            ),
        )
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
    }

    @Test
    fun `a gap under a minute reads as seconds`() {
        // apt's shortest real print is `invalid for another 43s`; without the
        // seconds the owner would be told their clock is off by "-0m".
        assertEquals("-43s", AptPackageManager.formatClockOffset(-43))
        assertEquals("+0s", AptPackageManager.formatClockOffset(0))
    }

    @Test
    fun `a classified failure still has one technical line for the log`() {
        val failure = AptPackageManager.classifyFailure(notValidYet)

        assertTrue(failure.summary.startsWith("the device clock is off by Release file for"))
        assertFalse(failure.summary.contains("E: "))
    }

    @Test
    fun `a date failure that survives the retry reports apt's measured gap`() {
        val failure = AptPackageManager.indexFailure(notValidYet, notValidYet)

        assertEquals(InstallFailure.ClockSkew("-2d 21h 19m"), failure)
    }

    @Test
    fun `the measured gap outlives a retry that failed for another reason`() {
        // The retry can fail for its own reason (a mirror that went away); the
        // device clock is still the thing the user has to fix.
        val failure = AptPackageManager.indexFailure(
            firstOutput = notValidYet,
            retryOutput = "W: Failed to fetch http://deb.debian.org/debian/InRelease  Temporary failure resolving 'deb.debian.org'",
        )

        assertEquals(InstallFailure.ClockSkew("-2d 21h 19m"), failure)
    }

    @Test
    fun `without a printed gap the release line is the detail`() {
        val noGap = "E: Release file for http://x/InRelease is not valid yet. Updates for this repository will not be applied."

        val failure = AptPackageManager.indexFailure(noGap, noGap)

        assertTrue(failure is InstallFailure.ClockSkew, "expected ClockSkew, got $failure")
        assertTrue(failure.detail.contains("is not valid yet"))
    }

    @Test
    fun `an expired index stays expired even though the retry ran`() {
        val expired = "E: Release file for http://x/InRelease is expired (invalid since 3d 2h 1min 2s)."

        assertTrue(AptPackageManager.indexFailure(expired, expired) is InstallFailure.ExpiredMetadata)
    }
}
