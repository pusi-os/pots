package com.pusios.pots.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Project storage under `media/projects/`: the active snapshot plus named
 * projects. The store is injectable over the app-storage primitives, so the
 * test runs on an in-memory map.
 */
class MediaProjectStoreTest {

    private class FakeStorage {
        val files = mutableMapOf<String, String>()

        fun read(path: String): ByteArray? = files[path]?.encodeToByteArray()
        fun write(path: String, content: String): Boolean {
            files[path] = content
            return true
        }
        fun list(dir: String): List<String> = files.keys.filter { it.startsWith("$dir/") }
        fun delete(path: String): Boolean = files.remove(path) != null
    }

    private fun store(storage: FakeStorage): MediaProjectStore = MediaProjectStore(
        readBytes = storage::read,
        writeText = storage::write,
        listPaths = storage::list,
        deletePath = storage::delete,
    )

    private fun project(name: String, updatedAtMs: Long, elements: Int = 1) = MediaProjectFile(
        schemaVersion = MEDIA_PROJECT_SCHEMA_VERSION,
        name = name,
        createdAtMs = updatedAtMs - 1000L,
        updatedAtMs = updatedAtMs,
        elements = List(elements) { i -> MediaProjectElement(id = "el-$i") },
    )

    @Test
    fun saveAndLoadRoundTrip() {
        val storage = FakeStorage()
        val st = store(storage)
        val saved = project("Проект, 14 ноя", 1000L)
        assertTrue(st.save("abc-123", saved))
        assertEquals(saved, st.load("abc-123"))
    }

    @Test
    fun loadMissingReturnsNull() {
        assertNull(store(FakeStorage()).load("nope"))
    }

    @Test
    fun listExcludesActiveSnapshotAndSortsNewestFirst() {
        val storage = FakeStorage()
        val st = store(storage)
        st.save(MediaProjectStore.ACTIVE_ID, project("Активный черновик", 5000L))
        st.save("p1", project("Старый", 1000L))
        st.save("p2", project("Новый", 3000L))
        val list = st.list()
        assertEquals(listOf("p2", "p1"), list.map { it.id })
        assertEquals("Новый", list[0].name)
        assertEquals(3000L, list[0].updatedAtMs)
        assertEquals(1, list[0].elementCount)
        assertTrue(list.none { it.id == MediaProjectStore.ACTIVE_ID })
    }

    @Test
    fun deleteRemovesTheProject() {
        val storage = FakeStorage()
        val st = store(storage)
        st.save("p1", project("Проект", 1000L))
        assertTrue(st.delete("p1"))
        assertNull(st.load("p1"))
        assertFalse(st.delete("p1"))
    }

    @Test
    fun pathSeparatorsInIdRefused() {
        val storage = FakeStorage()
        val st = store(storage)
        assertFalse(st.save("../evil", project("x", 1L)))
        assertFalse(st.save("a/b", project("x", 1L)))
        assertTrue(storage.files.isEmpty())
    }

    @Test
    fun corruptFileSkippedInListAndLoad() {
        val storage = FakeStorage()
        val st = store(storage)
        st.save("good", project("Хороший", 1000L))
        storage.files["media/projects/broken.$MEDIA_PROJECT_EXTENSION"] = "не файл проекта"
        val list = st.list()
        assertEquals(listOf("good"), list.map { it.id })
        assertNull(st.load("broken"))
    }
}
