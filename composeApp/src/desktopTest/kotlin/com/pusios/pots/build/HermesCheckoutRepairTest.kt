package com.pusios.pots.build

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The repair ladder that runs before `install.sh`, executed the way the device
 * runs it: the real shell functions out of the install command, against real git
 * repositories in a temp dir. No proot, no phone, no network — the clone the
 * ladder falls back to is pointed at a local repository through
 * `POTS_HERMES_REPO`, which is the only reason that variable exists.
 *
 * The state this is about is the one the device was in: `git clone` writes `.git`
 * first and the working tree last, so a run killed in between leaves `.git`
 * without files. `rev-parse HEAD` answers there, which is why a validation built
 * on it alone would pass and `install.sh` would still be missing.
 */
class HermesCheckoutRepairTest {

    private val hermes = BuildAgents.get(BuildAgents.HERMES)?.installCommand.orEmpty()

    private val sandbox: File = Files.createTempDirectory("pots-hermes-checkout").toFile()

    @AfterTest
    fun removeSandbox() {
        sandbox.deleteRecursively()
    }

    @Test
    fun `a checkout whose tree is intact is left alone`() {
        val checkout = checkoutOf(origin("whole"))
        val marker = File(checkout, "keep-me.txt").apply { writeText("the owner's file\n") }

        val (status, output) = runLadder(checkout)

        assertEquals(0, status, output)
        assertContains(output, "call1=ok")
        assertFalse(output.contains("broken"), output)
        assertEquals("the owner's file\n", marker.readText(), "a healthy checkout must not be touched")
    }

    @Test
    fun `a checkout with no working tree is repaired at HEAD`() {
        val checkout = checkoutOf(origin("treeless"))
        // Exactly what an interrupted clone leaves behind, and what the device had:
        // .git is there, HEAD resolves, the files are not.
        checkout.resolve("scripts").deleteRecursively()

        val (status, output) = runLadder(checkout)

        assertEquals(0, status, output)
        assertContains(output, "pots: hermes checkout: broken (scripts/install.sh is missing)")
        assertContains(output, "pots: hermes checkout: repaired at HEAD")
        assertContains(output, "call1=ok")
        assertTrue(File(checkout, "scripts/install.sh").isFile, "the tree was not put back")
    }

    @Test
    fun `a checkout HEAD cannot restore is re-cloned`() {
        // A HEAD that resolves but does not carry the installer: `checkout --force`
        // succeeds and restores nothing, so only a clone is left.
        val repo = origin("old", withInstaller = false)
        val withoutInstaller = revParse(repo, "HEAD")
        File(repo, "scripts").mkdirs()
        File(repo, "scripts/install.sh").writeText("#!/bin/sh\necho installed\n")
        git(repo, "add", ".")
        git(repo, "commit", "--quiet", "-m", "add the installer")
        val checkout = checkoutOf(repo, shallow = false)
        git(checkout, "checkout", "--quiet", "--force", "--detach", withoutInstaller)

        val (status, output) = runLadder(checkout)

        assertEquals(0, status, output)
        assertContains(output, "pots: hermes checkout: broken (scripts/install.sh is missing)")
        assertContains(output, "pots: hermes checkout: re-cloned")
        assertContains(output, "call1=ok")
        assertTrue(File(checkout, "scripts/install.sh").isFile, "the re-clone did not land")
    }

    @Test
    fun `a checkout whose HEAD does not resolve is re-cloned`() {
        // A clone that died before its first fetch: .git exists, there is nothing to
        // check out, and `git reset --hard` would answer 0 and change nothing.
        val checkout = File(sandbox, "empty").apply { mkdirs() }
        git(checkout, "init", "--quiet")

        val (status, output) = runLadder(checkout)

        assertEquals(0, status, output)
        assertContains(output, "pots: hermes checkout: broken (HEAD does not resolve)")
        assertContains(output, "pots: hermes checkout: re-cloned")
        assertContains(output, "call1=ok")
        assertTrue(File(checkout, "scripts/install.sh").isFile, "the re-clone did not land")
    }

    @Test
    fun `a checkout that cannot be re-cloned names the cause and is not retried`() {
        val checkout = File(sandbox, "gone").apply { mkdirs() }

        val (status, output) = runLadder(checkout, repo = File(sandbox, "no-such-origin"), calls = 2)

        assertEquals(0, status, output)
        assertContains(output, "pots: hermes checkout: broken (no .git)")
        // "Could not install Hermes" on its own says nothing; the owner reads this on
        // a phone, so the log names the reason. Free space decides the wording, hence
        // either line counts here.
        val named = output.contains("did not finish (network?)") ||
            output.contains("not enough free space on /root")
        assertTrue(named, output)
        assertContains(output, "call1=failed")
        // A second call must not download the 5 GiB checkout again to fail the same
        // way: the ladder records that it gave up.
        assertContains(output, "call2=failed")
        assertContains(output, "pots: hermes checkout: broken (the repair failed)")
    }

