package com.pusios.pots.adb

/** Longest package id the platform accepts; anything longer cannot name an installed package. */
private const val MAX_PACKAGE_NAME_LENGTH = 255

/**
 * True when [packageName] is a plain Android package id: letters, digits, dots and underscores.
 *
 * A package name reaches the device through `pm uninstall <name>`, i.e. through `/system/bin/sh`, so
 * a name carrying a separator or a metacharacter would run a second command — `uninstall` is the
 * one call in the core that takes free text and must not widen the read-only policy. The check is a
 * narrow character set rather than a "no semicolons" blocklist, for the same reason the classifier
 * refuses unknown programs: a blocklist that guesses is not a blocklist.
 */
fun isValidAdbPackageName(packageName: String): Boolean {
    if (packageName.isEmpty() || packageName.length > MAX_PACKAGE_NAME_LENGTH) return false
    return packageName.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '.' || it == '_' }
}
