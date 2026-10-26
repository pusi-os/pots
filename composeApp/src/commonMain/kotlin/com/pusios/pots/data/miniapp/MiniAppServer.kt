package com.pusios.pots.data.miniapp

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock

/** What a web mini-app may ask the host to do. */
interface MiniAppHostBridge {
    /** Calendar events in a window, or null when the host/platform cannot provide them. */
    fun listCalendar(fromMs: Long, toMs: Long): List<Map<String, Any?>>?

    /** Creates an event. Null when unsupported; a negative value means permission denied. */
    fun createCalendar(
        title: String,
        startIso: String,
        endIso: String?,
        notes: String?,
    ): Long?

    /** Files under `mini-apps/user/<path>`, the app's window onto user notes and files. */
    fun listFiles(path: String): List<Map<String, Any?>> {
        val safe = path.replace('\\', '/').trim('/')
        if (safe.split('/').any { it == ".." }) return emptyList()
        val base = "mini-apps/user" + if (safe.isBlank()) "" else "/$safe"
        return com.pusios.pots.listAppStorageFiles(base).map { file ->
            mapOf(
                "path" to file.removePrefix("mini-apps/user/"),
                "bytes" to (com.pusios.pots.readAppStorageBytes(file)?.size ?: 0),
            )
        }
    }

    fun readFile(path: String): String? {
        val safe = path.replace('\\', '/').trim('/')
        if (safe.isEmpty() || safe.split('/').any { it == ".." }) return null
        return com.pusios.pots.readAppStorageBytes("mini-apps/user/$safe")?.decodeToString()
    }

    fun writeFile(path: String, content: String): Boolean {
        val safe = path.replace('\\', '/').trim('/')
        if (safe.isEmpty() || safe.split('/').any { it == ".." }) return false
        return com.pusios.pots.writeAppStorageBytes("mini-apps/user/$safe", content.encodeToByteArray())
    }

    fun deleteFile(path: String): Boolean {
        val safe = path.replace('\\', '/').trim('/')
        if (safe.isEmpty() || safe.split('/').any { it == ".." }) return false
        return com.pusios.pots.deleteAppStorageFolder("mini-apps/user/$safe")
    }
}

expect fun createMiniAppHostBridge(): MiniAppHostBridge

/** The built-in HTTP server that serves apps and their host API. */
interface MiniAppServer {
    val state: StateFlow<MiniAppServerState>

    fun start()
    fun stop()
    fun restart()
    fun setPort(port: Int)
}

@Immutable
data class MiniAppServerState(
    val running: Boolean = false,
    val port: Int = DEFAULT_PORT,
    val startedAtEpochMs: Long = 0L,
    val requests: Long = 0L,
    val bytesServed: Long = 0L,
    val errors: Long = 0L,
    val lastError: String? = null,
) {
    val url: String get() = "http://127.0.0.1:$port"

    fun uptimeMs(now: Long = Clock.System.now().toEpochMilliseconds()): Long = if (running && startedAtEpochMs > 0) (now - startedAtEpochMs).coerceAtLeast(0) else 0

    companion object {
        const val DEFAULT_PORT = 8099
        val PortRange = 1024..65535
    }
}

class NoOpMiniAppServer : MiniAppServer {
    private val _state = kotlinx.coroutines.flow.MutableStateFlow(MiniAppServerState())
    override val state: StateFlow<MiniAppServerState> = _state

    override fun start() {
        _state.value = _state.value.copy(lastError = "The built-in server is not available on this platform")
    }

    override fun stop() {}
    override fun restart() {}
    override fun setPort(port: Int) {
        _state.value = _state.value.copy(port = port)
    }
}

expect fun createMiniAppServer(repository: MiniAppRepository, appSettings: com.pusios.pots.data.AppSettings): MiniAppServer
