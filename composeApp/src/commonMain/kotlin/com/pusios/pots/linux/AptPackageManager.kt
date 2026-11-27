package com.pusios.pots.linux

import kotlin.math.abs

/**
 * `${db:Status-Abbrev}` is dpkg's two-letter want/state pair (plus a padding
 * character), which is the only way to tell a really-installed package from one
 * that was removed with its config files left behind — `dpkg-query -W` lists both.
 */
private const val DPKG_FORMAT =
    """${'$'}{db:Status-Abbrev}\t${'$'}{Package}\t${'$'}{Version}\n"""

/** `N upgraded, M newly installed, K to remove and L not upgraded.` */
private val UPGRADE_SUMMARY = Regex("""(\d+)\s+upgraded""")

/** Installed and configured. Anything else (`rc`, `iU`, …) is not usable. */
private const val STATUS_INSTALLED = "ii"

/**
 * Longest a classified reason may be. The card shows one line, and apt's own
 * error line can carry a whole URL plus a duration.
 */
private const val DETAIL_LIMIT = 300

/** apt's own words for "the index's dates do not fit this clock". */
private val DATE_CHECK_FAILURE = Regex("not valid yet|is expired|invalid for")

/** The Release file is dated in the future: the device clock is behind it. */
private val RELEASE_NOT_VALID_YET = Regex("not valid yet|invalid for \\d")

/** The Release file is older than its own `Valid-Until`. */
private val RELEASE_EXPIRED = Regex("is expired|invalid since \\d")

/** DNS, TCP or TLS never got as far as an answer. */
private val NETWORK_FAILURE = Regex(
    "Could not resolve|Temporary failure resolving|Temporary failure in name resolution|" +
        "Network is unreachable|Connection timed out|Connection failed|Failed to connect|" +
        "server certificate verification failed",
)

/** The mirror answered, but not with an index this rootfs can use. */
private val MIRROR_FAILURE = Regex(
    "does not have a Release file|404\\s+Not Found|Hash Sum mismatch|Unable to find expected entry",
)

/**
 * Why a package-manager command failed, when the reason has a name worth giving
 * the user. The installer runs outside composition and owns no wording, so it
 * reports the class and the UI turns it into a sentence.
 *
 * [detail] is the evidence, as one line: apt's own error line, or a measured
 * clock offset. It stays untranslated because it is what the tool printed.
 */
sealed interface InstallFailure {
    val detail: String

    /**
     * The same failure as one technical line, for logs and for surfaces that have
     * no wording of their own (Pots Build shows the exception message verbatim).
     * User-facing sentences live in the UI layer.
     */
    val summary: String

    /**
     * The Release file is dated in the future relative to the device — apt's
     * `not valid yet`. apt's window is a week wide, so this is not a mirror
     * being late: the phone's clock is behind.
     */
    data class ClockSkew(override val detail: String) : InstallFailure {
        override val summary: String get() = "the device clock is off by $detail"
    }

    /** The Release file's own validity window has run out (`is expired`). */
    data class ExpiredMetadata(override val detail: String) : InstallFailure {
        override val summary: String get() = "the package index is expired: $detail"
    }

    /** The mirror answered, but not with a usable index: 404, wrong release. */
    data class Mirror(override val detail: String) : InstallFailure {
        override val summary: String get() = "the mirror sent no usable index: $detail"
    }

    /** No usable connection: DNS, TCP, TLS. */
    data class Network(override val detail: String) : InstallFailure {
        override val summary: String get() = "the mirror is unreachable: $detail"
    }

    /** Anything else; [detail] is apt's last error line. */
    data class Other(override val detail: String) : InstallFailure {
        override val summary: String get() = detail
    }
}

object AptPackageManager : PackageManagerSpec {

    override val listInstalledCommand = "dpkg-query -W -f='$DPKG_FORMAT' 2>/dev/null"

    override val updateCommand = "apt-get update -y"

    /**
     * The same refresh with apt's release-date checks off, for the one case
     * where they are wrong rather than protective: a device clock that is days
     * behind the mirror's Release file. Only ever run after [updateCommand]
     * failed on a date, never instead of it — the checks catch a real replay.
     */
    val updateWithoutDateCheckCommand =
        "$updateCommand -o Acquire::Check-Date=false -o Acquire::Check-Valid-Until=false"

    /**
     * Beyond this the sandbox's clock is the problem, not the mirror. apt's
     * `Valid-Until` window is a week, so a day of drift already breaks a fresh
     * index — and anything larger cannot be the mirror being slow.
     */
    val clockSkewToleranceSeconds: Long = 24 * 60 * 60

    /**
     * A signed, unit-tagged rendering of [seconds] — `-2d 21h 19m`. Language
     * free on purpose: this is the measurement, the sentence around it is the
     * UI's.
     */
    fun formatClockOffset(seconds: Long): String {
        var rest = abs(seconds)
        val days = rest / 86_400
        rest %= 86_400
        val hours = rest / 3_600
        rest %= 3_600
        val minutes = rest / 60
        return buildString {
            append(if (seconds < 0) "-" else "+")
            if (days > 0) append("${days}d ")
            if (days > 0 || hours > 0) append("${hours}h ")
            append("${minutes}m")
        }
    }

