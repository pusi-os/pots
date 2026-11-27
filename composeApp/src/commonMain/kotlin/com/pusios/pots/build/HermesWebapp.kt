package com.pusios.pots.build

/**
 * The Hermes web panel renderer (`apps/desktop/dist-webapp`), and the two ways
 * Pots gets one onto the device.
 *
 * **The renderer is built on the device.** `hermes webapp --build-only` runs a
 * `pnpm install` of `apps/desktop` and then vite; it cannot run in the same proot
 * call as the package install, because pnpm 12 stages every imported package in a
 * `…_pacquet-stage_…` directory and renames it into place, and that rename answers
 * ENOENT under proot's `--link2symlink` — the emulation dpkg needs for the base
 * packages. So the build is a proot call of its own, without the emulation
 * (measured on a Debian 12 arm64 rootfs from the same LXC index, with the Node and
 * pnpm `pm` itself installs: with the emulation the install fails in 24 s, without
 * it the same tree builds a complete 45 MB bundle — 1058 files under `assets/` —
 * in about seven minutes, of which the pnpm install is 1m 56s and vite 4m 3s).
 *
 * **The chain has one budget.** Three attempts of up to 25 minutes each were 75
 * minutes of a phone with no panel on it, so [BUILD_BUDGET_SECONDS] bounds the
 * whole chain, cleans included: an attempt that cannot be given
 * [ATTEMPT_START_SECONDS] is not started, a running attempt is cancelled when the
 * budget ends, and a chain that runs out serves the shipped bundle instead of
 * waiting longer. Every attempt starts with a line carrying what is left.
 *
 * **The bundle this app ships is the fallback, not the first choice.** CI builds
 * `apps/desktop/dist-webapp` from the commit `hermes-webapp.pin` names, packs it
 * into one `tar.xz`, and the APK carries it as an asset; it is unpacked only when
 * every on-device attempt failed, so the panel is never dead. The fallback is a
 * whole Hermes too, not just a renderer: the checkout is moved to the pin first,
 * because the bundle was built from that commit and from nothing else. Naming
 * [DIST_DIR] in [DIST_ENV] is what makes Hermes serve a bundle it did not build —
 * and that variable is set *only* for that fallback: set after a successful build
 * it would pin the panel to CI's copy for good, and Hermes would never rebuild.
 *
 * **The two halves have to be one version.** The renderer was built from Hermes X;
 * a sandbox running Hermes Y would talk to it over an API that may have moved,
 * which the owner sees as a blank panel and 404s on the assets. Two renderers can
 * serve the panel, and each carries the commit it was built from: the one the
 * checkout builds on the device stamps its commit in [BUILT_STAMP], the one CI
 * built is named by the pin and stamped in [DIST_DIR] when it is unpacked. The
 * panel serves a renderer only against the Hermes it was built from — a sentence
 * on the row instead of a broken page — and when both are there the device's own
 * build wins, because that one *is* the sandbox's Hermes. Nothing is ever served
 * silently mismatched.
 *
 * **An update moves the checkout, so the renderer has to move with it.**
 * `hermes update` advances the checkout to a new commit while both stamps still
 * name the old one, which is precisely the mismatch the gate refuses — so a
 * rebuild belongs to updating, not to repair. [rebuildNeeded] is what a start
 * asks before it spends the budget, and [recoveryActions] is what the row offers
 * when the chain could not finish: rebuild again, or move Hermes back to the
 * commit the shipped bundle was built from. Neither happens silently.
 */
object HermesWebapp {

    /** Names the bundle Hermes serves as it is: no build, no staleness mark. */
    const val DIST_ENV = "HERMES_WEBAPP_DIST"

    /**
     * Where the sandbox keeps the unpacked bundle. Deliberately not the checkout's
     * own `apps/desktop/dist-webapp`: a bundle dropped there would read as Hermes'
     * own build, which the next `hermes update` would try to rebuild and mark.
     */
    const val DIST_DIR = "/root/.hermes/webapp-dist"

