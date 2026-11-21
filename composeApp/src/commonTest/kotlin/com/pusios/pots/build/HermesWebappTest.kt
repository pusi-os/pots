package com.pusios.pots.build

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun `the device builds first, and only one attempt keeps the emulation`() {
        // pnpm 12 renames a staged directory into place for every package, and that
        // rename answers ENOENT under proot's --link2symlink — the flag dpkg needs
        // for the base packages, which is why the build is a proot call of its own.
        assertEquals(listOf(1, 2, 3), HermesWebapp.buildAttempts.map { it.number })
        assertFalse(HermesWebapp.buildAttempts.first().hardlinkEmulation, "the emulation is the failure")

        // The installer wrote the checkout, the venv and the pm tools under the
        // emulation, so exactly one attempt runs in that tree as it was left —
        // the only way the device answers whether the .l2s. artifacts break it.
        val emulated = HermesWebapp.buildAttempts.filter { it.hardlinkEmulation }
        assertEquals(listOf(2), emulated.map { it.number }, "one probe of the mixed tree")
        assertEquals("copy", emulated.single().env["pnpm_config_package_import_method"])
        assertTrue(
            HermesWebapp.buildAttempts.last().hardlinkEmulation.not(),
            "the last attempt is the cheap fallback, not the emulated one",
        )

        // pnpm 12 reads settings as pnpm_config_* from the environment only: pacquet
        // ignores npm_config_* (so a $HERMES_HOME/npmrc hook cannot carry them), and
        // its npmrc is for auth, registry and TLS. Nothing else may be handed over.
        val env = HermesWebapp.buildAttempts.flatMap { it.env.keys }
        assertTrue(env.all { it.startsWith("pnpm_config_") }, env.toString())
        assertEquals("copy", HermesWebapp.buildAttempts.first().env["pnpm_config_package_import_method"])
        assertTrue(
            HermesWebapp.buildAttempts.last().env.isEmpty(),
            "the last attempt drops the import method, so a failure says whether it mattered",
        )
    }

    @Test
    fun `every renderer path writes its own line to the install log`() {
        val lines = HermesWebapp.buildAttempts.map { HermesWebapp.builtOnDevice(it) } +
            HermesWebapp.buildAttempts.map { HermesWebapp.builtNothing(it) } +
            HermesWebapp.SHIPPED + HermesWebapp.NOT_BUILT

        assertEquals(lines.size, lines.toSet().size, "the owner must be able to tell the paths apart: $lines")
        assertEquals("renderer: built on device (attempt 1, no link2symlink)", lines.first())
        assertEquals(
            "renderer: built on device (attempt 2, link2symlink, copy import)",
            lines[1],
            "the emulated probe names itself, so a log line says which tree won",
        )
        assertTrue(lines.contains("renderer: shipped bundle"))
        assertTrue(lines.contains("renderer: not built and this app ships no bundle"))
    }

    @Test
    fun `an up-to-date answer is not a build`() {
        // `--force-build` makes this impossible, so seeing it means the flag never
        // reached Hermes: the bundle on disk belongs to an earlier run and must not
        // be reported as this attempt's build.
        assertEquals("Hermes Webapp renderer is up to date", HermesWebapp.UP_TO_DATE)
        val said = HermesWebapp.builtNothing(HermesWebapp.buildAttempts.first())
        assertTrue(said.contains("'up to date'"), said)
        assertTrue(said.contains("--force-build"), said)
    }

    @Test
    fun `a build counts only with the page, the assets and the pinned commit`() {
        val pin = "caafe5380123456789abcdef0123456789abcdef"

        // The page alone is not the bundle: a build that stopped after writing
        // index.html opens a blank screen. Every clause below is shipped to the
        // device verbatim, so the string is the contract, not a snapshot.
        assertTrue(
            HermesWebapp.built(pin).startsWith("test -f ${WebPanels.HERMES_BUNDLE} && "),
            HermesWebapp.built(pin),
        )
        assertTrue(
            HermesWebapp.built(pin).contains(
                "test -n \"\$(ls -A ${WebPanels.HERMES_BUNDLE_DIR}/assets 2>/dev/null)\"",
            ),
            "an empty assets/ is a blank screen, not a build: ${HermesWebapp.built(pin)}",
        )

        // `hermes webapp --build-only` writes no stamp, so the checkout's own
        // commit is what says which Hermes the bundle belongs to.
        assertTrue(
            HermesWebapp.built(pin).endsWith(
                " && test \"\$(git -C ${WebPanels.HERMES_CHECKOUT} rev-parse HEAD)\" = $pin",
            ),
            HermesWebapp.built(pin),
        )

        // Both answers are good enough to serve. Dropping the built half leaves a
        // developer build (no shipped archive) with no panel at all.
        assertEquals(
            "(${HermesWebapp.shipped(pin)}) || (${HermesWebapp.built(pin)})",
            HermesWebapp.check(pin),
        )
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
