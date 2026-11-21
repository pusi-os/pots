package com.pusios.pots.build

/**
 * The Hermes web panel renderer (`apps/desktop/dist-webapp`), and the three ways
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
 * **The bundle this app ships is the fallback, not the first choice.** CI builds
 * `apps/desktop/dist-webapp` from the commit `hermes-webapp.pin` names, packs it
 * into one `tar.xz`, and the APK carries it as an asset; it is unpacked only when
 * both on-device attempts failed, so the panel is never dead. Naming it in
 * [DIST_ENV] is what makes Hermes serve a bundle it did not build — and that
 * variable is set *only* for that fallback: set after a successful build it would
 * pin the panel to CI's copy for good, and Hermes would never rebuild.
 *
 * **The two halves have to be one version.** The renderer was built from Hermes X;
 * a sandbox running Hermes Y would talk to it over an API that may have moved,
 * which the owner sees as a blank panel and 404s on the assets. So the install pins
 * the checkout to X and the panel refuses to start when it is not X — a sentence on
 * the row instead of a broken page. Nothing is ever served silently mismatched.
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
     * What a finished build leaves behind: the page, and the scripts it loads.
     * The page alone is not the bundle — a build that produced `index.html` but
     * no files under `assets/` opens a blank screen and reads as a broken app.
     */
    const val BUILT_CHECK = "test -f " + WebPanels.HERMES_BUNDLE + " && test -n \"\$(ls -A " +
        WebPanels.HERMES_BUNDLE_DIR + "/assets 2>/dev/null)\""

    /**
     * The renderer the checkout built for itself, good when that checkout is the
     * commit this app pins. There is no stamp to read here: `hermes webapp
     * --build-only` writes none, so the commit is what says which Hermes the
     * bundle belongs to.
     */
    fun built(pin: String): String =
        "$BUILT_CHECK && test \"\$(git -C ${WebPanels.HERMES_CHECKOUT} rev-parse HEAD)\" = $pin"

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
     * Is there a renderer to serve for [pin]? Two equally good answers: one built
     * on the device from that commit, or the one this app ships, unpacked. A
     * developer build ships none and can only ever have the first.
     */
    fun check(pin: String): String = "(${shipped(pin)}) || (${built(pin)})"

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
     * One way to build the renderer on the device, in the order the app tries
     * them. [hardlinkEmulation] is proot's `--link2symlink`: dpkg needs it, and it
     * is exactly what breaks pnpm's staged rename, so every attempt runs without
     * it. [env] reaches pnpm as `pnpm_config_*` through proot's own environment —
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
     * The chain the device runs. Attempt 1 is the measured one: no emulation, and
     * `copy` so pnpm asks for no hardlink at all (Android's `protected_hardlinks`
     * may refuse one inside the app sandbox). Attempt 2 keeps the proot flags and
     * drops that setting — the only other difference worth a run: if the first
     * attempt died, this says whether the import method was the reason. Settings
     * alone never fix the failure (pnpm's staged rename is independent of
     * `package-import-method`), so there is no third variant to try.
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
            hardlinkEmulation = false,
            env = emptyMap(),
            why = "no link2symlink, no import method",
        ),
    )

    /** The command that builds the renderer from the checkout the install pinned. */
    const val BUILD_COMMAND = "/root/.local/bin/hermes webapp --build-only --force-build"

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

    /** The line that says the panel is serving the bundle CI built. */
    const val SHIPPED = "renderer: shipped bundle"

    /** The line that says there is no renderer at all — a developer build, both attempts failed. */
    const val NOT_BUILT = "renderer: not built and this app ships no bundle"

    /**
     * What the row says when the sandbox and the bundle are not the same Hermes.
     *
     * Both commits are named in short form: the owner cannot act on "the versions
     * differ", but "reinstall Hermes" is a button in the same card.
     */
    fun versionMismatch(pin: String, head: String): String =
        if (head.isBlank()) {
            "the sandbox's Hermes checkout could not be read — reinstall Hermes in Pots Build"
        } else {
            "the panel was built for Hermes ${pin.take(8)} but the sandbox runs ${head.take(8)} " +
                "— reinstall Hermes in Pots Build to match"
        }

    /** What the row says when the shipped bundle is there but could not be unpacked. */
    fun unpackFailed(reason: String): String =
        "could not unpack the Hermes panel this app ships" + (if (reason.isBlank()) "" else ": $reason")
}