    /** Guest path the app copies the shipped archive to before unpacking it. */
    const val ARCHIVE = "/root/.hermes/pots-webapp-dist.tar.xz"

    /**
     * What a successful on-device build stamps itself with: the commit it was
     * built from. `hermes webapp --build-only` writes no stamp of its own, so the
     * app writes this one — and only after the build left a page and its assets,
     * so a half-written bundle can never be read as this checkout's own.
     */
    const val BUILT_STAMP = "$DIST_DIR/.pots-built-from"

    /**
     * What a finished build leaves behind: the page, and the scripts it loads.
     * The page alone is not the bundle — a build that produced `index.html` but
     * no files under `assets/` opens a blank screen and reads as a broken app.
     */
    const val BUILT_CHECK = "test -f " + WebPanels.HERMES_BUNDLE + " && test -n \"\$(ls -A " +
        WebPanels.HERMES_BUNDLE_DIR + "/assets 2>/dev/null)\""

    /**
     * What Hermes prints when it decides the bundle is current and skips the
     * build (`hermes_cli/webapp.py`). `--force-build` should make it impossible;
     * seeing it means the flag never reached Hermes, and a bundle a failed
     * attempt half-wrote must not then be read as this attempt's build.
     */
    const val UP_TO_DATE = "Hermes Webapp renderer is up to date"

    /**
     * The commit the checkout built its own renderer from, as the app stamped it.
     * A failure here is the ordinary case: nothing was built on this device.
     */
    fun stampCommand(): String = "test -f $BUILT_STAMP && cat $BUILT_STAMP"

    /**
     * The bundle this app ships, unpacked into [DIST_DIR] for [pin]: the page,
     * the scripts, and the stamp naming the commit it was unpacked for, so a new
     * APK's bundle replaces an old one instead of being served against a backend
     * it was not built for.
     */
    fun shipped(pin: String): String =
        "test -f $DIST_DIR/index.html && test -d $DIST_DIR/assets " +
            "&& test \"\$(cat $DIST_DIR/.pots-pin 2>/dev/null)\" = $pin"

    /**
     * What the sandbox reports about the renderers it could serve: one probe per
     * field, and [decide] turns them into the answer. Kept apart from the shell so
     * the four cases the panel has to get right are unit-tested rather than
     * inferred from a command line that no test can run.
     */
    data class RendererFacts(
        /** The commit the checkout is on; blank when it cannot be read. */
        val head: String,
        /** The commit CI built the shipped bundle from; null when this app ships none. */
        val pin: String?,
        /** The commit the on-device build stamped; null when this checkout built nothing. */
        val builtFrom: String?,
        /** Whether that build left the page and its assets behind. */
        val built: Boolean,
        /** Whether the shipped bundle is unpacked for [pin] right now. */
        val shipped: Boolean,
    )

    /** What the panel can serve. */
    sealed interface Renderer {
        /** The checkout's own build — it *is* this sandbox's Hermes, so nothing is named. */
        object Device : Renderer

        /** The bundle CI built, already unpacked for its pin. */
        object Shipped : Renderer

        /** The bundle CI built for [pin] is on disk, one unpack away from being served. */
        data class Unpack(val pin: String) : Renderer

        /** Nothing may be served; [reason] is the sentence the row shows. */
        data class Refused(val reason: String) : Renderer
    }

