package com.pusios.pots.ui.miniapp

import com.pusios.pots.data.AppSettings
import com.pusios.pots.data.miniapp.MiniAppRepository
import com.pusios.pots.data.miniapp.SettingsMiniAppPersistence
import com.pusios.pots.ui.dynamicui.StoreDeleteAction
import com.pusios.pots.ui.dynamicui.StorePutAction
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MiniAppSessionTest {

    private fun repository(): MiniAppRepository {
        val settings = AppSettings(MapSettings())
        return MiniAppRepository(settings, SettingsMiniAppPersistence(settings))
    }

    @Test
    fun storePutWritesRecordAndBumpsRevision() {
        val repository = repository()
        var navigated: String? = null
        val session = MiniAppSession("draft", repository, onNavigate = { navigated = it })
        val before = session.revision.value

        session.onStorePut(
            StorePutAction(collection = "tasks", navigateTo = "done"),
            mapOf("title" to "Buy milk", "done" to "false"),
        )

        val record = repository.records("draft", "tasks").single()
        assertEquals("Buy milk", record.value["title"]?.toString()?.trim('"'))
        assertEquals("false", record.value["done"]?.toString())
        assertEquals("done", navigated)
        assertTrue(session.revision.value > before)
    }

    @Test
    fun storeDeleteRemovesOneRecordOrTheWholeCollection() {
        val repository = repository()
        val session = MiniAppSession("draft", repository)
        session.onStorePut(StorePutAction(collection = "tasks"), mapOf("title" to "a"))
        session.onStorePut(StorePutAction(collection = "tasks"), mapOf("title" to "b"))
        val first = repository.records("draft", "tasks").first()

        session.onStoreDelete(
            StoreDeleteAction(collection = "tasks", recordId = "{{record.id}}"),
            mapOf("recordId" to first.recordId),
        )
        assertEquals(1, repository.records("draft", "tasks").size)

        session.onStoreDelete(StoreDeleteAction(collection = "tasks"), emptyMap())
        assertTrue(repository.records("draft", "tasks").isEmpty())
    }

    @Test
    fun recordIdTemplateUsesCollectedFormValue() {
        val repository = repository()
        val session = MiniAppSession("draft", repository)
        session.onStorePut(
            StorePutAction(collection = "notes", recordId = "{{form.id}}"),
            mapOf("id" to "fixed-id", "title" to "x"),
        )
        assertEquals("fixed-id", repository.records("draft", "notes").single().recordId)
        assertTrue(repository.records("draft", "notes").single().value.containsKey("id"))
    }
}
