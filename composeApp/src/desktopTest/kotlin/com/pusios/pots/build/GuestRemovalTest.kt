package com.pusios.pots.build

import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The removal log is only worth reading if its lines match the disk: "удалено"
 * means gone, "не было" means there was nothing, "не удалось" means it is still
 * there — and the bytes it counts are the ones this walk actually took. These
 * cases run against a real temporary rootfs, because a delete that swallows its
 * own errors looks identical to a clean one from the outside.
 */
class GuestRemovalTest {

    private lateinit var rootfs: File

    @BeforeTest
    fun setUp() {
        rootfs = Files.createTempDirectory("guest-removal").toFile()
    }

    @AfterTest
    fun tearDown() {
        // A case below takes the write bit off a directory to make a delete fail.
        rootfs.walkBottomUp().forEach { it.setWritable(true) }
        rootfs.deleteRecursively()
    }

    @Test
    fun `a file that goes is logged with the bytes it freed`() {
        val file = File(rootfs, "root/.local/bin/pi").apply {
            parentFile.mkdirs()
            writeBytes(ByteArray(1_000))
        }

        val removed = deleteGuestPath(rootfs, "/root/.local/bin/pi")

        assertEquals("/root/.local/bin/pi", removed.path)
        assertEquals(RemovedOutcome.Deleted(1_000L), removed.outcome)
        assertFalse(file.exists())
    }

    @Test
    fun `a directory is logged with the whole tree it took`() {
        val dir = File(rootfs, "root/.opencode")
        File(dir, "bin").mkdirs()
        File(dir, "bin/opencode").writeBytes(ByteArray(2_000))
        File(dir, "README.md").writeBytes(ByteArray(500))

        val removed = deleteGuestPath(rootfs, "/root/.opencode")

        assertEquals(RemovedOutcome.Deleted(2_500L), removed.outcome)
        assertFalse(dir.exists())
    }

    @Test
    fun `a symlink goes without claiming bytes, and leaves its target alone`() {
        val target = File(rootfs, "root/.hermes/real").apply {
            parentFile.mkdirs()
            writeBytes(ByteArray(10))
        }
        val link = File(rootfs, "root/.local/bin/hermes")
        link.parentFile.mkdirs()
        Files.createSymbolicLink(link.toPath(), target.toPath())

        val removed = deleteGuestPath(rootfs, "/root/.local/bin/hermes")

        assertEquals(RemovedOutcome.Deleted(bytes = null), removed.outcome)
        assertFalse(Files.exists(link.toPath(), LinkOption.NOFOLLOW_LINKS))
        assertTrue(target.exists(), "the link's target is not the agent's to delete")
    }

    @Test
    fun `a path that was never there is reported as absent, not as deleted`() {
        val removed = deleteGuestPath(rootfs, "/root/.pi/agent")

        assertEquals(RemovedOutcome.Absent, removed.outcome)
        assertEquals("не было: /root/.pi/agent", AgentVersions.removalLine(removed))
    }

    @Test
    fun `a tree that cannot be emptied is a failure, and only what went is counted`() {
        assumeTrue("a permission failure needs a non-root user", System.getProperty("user.name") != "root")
        val dir = File(rootfs, "root/.hermes")
        val locked = File(dir, "hermes-agent").apply { mkdirs() }
        File(dir, "hermes.log").writeBytes(ByteArray(700))
        File(locked, "checkout.lock").writeBytes(ByteArray(300))
        // The tree can be walked but not unlinked: the files inside `locked` stay.
        assumeTrue("could not take the write bit off the directory", locked.setWritable(false, false))

        val removed = deleteGuestPath(rootfs, "/root/.hermes")

        val outcome = assertIs<RemovedOutcome.Failed>(removed.outcome)
        assertEquals(700L, outcome.freed, "only the file that actually went is counted")
        assertTrue(outcome.reason.isNotBlank())
        assertTrue(dir.exists(), "the tree is still on disk, and the log has to say so")
        assertEquals(
            "не удалось: /root/.hermes — ${outcome.reason} (частично освобождено 700 B)",
            AgentVersions.removalLine(removed),
        )
    }
}
