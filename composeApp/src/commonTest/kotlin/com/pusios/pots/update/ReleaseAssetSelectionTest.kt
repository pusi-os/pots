package com.pusios.pots.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** One asset per OS/architecture, taken from the names the release job actually produces. */
class ReleaseAssetSelectionTest {

    private val release = parseRelease(LATEST_RELEASE_JSON)!!

    @Test
    fun androidTakesTheApk() {
        assertEquals(
            "Pots-3.13.1-android.apk",
            selectAsset(release, UpdateOs.Android, UpdateArch.Arm64)?.name,
        )
        assertEquals(
            "Pots-3.13.1-android.apk",
            selectAsset(release, UpdateOs.Android, UpdateArch.X64)?.name,
        )
    }

    @Test
    fun windowsAndMacTakeTheirInstaller() {
        assertEquals("Pots-3.13.1-windows.msi", selectAsset(release, UpdateOs.Windows, UpdateArch.X64)?.name)
        assertEquals("Pots-3.13.1-macos.dmg", selectAsset(release, UpdateOs.MacOS, UpdateArch.Arm64)?.name)
    }

    @Test
    fun linuxPrefersTheAppImageOverTheRestOfTheContainers() {
        assertEquals("Pots-3.13.1-linux.AppImage", selectAsset(release, UpdateOs.Linux, UpdateArch.X64)?.name)
    }

    @Test
    fun anAssetOfAnotherArchitectureIsNeverChosen() {
        val armOnly = AppRelease(
            versionName = "9.0.0",
            versionCode = 9_000_000L,
            notes = "",
            pageUrl = "",
            assets = listOf(
                ReleaseAsset("Pots-9.0.0-linux-x86_64.tar.gz", 1, "https://example/x86"),
                ReleaseAsset("Pots-9.0.0-linux-aarch64.tar.gz", 1, "https://example/arm"),
            ),
        )

        assertEquals("Pots-9.0.0-linux-aarch64.tar.gz", selectAsset(armOnly, UpdateOs.Linux, UpdateArch.Arm64)?.name)
        assertEquals("Pots-9.0.0-linux-x86_64.tar.gz", selectAsset(armOnly, UpdateOs.Linux, UpdateArch.X64)?.name)
    }

    @Test
    fun aNeutralNameStillMatchesWhenNoArchitectureIsPublished() {
        val neutral = AppRelease(
            versionName = "9.0.0",
            versionCode = 9_000_000L,
            notes = "",
            pageUrl = "",
            assets = listOf(ReleaseAsset("Pots-9.0.0-linux.deb", 1, "https://example/deb")),
        )

        assertEquals("Pots-9.0.0-linux.deb", selectAsset(neutral, UpdateOs.Linux, UpdateArch.Arm64)?.name)
    }

    @Test
    fun linuxFallsBackThroughTheFormatPreferenceOrder() {
        val release = AppRelease(
            versionName = "9.0.0",
            versionCode = 9_000_000L,
            notes = "",
            pageUrl = "",
            assets = listOf(
                ReleaseAsset("Pots-9.0.0-linux.rpm", 1, "https://example/rpm"),
                ReleaseAsset("Pots-9.0.0-linux.deb", 1, "https://example/deb"),
            ),
        )

        assertEquals("Pots-9.0.0-linux.deb", selectAsset(release, UpdateOs.Linux, UpdateArch.X64)?.name)
    }

    @Test
    fun aReleaseWithoutABuildForThisOsSelectsNothing() {
        val androidOnly = AppRelease(
            versionName = "9.0.0",
            versionCode = 9_000_000L,
            notes = "",
            pageUrl = "",
            assets = listOf(ReleaseAsset("Pots-9.0.0-android.apk", 1, "https://example/apk")),
        )

        assertNull(selectAsset(androidOnly, UpdateOs.Linux, UpdateArch.X64))
        assertNull(selectAsset(androidOnly, UpdateOs.Windows, UpdateArch.X64))
        assertNull(selectAsset(androidOnly, UpdateOs.MacOS, UpdateArch.Arm64))
    }

    @Test
    fun anAssetOfTheRightOsWithAnUnknownFormatIsIgnored() {
        val odd = AppRelease(
            versionName = "9.0.0",
            versionCode = 9_000_000L,
            notes = "",
            pageUrl = "",
            assets = listOf(ReleaseAsset("Pots-9.0.0-linux.txt", 1, "https://example/txt")),
        )

        assertNull(selectAsset(odd, UpdateOs.Linux, UpdateArch.X64))
    }
}
