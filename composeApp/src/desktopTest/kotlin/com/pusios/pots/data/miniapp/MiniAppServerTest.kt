package com.pusios.pots.data.miniapp

import com.pusios.pots.data.AppSettings
import com.russhwolf.settings.MapSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MiniAppServerTest {

    private val settings = AppSettings(MapSettings())
    private val repository = MiniAppRepository(settings, SettingsMiniAppPersistence(settings))
    private val server = createMiniAppServer(repository, settings)

    @AfterTest
    fun tearDown() {
        server.stop()
        kotlinx.coroutines.runBlocking { repository.delete("server-test") }
    }

    @Test
    fun servesHealthAndWebFiles() {
        val freePort = ServerSocket(0).use { it.localPort }
        server.setPort(freePort)
        server.start()
        try {
            val health = get("http://127.0.0.1:$freePort/api/health")
            assertTrue(health.contains("\"ok\":true"))

            repository.saveDraft(
                MiniAppDraft(
                    id = "server-test",
                    name = "Server test",
                    screens = emptyList(),
                    kind = MiniAppKind.WEB,
                    entryPoint = "web/index.html",
                ),
            )
            repository.writeFile("server-test", "web/index.html", "<h1>hello</h1>")

            val page = get("http://127.0.0.1:$freePort/apps/server-test/web/index.html")
            assertEquals("<h1>hello</h1>", page)
        } finally {
            server.stop()
        }
    }

    @Test
    fun hostFilesPreserveJsonStringContents() {
        val freePort = ServerSocket(0).use { it.localPort }
        server.setPort(freePort)
        server.start()
        val base = "http://127.0.0.1:$freePort/api/host/files"
        val name = "pots-server-test-${System.nanoTime()}.txt"
        val content = "First line\nSecond \"quoted\" line \\ end"
        try {
            val connection = URL(base).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.bufferedWriter().use {
                it.write(
                    buildJsonObject {
                        put("path", name)
                        put("content", content)
                    }.toString(),
                )
            }
            assertEquals(200, connection.responseCode)
            assertEquals(content, get("$base?content=$name"))
        } finally {
            val connection = URL("$base?name=$name").openConnection() as HttpURLConnection
            connection.requestMethod = "DELETE"
            connection.responseCode
        }
    }

    @Test
    fun savedWebAppCanCreateReadAndDeleteRecords() {
        val freePort = ServerSocket(0).use { it.localPort }
        server.setPort(freePort)
        server.start()
        repository.saveDraft(MiniAppDraft(id = "server-test", name = "Server test", screens = emptyList()))
        val url = "http://127.0.0.1:$freePort/api/apps/server-test/records/items"
        val create = URL(url).openConnection() as HttpURLConnection
        create.requestMethod = "POST"
        create.doOutput = true
        create.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        create.outputStream.bufferedWriter().use { it.write("""{"title":"Молоко"}""") }
        val id = Json.parseToJsonElement(create.inputStream.bufferedReader().use { it.readText() }).jsonObject.getValue("id").jsonPrimitive.content
        val records = Json.parseToJsonElement(get(url)).jsonArray
        assertEquals("Молоко", records.single().jsonObject.getValue("value").jsonObject.getValue("title").jsonPrimitive.content)
        val delete = URL("$url?recordId=$id").openConnection() as HttpURLConnection
        delete.requestMethod = "DELETE"
        assertEquals(200, delete.responseCode)
        assertTrue(Json.parseToJsonElement(get(url)).jsonArray.isEmpty())
    }

    @Test
    fun renamingAndResavingKeepsWebAssets() {
        repository.writeFile("server-test", "web/index.html", "original page")
        repository.saveDraft(MiniAppDraft(id = "server-test", name = "Original", screens = emptyList(), kind = MiniAppKind.WEB, entryPoint = "web/index.html"))
        repository.updateMetadata("server-test", "Renamed", "")
        assertEquals("original page", repository.readFile("server-test", "web/index.html"))
        repository.writeFile("new-conversation", "web/app.js", "new script")
        repository.saveDraft(MiniAppDraft(id = "server-test", name = "Renamed", screens = emptyList(), sourceConversationId = "new-conversation", kind = MiniAppKind.WEB, entryPoint = "web/index.html"))
        assertEquals("original page", repository.readFile("server-test", "web/index.html"))
        assertEquals("new script", repository.readFile("server-test", "web/app.js"))
        kotlinx.coroutines.runBlocking { repository.delete("new-conversation") }
    }

    private fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        return connection.inputStream.bufferedReader().use { it.readText() }
    }
}
