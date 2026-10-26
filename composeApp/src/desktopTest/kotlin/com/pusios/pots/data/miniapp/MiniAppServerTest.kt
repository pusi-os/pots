package com.pusios.pots.data.miniapp

import com.pusios.pots.data.AppSettings
import com.russhwolf.settings.MapSettings
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
        repository.delete("server-test")
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

    private fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        return connection.inputStream.bufferedReader().use { it.readText() }
    }
}
