// Two pieces of text hygiene for the ADB tools: masking credential-looking values in the command
// shown to the owner, and cutting device output to its last few kilobytes with an honest marker.

package com.pusios.pots.adb

/** Keys whose value is a secret even when it arrives inside a shell command or an install URL. */
private val SECRET_ASSIGNMENT = Regex(
    """(?i)\b(token|password|passwd|secret|api[_-]?key|auth[_-]?key|key)(\s*[=:]\s*)(\S+)""",
)

/**
 * Masks the values that look like credentials in [text], for the approval dialog.
 *
 * The owner has to see *what* the agent wants to run, not the secret it wants to run it with: a
 * command like `curl -H "Authorization: Bearer <token>"` sits in the summary for as long as the
 * dialog is open, and a screenshot of that dialog must not be a credential leak.
 */
fun maskAdbSecrets(text: String): String = SECRET_ASSIGNMENT.replace(text) { match ->
    "${match.groupValues[1]}${match.groupValues[2]}***"
}

/** Marker the truncated shell output starts with, so the model knows what it is missing. */
private const val TRUNCATION_MARKER_PREFIX = "[output truncated: showing the last "

/**
 * Keeps the last [limitBytes] of UTF-8 output and says so at the top.
 *
 * Device commands cheerfully return megabytes (`dumpsys`, `logcat -d`, a package list), and the
 * tool result has to fit in a context window. The tail is what matters: errors and the end of a
 * log come last. A multi-byte character cut in half at the boundary is dropped rather than
 * decoded into a replacement glyph.
 */
fun truncateAdbShellOutput(output: String, limitBytes: Int = 8 * 1024): String {
    val bytes = output.encodeToByteArray()
    if (bytes.size <= limitBytes) return output
    var start = bytes.size - limitBytes
    // UTF-8 continuation bytes are 10xxxxxx; skip them so decoding starts on a character boundary.
    while (start < bytes.size && (bytes[start].toInt() and 0xC0) == 0x80) start++
    val tail = bytes.copyOfRange(start, bytes.size).decodeToString()
    val shownKb = (bytes.size - start) / 1024
    return "$TRUNCATION_MARKER_PREFIX$shownKb KB of ${bytes.size / 1024} KB]\n$tail"
}
