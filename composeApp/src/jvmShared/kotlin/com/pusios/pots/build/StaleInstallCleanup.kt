package com.pusios.pots.build

import java.io.File

// Host-side sweep of the debris a failed plugin install leaves behind. While a
// panel ran under proot's hardlink emulation (it no longer does — see
// WebPanels.PANEL_HARDLINK_EMULATION), a `git clone` install died inside proot's
// link2symlink interception, and the TemporaryDirectory cleanup could not delete
// the `.l2s.*` artifacts the emulation had itself created: the
// `.install-<rand>` tree stayed forever, and every retry added another one.
// From the host side these are ordinary files the app owns, so the sweep runs
// before the panel starts, on real files — no proot, no shell.

/**
 * Exactly the names Python's tempfile gives its `mkdtemp` results: `.install-`
 * plus 8 characters of `[a-z0-9_]`. `.install-metadata.json` and
 * `.install-metadata.json.lock` — the plugin pins, user data — do not match,
 * and neither does any real plugin directory. Only directories are taken.
 */
private val STALE_INSTALL_DIR = Regex("""^\.install-[a-z0-9_]{8}$""")

/**
 * Deletes the stale `.install-<rand>` directories in each of [dirs] and returns
 * how many went. Idempotent, and silent when there is nothing to sweep: the
 * caller logs only what actually went.
 */
fun cleanStaleInstallDirs(vararg dirs: File): Int =
    dirs.flatMap { dir -> dir.listFiles().orEmpty().toList() }
        .filter { it.isDirectory && STALE_INSTALL_DIR.matches(it.name) }
        .count { it.deleteRecursively() }
