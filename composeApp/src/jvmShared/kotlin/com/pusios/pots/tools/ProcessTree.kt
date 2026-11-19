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
 * Kills [root] and every descendant, deepest first, and answers with every pid
 * it tried to end.
 *
 * Bottom-up because a parent that is still alive can re-spawn what was just
 * killed. [childrenOf] and [kill] are parameters so the order can be tested
 * without spawning anything; production callers pass [hostChildren] and the
 * platform's kill.
 *
 * The returned pids are what a cancel has to wait for: a signal is delivered
 * asynchronously, so the tree is not gone the instant this returns.
 */
internal fun killProcessTree(
    root: Int,
    childrenOf: (Int) -> List<Int>,
    kill: (Int) -> Unit,
): List<Int> {
    val killed = mutableListOf<Int>()
    fun walk(pid: Int) {
        childrenOf(pid).forEach { walk(it) }
        // A pid that vanishes mid-walk still gets its attempt recorded, so the
        // caller waits on the whole tree rather than on a subset.
        runCatching { kill(pid) }
        killed += pid
    }
    walk(root)
    return killed
}

/**
 * Whether a cancel actually ended everything it killed.
 *
 * Every pid in [killedPids] has to be gone, not just proot: proot is lighter and
 * dies first, while a SIGKILLed child can still be readable in /proc for a
 * moment — answering "dead" on proot alone is what made the next start fail with
 * the port still taken.
 *
 * With no recorded tree (an older session, a PTY that died before writing a pid
 * file) only proot can be watched, which is the old, weaker guarantee.
 */
internal fun guestTreeIsDead(
    killedPids: List<Int>,
    prootAlive: Boolean,
    guestPid: Int?,
    isAlive: (Int) -> Boolean,
): Boolean {
    killedPids.forEach { if (isAlive(it)) return false }
    if (!prootAlive) return true
    return guestPid != null && !isAlive(guestPid)
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
