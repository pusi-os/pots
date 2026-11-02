package com.pusios.pots.ui.miniapp

import com.pusios.pots.data.AppSettings
import com.pusios.pots.data.miniapp.MiniAppCollection
import com.pusios.pots.data.miniapp.MiniAppRepository
import com.pusios.pots.data.miniapp.SettingsMiniAppPersistence
import com.pusios.pots.deleteAppStorageFolder
import com.russhwolf.settings.MapSettings
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MiniAppSaveStatsTest {

    // Desktop app storage is a real shared folder, so the draft folders these
    // tests touch must be cleaned before and after each run.
    private val folders = listOf("mini-apps/.drafts/draft-1", "mini-apps/.drafts/conversation-1")

    @BeforeTest
    fun cleanBefore() = folders.forEach { deleteAppStorageFolder(it) }

    @AfterTest
    fun cleanAfter() = folders.forEach { deleteAppStorageFolder(it) }

    private fun repository(): MiniAppRepository {
        val settings = AppSettings(MapSettings())
        return MiniAppRepository(settings, SettingsMiniAppPersistence(settings))
    }

    private fun recordValue(title: String) = buildJsonObject { put("title", JsonPrimitive(title)) }

    @Test
    fun statsPreviewMatchesWhatTheSaveMerges() {
        val repository = repository()
        // The agent declares a collection and writes a record under the
        // conversation id; the save merges it with the draft id.
        repository.setDraftCollections("conversation-1", listOf(MiniAppCollection("tasks")))
        repository.setDraftCollections("draft-1", listOf(MiniAppCollection("tasks"), MiniAppCollection("tags")))
        repository.putRecord("conversation-1", "tasks", "r1", recordValue("from conversation"))
        repository.putRecord("draft-1", "tasks", "r1", recordValue("from draft"))
        repository.putRecord("draft-1", "tasks", "r2", recordValue("another"))
        repository.writeFile("draft-1", "index.html", "<html></html>")

        val stats = repository.saveStats("draft-1", "conversation-1", screenCount = 3)

        assertEquals(3, stats.screens)
        assertEquals(2, stats.collections)
        assertEquals(2, stats.records)
        assertEquals(1, stats.files)
        assertTrue(stats.hasBackend)
    }

    @Test
    fun emptyBackendReportsNoBackend() {
        val repository = repository()

        val stats = repository.saveStats("draft-1", null, screenCount = 1)

        assertEquals(1, stats.screens)
        assertEquals(0, stats.collections)
        assertEquals(0, stats.records)
        assertEquals(0, stats.files)
        assertFalse(stats.hasBackend)
    }

    @Test
    fun theSameIdAsDraftAndConversationIsCountedOnce() {
        val repository = repository()
        repository.setDraftCollections("draft-1", listOf(MiniAppCollection("tasks")))
        repository.putRecord("draft-1", "tasks", "r1", recordValue("one"))

        val stats = repository.saveStats("draft-1", "draft-1", screenCount = 1)

        assertEquals(1, stats.collections)
        assertEquals(1, stats.records)
    }
}
