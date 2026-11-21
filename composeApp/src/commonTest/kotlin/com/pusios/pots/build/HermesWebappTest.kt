package com.pusios.pots.build

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
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
            HermesWebapp.SHIPPED + HermesWebapp.NOT_BUILT + HermesWebapp.BUDGET_SPENT +
            HermesWebapp.fallingBack(PIN)

        assertEquals(lines.size, lines.toSet().size, "the owner must be able to tell the paths apart: $lines")
        assertEquals("renderer: built on device (attempt 1, no link2symlink)", lines.first())
        assertEquals(
            "renderer: built on device (attempt 2, link2symlink, copy import)",
            lines[1],
            "the emulated probe names itself, so a log line says which tree won",
        )
        assertTrue(lines.contains("renderer: shipped bundle"))
        assertTrue(lines.contains("renderer: not built and this app ships no bundle"))
        assertTrue(
            lines.contains("renderer: device build budget spent, serving the shipped bundle"),
            "a chain that ran out of time is its own path, not a failed attempt: $lines",
        )
        assertTrue(
            lines.contains("renderer: build failed, falling back to the shipped bundle at $PIN"),
            "the fallback names the commit it moves the checkout to, so the log says " +
                "which Hermes the panel serves: $lines",
        )
    }

    @Test
    fun `the fallback moves the checkout to the pin, one commit deep`() {
        // The fallback's own command line, which a device runs blind: the pin is a
        // refspec here, and a typo in it breaks the one path that keeps a panel alive
        // on a device whose renderer build cannot finish.
        val checkout = WebPanels.HERMES_CHECKOUT
        assertEquals(
            "git -C $checkout fetch --depth 1 origin $PIN " +
                "&& git -C $checkout checkout --force --detach $PIN",
            HermesWebapp.pinCheckout(PIN),
        )
        assertFalse(
            HermesWebapp.pinCheckout(PIN).contains("--unshallow"),
            "the fallback fetches one commit, not the history",
        )
        // `--force` because the checkout is on `main` and may carry a half-written
        // build; `--detach` because no branch is at the pin.
        assertTrue(HermesWebapp.pinCheckout(PIN).contains("checkout --force --detach"))
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
    fun `a build counts only with the page, the assets and the stamp`() {
        // The page alone is not the bundle: a build that stopped after writing
        // index.html opens a blank screen. Every clause below is shipped to the
        // device verbatim, so the string is the contract, not a snapshot.
        assertTrue(
            HermesWebapp.BUILT_CHECK.startsWith("test -f ${WebPanels.HERMES_BUNDLE} && "),
            HermesWebapp.BUILT_CHECK,
        )
        assertTrue(
            HermesWebapp.BUILT_CHECK.contains(
                "test -n \"\$(ls -A ${WebPanels.HERMES_BUNDLE_DIR}/assets 2>/dev/null)\"",
            ),
            "an empty assets/ is a blank screen, not a build: ${HermesWebapp.BUILT_CHECK}",
        )

        // The stamp, not the pin: `hermes webapp --build-only` writes none, so the
        // app writes the commit it built from — and that is what the gate reads.
        assertEquals(
            "test -f ${HermesWebapp.BUILT_STAMP} && cat ${HermesWebapp.BUILT_STAMP}",
            HermesWebapp.stampCommand(),
        )
        assertEquals(
            "mkdir -p ${HermesWebapp.DIST_DIR} " +
                "&& printf '%s\\n' \"\$(git -C ${WebPanels.HERMES_CHECKOUT} rev-parse HEAD)\" " +
                "> ${HermesWebapp.BUILT_STAMP}.tmp && mv ${HermesWebapp.BUILT_STAMP}.tmp ${HermesWebapp.BUILT_STAMP}",
            HermesWebapp.stampBuilt(),
            "the stamp names the commit the build came from, and lands atomically",
        )
    }

    @Test
    fun `a renderer built on the device is served while the checkout is its commit`() {
        // The device build is the sandbox's own Hermes, so it is good on the commit
        // it was stamped from — whatever the pin, which names CI's bundle instead.
        assertEquals(HermesWebapp.Renderer.Device, HermesWebapp.decide(facts(builtFrom = HEAD, built = true)))
        assertEquals(
            HermesWebapp.Renderer.Device,
            HermesWebapp.decide(facts(head = OTHER, builtFrom = OTHER, built = true)),
            "a checkout past the pin still serves its own build",
        )

        // Both sources present: the build wins, because naming CI's bundle would
        // tell Hermes the renderer is ready and it would never rebuild.
        assertEquals(
            HermesWebapp.Renderer.Device,
            HermesWebapp.decide(facts(head = PIN, builtFrom = PIN, built = true, shipped = true)),
        )

        // A build the checkout has moved away from is a blank page: refused, with
        // both commits named so the owner knows what to do.
        val stale = assertIs<HermesWebapp.Renderer.Refused>(
            HermesWebapp.decide(facts(head = OTHER, builtFrom = HEAD, built = true)),
        )
        assertContains(stale.reason, HEAD.take(8))
        assertContains(stale.reason, OTHER.take(8))
        assertContains(stale.reason, "reinstall Hermes")
    }

    @Test
    fun `the shipped bundle is served only against the commit it was built from`() {
        assertEquals(HermesWebapp.Renderer.Shipped, HermesWebapp.decide(facts(head = PIN, shipped = true)))
        assertEquals(
            HermesWebapp.Renderer.Unpack(PIN),
            HermesWebapp.decide(facts(head = PIN)),
            "on disk but not unpacked is one unpack away, not a refusal",
        )

        val mismatched = assertIs<HermesWebapp.Renderer.Refused>(
            HermesWebapp.decide(facts(head = OTHER, shipped = true)),
        )
        assertContains(mismatched.reason, PIN.take(8))
        assertContains(mismatched.reason, OTHER.take(8))
    }

    @Test
    fun `nothing is served from a checkout that cannot be read or a bundle that is gone`() {
        assertEquals(
            HermesWebapp.UNREADABLE,
            assertIs<HermesWebapp.Renderer.Refused>(HermesWebapp.decide(facts(head = ""))).reason,
        )

        // A stamp that matches the checkout with no page and assets behind it: the
        // build is broken, not from another commit — rebuilding is the answer.
        assertEquals(
            HermesWebapp.NOT_BUILT_ROW,
            assertIs<HermesWebapp.Renderer.Refused>(
                HermesWebapp.decide(facts(builtFrom = HEAD, built = false)),
            ).reason,
        )

        // A developer build: no bundle shipped, nothing built here.
        assertEquals(
            HermesWebapp.NOT_BUILT_ROW,
            assertIs<HermesWebapp.Renderer.Refused>(HermesWebapp.decide(facts(pin = null))).reason,
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

    @Test
    fun `one budget pays for three measured builds, not three attempt deadlines`() {
        // Three attempts of up to 25 minutes were 75 minutes of a phone with no
        // panel on it, so the chain gets a deadline of its own.
        assertTrue(
            HermesWebapp.BUILD_BUDGET_SECONDS <
                HermesWebapp.buildAttempts.size * HermesWebapp.BUILD_ATTEMPT_TIMEOUT_SECONDS,
            "the chain may not spend three full attempt timeouts",
        )

        // Walked the way the device walks it: an attempt starts only if the budget
        // can pay the start cost, and a measured build costs about seven minutes.
        var now = 0L
        val budget = HermesWebapp.BuildBudget(nowMillis = { now })
        val started = mutableListOf<Int>()
        val leftBeforeStart = mutableListOf<Long>()
        for (attempt in HermesWebapp.buildAttempts) {
            if (!budget.attemptFits()) break
            started += attempt.number
            leftBeforeStart += budget.leftSeconds()
            now += 7 * 60L * 1000
        }
        assertEquals(
            HermesWebapp.buildAttempts.map { it.number },
            started,
            "three measured builds have to fit, or a slow network throws away a build that was going to work",
        )

        // And the third starts with less than its own deadline left: that deadline
        // is a hang detector, not the price of a build — see ATTEMPT_START_SECONDS.
        assertTrue(
            leftBeforeStart.last() < HermesWebapp.BUILD_ATTEMPT_TIMEOUT_SECONDS,
            "${leftBeforeStart.last()}s left before the third build, one attempt's deadline is " +
                "${HermesWebapp.BUILD_ATTEMPT_TIMEOUT_SECONDS}s",
        )
    }

    @Test
    fun `a spent budget serves the shipped bundle instead of throwing`() {
        // A chain that ran out of time is not an exception and not a hung status:
        // the install unpacks the bundle CI built, so the panel comes up anyway.
        val spent = HermesWebapp.afterBuild(HermesWebapp.RendererBuild.OUT_OF_BUDGET)
        assertTrue(spent.serveShippedBundle, "the panel must still come up")
        assertEquals(HermesWebapp.BUDGET_SPENT, spent.line)
        assertTrue(
            spent.line!!.contains("serving the shipped bundle"),
            "the owner has to read that the panel still comes up: ${spent.line}",
        )

        // A build that won serves nothing: naming the shipped bundle after a device
        // build would tell Hermes the renderer is ready and stop it rebuilding.
        val built = HermesWebapp.afterBuild(HermesWebapp.RendererBuild.BUILT)
        assertFalse(built.serveShippedBundle, "a device build is not a fallback")
        assertNull(built.line, "nothing to say beyond the line the winning attempt wrote")

        // Every attempt failed with budget to spare: the same fallback, but each
        // attempt already wrote why, so there is no budget sentence to add.
        val failed = HermesWebapp.afterBuild(HermesWebapp.RendererBuild.FAILED)
        assertTrue(failed.serveShippedBundle)
        assertNull(failed.line)
    }

    @Test
    fun `an attempt the budget cannot pay for is not started`() {
        var now = 0L
        val budget = HermesWebapp.BuildBudget(nowMillis = { now })
        assertTrue(budget.attemptFits(), "a fresh budget pays for the first attempt")

        // The start cost is the measured build plus a margin, not the attempt's own
        // hang deadline: on ten minutes left the third attempt still has a build in
        // it, and refusing it there is what ended the chain after two attempts.
        now = (HermesWebapp.BUILD_BUDGET_SECONDS - HermesWebapp.ATTEMPT_START_SECONDS) * 1000
        assertEquals(HermesWebapp.ATTEMPT_START_SECONDS, budget.leftSeconds())
        assertTrue(budget.attemptFits(), "ten minutes left is one measured build and a margin")

        now += 2 * 1000
        assertFalse(budget.attemptFits(), "a second less is not enough")

        // An empty budget reads zero, not a negative number, and starts nothing.
        now = (HermesWebapp.BUILD_BUDGET_SECONDS + 5 * 60L) * 1000
        assertEquals(0L, budget.leftSeconds())
        assertEquals(0L, budget.leftMinutes())
        assertFalse(budget.attemptFits())
    }

    @Test
    fun `every attempt starts with a line that counts the budget down`() {
        val second = HermesWebapp.buildAttempts[1]
        val line = HermesWebapp.buildingOnDevice(second, minutesLeft = 24)

        // The owner sees the count, not just "building": which attempt of how
        // many, and how much of the wait is still paid for.
        assertTrue(line.contains("attempt 2 of 3"), line)
        assertTrue(line.contains("~24 min left of the build budget"), line)
        // The attempt's own name stays, because the line that follows it —
        // "built on device (attempt 2, …)" — is read against it.
        assertTrue(line.contains(second.why), line)
        // And the estimate to compare the countdown with.
        assertTrue(line.contains("about seven minutes"), line)

        // Rounded up: a budget with a second left must not read "0 min left".
        var now = 0L
        val budget = HermesWebapp.BuildBudget(nowMillis = { now })
        now = HermesWebapp.BUILD_BUDGET_SECONDS * 1000 - 1000
        assertEquals(1L, budget.leftMinutes())
    }

    /** The facts the start reports, with the ordinary release case as the default. */
    private fun facts(
        head: String = HEAD,
        pin: String? = PIN,
        builtFrom: String? = null,
        built: Boolean = false,
        shipped: Boolean = false,
    ) = HermesWebapp.RendererFacts(head, pin, builtFrom, built, shipped)

    private companion object {
        /** The commit CI built this app's bundle from. */
        const val PIN = "caafe5389ae585ef77387a962aee05b1bcf17029"

        /** The commit the checkout is on when nothing has moved it. */
        const val HEAD = "caafe5380123456789abcdef0123456789abcdef"

        /** A Hermes the checkout could have moved on to. */
        const val OTHER = "0123456789abcdef0123456789abcdef01234567"
    }
}
