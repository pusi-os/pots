package com.pusios.pots.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The release payload is the contract with the release job in `.github/workflows/release.yml`:
 * the tag carries the version, and the assets are named `Pots-<version>-<os>.<ext>`.
 */
class ReleaseParsingTest {

    @Test
    fun parsesTheLiveLatestReleaseShape() {
        val release = parseRelease(LATEST_RELEASE_JSON)

        assertEquals("3.13.1", release?.versionName)
        assertEquals(3_013_001L, release?.versionCode)
        assertEquals("https://github.com/pusi-os/pots/releases/tag/v3.13.1", release?.pageUrl)
        assertTrue(release?.notes?.contains("Улучшения") == true)
        assertEquals(8, release?.assets?.size)
        assertEquals(
            "Pots-3.13.1-android.apk",
            release?.assets?.firstOrNull { it.name.endsWith(".apk") }?.name,
        )
        assertEquals(
            246_704_254L,
            release?.assets?.firstOrNull { it.name.endsWith(".apk") }?.sizeBytes,
        )
    }

    @Test
    fun toleratesUnknownFieldsAndAMissingBody() {
        val release = parseRelease(
            """
            {"tag_name":"v3.14.0","html_url":"https://x","draft":false,"prerelease":false,
             "assets":[{"name":"Pots-3.14.0-android.apk","size":10,
                        "browser_download_url":"https://example/Pots-3.14.0-android.apk",
                        "uploader":{"login":"bot"}}]}
            """.trimIndent(),
        )

        assertEquals("3.14.0", release?.versionName)
        assertEquals("", release?.notes)
        assertEquals(1, release?.assets?.size)
    }

    @Test
    fun rejectsABodyWithoutATag() {
        assertNull(parseRelease("""{"assets":[{"name":"Pots-android.apk","browser_download_url":"https://example/a"}]}"""))
    }

    @Test
    fun rejectsABodyWithoutAssets() {
        assertNull(parseRelease("""{"tag_name":"v3.14.0","assets":[]}"""))
    }

    @Test
    fun rejectsABodyThatIsNotJson() {
        assertNull(parseRelease("<html>rate limited</html>"))
        assertNull(parseRelease(""))
    }

    @Test
    fun assetsWithoutADownloadUrlAreDropped() {
        val release = parseRelease(
            """{"tag_name":"v1.2.3","assets":[{"name":"Pots-1.2.3-android.apk","size":1},
                                             {"name":"Pots-1.2.3-windows.msi","size":2,"browser_download_url":"https://example/w.msi"}]}""",
        )

        assertEquals(listOf("Pots-1.2.3-windows.msi"), release?.assets?.map { it.name })
    }

    @Test
    fun readsTheOsAndArchitectureFromTheAssetName() {
        val release = parseRelease(LATEST_RELEASE_JSON)!!
        val byName = release.assets.associateBy { it.name }

        assertEquals(UpdateOs.Android, byName.getValue("Pots-3.13.1-android.apk").os)
        assertEquals(UpdateOs.Windows, byName.getValue("Pots-3.13.1-windows.msi").os)
        assertEquals(UpdateOs.MacOS, byName.getValue("Pots-3.13.1-macos.dmg").os)
        assertEquals(UpdateOs.Linux, byName.getValue("Pots-3.13.1-linux.deb").os)
        // The tarball puts its architecture between the OS and the extension.
        assertEquals(UpdateOs.Linux, byName.getValue("Pots-3.13.1-linux-x86_64.tar.gz").os)

        assertEquals(UpdateArch.X64, byName.getValue("Pots-3.13.1-linux-x86_64.tar.gz").arch)
        // The other Linux containers are published without an architecture token.
        assertNull(byName.getValue("Pots-3.13.1-linux.AppImage").arch)
        assertNull(byName.getValue("Pots-3.13.1-android.apk").arch)
    }

