package com.pusios.pots.build

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The debris sweep may only take what a failed install leaves and may never
 * touch the metadata beside it. The cases run against real temporary files,
 * because a matcher that is one character too wide would delete the plugin
 * pins — the owner's data — and look identical to a clean one from the outside.
 */
class StaleInstallCleanupTest {

    private lateinit var dir: File

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("stale-install").toFile()
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `stale install cleanup never touches install metadata`() {
        val debris = listOf(".install-2576o5_f", ".install-fi5bt9ph")
        debris.forEach { File(dir, "$it/plugin/.git/objects/pack").mkdirs() }
        val metadata = File(dir, ".install-metadata.json").apply { writeText("{}") }
        val lock = File(dir, ".install-metadata.json.lock").apply { createNewFile() }
        val plugins = File(dir, "gadget").apply { mkdirs() }

        val removed = cleanStaleInstallDirs(dir)

        assertEquals(2, removed)
        debris.forEach { assertFalse(File(dir, it).exists(), "$it must be removed") }
        assertTrue(metadata.exists(), ".install-metadata.json holds plugin pins — user data")
        assertTrue(lock.exists(), ".install-metadata.json.lock must survive")
        assertTrue(plugins.exists(), "real plugin dirs must survive")
    }

    @Test
    fun `names that are not exactly install plus eight tempfile characters survive`() {
        val notDebris = listOf(".install-metadata.json", ".install-short1", ".install-longer123", "install-ab12cd34")
        notDebris.forEach { File(dir, it).apply { mkdir() } }

        val removed = cleanStaleInstallDirs(dir)

        assertEquals(0, removed)
        notDebris.forEach { assertTrue(File(dir, it).exists(), "$it must survive") }
    }

    @Test
    fun `the sweep is idempotent`() {
        File(dir, ".install-ab12cd34/.git").mkdirs()

        assertEquals(1, cleanStaleInstallDirs(dir))
        assertEquals(0, cleanStaleInstallDirs(dir))
    }

    @Test
    fun `a missing directory is nothing to sweep`() {
        assertEquals(0, cleanStaleInstallDirs(File(dir, "does-not-exist")))
    }
}