    @Test
    fun `a re-clone is one commit deep, so nothing has to walk the history`() {
        // The install follows `main` and pins nothing, so the checkout carries one
        // commit and nothing in the install looks further back. The repair ladder
        // re-clones with the same rule as the first clone — that is the whole point
        // of keeping the rule in one function.
        val repo = origin("deep")
        File(repo, "second.txt").writeText("another commit\n")
        git(repo, "add", ".")
        git(repo, "commit", "--quiet", "-m", "second")
        val checkout = File(sandbox, "shallow-checkout").apply { mkdirs() }

        val (status, output) = runLadder(checkout)

        assertEquals(0, status, output)
        assertContains(output, "pots: hermes checkout: re-cloned")
        assertEquals("true", revParse(checkout, "--is-shallow-repository"), "the re-clone is not shallow")
        assertEquals(1, File(checkout, ".git/shallow").readLines().count(), "more than one commit was fetched")
    }

    /** The shell function [name] out of the install command, as the device runs it. */
    private fun shellFunction(name: String): String {
        val start = hermes.indexOf("$name() {")
        assertTrue(start >= 0, "the install script has no $name")
        val end = hermes.indexOf("\n}\n", start)
        assertTrue(end > start, "$name has no closing brace at column 0")
        return hermes.substring(start, end + 3)
    }

    /**
     * Runs the ladder against [checkout]. [repo] is what a re-clone points at, so a
     * path that does not exist stands for a clone that cannot finish. [calls] above
     * one exercises the second validation the install command's retry makes.
     */
    private fun runLadder(checkout: File, repo: File = origin("origin"), calls: Int = 1): Pair<Int, String> {
        val script = buildString {
            appendLine("set -e")
            appendLine("POTS_CHECKOUT_DEAD=''")
            for (function in ladderFunctions) appendLine(shellFunction(function))
            for (call in 1..calls) {
                appendLine(
                    "if pots_ensure_hermes_checkout ${quote(checkout.absolutePath)}; then echo 'call$call=ok'; else echo 'call$call=failed'; fi",
                )
            }
            appendLine("echo 'ladder=done'")
        }
        val process = ProcessBuilder("/bin/bash", "-c", script)
            .apply {
                environment()["POTS_HERMES_REPO"] = "file://${repo.absolutePath}"
                environment()["POTS_CHECKOUT_DEAD"] = ""
            }
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        return process.waitFor() to output
    }

    /** An origin repository with one commit; `scripts/install.sh` is in it unless told otherwise. */
    private fun origin(name: String, withInstaller: Boolean = true): File {
        val repo = File(sandbox, "$name-origin").apply { mkdirs() }
        // `main`, because that is the branch the install clones and hands to
        // `install.sh --channel main`. A runner whose git defaults to `master`
        // would otherwise make the re-clone fail for the wrong reason.
        git(repo, "init", "--quiet", "-b", "main")
        File(repo, "README.md").writeText("hermes-agent\n")
        if (withInstaller) {
            File(repo, "scripts").mkdirs()
            File(repo, "scripts/install.sh").writeText("#!/bin/sh\necho installed\n")
        }
        git(repo, "add", ".")
        git(repo, "commit", "--quiet", "-m", "initial")
        return repo
    }

    /** A clone of [repo], named after it so one test cannot see another's. */
    private fun checkoutOf(repo: File, shallow: Boolean = true): File {
        val target = File(sandbox, "${repo.name}-checkout")
        val args = listOf("clone", "--quiet") +
            (if (shallow) listOf("--depth", "1") else emptyList()) +
            listOf("file://${repo.absolutePath}", target.absolutePath)
        git(sandbox, *args.toTypedArray())
        return target
    }

    private fun revParse(repo: File, rev: String): String {
        val process = ProcessBuilder("git", "-C", repo.absolutePath, "rev-parse", rev)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        assertEquals(0, process.waitFor(), "git rev-parse $rev failed: $output")
        return output
    }

    private fun git(dir: File, vararg args: String) {
        val process = ProcessBuilder(listOf("git", "-C", dir.absolutePath) + args)
            .apply {
                // A sandbox and a CI runner have no git identity configured, and a
                // commit without one fails.
                environment()["GIT_AUTHOR_NAME"] = "pots"
                environment()["GIT_AUTHOR_EMAIL"] = "pots@example.com"
                environment()["GIT_COMMITTER_NAME"] = "pots"
                environment()["GIT_COMMITTER_EMAIL"] = "pots@example.com"
            }
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), "git ${args.joinToString(" ")} failed: $output")
    }

    /** One path, one shell word — a temp dir may hold anything. */
    private fun quote(path: String): String = "'" + path.replace("'", "'\\''") + "'"

    /** The ladder's functions, in the order the install command defines them. */
    private val ladderFunctions = listOf(
        "pots_clone_hermes",
        "pots_checkout_usable",
        "pots_ensure_hermes_checkout",
    )
}