    /**
     * The rule, in the order it matters:
     *
     * 1. a device build stamped with the commit the checkout is on — always good,
     *    whatever the pin says, because it was built from this very Hermes;
     * 2. the bundle CI built, while the checkout is the commit it was built from:
     *    already unpacked, or one unpack away;
     * 3. nothing. A renderer from another commit is a blank page and 404s, so the
     *    row says which two commits disagree instead of opening it.
     */
    fun decide(facts: RendererFacts): Renderer {
        if (facts.head.isBlank()) return Renderer.Refused(UNREADABLE)
        if (facts.built && facts.builtFrom == facts.head) return Renderer.Device
        if (facts.pin != null && facts.pin == facts.head) {
            return if (facts.shipped) Renderer.Shipped else Renderer.Unpack(facts.pin)
        }
        // Neither source is this Hermes. Name the commit a renderer *was* built
        // from, when there is one: a stamp that matches the checkout with no files
        // left behind is a broken build, and rebuilding is the answer to that.
        val builtFrom = facts.builtFrom ?: facts.pin
        return Renderer.Refused(
            if (builtFrom == null || builtFrom == facts.head) NOT_BUILT_ROW else versionMismatch(builtFrom, facts.head),
        )
    }

    /**
     * Whether the panel is refused only because what is on disk was built from
     * another commit — the state `hermes update` leaves behind, and the one a
     * rebuild fixes.
     *
     * False when the checkout cannot be read: there is nothing to build from.
     * False when something on disk already matches it: a build from this very
     * commit is served as it is, and a shipped bundle for it is unpacked instead.
     * True for everything else, including a stamp with no files behind it — that
     * is a broken build, and building again is the answer to it.
     */
    fun rebuildNeeded(facts: RendererFacts): Boolean {
        if (facts.head.isBlank()) return false
        if (facts.built && facts.builtFrom == facts.head) return false
        return facts.pin != facts.head
    }

    /**
     * Whether a start should spend the build budget now.
     *
     * Once per checkout: an owner who has already waited out a failed chain for
     * [RendererFacts.head] is not made to wait it again on the next tap — the row
     * offers [PanelRecovery.REBUILD_RENDERER] instead. [rebuilt] is the commits a
     * chain was already tried for in this run of the app, and [force] is that
     * button: a deliberate retry of the same commit.
     *
     * A renderer that already matches the checkout is never rebuilt, however the
     * question was asked: the button means "try again", not "build a working one
     * over".
     */
    fun shouldRebuild(facts: RendererFacts, rebuilt: Set<String>, force: Boolean = false): Boolean =
        rebuildNeeded(facts) && (force || facts.head !in rebuilt)

    /**
     * What the row offers when nothing may be served: the two ways out that do
     * not need a reinstall. Rebuilding is the cure for the ordinary case; moving
     * the checkout back to the commit the shipped bundle was built from is the
     * owner's deliberate choice when the build cannot finish on this device.
     */
    fun recoveryActions(facts: RendererFacts): Set<PanelRecovery> = buildSet {
        if (facts.head.isNotBlank()) add(PanelRecovery.REBUILD_RENDERER)
        if (facts.pin != null && facts.pin != facts.head) add(PanelRecovery.ROLLBACK_CHECKOUT)
    }

    /**
     * Unpacks the shipped archive into [DIST_DIR]. `tar -xJ` runs `xz`, which a
     * Debian base does not carry on its own — that is why `xz-utils` is a base
     * package and the Hermes install asks for it again on an older rootfs.
     */
    fun unpack(pin: String): String =
        "rm -rf $DIST_DIR && mkdir -p $DIST_DIR && tar -xJf $ARCHIVE -C $DIST_DIR " +
            "&& printf '%s\\n' $pin > $DIST_DIR/.pots-pin"

    /** The commit a checkout is on — the version gate below reads it. */
    fun headCommand(checkout: String): String = "git -C $checkout rev-parse HEAD"

    /**
     * Stamps a successful on-device build with the commit it was built from.
     *
     * Written atomically — a temporary file renamed into place — so a run killed
     * between the write and the rename cannot leave a half stamp that reads as a
     * build of some other commit. Called only after [BUILT_CHECK] passed: a stamp
     * is what makes a bundle the panel's own, and a failed attempt leaves none.
     */
    fun stampBuilt(): String =
        "mkdir -p $DIST_DIR " +
            "&& printf '%s\\n' \"\$(git -C ${WebPanels.HERMES_CHECKOUT} rev-parse HEAD)\" > $BUILT_STAMP.tmp " +
            "&& mv $BUILT_STAMP.tmp $BUILT_STAMP"

