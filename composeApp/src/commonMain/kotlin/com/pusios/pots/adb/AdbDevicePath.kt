// Device-side paths that may appear in a shell command. The allowlist is deliberately narrow: a
// path is spliced into `pm install -r <path>`, so anything that could start a second command, or
// walk up with `..`, is refused before the string reaches the device.

package com.pusios.pots.adb

/**
 * Characters an on-device APK path may contain. The path goes into a `pm install` shell command
 * verbatim, so anything that could start a second command — `;`, `&&`, `$()`, backticks, quotes,
 * redirects, whitespace — is refused rather than escaped.
 */
private val ADB_DEVICE_PATH_REGEX = Regex("""/[A-Za-z0-9._@+/=-]+""")

/**
 * True when [path] is an absolute path on the device that is safe to interpolate into a shell
 * command, e.g. `/sdcard/Download/app.apk`.
 *
 * `..` segments are refused as well: a path is allowed to name a file, not to climb out of the
 * directory the user was thinking about.
 */
fun isValidAdbDevicePath(path: String): Boolean {
    val text = path.trim()
    if (text.length !in 2..4096) return false
    if (!ADB_DEVICE_PATH_REGEX.matches(text)) return false
    return text.split('/').none { it == ".." }
}
