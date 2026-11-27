package com.pusios.pots.linux

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The apt configuration is written once, during setup, and read by every later
 * apt call — including the ones Pots Build makes to install agents. A rootfs
 * without it cannot refresh its index at all, so the files matter as much as the
 * commands that run after them.
 */
class RootfsConfigTest {

    private lateinit var rootfs: File

    @BeforeTest
    fun setUp() {
        rootfs = Files.createTempDirectory("rootfs-config").toFile()
    }

    @AfterTest
    fun tearDown() {
        rootfs.deleteRecursively()
    }

    @Test
    fun `writes the sandbox and validity window settings apt needs`() {
        RootfsConfig.write(rootfs)

        val conf = File(rootfs, RootfsConfig.APT_CONF_PATH)
        assertTrue(conf.isFile, "apt.conf.d file was not written")
        val text = conf.readText()
        assertTrue(text.contains("APT::Sandbox::User \"root\";"), "apt cannot drop to _apt under proot: $text")
        assertTrue(
            text.contains("Acquire::Check-Valid-Until \"false\";"),
            "an image older than the mirror's window must still refresh: $text",
        )
    }

    @Test
    fun `leaves apt's own date check switched on`() {
        RootfsConfig.write(rootfs)

        // Check-Date is what turns a device clock days behind the mirror into a
        // message the card can explain; switching it off would hide the cause.
        assertFalse(File(rootfs, RootfsConfig.APT_CONF_PATH).readText().contains("Check-Date"))
    }

    @Test
    fun `writes the dpkg io flag too`() {
        RootfsConfig.write(rootfs)

        assertEquals("force-unsafe-io\n", File(rootfs, RootfsConfig.DPKG_CONF_PATH).readText())
    }

    @Test
    fun `a second run rewrites the same files`() {
        RootfsConfig.write(rootfs)
        RootfsConfig.write(rootfs)

        assertEquals(RootfsConfig.aptConf, File(rootfs, RootfsConfig.APT_CONF_PATH).readText())
        assertEquals(RootfsConfig.dpkgConf, File(rootfs, RootfsConfig.DPKG_CONF_PATH).readText())
    }
}
