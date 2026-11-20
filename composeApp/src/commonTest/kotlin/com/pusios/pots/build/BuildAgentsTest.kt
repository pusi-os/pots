package com.pusios.pots.build

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Hermes' install command is a shell script that runs on the phone, so its text is
 * the artifact — the same reason [WebPanelsTest] pins its port probe. String tests
 * on purpose, and every one of them stands for a failure that already happened on a
 * device.
 */
class BuildAgentsTest {

    private val hermes = BuildAgents.get(BuildAgents.HERMES)?.installCommand.orEmpty()

    @Test
    fun `OpenCode comes from the v2 installer, which is the one that installs 2 x`() {
        // The v1 script refuses a 2.x release outright — "This installer only
        // installs OpenCode 1" — so the branch is the whole upgrade. v2 unpacks
        // `@opencode/cli` into the same `$HOME/.opencode/bin`, so nothing else moves.
        val opencode = BuildAgents.get(BuildAgents.OPENCODE)?.installCommand.orEmpty()
        assertContains(opencode, "anomalyco/opencode/refs/heads/v2/install")
        assertFalse(opencode.contains("refs/heads/v1/install"), opencode)
        assertContains(opencode, "--no-modify-path", message = "a sandbox has no login shell to read rc files")
    }

    @Test
    fun `the link probe links between the two directories uv links between`() {
        // The probe that shipped before linked two names inside /root. That works
        // even where the real link (installs/… → cache/uv) is refused, so it
        // reported ok while the install kept dying.
        assertFalse(hermes.contains("pots-link-probe"), "single-directory probe is back")
        assertTrue(hermes.contains("cross-dir ln:"), hermes)
        assertTrue(hermes.contains("stat -c '%d %n'"), "the device ids are what settles it")
    }

    @Test
    fun `the uv wrapper replaces the store binary pm actually executes`() {
        assertTrue(hermes.contains("/uv-*/uv"), "pm runs the store entry, never `uv` from PATH")
        assertTrue(hermes.contains("uv.real"), "the real binary has to survive")
        assertTrue(hermes.contains("pots-uv-wrapper"), "a second run must recognise its own wrapper")
    }

    @Test
    fun `copy mode is injected after the subcommand, never prepended`() {
        // `uv --link-mode=copy cache prune` answers "unexpected argument", and pm
        // makes that call — plus `cache clean`, `python find` and `pip check`.
        assertTrue(hermes.contains("\"\$sub\" --link-mode=copy"), hermes)
        assertTrue(hermes.contains("--link-mode=copy --no-cache"))
        assertFalse(hermes.contains("uv.real\" --link-mode"), "flags cannot be prepended")
    }

    @Test
    fun `the wrapped entry keeps verifying, so pm cannot publish a pristine uv over it`() {
        // pm re-hashes its tool closure on every explicit install and replaces an
        // entry whose digest moved — which would undo the wrapper just before the
        // stage it exists for.
        assertTrue(hermes.contains("tree_digest"), "the digest is pm's own, not a copy of it")
        assertTrue(hermes.contains("uv digest refreshed"))
    }

    @Test
    fun `the l2s cleanup needs evidence, not the calendar`() {
        // Deleting a healthy runtime only makes the installer rebuild it for
        // nothing, and a scan that cannot run must never be read as "clean".
        assertTrue(hermes.contains("-name '.l2s.*'"), "the cleanup is gated on the artifact itself")
        assertTrue(hermes.contains("POTS_L2S=unknown"), "a failed scan counts as evidence")
        assertTrue(hermes.contains("leaving the existing runtime alone"), hermes)
    }

    @Test
    fun `facts json is rewritten the way pm rewrites it`() {
        // pm reads this file as utf-8-sig and only ever writes it atomically; a
        // plain write_text truncates first, so a killed install would leave pm a
        // half file it cannot read — the exact class of breakage this path fixes.
        assertTrue(hermes.contains("from pm.filesystem import durable_write_bytes"), hermes)
        assertTrue(hermes.contains("encoding=\"utf-8-sig\""), hermes)
        assertFalse(hermes.contains("facts.write_text"), "the non-atomic write is back")
    }

    @Test
    fun `the l2s cleanup runs once, behind a marker`() {
        assertTrue(hermes.contains(".pots-uv-copy-mode"))
        assertTrue(
            hermes.contains("rm -rf /root/.hermes/installs/*/pm-runtime /root/.hermes/cache/uv"),
            "the poisoned runtime and cache are what the retry would trip over",
        )
    }
}
