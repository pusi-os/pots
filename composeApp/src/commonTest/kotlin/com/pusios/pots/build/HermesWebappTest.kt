package com.pusios.pots.build

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The renderer chain, as the device runs it: which attempt comes first, what each
 * one may not carry, and what the log says when one of them wins.
 *
 * These are the facts the owner reads back from an install log on a phone, so they
 * are pinned here rather than discovered again per run.
 */
class HermesWebappTest {

    @Test
    fun `the device builds first, and never with the emulation that breaks pnpm`() {
        // pnpm 12 renames a staged directory into place for every package, and that
        // rename answers ENOENT under proot's --link2symlink — the flag dpkg needs
        // for the base packages, which is why the build is a proot call of its own.
        assertEquals(listOf(1, 2), HermesWebapp.buildAttempts.map { it.number })
        assertTrue(HermesWebapp.buildAttempts.none { it.hardlinkEmulation }, "the emulation is the failure")

        // pnpm 12 reads settings as pnpm_config_* from the environment only: pacquet
        // ignores npm_config_* (so a $HERMES_HOME/npmrc hook cannot carry them), and
        // its npmrc is for auth, registry and TLS. Nothing else may be handed over.
        val env = HermesWebapp.buildAttempts.flatMap { it.env.keys }
        assertTrue(env.all { it.startsWith("pnpm_config_") }, env.toString())
        assertEquals("copy", HermesWebapp.buildAttempts.first().env["pnpm_config_package_import_method"])
        assertTrue(
            HermesWebapp.buildAttempts.last().env.isEmpty(),
            "the second attempt drops the import method, so a failure says whether it mattered",
        )
    }

    @Test
    fun `every renderer path writes its own line to the install log`() {
        val lines = HermesWebapp.buildAttempts.map { HermesWebapp.builtOnDevice(it) } +
            HermesWebapp.SHIPPED + HermesWebapp.NOT_BUILT

        assertEquals(lines.size, lines.toSet().size, "the owner must be able to tell the paths apart: $lines")
        assertEquals("renderer: built on device (attempt 1, no link2symlink)", lines.first())
        assertTrue(lines.contains("renderer: shipped bundle"))
    }

    @Test
    fun `the build runs from the pinned checkout, against a store the device owns`() {
        assertTrue(HermesWebapp.BUILD_COMMAND.startsWith("/root/.local/bin/hermes webapp --build-only"))
        // --force-build: a half-written bundle from a failed attempt must not read
        // as "up to date" and skip the build entirely.
        assertTrue(HermesWebapp.BUILD_COMMAND.contains("--force-build"))

        // Hermes forces pnpm's store to $HERMES_ROOT/cache/pnpm itself
        // (pm/packages.py overwrites pnpm_config_store_dir), so the only hygiene
        // left is deleting it — a store a failed attempt left is made of .l2s.
        // symlinks and the next build dies in rolldown against them.
        assertTrue(HermesWebapp.CLEAN_COMMAND.contains("/root/.hermes/cache/pnpm/store"))
        assertTrue(HermesWebapp.CLEAN_COMMAND.contains("/root/.hermes/hermes-agent/.build/webapp-workspace/node_modules"))
    }
}
