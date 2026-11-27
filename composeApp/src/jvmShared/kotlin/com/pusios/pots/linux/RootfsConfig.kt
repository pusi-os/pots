package com.pusios.pots.linux

import java.io.File

/**
 * The apt and dpkg configuration a proot rootfs needs, written once while the
 * rootfs is being set up and read by every apt call after it.
 *
 * That "every call" is the point. Pots Build installs its agents with apt calls
 * of its own and never goes through `LinuxInstaller`, so a flag passed on the
 * installer's command line would not reach them; files under `apt.conf.d` do.
 */
object RootfsConfig {

    const val APT_CONF_PATH = "etc/apt/apt.conf.d/99pots-nosandbox"

    const val DPKG_CONF_PATH = "etc/dpkg/dpkg.cfg.d/force-unsafe-io"

    val aptConf: String = buildString {
        // apt drops to `_apt` via setresuid for downloads. Work profiles and
        // system clones block that syscall (termux/proot#369), so `apt-get update`
        // fails and every package then looks missing. proot's root is fake anyway,
        // so staying root costs nothing; the file also covers shell-run apt-get.
        append("APT::Sandbox::User \"root\";\n")
        // A rootfs image is frozen at the moment it was built, while a mirror's
        // Release file carries a `Valid-Until` about a week out. Past that window
        // apt refuses every index with `is expired` and the install cannot
        // proceed at all, so the window is not enforced here. This skips only the
        // freshness check — packages are still verified against the Release
        // file's signed hashes.
        //
        // `Acquire::Check-Date` is deliberately left at its default: it is what
        // makes a device clock that is days behind surface as apt's `not valid
        // yet` instead of an install that silently unpacks stale metadata.
        append("Acquire::Check-Valid-Until \"false\";\n")
    }

    /**
     * dpkg fsyncs every unpacked file by default, which turns a base install
     * into a multi-minute affair on a phone.
     */
    val dpkgConf: String = "force-unsafe-io\n"

    /** Writes both files, creating their directories. Safe to run twice. */
    fun write(rootfsDir: File) {
        writeConf(rootfsDir, APT_CONF_PATH, aptConf)
        writeConf(rootfsDir, DPKG_CONF_PATH, dpkgConf)
    }

    private fun writeConf(rootfsDir: File, path: String, content: String) {
        val file = File(rootfsDir, path)
        file.parentFile?.mkdirs()
        file.writeText(content)
    }
}
