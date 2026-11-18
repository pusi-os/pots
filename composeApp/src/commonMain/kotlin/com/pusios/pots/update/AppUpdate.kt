package com.pusios.pots.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** GitHub's "latest published release" endpoint for Pots. */
const val POTS_RELEASES_URL = "https://api.github.com/repos/pusi-os/pots/releases/latest"

/** Public page a user can fall back to when the in-app update cannot run. */
const val POTS_RELEASES_PAGE = "https://github.com/pusi-os/pots/releases/latest"

/** One downloadable file attached to a release. */
data class ReleaseAsset(
    val name: String,
    val sizeBytes: Long,
    val downloadUrl: String,
) {
    /**
     * The OS this asset belongs to, read from the `<name>-<os>.<ext>` convention of the release
     * job — and from `<name>-<os>-<arch>.<ext>` for the tarball, which carries an architecture
     * token before the extension.
     */
    val os: UpdateOs?
        get() = UpdateOs.entries.firstOrNull {
            name.contains("-${it.assetToken}.") || name.contains("-${it.assetToken}-")
        }

    /**
     * The CPU family the asset is built for, or null when its name does not say — the
     * `.deb`/`.rpm`/`.AppImage`/`.flatpak` builds are named without an architecture token,
     * the tarball is the only one that carries it.
     */
    val arch: UpdateArch?
        get() = when {
            name.contains("aarch64") || name.contains("arm64") -> UpdateArch.Arm64
            name.contains("x86_64") || name.contains("amd64") || name.contains("x64") -> UpdateArch.X64
            else -> null
        }
}

/** A parsed GitHub release. */
data class AppRelease(
    val versionName: String,
    /** [versionCodeOf] of [versionName] — the only comparable signal a release publishes. */
    val versionCode: Long,
    val notes: String,
    val pageUrl: String,
    val assets: List<ReleaseAsset>,
)

enum class UpdateOs(val assetToken: String, val formats: List<String>) {
    Android("android", listOf(".apk")),
    Windows("windows", listOf(".msi")),
    MacOS("macos", listOf(".dmg")),
    Linux("linux", listOf(".AppImage", ".appimage", ".deb", ".rpm", ".flatpak", ".tar.gz")),
    ;

    /** Position of the asset's format in this OS's preference list, or -1 when unsupported. */
    fun formatRank(fileName: String): Int = formats.indexOfFirst { fileName.endsWith(it) }
}

enum class UpdateArch { X64, Arm64 }

/** Why an update could not be checked, downloaded or handed over. */
enum class UpdateError {
    /** The request never reached GitHub: offline, DNS, timeout. */
    NetworkError,

    /** GitHub answered 403/429 — the unauthenticated rate limit. */
    RateLimited,
    NotFound,
    ServerError,

    /** 200 with a body that carries no usable version or assets. */
    InvalidResponse,

    NotEnoughSpace,

    /** Fewer bytes landed on disk than the release declares. */
    IncompleteDownload,

    /** The container header does not match the file type its name promises. */
    CorruptArchive,

    /** The release has no build for this OS/architecture. */
    NoAssetForPlatform,

    /** iOS installs through the App Store, the web build has no filesystem. */
    UnsupportedPlatform,
}

/** The version a running build compares itself against. */
sealed interface UpdateCheck {
    data class Available(val release: AppRelease, val asset: ReleaseAsset) : UpdateCheck
    data class UpToDate(val versionName: String) : UpdateCheck
    data class Failed(val error: UpdateError) : UpdateCheck
}

/** Bytes that landed, out of how many, and how fast. */
data class DownloadProgress(
    val bytesReceived: Long,
    val totalBytes: Long,
    val bytesPerSecond: Long,
) {
    val fraction: Float
        get() = if (totalBytes <= 0L) 0f else (bytesReceived.toFloat() / totalBytes).coerceIn(0f, 1f)
}

/** What the downloader is doing right now, so the UI can follow along. */
sealed interface UpdateEvent {
    data class Progress(val progress: DownloadProgress) : UpdateEvent

    /** The bytes are on disk; the container header is being validated. Archives only. */
    data object Unpacking : UpdateEvent
}

sealed interface UpdateDownload {
    /** The file is written and verified under `updates/<fileName>` in the app files directory. */
    data class Ready(val fileName: String) : UpdateDownload
    data class Failed(val error: UpdateError) : UpdateDownload
}

/**
 * Numeric stand-in for the Android versionCode, used on both sides of the comparison.
 *
 * A GitHub release publishes only its tag (`v3.13.1`) — the APK's versionCode never leaves
 * the build. Encoding the tag into a number keeps the check honest: `3.9.0` (3_009_000)
 * sorts below `3.13.0` (3_013_000), where a string comparison would call 3.9 the winner.
 * Minor and patch are expected to stay below 1000, which the release numbering does.
 */
