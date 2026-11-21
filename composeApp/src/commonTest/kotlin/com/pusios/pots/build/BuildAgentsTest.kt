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
    fun `the install follows main, one commit deep, and pins nothing`() {
        // The owner's rule: the native installer, the latest Hermes. A pinned
        // install walks the history to prove the commit is an ancestor of the
        // branch — history a depth-1 clone has not got — and it leaves the sandbox
        // a release behind the renderer it could build for itself. The commit
        // `hermes-webapp.pin` names is the fallback's address (HermesWebapp.
        // pinCheckout), not something this install reads.
        assertTrue(hermes.contains("git clone --depth 1 --branch main"), hermes)
        assertFalse(hermes.contains("git clone --filter"), "the blobless clone is back")
        assertFalse(hermes.contains("--unshallow"), "the deepening existed only for the pin")
        assertFalse(hermes.contains("merge-base"), hermes)
        assertFalse(hermes.contains("POTS_WEBAPP_PIN"), "the install pins nothing")
        assertFalse(hermes.contains("--commit"), hermes)
        assertTrue(
            hermes.contains(
                "scripts/install.sh --non-interactive --skip-browser --skip-computer-use --channel main",
            ),
            "the installer's own channel, unconditionally",
        )
    }

    @Test
    fun `the release tags are fetched on every install, so the version is a release`() {
        // `hermes --version` names the newest release tag reachable from HEAD, and a
        // depth-1 clone carries no tags at all: without this fetch the agent row
        // reads `git.<sha>` where the release number belongs. It is unconditional —
        // the pin used to guard it, and the pin is gone.
        assertTrue(hermes.contains("'+refs/tags/*:refs/tags/*'"), hermes)
        assertTrue(hermes.contains("--filter=blob:none"), "ref-only: no file contents")
        // After the installer, which is what makes the tags worth having.
        assertTrue(
            hermes.indexOf("refs/tags/*:refs/tags/*") > hermes.indexOf("scripts/install.sh --non-interactive"),
            "the tags are fetched once the checkout is installed",
        )
    }

    @Test
    fun `a checkout left without a tree is repaired before the installer runs`() {
        // `git clone` creates `.git` first and the working tree last, so a run killed
        // in between leaves `.git` with no files. The next run saw `.git`, skipped the
        // clone, and died on a `scripts/install.sh` that was not there. Validation is
        // judged by HEAD and by install.sh itself, never by an exit code: `git reset
        // --hard` answers 0 on a HEAD that does not resolve.
        val definition = hermes.indexOf("pots_ensure_hermes_checkout() {")
        val firstCall = hermes.indexOf("pots_ensure_hermes_checkout /root/.hermes/hermes-agent")
        val wrapper = hermes.indexOf("\npots_wrap_uv\n")
        assertTrue(definition >= 0, "the install script validates no checkout")
        assertTrue(
            firstCall > definition && firstCall < wrapper,
            "the checkout is validated after the uv wrapper, which imports pm from it",
        )
        // The retry reaches pots_install_hermes again, and must not repeat a failure it
        // cannot fix — the same validation runs at its top.
        val install = hermes.indexOf("pots_install_hermes() {")
        val retried = hermes.indexOf("pots_ensure_hermes_checkout /root/.hermes/hermes-agent", install)
        assertTrue(
            retried > install && retried < hermes.indexOf("scripts/install.sh --non-interactive"),
            "the retry does not re-validate the checkout",
        )
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
        assertTrue(hermes.contains("refs/tags/*:refs/tags/*"), "the version still comes from the tags")
    }
}
