package com.pusios.pots.adb

/**
 * Characters that turn one command into several, or feed a command's output back into the shell.
 *
 * A device that was connected by `host:port` without pairing is untrusted, and the agent may only
 * run read-only commands on it. Since the ADB shell service hands the command to `/system/bin/sh`,
 * a metacharacter anywhere in the string is enough to escape any allowlist — so any of these makes
 * the whole command non-read-only, even when it sits inside what looks like an argument.
 */
private val SHELL_METACHARACTERS = listOf(";", "&", "|", ">", "<", "`", "$", "\\", "\n", "\r")

/** Commands whose every invocation only reads device state, whatever their arguments are. */
private val ALWAYS_READ_ONLY = setOf(
    "getprop",
    "dumpsys",
    "df",
    "du",
    "ps",
    "id",
    "whoami",
    "uname",
    "uptime",
    "cat",
    "ls",
)

/**
 * True when [command] only reads device state, so it is safe to run on an untrusted device.
 *
 * The list is a conservative allowlist, not a blocklist of dangerous commands: a command we have
 * never seen is refused, because the cost of refusing a harmless `top` is one retry, while the cost
 * of allowing a harmful one is a wiped device. Sub-commands that flip a tool from reading to writing
 * are checked separately — `pm list` reads, `pm install` writes; `settings get` reads, `settings put`
 * writes; `logcat` alone blocks forever, `logcat -d` dumps and exits.
 */
fun isAdbShellCommandReadOnly(command: String): Boolean {
    val trimmed = command.trim()
    if (trimmed.isEmpty()) return false
    if (SHELL_METACHARACTERS.any { trimmed.contains(it) }) return false
    val tokens = trimmed.split(' ', '\t').filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return false
    val args = tokens.drop(1)
    return when (tokens.first()) {
        in ALWAYS_READ_ONLY -> true
        "logcat" -> args.any { it == "-d" || it == "--dump" }
        "pm" -> args.firstOrNull() == "list" || args.firstOrNull() == "path"
        "settings" -> args.firstOrNull() == "get"
        // `date` prints the clock with no arguments or with a +format; any other argument sets it.
        "date" -> args.isEmpty() || args.all { it.startsWith("+") }
        // Unknown programs are refused: an allowlist that guesses is not an allowlist.
        else -> false
    }
}
