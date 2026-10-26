package com.pusios.pots.data.miniapp

import com.pusios.pots.data.AppSettings
import com.pusios.pots.data.SharedJson
import com.pusios.pots.deleteAppStorageFolder
import com.pusios.pots.listAppStorageFiles
import com.pusios.pots.readAppStorageBytes
import com.pusios.pots.writeAppStorageBytes
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.time.Instant

actual fun createMiniAppServer(repository: MiniAppRepository, appSettings: AppSettings): MiniAppServer = NanoMiniAppServer(repository, appSettings, createMiniAppHostBridge())

/**
 * The built-in server: static app folders plus a small same-origin JSON API for
 * records, files, notes and the calendar. It binds loopback only, so the phone's
 * browser can open an app while nobody on the network can.
 */
class NanoMiniAppServer(
    private val repository: MiniAppRepository,
    private val appSettings: AppSettings,
    private val host: MiniAppHostBridge,
) : MiniAppServer {

    private val _state = MutableStateFlow(
        MiniAppServerState(port = appSettings.getMiniAppServerPort()),
    )
    override val state: StateFlow<MiniAppServerState> = _state.asStateFlow()

    private var server: NanoHTTPD? = null
    private val lock = Any()

    override fun start() {
        synchronized(lock) {
            if (server != null) return
            val port = appSettings.getMiniAppServerPort().coerceIn(MiniAppServerState.PortRange)
            try {
                val http = object : NanoHTTPD("127.0.0.1", port) {
                    override fun serve(session: IHTTPSession): Response = handle(session)
                }
                http.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                server = http
                _state.update {
                    it.copy(
                        running = true,
                        port = port,
                        startedAtEpochMs = System.currentTimeMillis(),
                        lastError = null,
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(running = false, lastError = e.message ?: "Could not start the server")
                }
            }
        }
    }

    override fun stop() {
        synchronized(lock) {
            server?.stop()
            server = null
            _state.update { it.copy(running = false, startedAtEpochMs = 0L) }
        }
    }

    override fun restart() {
        stop()
        start()
    }

    override fun setPort(port: Int) {
        val safe = port.coerceIn(MiniAppServerState.PortRange)
        appSettings.setMiniAppServerPort(safe)
        _state.update { it.copy(port = safe) }
        if (server != null) restart()
    }

    private fun handle(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        _state.update { it.copy(requests = it.requests + 1) }
        return try {
            if (session.method == NanoHTTPD.Method.OPTIONS) {
                return cors(NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NO_CONTENT, "text/plain", ""))
            }
            if (!sameOrigin(session)) {
                return cors(error(NanoHTTPD.Response.Status.FORBIDDEN, "cross-origin request refused"))
            }
            val path = URLDecoder.decode(session.uri, "UTF-8")
            val response = when {
                path == "/" -> index()
                path == "/api/health" -> health()
                path == "/api/apps" -> apps()
                path.startsWith("/apps/") -> staticFile(path.removePrefix("/apps/"))
                path.startsWith("/api/apps/") -> appApi(session, path.removePrefix("/api/apps/"))
                path.startsWith("/api/host/") -> hostApi(session, path.removePrefix("/api/host/"))
                else -> error(NanoHTTPD.Response.Status.NOT_FOUND, "not found")
            }
            cors(response)
        } catch (e: Exception) {
            _state.update { it.copy(errors = it.errors + 1, lastError = e.message) }
            cors(error(NanoHTTPD.Response.Status.INTERNAL_ERROR, e.message ?: "server error"))
        }
    }

    private fun sameOrigin(session: NanoHTTPD.IHTTPSession): Boolean {
        val origin = session.headers["origin"] ?: session.headers["referer"] ?: return true
        return origin.startsWith("http://127.0.0.1:") ||
            origin.startsWith("http://localhost:") ||
            origin == "null"
    }

    private fun cors(response: NanoHTTPD.Response): NanoHTTPD.Response {
        val port = _state.value.port
        response.addHeader("Access-Control-Allow-Origin", "http://127.0.0.1:$port")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type")
        response.addHeader("Cache-Control", "no-store")
        return response
    }

    private fun index(): NanoHTTPD.Response {
        val apps = repository.apps.value
        val body = buildString {
            append("<!doctype html><meta charset=utf-8><title>Pots mini-apps</title>")
            append("<style>body{font:16px system-ui;background:#12121a;color:#eee;padding:24px}a{color:#b8a1ff}</style>")
            append("<h1>Pots mini-apps</h1><p>Server on port ").append(_state.value.port).append("</p><ul>")
            apps.forEach { app ->
                val entry = app.entryPoint ?: "web/index.html"
                append("<li><a href=\"/apps/").append(app.id).append("/").append(entry).append("\">")
                append(app.icon.ifBlank { "🧩" }).append(" ").append(app.name).append("</a></li>")
            }
            append("</ul>")
        }
        return html(body)
    }

    private fun health(): NanoHTTPD.Response {
        val s = _state.value
        return json(
            buildJsonObject {
                put("ok", s.running)
                put("port", s.port)
                put("uptimeMs", s.uptimeMs())
                put("requests", s.requests)
                put("bytesServed", s.bytesServed)
                put("errors", s.errors)
                put("apps", repository.apps.value.size)
            },
        )
    }

    private fun apps(): NanoHTTPD.Response = json(
        buildJsonArray {
            repository.apps.value.forEach { app ->
                add(
                    buildJsonObject {
                        put("id", app.id)
                        put("name", app.name)
                        put("description", app.description)
                        put("icon", app.icon)
                        put("kind", app.kind.name.lowercase())
                        put("entryPoint", app.entryPoint)
                        put("screens", app.screens.size)
                        put("collections", app.collections.size)
                        put("files", app.files.size)
                    },
                )
            }
        },
    )

    private fun staticFile(relative: String): NanoHTTPD.Response {
        val clean = relative.trimStart('/')
        val (appId, rest) = clean.split('/', limit = 2).let {
            it.firstOrNull().orEmpty() to it.getOrNull(1).orEmpty()
        }
        if (appId.isBlank()) return error(NanoHTTPD.Response.Status.NOT_FOUND, "missing app")
        val app = repository.get(appId) ?: return error(NanoHTTPD.Response.Status.NOT_FOUND, "unknown app")
        if (rest.contains("..")) return error(NanoHTTPD.Response.Status.FORBIDDEN, "bad path")
        val requested = rest.ifBlank { app.entryPoint ?: "web/index.html" }
        val withIndex = if (requested.endsWith("/")) requested + "index.html" else requested
        val bytes = repository.readFileBytes(app.id, withIndex)
            ?: repository.readFileBytes(app.id, "web/$withIndex")
            ?: return error(NanoHTTPD.Response.Status.NOT_FOUND, "not found")
        val response = NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            mimeType(withIndex),
            ByteArrayInputStream(bytes),
            bytes.size.toLong(),
        )
        _state.update { it.copy(bytesServed = it.bytesServed + bytes.size) }
        response.addHeader("X-Content-Type-Options", "nosniff")
        response.addHeader(
            "Content-Security-Policy",
            "default-src 'self' 'unsafe-inline' 'unsafe-eval' data: blob:; connect-src 'self' http://127.0.0.1:* http://localhost:*",
        )
        return response
    }

    private fun appApi(session: NanoHTTPD.IHTTPSession, rest: String): NanoHTTPD.Response {
        val parts = rest.split('/').filter { it.isNotEmpty() }
        val appId = parts.getOrNull(0) ?: return error(NanoHTTPD.Response.Status.BAD_REQUEST, "missing app")
        val app = repository.get(appId) ?: return error(NanoHTTPD.Response.Status.NOT_FOUND, "unknown app")
        val resource = parts.getOrNull(1)
        return when {
            resource == "manifest" -> json(SharedJson.encodeToJsonElement(app) as JsonObject)
            resource == "collections" -> collections(appId)
            resource == "records" -> recordsApi(session, appId, parts.getOrNull(2))
            else -> error(NanoHTTPD.Response.Status.NOT_FOUND, "unknown resource")
        }
    }

    private fun collections(appId: String): NanoHTTPD.Response {
        val app = repository.get(appId) ?: return error(NanoHTTPD.Response.Status.NOT_FOUND, "unknown app")
        return json(
            buildJsonArray {
                app.collections.forEach { collection ->
                    add(
                        buildJsonObject {
                            put("name", collection.name)
                            put("description", collection.description)
                            put("fields", buildJsonArray { collection.fields.forEach { add(JsonPrimitive(it.name)) } })
                            put("count", repository.records(appId, collection.name).size)
                        },
                    )
                }
            },
        )
    }

    private fun recordsApi(session: NanoHTTPD.IHTTPSession, appId: String, collection: String?): NanoHTTPD.Response {
        if (collection.isNullOrBlank()) return error(NanoHTTPD.Response.Status.BAD_REQUEST, "missing collection")
        return when (session.method) {
            NanoHTTPD.Method.GET -> json(
                buildJsonArray {
                    repository.records(appId, collection).forEach { record ->
                        add(
                            buildJsonObject {
                                put("id", record.recordId)
                                put("value", record.value)
                            },
                        )
                    }
                },
            )

            NanoHTTPD.Method.POST, NanoHTTPD.Method.PUT -> {
                val body = readBody(session)
                val element = runCatching { SharedJson.parseToJsonElement(body) }.getOrNull()
                val value = element as? JsonObject ?: return error(NanoHTTPD.Response.Status.BAD_REQUEST, "record must be a JSON object")
                val recordId = session.parameters["recordId"]?.firstOrNull()
                val record = repository.putRecord(appId, collection, recordId, value)
                json(
                    buildJsonObject {
                        put("ok", true)
                        put("id", record.recordId)
                    },
                )
            }

            NanoHTTPD.Method.DELETE -> {
                val recordId = session.parameters["recordId"]?.firstOrNull()
                if (recordId != null) {
                    repository.deleteRecord(appId, collection, recordId)
                } else {
                    repository.clearCollection(appId, collection)
                }
                json(buildJsonObject { put("ok", true) })
            }

            else -> error(NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED, "method not allowed")
        }
    }

    private fun hostApi(session: NanoHTTPD.IHTTPSession, rest: String): NanoHTTPD.Response {
        val resource = rest.substringBefore('/')
        return when (resource) {
            "info" -> json(
                buildJsonObject {
                    put("platform", "local")
                    put("calendar", host.listCalendar(0, 0) != null)
                    put("files", true)
                },
            )

            "files", "notes" -> hostFiles(session, rest.substringAfter('/', ""), resource == "notes")

            "calendar" -> hostCalendar(session)

            else -> error(NanoHTTPD.Response.Status.NOT_FOUND, "unknown host resource")
        }
    }

    private fun hostFiles(session: NanoHTTPD.IHTTPSession, path: String, notes: Boolean): NanoHTTPD.Response {
        val requested = session.parameters["path"]?.firstOrNull().orEmpty().ifBlank { path }
        val safe = requested.replace('\\', '/').trim('/')
        if (safe.split('/').any { it == ".." }) return error(NanoHTTPD.Response.Status.FORBIDDEN, "bad path")
        val base = "mini-apps/user" + if (notes) "/notes" else ""
        val folder = if (safe.isBlank()) base else "$base/$safe"
        return when (session.method) {
            NanoHTTPD.Method.GET -> {
                val content = session.parameters["content"]?.firstOrNull()
                if (content != null) {
                    val bytes = readAppStorageBytes("$folder/$content") ?: return error(NanoHTTPD.Response.Status.NOT_FOUND, "not found")
                    text(bytes.decodeToString())
                } else {
                    json(
                        buildJsonArray {
                            listAppStorageFiles(folder).forEach { file ->
                                add(
                                    buildJsonObject {
                                        put("path", file.removePrefix("mini-apps/user/"))
                                        put("bytes", readAppStorageBytes(file)?.size ?: 0)
                                    },
                                )
                            }
                        },
                    )
                }
            }

            NanoHTTPD.Method.POST, NanoHTTPD.Method.PUT -> {
                val body = readBody(session)
                val obj = runCatching { SharedJson.parseToJsonElement(body) }.getOrNull() as? JsonObject
                    ?: return error(NanoHTTPD.Response.Status.BAD_REQUEST, "body must be JSON")
                val name = obj["path"]?.toString()?.trim('"')?.takeIf { it.isNotBlank() } ?: return error(NanoHTTPD.Response.Status.BAD_REQUEST, "missing path")
                val content = obj["content"]?.toString()?.trim('"') ?: ""
                val full = if (safe.isBlank()) "$base/$name" else "$base/$safe/$name"
                if (full.split('/').any { it == ".." }) return error(NanoHTTPD.Response.Status.FORBIDDEN, "bad path")
                val ok = writeAppStorageBytes(full, content.encodeToByteArray())
                json(
                    buildJsonObject {
                        put("ok", ok)
                        put("path", full.removePrefix("mini-apps/user/"))
                    },
                )
            }

            NanoHTTPD.Method.DELETE -> {
                val name = session.parameters["name"]?.firstOrNull() ?: return error(NanoHTTPD.Response.Status.BAD_REQUEST, "missing name")
                val full = if (safe.isBlank()) "$base/$name" else "$base/$safe/$name"
                json(buildJsonObject { put("ok", deleteAppStorageFolder(full)) })
            }

            else -> error(NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED, "method not allowed")
        }
    }

    private fun hostCalendar(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        return when (session.method) {
            NanoHTTPD.Method.GET -> {
                val from = parseMillis(session.parameters["from"]?.firstOrNull()) ?: 0L
                val to = parseMillis(session.parameters["to"]?.firstOrNull()) ?: (System.currentTimeMillis() + 7L * 24 * 60 * 60 * 1000)
                val events = host.listCalendar(from, to) ?: return error(NanoHTTPD.Response.Status.NOT_IMPLEMENTED, "calendar unavailable")
                json(buildJsonArray { events.forEach { event -> add(toJson(event)) } })
            }

            NanoHTTPD.Method.POST -> {
                val obj = runCatching { SharedJson.parseToJsonElement(readBody(session)) }.getOrNull() as? JsonObject
                    ?: return error(NanoHTTPD.Response.Status.BAD_REQUEST, "body must be JSON")
                val title = obj["title"]?.toString()?.trim('"')?.takeIf { it.isNotBlank() }
                    ?: return error(NanoHTTPD.Response.Status.BAD_REQUEST, "missing title")
                val start = obj["start"]?.toString()?.trim('"') ?: return error(NanoHTTPD.Response.Status.BAD_REQUEST, "missing start")
                val end = obj["end"]?.toString()?.trim('"')
                val notes = obj["notes"]?.toString()?.trim('"')
                val id = host.createCalendar(title, start, end, notes)
                    ?: return error(NanoHTTPD.Response.Status.NOT_IMPLEMENTED, "calendar unavailable")
                if (id < 0) return error(NanoHTTPD.Response.Status.FORBIDDEN, "calendar permission denied")
                json(
                    buildJsonObject {
                        put("ok", true)
                        put("id", id)
                    },
                )
            }

            else -> error(NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED, "method not allowed")
        }
    }

    private fun parseMillis(value: String?): Long? = value?.let {
        runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
            ?: it.toLongOrNull()
    }

    private fun toJson(map: Map<String, Any?>): JsonObject = buildJsonObject {
        map.forEach { (key, value) ->
            when (value) {
                null -> put(key, JsonPrimitive(""))
                is Boolean -> put(key, JsonPrimitive(value))
                is Number -> put(key, JsonPrimitive(value))
                else -> put(key, JsonPrimitive(value.toString()))
            }
        }
    }

    private fun readBody(session: NanoHTTPD.IHTTPSession): String {
        val files = HashMap<String, String>()
        session.parseBody(files)
        return files["postData"].orEmpty()
    }

    private fun json(element: kotlinx.serialization.json.JsonElement): NanoHTTPD.Response = text(element.toString(), "application/json")

    private fun text(value: String, mime: String = "text/plain; charset=utf-8"): NanoHTTPD.Response = NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, mime, value)

    private fun html(value: String): NanoHTTPD.Response = text(value, "text/html; charset=utf-8")

    private fun error(status: NanoHTTPD.Response.IStatus, message: String): NanoHTTPD.Response = NanoHTTPD.newFixedLengthResponse(
        status,
        "application/json",
        buildJsonObject {
            put("ok", false)
            put("error", message)
        }.toString(),
    )

    private fun mimeType(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "js", "mjs" -> "text/javascript; charset=utf-8"
        "json" -> "application/json"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "ico" -> "image/x-icon"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "ttf" -> "font/ttf"
        "txt", "md", "csv" -> "text/plain; charset=utf-8"
        "wasm" -> "application/wasm"
        else -> "application/octet-stream"
    }
}
