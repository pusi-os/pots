package com.pusios.pots.tools

import com.pusios.pots.data.currentConversationIdOrNull
import com.pusios.pots.data.miniapp.MiniAppAutomation
import com.pusios.pots.data.miniapp.MiniAppCollection
import com.pusios.pots.data.miniapp.MiniAppField
import com.pusios.pots.data.miniapp.MiniAppKind
import com.pusios.pots.data.miniapp.MiniAppRepository
import com.pusios.pots.network.tools.ParameterSchema
import com.pusios.pots.network.tools.Tool
import com.pusios.pots.network.tools.ToolInfo
import com.pusios.pots.network.tools.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.tool_mini_app_data_description
import pots.composeapp.generated.resources.tool_mini_app_data_name
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The agent's handle on the mini-app being built.
 *
 * It is the same idea as [PotsikTool]: one tool with an `action` switch rather
 * than a dozen tools, because every action is the same small thing done to the
 * same app — declare its data, write a row, read rows, register an automation.
 *
 * While the user is in Interactive UI the app has no id yet, so the tool writes
 * under the conversation id; Save moves those records and the declared schema
 * onto the app. Once saved, `app_id` targets the app directly, which is what
 * scheduled automations use.
 */
@OptIn(ExperimentalUuidApi::class)
object MiniAppDbTool {

    const val ID = "mini_app_data"

    val toolInfo = ToolInfo(
        id = ID,
        name = "Mini-app data",
        description = "Design and use a mini-app's persistent SQLite-backed data",
        nameRes = Res.string.tool_mini_app_data_name,
        descriptionRes = Res.string.tool_mini_app_data_description,
        userToggleable = false,
    )