    @Test
    fun archiveHeadersAreCheckedAgainstTheName() {
        assertTrue(archiveHeaderMatches("Pots-android.apk", byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0)))
        assertFalse(archiveHeaderMatches("Pots-android.apk", "<html>".encodeToByteArray()))
        assertTrue(archiveHeaderMatches("Pots-linux-x86_64.tar.gz", byteArrayOf(0x1F, 0x8B.toByte(), 8, 0)))
        assertFalse(archiveHeaderMatches("Pots-linux-x86_64.tar.gz", "not gzip".encodeToByteArray()))
        assertTrue(archiveHeaderMatches("Pots-linux.deb", "!<arch>\ndebian".encodeToByteArray()))
        assertTrue(archiveHeaderMatches("Pots-linux.rpm", byteArrayOf(0xED.toByte(), 0xAB.toByte(), 0xEE.toByte(), 0xDB.toByte())))
        assertTrue(archiveHeaderMatches("Pots-windows.msi", byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte())))
        assertTrue(archiveHeaderMatches("Pots-linux.AppImage", byteArrayOf(0x7F, 0x45, 0x4C, 0x46)))
        // A container the app knows nothing about is not rejected on a guess.
        assertTrue(archiveHeaderMatches("Pots-macos.dmg", "koly".encodeToByteArray()))
    }

    @Test
    fun archivesAreTheOnlyContainersThatNeedUnpacking() {
        assertTrue(requiresUnpacking("Pots-3.13.1-linux-x86_64.tar.gz"))
        assertTrue(requiresUnpacking("Pots-3.13.1-linux.flatpak"))
        assertFalse(requiresUnpacking("Pots-3.13.1-android.apk"))
        assertFalse(requiresUnpacking("Pots-3.13.1-windows.msi"))
        assertFalse(requiresUnpacking("Pots-3.13.1-linux.AppImage"))
    }
}

/**
 * A trimmed copy of `GET /repos/pusi-os/pots/releases/latest` fetched on 2026-11-17, keeping
 * every asset name, size and download URL so asset selection is tested against real data.
 */
internal val LATEST_RELEASE_JSON = """
{
  "url": "https://api.github.com/repos/pusi-os/pots/releases/276543210",
  "html_url": "https://github.com/pusi-os/pots/releases/tag/v3.13.1",
  "tag_name": "v3.13.1",
  "name": "Release v3.13.1",
  "draft": false,
  "prerelease": false,
  "author": {"login": "github-actions[bot]"},
  "body": "\n### Улучшения\n- Режим «поверх окон»: Потсик остаётся на рабочем столе.\n",
  "assets": [
    {"name": "Pots-3.13.1-android.apk", "size": 246704254,
     "browser_download_url": "https://github.com/pusi-os/pots/releases/download/v3.13.1/Pots-3.13.1-android.apk",
     "content_type": "application/vnd.android.package-archive"},
    {"name": "Pots-3.13.1-linux-x86_64.tar.gz", "size": 310000000,
     "browser_download_url": "https://github.com/pusi-os/pots/releases/download/v3.13.1/Pots-3.13.1-linux-x86_64.tar.gz"},
    {"name": "Pots-3.13.1-linux.AppImage", "size": 320000000,
     "browser_download_url": "https://github.com/pusi-os/pots/releases/download/v3.13.1/Pots-3.13.1-linux.AppImage"},
    {"name": "Pots-3.13.1-linux.deb", "size": 300000000,
     "browser_download_url": "https://github.com/pusi-os/pots/releases/download/v3.13.1/Pots-3.13.1-linux.deb"},
    {"name": "Pots-3.13.1-linux.flatpak", "size": 330000000,
     "browser_download_url": "https://github.com/pusi-os/pots/releases/download/v3.13.1/Pots-3.13.1-linux.flatpak"},
    {"name": "Pots-3.13.1-linux.rpm", "size": 300000000,
     "browser_download_url": "https://github.com/pusi-os/pots/releases/download/v3.13.1/Pots-3.13.1-linux.rpm"},
    {"name": "Pots-3.13.1-macos.dmg", "size": 290000000,
     "browser_download_url": "https://github.com/pusi-os/pots/releases/download/v3.13.1/Pots-3.13.1-macos.dmg"},
    {"name": "Pots-3.13.1-windows.msi", "size": 280000000,
     "browser_download_url": "https://github.com/pusi-os/pots/releases/download/v3.13.1/Pots-3.13.1-windows.msi"}
  ]
}
"""
