package com.pusios.pots.adb

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The device registry against a real directory: persistence, replacement by id, and corrupt input. */
class AdbDeviceRegistryTest {
    private lateinit var directory: File

    @BeforeTest
    fun setUp() {
        directory = Files.createTempDirectory("pots-adb-registry").toFile()
    }

    @AfterTest
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun device(endpoint: String, label: String = "Pixel", trusted: Boolean = true) = AdbDevice(
        id = endpoint,
        label = label,
        host = endpoint.substringBefore(':'),
        port = 5555,
        tls = true,
        trusted = trusted,
    )

    @Test
    fun `an absent registry is empty`() {
        assertTrue(AdbDeviceRegistry(directory).all().isEmpty())
    }

    @Test
    fun `upsert replaces the entry with the same id`() {
        val registry = AdbDeviceRegistry(directory)
        registry.upsert(device("192.168.1.5:5555"))
        registry.upsert(device("192.168.1.6:5555"))
        registry.upsert(device("192.168.1.5:5555", label = "Renamed"))

        val reloaded = AdbDeviceRegistry(directory).all()
        assertEquals(2, reloaded.size)
        assertEquals("Renamed", reloaded.first { it.id == "192.168.1.5:5555" }.label)
    }

    @Test
    fun `trust can be flipped and a device forgotten`() {
        val registry = AdbDeviceRegistry(directory)
        registry.upsert(device("192.168.1.5:5555", trusted = false))

        assertFalse(registry.all().single().trusted)
        assertTrue(registry.setTrusted("192.168.1.5:5555", true).single().trusted)
        assertTrue(AdbDeviceRegistry(directory).all().single().trusted)
        assertFalse(registry.setTrusted("192.168.1.5:5555", false).single().trusted)
        assertTrue(registry.remove("192.168.1.5:5555").isEmpty())
        assertTrue(AdbDeviceRegistry(directory).all().isEmpty())
    }

    @Test
    fun `touch records the model and the timestamp`() {
        val registry = AdbDeviceRegistry(directory)
        registry.upsert(device("192.168.1.5:5555"))

        val touched = registry.touch("192.168.1.5:5555", lastModel = "Pixel 8", lastSeenEpochMs = 1_700_000_000_000L).single()
        assertEquals("Pixel 8", touched.lastModel)
        assertEquals(1_700_000_000_000L, touched.lastSeenEpochMs)

        // A later connect that does not know the model must not erase the one we have.
        val again = registry.touch("192.168.1.5:5555", lastModel = null, lastSeenEpochMs = 1_700_000_001_000L).single()
        assertEquals("Pixel 8", again.lastModel)
        assertEquals(1_700_000_001_000L, again.lastSeenEpochMs)
    }

    @Test
    fun `a corrupt file degrades to an empty list`() {
        directory.mkdirs()
        File(directory, "devices.json").writeText("{ this is not json")

        assertTrue(AdbDeviceRegistry(directory).all().isEmpty())
        // And it recovers: the next write replaces the broken file.
        assertEquals(1, AdbDeviceRegistry(directory).upsert(device("192.168.1.5:5555")).size)
    }
}