    fun create(repository: MiniAppRepository): Tool = object : Tool {
        override val schema = ToolSchema(
            name = ID,
            description = "Design and use the persistent data of the mini-app being built. " +
                "Actions: declare_collection (design a table), declare_automation (a standing task, optionally with cron), " +
                "set_capabilities (what the app may use: calendar, notes, files, notifications, scheduling), " +
                "set_entry (make this a web app served from its folder and opened in the browser), " +
                "write_file / write_file_base64 / read_file / list_files / delete_file (the app's own files: web pages, scripts, CSS, images), " +
                "insert, query, delete, clear, list_collections, validate_standalone. " +
                "Records are JSON documents in SQLite, so a collection is a named set of records with a suggested field list. " +
                "Use list_collections/query to inspect the schema and the saved data before answering. " +
                "The app's screens can display a collection with the pots-ui 'records' node and write to it with store_put/store_delete actions.",
            parameters = mapOf(
                "action" to ParameterSchema(
                    type = "string",
                    description = "declare_collection, declare_automation, set_capabilities, set_entry, write_file, write_file_base64, read_file, list_files, delete_file, insert, query, delete, clear, list_collections, validate_standalone",
                    required = true,
                ),
                "app_id" to ParameterSchema(
                    type = "string",
                    description = "Saved app id. Omit while building in Interactive UI; the current draft is used.",
                    required = false,
                ),
                "collection" to ParameterSchema(
                    type = "string",
                    description = "Collection (table) name, for declare_collection/insert/query/delete/clear.",
                    required = false,
                ),
                "fields" to ParameterSchema(
                    type = "string",
                    description = "For declare_collection: fields as 'name:type, name:type' (types: string, number, boolean, date, json).",
                    required = false,
                ),
                "description" to ParameterSchema(
                    type = "string",
                    description = "Human-readable purpose of the collection.",
                    required = false,
                ),
                "data" to ParameterSchema(
                    type = "object",
                    description = "Record values, for insert. Keys are field names.",
                    required = false,
                ),
                "record_id" to ParameterSchema(
                    type = "string",
                    description = "Record id, for delete. Omit to clear the whole collection.",
                    required = false,
                ),
                "limit" to ParameterSchema(
                    type = "integer",
                    description = "Maximum records returned by query (default 50).",
                    required = false,
                ),
                "title" to ParameterSchema(
                    type = "string",
                    description = "For declare_automation: short human title.",
                    required = false,
                ),
                "prompt" to ParameterSchema(
                    type = "string",
                    description = "For declare_automation: what the agent should do on each run.",
                    required = false,
                ),
                "cron" to ParameterSchema(
                    type = "string",
                    description = "For declare_automation: optional cron expression for a recurring run.",
                    required = false,
                ),
                "capabilities" to ParameterSchema(
                    type = "string",
                    description = "For set_capabilities: comma-separated list, e.g. 'calendar,notes,files,notifications,scheduling'.",
                    required = false,
                ),
                "kind" to ParameterSchema(
                    type = "string",
                    description = "For set_entry: 'web' to serve the folder in the browser, or 'native' for pots-ui screens.",
                    required = false,
                ),
                "entry_point" to ParameterSchema(
                    type = "string",
                    description = "For set_entry: file the browser opens, e.g. 'web/index.html'.",
                    required = false,
                ),
                "path" to ParameterSchema(
                    type = "string",
                    description = "File path inside the app folder, e.g. 'web/index.html'. Never absolute and never contains '..'.",
                    required = false,
                ),
                "content" to ParameterSchema(
                    type = "string",
                    description = "For write_file: UTF-8 text (HTML, CSS, JS, SVG, JSON...).",
                    required = false,
                ),
                "data_base64" to ParameterSchema(
                    type = "string",
                    description = "For write_file_base64: binary content (PNG, WOFF2...) as base64.",
                    required = false,
                ),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val action = args["action"]?.toString()?.lowercase()
                ?: return mapOf("success" to false, "error" to "Missing action")
            val appId = args["app_id"]?.toString()?.takeIf { it.isNotBlank() }
                ?: currentConversationIdOrNull()
                ?: "scratch"
            return when (action) {
                "declare_collection" -> {
                    val name = args["collection"]?.toString()?.takeIf { it.isNotBlank() }
                        ?: return mapOf("success" to false, "error" to "Missing collection")
                    val fields = parseFields(args["fields"])
                    repository.declareCollection(
                        appId,
                        MiniAppCollection(
                            name = name,
                            fields = fields,
                            description = args["description"]?.toString().orEmpty(),
                        ),
                    )
                    mapOf("success" to true, "app_id" to appId, "collection" to name, "fields" to fields.map { it.name })
                }

                "declare_automation" -> {
                    val title = args["title"]?.toString()?.takeIf { it.isNotBlank() }
                        ?: return mapOf("success" to false, "error" to "Missing title")
                    val prompt = args["prompt"]?.toString()?.takeIf { it.isNotBlank() }
                        ?: return mapOf("success" to false, "error" to "Missing prompt")
                    val automation = MiniAppAutomation(
                        id = Uuid.random().toString(),
                        title = title,
                        prompt = prompt,
                        cron = args["cron"]?.toString()?.takeIf { it.isNotBlank() },
                    )
                    repository.addAutomation(appId, automation)
                    mapOf("success" to true, "app_id" to appId, "automation" to automation.title, "cron" to automation.cron)
                }

                "set_capabilities" -> {
                    val capabilities = args["capabilities"]?.toString()
                        .orEmpty()
                        .split(',')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    repository.setDraftCapabilities(appId, capabilities)
                    mapOf("success" to true, "app_id" to appId, "capabilities" to capabilities)
                }

                "set_entry" -> {
                    val entry = args["entry_point"]?.toString()?.takeIf { it.isNotBlank() }
                    val kind = if (args["kind"]?.toString()?.equals("web", ignoreCase = true) == true || entry != null) {
                        MiniAppKind.WEB
                    } else {
                        MiniAppKind.NATIVE
                    }
                    repository.setEntry(appId, entry, kind)
                    mapOf("success" to true, "app_id" to appId, "kind" to kind.name.lowercase(), "entry_point" to entry)
                }

                "write_file" -> {
                    val path = args["path"]?.toString()?.takeIf { it.isNotBlank() }
                        ?: return mapOf("success" to false, "error" to "Missing path")
                    val content = args["content"]?.toString().orEmpty()
                    val ok = repository.writeFile(appId, path, content)
                    if (!ok) return mapOf("success" to false, "error" to "Could not write $path")
                    mapOf("success" to true, "path" to path, "bytes" to content.encodeToByteArray().size)
                }

                "write_file_base64" -> {
                    val path = args["path"]?.toString()?.takeIf { it.isNotBlank() }
                        ?: return mapOf("success" to false, "error" to "Missing path")
                    val encoded = args["data_base64"]?.toString()?.takeIf { it.isNotBlank() }
                        ?: return mapOf("success" to false, "error" to "Missing data_base64")
                    val bytes = runCatching { kotlin.io.encoding.Base64.decode(encoded) }.getOrNull()
                        ?: return mapOf("success" to false, "error" to "Invalid base64")
                    val ok = repository.writeFileBytes(appId, path, bytes)
                    if (!ok) return mapOf("success" to false, "error" to "Could not write $path")
                    mapOf("success" to true, "path" to path, "bytes" to bytes.size)
                }

                "read_file" -> {
                    val path = args["path"]?.toString()?.takeIf { it.isNotBlank() }
                        ?: return mapOf("success" to false, "error" to "Missing path")
                    val content = repository.readFile(appId, path)
                        ?: return mapOf("success" to false, "error" to "No such file: $path")
                    mapOf("success" to true, "path" to path, "content" to content.take(200_000))
                }

                "list_files" -> {
                    mapOf(
                        "success" to true,
                        "app_id" to appId,
                        "folder" to repository.appFolder(appId),
                        "files" to repository.files(appId).map {
                            mapOf("path" to it.path, "mime" to it.mimeType, "bytes" to it.sizeBytes)
                        },
                    )
                }

                "delete_file" -> {
                    val path = args["path"]?.toString()?.takeIf { it.isNotBlank() }
                        ?: return mapOf("success" to false, "error" to "Missing path")
                    mapOf("success" to repository.deleteFile(appId, path), "path" to path)
                }

                "insert" -> {
                    val collection = args["collection"]?.toString()?.takeIf { it.isNotBlank() }
                        ?: return mapOf("success" to false, "error" to "Missing collection")
                    val data = args["data"]
                    val value: JsonObject = when (data) {
                        is Map<*, *> -> JsonObject(data.entries.associate { (k, v) -> k.toString() to toJson(v) })

                        is String -> runCatching {
                            com.pusios.pots.data.SharedJson.parseToJsonElement(data) as JsonObject
                        }.getOrNull() ?: JsonObject(emptyMap())

                        else -> JsonObject(emptyMap())
                    }
                    val record = repository.putRecord(appId, collection, args["record_id"]?.toString(), value)
                    mapOf("success" to true, "record_id" to record.recordId, "collection" to collection)
                }

                "query" -> {
                    val collection = args["collection"]?.toString()?.takeIf { it.isNotBlank() }
                        ?: return mapOf("success" to false, "error" to "Missing collection")
                    val limit = (args["limit"] as? Number)?.toInt()?.coerceIn(1, 200) ?: 50
                    val records = repository.records(appId, collection).take(limit).map {
                        mapOf("id" to it.recordId, "value" to it.value)
                    }
                    mapOf("success" to true, "collection" to collection, "count" to records.size, "records" to records)
                }

                "delete" -> {
                    val collection = args["collection"]?.toString()?.takeIf { it.isNotBlank() }
                        ?: return mapOf("success" to false, "error" to "Missing collection")
                    val recordId = args["record_id"]?.toString()?.takeIf { it.isNotBlank() }
                    if (recordId != null) {
                        repository.deleteRecord(appId, collection, recordId)
                    } else {
                        repository.clearCollection(appId, collection)
                    }
                    mapOf("success" to true, "collection" to collection, "record_id" to recordId)
                }

                "clear" -> {
                    val collection = args["collection"]?.toString()?.takeIf { it.isNotBlank() }
                        ?: return mapOf("success" to false, "error" to "Missing collection")
                    repository.clearCollection(appId, collection)
                    mapOf("success" to true, "collection" to collection)
                }

                "list_collections" -> {
                    val meta = repository.draftMeta(appId)
                    val app = repository.get(appId)
                    val collections = app?.collections ?: meta.collections
                    mapOf(
                        "success" to true,
                        "app_id" to appId,
                        "collections" to collections.map { collection ->
                            mapOf(
                                "name" to collection.name,
                                "description" to collection.description,
                                "fields" to collection.fields.map { it.name },
                                "count" to repository.records(appId, collection.name).size,
                            )
                        },
                    )
                }

                "validate_standalone" -> {
                    val app = repository.get(appId)
                    val meta = repository.draftMeta(appId)
                    val collections = app?.collections ?: meta.collections
                    val screens = app?.screens ?: emptyList()
                    val entry = app?.entryPoint ?: meta.entryPoint
                    val hasScreens = screens.isNotEmpty() || entry != null
                    val checks = mapOf(
                        "has_screen_or_entry" to hasScreens,
                        "has_collections" to collections.isNotEmpty(),
                        "has_records_or_files" to (repository.allRecords(appId).isNotEmpty() || (app?.files?.isNotEmpty() == true)),
                        "standalone_ready" to (app?.standaloneReady == true),
                    )
                    mapOf(
                        "success" to true,
                        "app_id" to appId,
                        "ready" to (hasScreens),
                        "checks" to checks,
                        "message" to if (hasScreens) "App can be saved as standalone." else "No screens or entry point yet.",
                    )
                }

                else -> mapOf("success" to false, "error" to "Unknown action: $action")
            }
        }
    }

    private fun parseFields(raw: Any?): List<MiniAppField> {
        val text = when (raw) {
            is String -> raw
            is List<*> -> raw.joinToString(",") { it.toString() }
            else -> ""
        }
        return text.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { entry ->
                val name = entry.substringBefore(':').trim()
                val type = entry.substringAfter(':', "string").trim()
                MiniAppField(name = name, type = type)
            }
    }

    private fun toJson(value: Any?): JsonPrimitive = when (value) {
        null -> JsonPrimitive("")
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }
}