    /**
     * Points the checkout at [pin] — the commit CI built the shipped bundle from.
     *
     * The install follows `main`, so this is the one path that moves the sandbox's
     * Hermes at all, and it runs only when the device could not build a renderer of
     * its own: the bundle is from that commit and from nothing else.
     */
    fun pinCheckout(pin: String): String =
        "git -C ${WebPanels.HERMES_CHECKOUT} fetch --depth 1 origin $pin " +
            "&& git -C ${WebPanels.HERMES_CHECKOUT} checkout --force --detach $pin"

    /**
     * Fetches the release tags. `hermes --version` names the newest release tag
     * reachable from HEAD, and a `--depth 1` clone carries no tags at all — so
     * without this the row shows `git.<sha>` where the release number belongs.
     * Ref-only: no file contents are pulled by this fetch.
     */
    const val TAGS_FETCH = "git -C " + WebPanels.HERMES_CHECKOUT +
        " fetch --filter=blob:none origin '+refs/tags/*:refs/tags/*'"

    /**
     * One way to build the renderer on the device, in the order the app tries
     * them. [hardlinkEmulation] is proot's `--link2symlink`: dpkg needs it, and it
     * is exactly what breaks pnpm's staged rename, so the first attempt drops it —
     * but the install writes nothing under it any more, and one attempt keeps it
     * anyway as the chain's own decision: the flag is then the only difference
     * from the first attempt, which is the question only the device can answer.
     * [env] reaches pnpm as `pnpm_config_*` through proot's own environment —
     * the only channel pnpm 12 reads: pacquet ignores `npm_config_*` (so a
     * `$HERMES_HOME/npmrc` hook cannot carry these settings at all), and its
     * `npmrc` is for auth, registry and TLS only.
     */
    data class BuildAttempt(
        val number: Int,
        val hardlinkEmulation: Boolean,
        val env: Map<String, String>,
        /** How the log names it — the owner reads this to know what worked. */
        val why: String,
    )

    /**
     * The chain the device runs, most likely to work first. Attempt 1 is the
     * measured one: no emulation, and `copy` so pnpm asks for no hardlink at all
     * (Android's `protected_hardlinks` may refuse one inside the app sandbox).
     * Attempt 2 keeps the emulation, though nothing on disk was written under it
     * any more: the flag is the only difference from attempt 1, so the device
     * answers whether `--link2symlink` by itself is what breaks the build — a
     * question no measurement on a plain rootfs settles. The attempt is kept as
     * the chain's own decision, not because the installer left such a tree.
     * Attempt 3 drops the import method, which says whether `copy` was needed at
     * all. Settings alone never fix the failure (pnpm's staged rename is
     * independent of `package-import-method`), so there is no fourth variant to try.
     */
    val buildAttempts: List<BuildAttempt> = listOf(
        BuildAttempt(
            number = 1,
            hardlinkEmulation = false,
            env = mapOf("pnpm_config_package_import_method" to "copy"),
            why = "no link2symlink",
        ),
        BuildAttempt(
            number = 2,
            hardlinkEmulation = true,
            env = mapOf("pnpm_config_package_import_method" to "copy"),
            why = "link2symlink, copy import",
        ),
        BuildAttempt(
            number = 3,
            hardlinkEmulation = false,
            env = emptyMap(),
            why = "no link2symlink, no import method",
        ),
    )

    /** The command that builds the renderer from the checkout the install left. */
    const val BUILD_COMMAND = "/root/.local/bin/hermes webapp --build-only --force-build"