    /** True when apt refused the index over a date, not over the network. */
    fun isDateCheckFailure(output: String): Boolean = DATE_CHECK_FAILURE.containsMatchIn(output)

    /**
     * Names the reason an apt command failed from its own output. The classes
     * follow what the user can do about it — fix the clock, wait for the mirror,
     * fix the connection — so a date failure is checked before anything that a
     * `Failed to fetch` line could also match.
     */
    fun classifyFailure(output: String): InstallFailure {
        val lines = output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        lines.firstOrNull { RELEASE_NOT_VALID_YET.containsMatchIn(it) }
            ?.let { return InstallFailure.ClockSkew(it.detailLine()) }
        lines.firstOrNull { RELEASE_EXPIRED.containsMatchIn(it) }
            ?.let { return InstallFailure.ExpiredMetadata(it.detailLine()) }
        lines.firstOrNull { NETWORK_FAILURE.containsMatchIn(it) }
            ?.let { return InstallFailure.Network(it.detailLine()) }
        lines.firstOrNull { MIRROR_FAILURE.containsMatchIn(it) }
            ?.let { return InstallFailure.Mirror(it.detailLine()) }
        val lastError = lines.lastOrNull { it.startsWith("E:") } ?: lines.lastOrNull().orEmpty()
        return InstallFailure.Other(lastError.detailLine())
    }

    override val upgradeCommand = "apt-get upgrade -y"

    /**
     * What to report once a refresh has failed both with and without the date
     * checks: a measured offset past [clockSkewToleranceSeconds] is the cause
     * whatever apt printed last, and with no measurement to go on the output has
     * to name it. Both runs are read together because the second one only repeats
     * the first's date error or replaces it with a later one.
     */
    fun indexFailure(firstOutput: String, retryOutput: String, clockOffsetSeconds: Long?): InstallFailure = clockOffsetSeconds
        ?.takeIf { abs(it) > clockSkewToleranceSeconds }
        ?.let { InstallFailure.ClockSkew(formatClockOffset(it)) }
        ?: classifyFailure("$firstOutput\n$retryOutput")

    override fun searchCommand(query: String, limit: Int): String = "apt-cache search ${shellQuote(query)} | head -n $limit"

    // --no-install-recommends keeps a phone-sized rootfs from pulling in docs,
    // X11 and systemd dependencies it can never use.
    override fun installCommand(names: List<String>): String = "apt-get install -y --no-install-recommends ${shellQuoteAll(names)}"

    override fun removeCommand(name: String): String = "apt-get remove -y ${shellQuote(name)}"

    override fun parseInstalled(raw: String): List<PackageEntry> = raw.lineSequence()
        .mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 3) return@mapNotNull null
            if (parts[0].trim() != STATUS_INSTALLED) return@mapNotNull null
            val name = parts[1].trim()
            if (name.isEmpty()) return@mapNotNull null
            PackageEntry(name, parts[2].trim())
        }
        .distinctBy { "${it.name}@${it.version}" }
        .sortedBy { it.name.lowercase() }
        .toList()

    // `apt-cache search` prints `name - short description` and no version. The
    // Packages list renders a blank version as no version at all, and the install
    // action only needs the name.
    override fun parseSearch(raw: String): List<PackageEntry> = raw.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("E:") && !it.startsWith("W:") }
        .mapNotNull { line ->
            val sepIdx = line.indexOf(" - ")
            val name = (if (sepIdx >= 0) line.substring(0, sepIdx) else line).trim()
            if (name.isEmpty() || name.contains(' ')) return@mapNotNull null
            val description = if (sepIdx >= 0) line.substring(sepIdx + 3).trim() else null
            PackageEntry(name, version = "", description = description?.takeIf { it.isNotEmpty() })
        }
        .distinctBy { it.name }
        .toList()

    // apt marks real failures with an `E:` prefix. `W:` warnings (unsigned repo,
    // missing translation index) are routine inside a proot rootfs.
    override fun hasErrors(stdout: String, stderr: String): Boolean = stdout.lineSequence().any { it.startsWith("E:") } ||
        stderr.lineSequence().any { it.startsWith("E:") }

    override fun countUpgraded(stdout: String): Int = stdout.lineSequence()
        .firstNotNullOfOrNull { line -> UPGRADE_SUMMARY.find(line)?.groupValues?.get(1)?.toIntOrNull() }
        ?: 0
}

/**
 * One line of apt output as the card shows it: severity prefix gone (the
 * sentence already says what failed) and capped, because a Release-file error
 * carries a full URL and a duration.
 */
private fun String.detailLine(): String = removePrefix("E:").removePrefix("W:").trim().take(DETAIL_LIMIT)