fun versionCodeOf(versionName: String): Long? {
    val core = versionName.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
    val parts = core.split('.')
    if (parts.size > 3) return null
    val numbers = parts.map { it.toLongOrNull() ?: return null }
    val major = numbers.getOrNull(0) ?: return null
    val minor = numbers.getOrNull(1) ?: 0L
    val patch = numbers.getOrNull(2) ?: 0L
    if (major < 0 || minor !in 0..999 || patch !in 0..999) return null
    return major * 1_000_000 + minor * 1_000 + patch
}

private val releaseJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class ReleaseDto(
    @SerialName("tag_name") val tagName: String = "",
    @SerialName("html_url") val htmlUrl: String = "",
    val body: String = "",
    val assets: List<AssetDto> = emptyList(),
)

@Serializable
private data class AssetDto(
    val name: String = "",
    @SerialName("browser_download_url") val downloadUrl: String = "",
    val size: Long = 0,
)

/** Parses a `GET /releases/latest` body; null when it carries no usable version or assets. */
fun parseRelease(body: String): AppRelease? {
    val dto = try {
        releaseJson.decodeFromString<ReleaseDto>(body)
    } catch (_: Exception) {
        return null
    }
    val versionName = dto.tagName.trim().removePrefix("v")
    val versionCode = versionCodeOf(versionName) ?: return null
    val assets = dto.assets.mapNotNull { asset ->
        asset.name.takeIf { it.isNotBlank() && asset.downloadUrl.isNotBlank() }
            ?.let { ReleaseAsset(name = it, sizeBytes = asset.size, downloadUrl = asset.downloadUrl) }
    }
    if (assets.isEmpty()) return null
    return AppRelease(
        versionName = versionName,
        versionCode = versionCode,
        notes = dto.body.trim(),
        pageUrl = dto.htmlUrl,
        assets = assets,
    )
}

/**
 * The single asset that should be installed on [os]/[arch].
 *
 * Assets built for another CPU family are dropped rather than ranked, so an arm64 host
 * never receives an x86_64 tarball; names without an architecture token (`.deb`, `.rpm`,
 * `.AppImage`, `.flatpak`, `.dmg`, `.msi`, `.apk`) are architecture-neutral as published.
 */
fun selectAsset(release: AppRelease, os: UpdateOs, arch: UpdateArch): ReleaseAsset? = release.assets
    .filter { it.os == os && os.formatRank(it.name) >= 0 }
    .filter { it.arch == null || it.arch == arch }
    .minWithOrNull(compareBy({ os.formatRank(it.name) }, { it.name }))

/** True for containers the app must open before the OS installer can use them. */
fun requiresUnpacking(fileName: String): Boolean = when {
    fileName.endsWith(".tar.gz", ignoreCase = true) -> true
    fileName.endsWith(".zip", ignoreCase = true) -> true
    fileName.endsWith(".flatpak", ignoreCase = true) -> true
    else -> false
}

/**
 * Whether the first bytes of a download match the container its name promises.
 *
 * This is the "битый архив" guard: a proxy error page or a truncated file still reports a
 * 200 and a byte count, but it never starts with the magic number of an APK, a gzip stream,
 * a deb, an rpm, an MSI or an ELF AppImage. Unknown containers are accepted as-is.
 */
fun archiveHeaderMatches(fileName: String, head: ByteArray): Boolean {
    fun startsWith(vararg bytes: Int): Boolean = head.size >= bytes.size && bytes.indices.all { head[it] == bytes[it].toByte() }

    return when {
        fileName.endsWith(".apk", ignoreCase = true) || fileName.endsWith(".zip", ignoreCase = true) ->
            startsWith(0x50, 0x4B, 0x03, 0x04) || startsWith(0x50, 0x4B, 0x05, 0x06)

        fileName.endsWith(".tar.gz", ignoreCase = true) -> startsWith(0x1F, 0x8B)

        fileName.endsWith(".deb", ignoreCase = true) -> head.size >= 8 && head.decodeToString(0, 8) == "!<arch>\n"

        fileName.endsWith(".rpm", ignoreCase = true) -> startsWith(0xED, 0xAB, 0xEE, 0xDB)

        fileName.endsWith(".msi", ignoreCase = true) -> startsWith(0xD0, 0xCF, 0x11, 0xE0)

        fileName.endsWith(".AppImage", ignoreCase = true) -> startsWith(0x7F, 0x45, 0x4C, 0x46)

        else -> true
    }
}