    /**
     * How long one on-device build may run. The measured build is about seven
     * minutes (pnpm 1m 56s, vite 4m 3s); a phone with a slow flash and a cold
     * store is several times that, and a timeout here costs the whole attempt.
     *
     * Twenty-five minutes is a hard deadline for the command, but it is not the
     * bound a hung build hits first: the installer's silence detector (20 min of
     * no output) fires earlier and the log then reads "no output for 20 min",
     * not "timed out".
     */
    const val BUILD_ATTEMPT_TIMEOUT_SECONDS = 1500L

    /**
     * What the budget must still hold to start an attempt: the measured build
     * (about seven minutes) with room for a phone slower than the measurement.
     *
     * Deliberately not [BUILD_ATTEMPT_TIMEOUT_SECONDS]. That number is a hang
     * detector for one attempt, not what a build costs, and demanding it up front
     * ends the chain after two attempts — 35 minutes minus two measured builds
     * leaves 21, less than 25 — while the third, the one that answers whether
     * `copy` was needed at all, still had twenty minutes to run in. An attempt
     * started on ten minutes and cut by the hard deadline costs time only: the
     * next attempt wipes the store first, and the fallback is the same either way.
     */
    const val ATTEMPT_START_SECONDS = 10 * 60L

    /**
     * What the whole chain may spend — every attempt and its clean, not one of
     * them. Three attempts of seven to ten minutes fit in 35 minutes with room
     * for a slow network; without this bound the worst case was three attempts
     * of 25 minutes, 75 minutes of a phone screen with no end in sight.
     *
     * A chain that outlives it stops and the panel serves the shipped bundle: a
     * panel that is slower to build beats a panel that never appears.
     */
    const val BUILD_BUDGET_SECONDS = 35 * 60L

    /**
     * What the chain of attempts may still spend, counted from the first attempt.
     *
     * The clock is a parameter because this file is common and only the platform
     * knows one: the device passes `System::currentTimeMillis`, tests pass a
     * counter they can move.
     */
    class BuildBudget(
        private val nowMillis: () -> Long,
        val totalSeconds: Long = BUILD_BUDGET_SECONDS,
    ) {
        private val startedAt = nowMillis()

        /** Seconds still available — never negative, so a log line cannot read "-3 min". */
        fun leftSeconds(): Long = (totalSeconds - (nowMillis() - startedAt) / 1000).coerceAtLeast(0)

        /** Whole minutes, rounded up: "~0 min left" must not appear while time remains. */
        fun leftMinutes(): Long = (leftSeconds() + 59) / 60

        /**
         * Whether one more attempt fits. An attempt the budget cannot pay for is
         * not started: the chain would spend its last minutes on a build that has
         * to be cut off, and the panel gains nothing from it.
         */
        fun attemptFits(attemptSeconds: Long = ATTEMPT_START_SECONDS): Boolean =
            leftSeconds() >= attemptSeconds
    }

    /**
     * Wipes what a failed attempt leaves behind, before the next one starts.
     *
     * The pnpm store of this build is forced to `$HERMES_ROOT/cache/pnpm` by Hermes
     * itself (`pm/packages.py` overwrites `pnpm_config_store_dir`), so it cannot be
     * pointed elsewhere — only removed. A store a failed attempt leaves behind is
     * made of `.l2s.` symlinks, and a later build against it dies in rolldown with
     * `Could not resolve '../cjs/…'`. `node_modules` and the previous generation
     * go with it for the same reason: they were linked against that store.
     */
    const val CLEAN_COMMAND =
        "rm -rf /root/.hermes/hermes-agent/.build/webapp-workspace/node_modules " +
            "/root/.hermes/hermes-agent/.cache/node-deps-previous " +
            "/root/.hermes/cache/pnpm/store /root/.hermes/cache/pnpm/cache " +
            "/root/.hermes/cache/pnpm/state"

    /** The line that says a device build won, and which attempt it was. */
    fun builtOnDevice(attempt: BuildAttempt): String =
        "renderer: built on device (attempt ${attempt.number}, ${attempt.why})"

