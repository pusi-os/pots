package com.pusios.pots.data.miniapp

import com.pusios.pots.data.AppSettings
import com.russhwolf.settings.MapSettings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MiniAppRepositoryTest {

    private fun repository(): MiniAppRepository {
        val settings = AppSettings(MapSettings())
        return MiniAppRepository(settings, SettingsMiniAppPersistence(settings))
    }

    private fun draft(
        id: String = "app-1",
        name: String = "Tracker",
        source: String? = null,
        screens: List<MiniAppScreen> = listOf(MiniAppScreen("s1", "Home", "{}")),
    ) = MiniAppDraft(
        id = id,
        name = name,
        description = "A tiny tracker",
        screens = screens,
        sourceConversationId = source,
    )

    @Test
    fun saveCreatesFirstVersionAndUpdatesTree() {
        val repository = repository()
        repository.saveDraft(draft())
        repository.saveDraft(draft(name = "Tracker 2"))

        val app = assertNotNull(repository.get("app-1"))
        assertEquals("Tracker 2", app.name)
        val versions = repository.versions("app-1")
        assertEquals(listOf(2, 1), versions.map { it.version })
        assertEquals(null, versions.last().parentVersion)
        assertEquals(1, versions.first().parentVersion)
        assertEquals(2, repository.versionCount("app-1"))
    }

    @Test
    fun recordsFromDraftConversationMoveToTheSavedApp() {
        val repository = repository()
        repository.putRecord(
            "conversation-1",
            "tasks",
            "r1",
            buildJsonObject { put("title", JsonPrimitive("Buy milk")) },
        )

        repository.saveDraft(draft(id = "app-1", source = "conversation-1"))

        val records = repository.records("app-1", "tasks")
        assertEquals(1, records.size)
        assertEquals("Buy milk", records.first().value["title"]?.toString()?.trim('"'))
    }

    @Test
    fun resavingDoesNotOverwriteNewerAppRecordsWithOldConversationData() {
        val settings = AppSettings(MapSettings())
        val persistence = SettingsMiniAppPersistence(settings)
        val repository = MiniAppRepository(settings, persistence)
        persistence.upsertRecord(MiniAppRecord("conversation", "tasks", "one", buildJsonObject { put("title", JsonPrimitive("old")) }, updatedAt = 10))
        repository.saveDraft(draft(id = "app-1", source = "conversation"))
        persistence.upsertRecord(MiniAppRecord("app-1", "tasks", "one", buildJsonObject { put("title", JsonPrimitive("new")) }, updatedAt = 20))
        repository.saveDraft(draft(id = "app-1", source = "conversation"))
        assertEquals(JsonPrimitive("new"), repository.records("app-1", "tasks").single().value["title"])
    }

    @Test
    fun resavingDoesNotResurrectDeletedConversationRecords() {
        val repository = repository()
        repository.putRecord("conversation", "tasks", "one", buildJsonObject { put("title", JsonPrimitive("old")) })
        repository.saveDraft(draft(id = "app-1", source = "conversation"))
        repository.deleteRecord("app-1", "tasks", "one")
        repository.saveDraft(draft(id = "app-1", source = "conversation"))
        assertTrue(repository.records("app-1", "tasks").isEmpty())
    }

    @Test
    fun collectionsDeclaredOnADraftSurviveSave() {
        val repository = repository()
        repository.setDraftCollections(
            "conversation-1",
            listOf(MiniAppCollection("tasks", listOf(MiniAppField("title")))),
        )
        repository.saveDraft(draft(id = "app-1", source = "conversation-1"))

        assertEquals(listOf("tasks"), repository.get("app-1")!!.collections.map { it.name })
    }

    @Test
    fun conversationDeclarationsMergeIntoExistingEditorDraft() {
        val repository = repository()
        repository.setDraftCollections("editor", listOf(MiniAppCollection("old")))
        repository.setDraftCollections("conversation", listOf(MiniAppCollection("new")))
        repository.saveDraft(draft(id = "editor", source = "conversation"))
        assertEquals(setOf("old", "new"), repository.get("editor")!!.collections.map { it.name }.toSet())
    }

    @Test
    fun editingForkKeepsCollectionsAndDataIndependent() {
        val repository = repository()
        repository.setDraftCollections("original", listOf(MiniAppCollection("tasks", listOf(MiniAppField("title")))))
        repository.saveDraft(draft(id = "original"))
        repository.putRecord("original", "tasks", "one", buildJsonObject { put("title", JsonPrimitive("Original")) })

        assertTrue(repository.forkToDraft("original", "copy"))
        repository.putRecord("copy", "tasks", "one", buildJsonObject { put("title", JsonPrimitive("Edited")) })
        repository.saveDraft(draft(id = "copy"))

        assertEquals(listOf("tasks"), repository.get("copy")!!.collections.map { it.name })
        assertEquals(JsonPrimitive("Original"), repository.records("original", "tasks").single().value["title"])
        assertEquals(JsonPrimitive("Edited"), repository.records("copy", "tasks").single().value["title"])
        assertEquals(1, repository.versionCount("original"))
    }

    @Test
    fun exportImportRoundTripsScreensAndData() {
        val repository = repository()
        repository.saveDraft(draft())
        repository.putRecord("app-1", "tasks", "r1", buildJsonObject { put("title", JsonPrimitive("One")) })
        val exported = assertNotNull(repository.export("app-1"))

        val other = repository()
        val imported = other.import(exported).getOrThrow()
        assertEquals("app-1", imported.id)
        assertEquals("Tracker", imported.name)
        assertEquals(1, other.records("app-1", "tasks").size)
        assertEquals(1, other.versionCount("app-1"))
    }

    @Test
    fun importingTheSameIdAgainGetsANewId() {
        val repository = repository()
        repository.saveDraft(draft())
        val exported = assertNotNull(repository.export("app-1"))

        val imported = repository.import(exported).getOrThrow()
        assertNotEquals("app-1", imported.id)
        assertEquals(2, repository.apps.value.size)
    }

    @Test
    fun toolbarSelectionPersistsAndPinnedAppsFollowIt() {
        val settings = AppSettings(MapSettings())
        val repository = MiniAppRepository(settings, SettingsMiniAppPersistence(settings))
        repository.saveDraft(draft(id = "a", name = "A"))
        repository.saveDraft(draft(id = "b", name = "B"))

        repository.setToolbarIds(listOf("b"))
        assertEquals(listOf("b"), repository.pinnedApps().map { it.id })

        val reopened = MiniAppRepository(settings, SettingsMiniAppPersistence(settings))
        assertEquals(listOf("b"), reopened.toolbarIds.value)
    }

    @Test
    fun pinToToolbarAddsTheAppToAnExplicitSelection() {
        val repository = repository()
        repository.saveDraft(draft(id = "a", name = "A"))
        repository.saveDraft(draft(id = "b", name = "B"))
        repository.setToolbarIds(listOf("a"))

        repository.pinToToolbar("b")

        assertEquals(listOf("a", "b"), repository.pinnedApps().map { it.id })
    }

    @Test
    fun pinToToolbarIsANoOpWithoutAnExplicitSelection() {
        val repository = repository()
        repository.saveDraft(draft(id = "a", name = "A"))

        // null selection means "show every visible app", so pinning must not
        // convert it into a one-app selection.
        repository.pinToToolbar("a")

        assertNull(repository.toolbarIds.value)
        assertEquals(listOf("a"), repository.pinnedApps().map { it.id })
    }

    @Test
    fun pinningAnAlreadyPinnedAppDoesNotDuplicateIt() {
        val repository = repository()
        repository.saveDraft(draft(id = "a", name = "A"))
        repository.setToolbarIds(listOf("a"))

        repository.pinToToolbar("a")

        assertEquals(listOf("a"), repository.toolbarIds.value)
    }

    @Test
    fun deleteRemovesAppHistoryAndData() {
        val repository = repository()
        repository.saveDraft(draft())
        repository.putRecord("app-1", "tasks", null, JsonObject(emptyMap()))
        repository.delete("app-1")

        assertNull(repository.get("app-1"))
        assertTrue(repository.versions("app-1").isEmpty())
        assertTrue(repository.records("app-1", "tasks").isEmpty())
    }
}
