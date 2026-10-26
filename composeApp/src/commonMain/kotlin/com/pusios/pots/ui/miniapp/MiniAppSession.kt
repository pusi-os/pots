@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.pusios.pots.ui.miniapp

import com.pusios.pots.data.miniapp.MiniAppHostBridge
import com.pusios.pots.data.miniapp.MiniAppRecord
import com.pusios.pots.data.miniapp.MiniAppRepository
import com.pusios.pots.data.miniapp.createMiniAppHostBridge
import com.pusios.pots.ui.dynamicui.HostCallAction
import com.pusios.pots.ui.dynamicui.MiniAppRuntime
import com.pusios.pots.ui.dynamicui.StoreDeleteAction
import com.pusios.pots.ui.dynamicui.StorePutAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The bridge between a rendered pots-ui tree and an app's persistent data.
 *
 * One instance serves both a saved app and an interactive draft: the draft is
 * just an app id that has records and a schema but no saved manifest yet. That
 * is what lets the agent seed a database while building, and the user press
 * Save without copying anything.
 */
class MiniAppSession(
    val appId: String,
    private val repository: MiniAppRepository,
    private val onNavigate: (String) -> Unit = {},
    private val host: MiniAppHostBridge = createMiniAppHostBridge(),
) : MiniAppRuntime {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _revision = MutableStateFlow(0)
    override val revision: StateFlow<Int> = _revision.asStateFlow()

    override fun records(collection: String): List<MiniAppRecord> = repository.records(appId, collection)

    override fun onStorePut(action: StorePutAction, values: Map<String, String>) {
        if (action.collection.isBlank()) return
        val resolved = values.mapValues { (_, value) -> resolve(value, values) }
        val recordId = action.recordId?.let { resolve(it, values) }?.takeIf { it.isNotBlank() }
            ?: resolved["id"]?.takeIf { it.isNotBlank() }
        val fields = resolved
            .filterKeys { it != "recordId" }
            .mapValues { (_, value) -> toPrimitive(value) }
            .toMutableMap()
        if (recordId != null) fields["id"] = JsonPrimitive(recordId)
        repository.putRecord(appId, action.collection, recordId, JsonObject(fields))
        _revision.value++
        action.navigateTo?.takeIf { it.isNotBlank() }?.let(onNavigate)
    }

    override fun onStoreDelete(action: StoreDeleteAction, values: Map<String, String>) {
        if (action.collection.isBlank()) return
        val recordId = action.recordId?.let { resolve(it, values) }?.takeIf { it.isNotBlank() }
            ?: values["recordId"]?.takeIf { it.isNotBlank() }
            ?: values["id"]?.takeIf { it.isNotBlank() }
        if (recordId != null) {
            repository.deleteRecord(appId, action.collection, recordId)
        } else {
            repository.clearCollection(appId, action.collection)
        }
        _revision.value++
    }

    override fun onNavigate(screenId: String) {
        if (screenId.isNotBlank()) onNavigate(screenId)
    }

    override fun onHostCall(action: HostCallAction, values: Map<String, String>) {
        if (action.service.isBlank()) return
        val params = buildMap {
            action.params?.forEach { (key, value) -> put(key, resolve(value.content, values)) }
            putAll(values)
        }
        scope.launch {
            val result: List<JsonObject> = when (action.service.lowercase()) {
                "calendar.list" -> {
                    val from = params["from"]?.let { parseMillis(it) } ?: 0L
                    val to = params["to"]?.let { parseMillis(it) } ?: (kotlin.time.Clock.System.now().toEpochMilliseconds() + 7L * 86_400_000)
                    host.listCalendar(from, to)?.map { toJson(it) }.orEmpty()
                }

                "calendar.create" -> {
                    val title = params["title"].orEmpty()
                    val start = params["start"].orEmpty()
                    val id = host.createCalendar(title, start, params["end"], params["notes"])
                    listOf(
                        buildJsonObject {
                            put("id", id ?: -1L)
                            put("title", title)
                            put("start", start)
                        },
                    )
                }

                "files.list" -> host.listFiles(params["path"].orEmpty()).map { toJson(it) }

                "files.read" -> {
                    val path = params["path"].orEmpty()
                    host.readFile(path)?.let {
                        listOf(
                            buildJsonObject {
                                put("path", path)
                                put("content", it)
                            },
                        )
                    }.orEmpty()
                }

                "files.write" -> {
                    val path = params["path"].orEmpty()
                    val content = params["content"].orEmpty()
                    listOf(
                        buildJsonObject {
                            put("ok", host.writeFile(path, content))
                            put("path", path)
                        },
                    )
                }

                "files.delete" -> {
                    val path = params["path"].orEmpty()
                    listOf(
                        buildJsonObject {
                            put("ok", host.deleteFile(path))
                            put("path", path)
                        },
                    )
                }

                "notes.list" -> host.listFiles("notes").filter {
                    it["path"].toString().endsWith(".md")
                }.map { toJson(it) }

                "notes.write" -> {
                    val title = params["title"].orEmpty().ifBlank { "note" }
                    val slug = title.lowercase().map { if (it.isLetterOrDigit()) it else '-' }.joinToString("").trim('-')
                        .ifBlank { "note" }
                    val path = "notes/$slug-${kotlin.time.Clock.System.now().toEpochMilliseconds()}.md"
                    val ok = host.writeFile(path, params["content"].orEmpty())
                    listOf(
                        buildJsonObject {
                            put("ok", ok)
                            put("path", path)
                            put("title", title)
                        },
                    )
                }

                else -> emptyList()
            }
            val collection = action.storeTo?.takeIf { it.isNotBlank() } ?: return@launch
            if (action.service.lowercase().endsWith(".list")) {
                repository.clearCollection(appId, collection)
            }
            result.forEach { value ->
                repository.putRecord(appId, collection, null, value)
            }
            _revision.value++
        }
    }

    private fun parseMillis(value: String): Long? = runCatching { Instant.parse(value).toEpochMilliseconds() }
        .getOrNull() ?: value.toLongOrNull()

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

    /** `{{form.id}}` and `{{recordId}}` templates, resolved against the pressed form. */
    private fun resolve(template: String, values: Map<String, String>): String {
        var result = template
        val matches = Regex("""\{\{\s*([A-Za-z0-9_.-]+)\s*}}""").findAll(template).toList()
        for (match in matches) {
            val key = match.groupValues[1]
            val value = values[key] ?: values[key.substringAfterLast('.')] ?: ""
            result = result.replace(match.value, value)
        }
        return result
    }

    private fun toPrimitive(value: String): JsonPrimitive = when {
        value.equals("true", ignoreCase = true) -> JsonPrimitive(true)
        value.equals("false", ignoreCase = true) -> JsonPrimitive(false)
        value.toLongOrNull() != null -> JsonPrimitive(value.toLong())
        value.toDoubleOrNull() != null -> JsonPrimitive(value.toDouble())
        else -> JsonPrimitive(value)
    }
}