    /**
     * The line an attempt starts with, carrying what the budget has left.
     *
     * The count is the point: a build that runs for half an hour in silence is
     * what makes an owner think nothing is happening, so every attempt says how
     * much of the wait is still paid for before it begins.
     */
    fun buildingOnDevice(attempt: BuildAttempt, minutesLeft: Long): String =
        "renderer: building on device (attempt ${attempt.number} of ${buildAttempts.size}, " +
            "${attempt.why}), ~$minutesLeft min left of the build budget — about seven minutes"

    /** The line that says an attempt was answered "up to date": the flag never arrived. */
    fun builtNothing(attempt: BuildAttempt): String =
        "renderer: attempt ${attempt.number} built nothing — Hermes answered 'up to date', " +
            "so --force-build did not reach it"

    /** The line that says the panel is serving the bundle CI built. */
    const val SHIPPED = "renderer: shipped bundle"

    /**
     * The line that says where the panel fell back to, and which commit the bundle
     * it serves came from: the checkout is moved to that commit before the unpack,
     * so the log names both halves of the fallback in one place.
     */
    fun fallingBack(pin: String): String =
        "renderer: build failed, falling back to the shipped bundle at $pin"

    /**
     * The line that says the chain ran out of time rather than failing — and that
     * the panel comes up anyway on the bundle CI built. Both halves matter: the
     * owner is waiting on a screen, and "budget spent" alone reads as a failure.
     */
    const val BUDGET_SPENT = "renderer: device build budget spent, serving the shipped bundle"

    /** The line that says there is no renderer at all — a developer build, every attempt failed. */
    const val NOT_BUILT = "renderer: not built and this app ships no bundle"

    /** How the chain of device builds ended. */
    enum class RendererBuild { BUILT, FAILED, OUT_OF_BUDGET }

    /**
     * What the install does once the chain is over, decided here rather than in
     * the proot code so the rule is testable: [serveShippedBundle] is what keeps
     * the panel alive, and [line] is the sentence the owner reads when the chain
     * stopped for time instead of failing.
     */
    data class RendererFallback(
        val serveShippedBundle: Boolean,
        val line: String?,
    )

    /**
     * The rule: a build that won ends the chain; anything else serves the bundle
     * CI built, and a chain that ran out of time says so — "budget spent" alone
     * reads as a failure, and the owner is waiting on a screen that will appear.
     */
    fun afterBuild(outcome: RendererBuild): RendererFallback =
        when (outcome) {
            RendererBuild.BUILT -> RendererFallback(serveShippedBundle = false, line = null)
            RendererBuild.FAILED -> RendererFallback(serveShippedBundle = true, line = null)
            RendererBuild.OUT_OF_BUDGET -> RendererFallback(serveShippedBundle = true, line = BUDGET_SPENT)
        }

    /**
     * What the row says when the sandbox and the renderer are not the same Hermes.
     *
     * Both commits are named in short form: the owner cannot act on "the versions
     * differ". The cure is named too — a rebuild is what an update leaves behind,
     * and it is a button in the same card — because "reinstall Hermes" would cost
     * a fresh 5 GiB checkout for a mismatch one build fixes.
     */
    fun versionMismatch(expected: String, head: String): String =
        "the panel was built for Hermes ${expected.take(8)} but the sandbox runs ${head.take(8)} " +
            "— rebuild the renderer, or roll Hermes back to the panel's version"

    /** What the row says when the sandbox's Hermes cannot be read at all. */
    const val UNREADABLE = "the sandbox's Hermes checkout could not be read — reinstall Hermes in Pots Build"

    /** What the row says when there is no renderer for this Hermes and none is shipped. */
    const val NOT_BUILT_ROW = "Hermes' webapp is not built — run `hermes webapp --build-only` in the sandbox"

    /** What the row says when the shipped bundle is there but could not be unpacked. */
    fun unpackFailed(reason: String): String =
        "could not unpack the Hermes panel this app ships" + (if (reason.isBlank()) "" else ": $reason")
}
