package com.pusios.pots.build

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

// Cleaning before an install: a previous run that stopped halfway leaves paths
// behind, and the next installer trips over them. The order is fixed by the card —
// find, say so, take it, then install — and every delete passes the keepPaths
// guard, so the owner's config, state and board survive a cleanup that was
// pointed at their parent.
//
// Host side, on a real rootfs: the guest answers nothing here (no binary, no
// shell), so a decision that "looks right" is only checked by running it against
// files on disk.

/**
 * Removes what a previous install of [spec] left behind and returns the lines the
 * install log gets, in the order the owner reads them: what was found, what was
 * refused, the disk it was found on, what went.
 *
 * [binaryAnswers] is the spec's own `--version` probe. A binary that answers means
 * the paths are the installation and nothing is touched: a reinstall is an update,
 * and an update must not begin by deleting what it updates. A binary that does not
 * answer while the paths are still there is the debris of a run that stopped
 * halfway.
 *
 * [disk] is asked only when there is something to delete — the free space is the
 * likeliest reason the previous run stopped, so it belongs next to the cleanup
 * that reacts to it.
 *
 * Only [AgentVersionSpec.installPaths] is considered. Hermes' 5 GiB checkout is
 * its [AgentVersionSpec.bulkPath], and a broken one is repaired by the installer
 * that follows rather than downloaded again.
 */
fun cleanLeftovers(
    rootfsDir: File,
    spec: AgentVersionSpec,
    binaryAnswers: Boolean,
    disk: () -> String,
): List<String> {
    // NOFOLLOW_LINKS: a link into a tree that is already gone still has to be
    // found, and `File.exists()` follows the link and answers no.
    val existing = spec.installPaths
        .filter { Files.exists(File(rootfsDir, it.trimStart('/')).toPath(), LinkOption.NOFOLLOW_LINKS) }
        .toSet()
    val plan = AgentVersions.cleanupPlan(spec, existing, binaryAnswers)
    if (plan.found.isEmpty()) return listOf(AgentVersions.nothingToClean(spec))
    val lines = mutableListOf(AgentVersions.leftoversFound(spec, plan.found))
    for (path in plan.refused) lines += AgentVersions.refusedToRemove(spec, path)
    if (plan.remove.isEmpty()) return lines
    lines += "pots: ${spec.id}: df -h /root | tail -1 → ${disk().trim()}"
    val removed = plan.remove.map { deleteGuestPath(rootfsDir, it) }
    // A path that would not go is not "cleaned" — its own line says why.
    for (path in removed.filter { it.outcome !is RemovedOutcome.Deleted }) {
        lines += AgentVersions.removalLine(path)
    }
    lines += AgentVersions.cleaned(spec, removed.filter { it.outcome is RemovedOutcome.Deleted }.map { it.path })
    return lines
}
