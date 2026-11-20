package com.pusios.pots.build

/**
 * The Hermes web panel renderer this app ships, and what the sandbox does with it.
 *
 * The phone cannot build that renderer: the first thing `hermes webapp --build-only`
 * does is a `pnpm install` of `apps/desktop`, and pnpm 12's staged renames answer
 * ENOENT under proot's `--link2symlink` — which dpkg needs, so the emulation stays
 * (measured in t_cf73367d; without it the same tree builds in 1m55s). So CI builds
 * the bundle from the commit `hermes-webapp.pin` names, packs
 * `apps/desktop/dist-webapp` into one `tar.xz`, and the APK carries it as an asset.
 *
 * Hermes needs nothing invented for the other half: `HERMES_WEBAPP_DIST` makes it
 * serve a bundle it did not build, without building or marking one
 * (`hermes_cli/webapp.py`).
 *
 * **The two halves have to be one version.** The bundle was built from Hermes X; a
 * sandbox running Hermes Y would talk to it over an API that may have moved, which
 * the owner sees as a blank panel and 404s on the assets. So the install pins the
 * checkout to X and the panel refuses to start when it is not X — a sentence on the
 * row instead of a broken page. Nothing is ever served silently mismatched.
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
     * The unpacked bundle for [pin], and nothing else: the page alone is not the
     * bundle (`index.html` loads the scripts under `assets/`), and the stamp inside
     * names the commit it was unpacked for, so a new APK's bundle replaces an old
     * one instead of being served against a backend it was not built for.
     */
    fun check(pin: String): String =
        "test -f $DIST_DIR/index.html && test -d $DIST_DIR/assets " +
            "&& test \"\$(cat $DIST_DIR/.pots-pin 2>/dev/null)\" = $pin"

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
