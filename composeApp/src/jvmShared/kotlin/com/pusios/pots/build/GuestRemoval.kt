package com.pusios.pots.build

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Deletes a guest path from the host side. The rootfs is a plain directory, so no
 * proot round-trip is needed — and a round-trip is exactly what fails on a
 * symlink whose target resolves only inside the guest.
 *
 * It reports what happened rather than assuming it: a path that was not there and
 * a path that would not go are different lines in the removal log, and the bytes
 * a tree freed are the ones this walk actually took, not the size it had before.
 */
fun deleteGuestPath(rootfsDir: File, guestPath: String): RemovedPath =
    RemovedPath(guestPath, deleteTree(File(rootfsDir, guestPath.trimStart('/'))))

/**
 * Deletes [target] and its contents without following symlinks — a guest tree can
 * hold links back into the rootfs, and a walk that followed one would delete the
 * Debian install instead. Every file it does take is counted into the outcome;
 * the first failure that stops it is what the log reports.
 */
fun deleteTree(target: File): RemovedOutcome {
    val path = target.toPath()
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return RemovedOutcome.Absent
    if (Files.isSymbolicLink(path)) {
        return runCatching { Files.delete(path) }
            .fold(
                onSuccess = { RemovedOutcome.Deleted(bytes = null) },
                onFailure = { RemovedOutcome.Failed(it.describe()) },
            )
    }

    var freed = 0L
    val failures = mutableListOf<String>()
    runCatching {
        Files.walkFileTree(
            path,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    try {
                        if (Files.deleteIfExists(file) && attrs.isRegularFile) freed += attrs.size()
                    } catch (e: IOException) {
                        failures += "${file.fileName}: ${e.describe()}"
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    failures += "${file.fileName}: ${exc.describe()}"
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (exc != null) failures += "${dir.fileName}: ${exc.describe()}"
                    try {
                        Files.deleteIfExists(dir)
                    } catch (e: IOException) {
                        failures += "${dir.fileName}: ${e.describe()}"
                    }
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }.onFailure { failures += it.describe() }

    val stillThere = Files.exists(path, LinkOption.NOFOLLOW_LINKS)
    return if (failures.isEmpty() && !stillThere) {
        RemovedOutcome.Deleted(freed)
    } else {
        RemovedOutcome.Failed(reason = failures.describeAll(), freed = freed)
    }
}

/**
 * Total size of the regular files under [dir], without following symlinks — an
 * LXC rootfs contains link loops that would trap a naive recursive walk.
 */
fun directorySize(dir: File): Long {
    if (!dir.exists()) return 0L
    var total = 0L
    runCatching {
        Files.walkFileTree(
            dir.toPath(),
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile) total += attrs.size()
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                    FileVisitResult.CONTINUE
            },
        )
    }
    return total
}

private fun Throwable.describe(): String = message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

/** The first failure names the cause; the rest are a count, not another wall of text. */
private fun List<String>.describeAll(): String = when {
    isEmpty() -> "каталог остался"
    size == 1 -> single()
    else -> "${first()} (и ещё ${size - 1})"
}
