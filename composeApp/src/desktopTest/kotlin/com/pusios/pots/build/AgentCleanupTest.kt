package com.pusios.pots.build

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Cleaning before an install, against a real temporary rootfs: the guest answers
 * nothing here — no binary, no shell — so "the leftover was taken" and "the user's
 * data is still there" are only facts if the files are checked on disk.
 */
class AgentCleanupTest {

    private lateinit var rootfs: File

    /** `df -h /root | tail -1`, as the guest prints it. */
    private val diskLine = "/dev/block/dm-52 108G 60G 48G 56% /"

    private var diskAsked = 0

    @BeforeTest
    fun setUp() {
        rootfs = Files.createTempDirectory("agent-cleanup").toFile()
        diskAsked = 0
    }

    @AfterTest
    fun tearDown() {
        rootfs.walkBottomUp().forEach { it.setWritable(true) }
        rootfs.deleteRecursively()
    }

    private fun disk(): String {
        diskAsked++
        return "  $diskLine\n"
    }

    private fun put(guestPath: String, bytes: Int = 64): File =
        File(rootfs, guestPath.trimStart('/')).apply {
            parentFile.mkdirs()
            writeBytes(ByteArray(bytes))
        }

    @Test
    fun `leftovers of a run that stopped halfway are found, said, and taken`() {
        val binary = put("/root/.local/bin/pi")

        val lines = cleanLeftovers(rootfs, AgentVersions.get(BuildAgents.PI)!!, binaryAnswers = false, disk = ::disk)

        assertEquals(
            listOf(
                "pots: pi: leftovers found (/root/.local/bin/pi)",
                "pots: pi: df -h /root | tail -1 → $diskLine",
                "pots: pi: cleaned (/root/.local/bin/pi)",
            ),
            lines,
        )
        assertFalse(Files.exists(binary.toPath(), LinkOption.NOFOLLOW_LINKS), "the leftover is gone")
        assertEquals(1, diskAsked, "the disk is measured once, next to the cleanup")
    }

    @Test
    fun `a binary that answers is the installation, not a leftover`() {
        val binary = put("/root/.local/bin/pi")

        val lines = cleanLeftovers(rootfs, AgentVersions.get(BuildAgents.PI)!!, binaryAnswers = true, disk = ::disk)

        assertEquals(listOf("pots: pi: nothing to clean"), lines)
        assertTrue(binary.exists(), "a reinstall is an update, not a reason to delete")
        assertEquals(0, diskAsked, "nothing is deleted, so there is nothing to measure")
    }

    @Test
    fun `an install that was never there has nothing to clean`() {
        val lines = cleanLeftovers(rootfs, AgentVersions.get(BuildAgents.OPENCODE)!!, binaryAnswers = false, disk = ::disk)

        assertEquals(listOf("pots: opencode: nothing to clean"), lines)
        assertEquals(0, diskAsked)
    }

    @Test
    fun `a cleanup pointed at the directory that holds the user's data takes nothing`() {
        // The card's worst case: /root/.hermes holds auth.json, the profiles and the
        // board, and it is the parent of the checkout.
        val guarding = AgentVersions.get(BuildAgents.HERMES)!!.copy(
            installPaths = listOf("/root/.hermes"),
            keepPaths = listOf("/root/.hermes/auth.json"),
        )
        val auth = put("/root/.hermes/auth.json")
        val board = put("/root/.hermes/kanban/kanban.db")

        val lines = cleanLeftovers(rootfs, guarding, binaryAnswers = false, disk = ::disk)

        assertEquals(
            listOf(
                "pots: hermes: leftovers found (/root/.hermes)",
                "pots: hermes: refusing to remove /root/.hermes — it holds user data",
            ),
            lines,
        )
        assertTrue(auth.exists(), "the owner's authorisation is not the cleanup's to take")
        assertTrue(board.exists(), "nor is the board")
        assertEquals(0, diskAsked)
    }

    @Test
    fun `a broken hermes link goes and the checkout is left for the installer`() {
        // The checkout is bulkPath: 5 GiB that the repair ladder deals with, not
        // this cleanup. Only the link into PATH is a leftover here.
        val link = File(rootfs, "root/.local/bin/hermes").apply { parentFile.mkdirs() }
        Files.createSymbolicLink(link.toPath(), File(rootfs, "root/.hermes/hermes-agent/.hermes/bin/hermes").toPath())
        val checkout = put("/root/.hermes/hermes-agent/scripts/install.sh")

        val lines = cleanLeftovers(rootfs, AgentVersions.get(BuildAgents.HERMES)!!, binaryAnswers = false, disk = ::disk)

        assertEquals(
            listOf(
                "pots: hermes: leftovers found (/root/.local/bin/hermes)",
                "pots: hermes: df -h /root | tail -1 → $diskLine",
                "pots: hermes: cleaned (/root/.local/bin/hermes)",
            ),
            lines,
        )
        assertFalse(Files.exists(link.toPath(), LinkOption.NOFOLLOW_LINKS), "a dangling link is debris")
        assertTrue(checkout.exists(), "the checkout is not the cleanup's to delete")
    }
}
