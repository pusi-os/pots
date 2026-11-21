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

    @Test
    fun `the install leaves the checkout on the commit this app's panel was built from`() {
        // install.sh pins `--commit` itself, on a fresh clone and on a rerun over an
        // existing checkout alike (install.sh:996) — but only after it walks the
        // history to prove the commit is an ancestor of the branch, and a depth-1
        // clone has no history to walk: the commit is not an object there at all.
        // That is how the install died on the phone ("commit … is not on branch
        // main"). So a pinned clone carries the commit graph, and a depth-1 checkout
        // an older build left behind is deepened before the installer looks. The
        // post-install pin below stays for the paths that do not pin.
        assertTrue(hermes.contains("/root/.hermes/pots-webapp-pin"), "the app writes the pin, the install reads it")
        val pinBranch = hermes.indexOf("if [ -n \"\$POTS_WEBAPP_PIN\" ]; then")
        val blobless = hermes.indexOf("git clone --filter=blob:none")
        val shallow = hermes.indexOf("git clone --depth 1")
        assertTrue(
            pinBranch in 0..<blobless && blobless < shallow,
            "the pinned branch is the one that clones history; --depth 1 stays for a build with no pin",
        )
        assertTrue(
            hermes.indexOf("rev-parse --is-shallow-repository") in 0..<hermes.indexOf("fetch --unshallow"),
            "an existing depth-1 checkout is what a device already has",
        )
        assertTrue(
            hermes.indexOf("fetch --unshallow") in 0..<hermes.indexOf("if ! pots_install_hermes"),
            "the checkout is deepened before the installer checks the pin, not after",
        )
        assertTrue(hermes.contains("fetch --depth 1 origin"), "a shallow clone has to fetch the pinned commit")
        assertTrue(hermes.contains("checkout --force --detach"), "the pin is a detached checkout, not a branch")
        assertTrue(hermes.contains("POTS_WEBAPP_PIN"), hermes)
    }

    @Test
    fun `the install never builds the renderer, which needs a proot call of its own`() {
        // `hermes webapp --build-only` is a pnpm install of apps/desktop, and pnpm
        // 12's staged renames answer ENOENT under the --link2symlink this proot call
        // carries for dpkg. Pots Build builds the renderer afterwards, in a proot
        // call without the emulation — the install script must not try, and must not
        // claim the bundle ships prebuilt either.
        assertFalse(hermes.contains("webapp --build-only"), hermes)
        assertFalse(hermes.contains("renderer ships with the app"), hermes)
        assertTrue(hermes.contains("POTS_WEBAPP_PIN"), "the pin still reaches the installer")
    }
}
