package com.pusios.pots.tools

import java.io.File

/**
 * Guest command that records the shell's own pid in the bind-mounted `/tmp`
 * before running [command].
 *
 * proot does not namespace pids, so the number written here is a host pid: it is
 * the one thing a cancel can use to end the command itself. Without it, killing
 * proot leaves the command — and the port it holds — running as an orphan.
 *
 * No `exec` on purpose: a background command is arbitrary (`a && b`, pipelines),
 * and `exec` would only apply to the first simple command. Keeping the shell as
 * the recorded process is also what makes the walk in [killProcessTree] find the
 * rest of the tree.
 */
internal fun guestPidCommand(pidFileName: String, command: String): String = "echo \$\$ > /tmp/$pidFileName; $command"

/**
 * Kills [root] and every descendant, deepest first.
 *
 * Bottom-up because a parent that is still alive can re-spawn what was just
 * killed. [childrenOf] and [kill] are parameters so the order can be tested
 * without spawning anything; production callers pass [hostChildren] and the
 * platform's kill.
 */
internal fun killProcessTree(
    root: Int,
    childrenOf: (Int) -> List<Int>,
    kill: (Int) -> Unit,
) {
    childrenOf(root).forEach { killProcessTree(it, childrenOf, kill) }
    runCatching { kill(root) }
}

/** Direct children of [parent], read from /proc — no busybox, no extra process. */
internal fun hostChildren(parent: Int): List<Int> = runCatching {
    File("/proc").listFiles()
        ?.mapNotNull { it.name.toIntOrNull() }
        ?.filter { pid -> parentOf(pid) == parent }
        .orEmpty()
}.getOrDefault(emptyList())

/**
 * True while [pid] is a live process. A zombie counts as gone: it holds no port
 * and nothing can be done about it, and it disappears as soon as it is reaped.
 */
internal fun hostProcessAlive(pid: Int): Boolean {
    val stat = File("/proc/$pid/stat")
    if (!stat.exists()) return false
    // A read error other than "gone" is not a death certificate.
    return runCatching { stat.readText().substringAfterLast(")").trim().firstOrNull() != 'Z' }.getOrDefault(true)
}

private fun parentOf(pid: Int): Int? = runCatching {
    // Format: `pid (comm) state ppid ...`; comm may hold spaces and parens, so
    // everything is parsed after the last ')'.
    val stat = File("/proc/$pid/stat").readText()
    stat.substringAfterLast(")").trim().split(' ').getOrNull(1)?.toIntOrNull()
}.getOrNull()
