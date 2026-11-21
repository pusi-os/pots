package com.pusios.pots.build

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
    fun `Hermes installs in two proot calls, and only the prerequisite has the emulation`() {
        // One proot call cannot be both: dpkg needs `--link2symlink`, and uv answers
        // EPERM on the `.l2s.` artifacts that emulation leaves behind. So the single
        // package-manager step goes in a call of its own, with the emulation, and
        // everything else — clone, install.sh, the tags — runs without it.
        val calls = BuildAgents.installCalls(BuildAgents.get(BuildAgents.HERMES)!!)

        assertEquals(2, calls.size, "the prerequisite and the install: $calls")
        val (prereq, install) = calls
        assertTrue(prereq.hardlinkEmulation, "apt/dpkg needs the emulation")
        assertContains(prereq.command, "apt-get install -y --no-install-recommends xz-utils")
        assertFalse(prereq.command.contains("install.sh"), "the prerequisite is dpkg and nothing else")
        assertFalse(install.hardlinkEmulation, "uv must run without it")
        assertContains(install.command, "scripts/install.sh --non-interactive")
        assertFalse(install.command.contains("apt-get"), "dpkg belongs to the prerequisite")
    }

    @Test
    fun `an agent that needs no package manager stays one proot call`() {
        for (id in listOf(BuildAgents.OPENCODE, BuildAgents.PI)) {
            val agent = BuildAgents.get(id)!!
            assertNull(agent.prereqCommand, "$id declares a prerequisite it does not have")
            val calls = BuildAgents.installCalls(agent)
            assertEquals(1, calls.size, "$id must install in one call, as it always has")
            assertTrue(calls.single().hardlinkEmulation, "$id keeps the emulation it has always had")
            assertTrue(agent.debrisPaths.isEmpty(), "$id clears nothing: it never ran dpkg")
        }
    }

    @Test
    fun `the install script carries no uv workaround`() {
        // Every one of these was a lever pulled at the wrong stage: pm strips every
        // UV_* variable, builds its runtime with --no-config, and fills its cache
        // with a hardlink `--link-mode` does not reach. The fix is the proot call,
        // so the levers are gone rather than disabled.
        assertFalse(hermes.contains("UV_LINK_MODE"), "the environment lever is back")
        assertFalse(hermes.contains("UV_NO_CACHE"), hermes)
        assertFalse(hermes.contains("uv.toml"), "the config-file lever is back")
        assertFalse(hermes.contains("pots_wrap_uv"), "the wrapper is back")
        assertFalse(hermes.contains("uv.real"), hermes)
        assertFalse(hermes.contains("POTS_UV_MARK"), "the one-time marker is back")
        assertFalse(hermes.contains(".pots-uv-copy-mode"), hermes)
        assertFalse(hermes.contains("tree_digest"), "pm's digest is rewritten again")
        assertFalse(hermes.contains("link probe"), "the diagnosis of a failure that is gone is back")
        assertFalse(hermes.contains("cross-dir ln"), hermes)
        assertFalse(hermes.contains("-name '.l2s.*'"), "the scan is back: the tree is cleared instead")
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
        val call = hermes.indexOf("pots_ensure_hermes_checkout /root/.hermes/hermes-agent")
        val install = hermes.indexOf("scripts/install.sh --non-interactive")
        assertTrue(definition >= 0, "the install script validates no checkout")
        assertTrue(call > definition, "the ladder is defined before it is called")
        assertTrue(call < install, "the checkout is validated before the installer reads it")
        // One call: the second one existed for the uv wrapper's retry, and both are gone.
        assertEquals(
            1,
            hermes.split("pots_ensure_hermes_checkout /root/.hermes/hermes-agent").size - 1,
            "the install validates the checkout more than once",
        )
    }

    @Test
    fun `the install never builds the renderer, which needs a proot call of its own`() {
        // `hermes webapp --build-only` is a pnpm install of apps/desktop, and pnpm
        // 12's staged renames answer ENOENT under proot's hardlink emulation. Pots
        // Build builds the renderer afterwards, in a proot call without it — the
        // install script must not try, and must not claim the bundle ships prebuilt.
        assertFalse(hermes.contains("webapp --build-only"), hermes)
        assertFalse(hermes.contains("renderer ships with the app"), hermes)
        assertTrue(hermes.contains("refs/tags/*:refs/tags/*"), "the version still comes from the tags")
    }
}
