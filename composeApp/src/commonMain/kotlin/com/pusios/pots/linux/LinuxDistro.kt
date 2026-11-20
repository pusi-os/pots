package com.pusios.pots.linux

/**
 * The Linux distributions Pots can run under proot.
 *
 * Pots Build is always [DEBIAN] — its coding agents are vendor scripts that expect
 * glibc and apt. The chat sandbox lets the user pick, and when it is also Debian
 * the two share a single rootfs instead of installing one each.
 */
enum class LinuxDistro(
    val id: String,
    val displayName: String,
    /**
     * Installed as the last step of setup. The environment is not considered
     * ready until these are in, so an interrupted install can never present
     * itself as usable.
     */
    val basePackages: List<String>,
    /** Installed on demand from the Settings card's "Install Packages" action. */
    val optionalPackages: List<String>,
    val packageManager: PackageManagerSpec,
) {
    /**
     * Base set is Pots Build's proven list: `tar` because Pi's installer extracts
     * a `.tar.gz` of its Node runtime, `coreutils` because the agent installers
     * verify their downloads with `sha256sum`.
     *
     * `libatomic1` is there for the Node runtime the *agents* fetch, not for
     * bookworm's own: nodejs.org's linux-arm64 build links `libatomic.so.1`, and a
     * rootfs carrying only this list has no such library — `node --version` then
     * dies with `error while loading shared libraries: libatomic.so.1` (exit 127)
     * before any JavaScript runs. Hermes' `pm` unpacks exactly that tarball for its
     * frontend builds, so without it `hermes webapp --build-only` fails at its
     * first step. Nothing else in the list pulls it in: Debian's own `nodejs` and
     * `python3` depend on `libnode108`/`libgcc-s1`, neither of which is this
     * library.
     */
    DEBIAN(
        id = "debian",
        displayName = "Debian 12",
        basePackages = listOf(
            "bash", "ca-certificates", "curl", "wget", "git",
            // `tar -xJ` runs xz, and the Hermes web panel this app ships arrives as
            // a tar.xz: without it the panel cannot unpack its own renderer.
            "xz-utils",
            "nano", "less", "unzip", "python3", "tar", "coreutils",
            "libatomic1",
        ),
        optionalPackages = listOf(
            "jq",
            "nodejs",
            "npm",
            "python3-pip",
            "openssh-client",
            "lftp",
            "rsync",
        ),
        packageManager = AptPackageManager,
    ),

    /**
     * `bash` is infrastructure rather than convenience: every persistent shell
     * session execs it directly, so the minirootfs is unusable without it.
     */
    ALPINE(
        id = "alpine",
        displayName = "Alpine Linux",
        basePackages = listOf("bash"),
        optionalPackages = listOf(
            "curl", "wget", "git", "jq", "python3", "py3-pip", "nodejs",
            "openssh-client", "lftp", "rsync",
        ),
        packageManager = ApkPackageManager,
    ),
    ;

    /**
     * Packages the Packages tab must never offer to uninstall — removing any of
     * them breaks the shell sessions the sandbox itself runs on.
     */
    val protectedPackages: Set<String> = basePackages.toSet()

    companion object {
        /**
         * What a fresh install becomes unless the user picks otherwise, and what
         * Pots Build always uses.
         */
        val DEFAULT = DEBIAN

        /**
         * Installs made before the distro was recorded are Alpine — that was the
         * only thing the chat sandbox could be.
         */
        val LEGACY = ALPINE

        fun fromId(id: String?): LinuxDistro = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
