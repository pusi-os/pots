@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.pusios.pots.data.miniapp

import com.pusios.pots.data.AppSettings
import com.pusios.pots.data.SharedJson
import com.pusios.pots.deleteAppStorageFolder
import com.pusios.pots.listAppStorageFiles
import com.pusios.pots.moveAppStorageFolder
import com.pusios.pots.readAppStorageBytes
import com.pusios.pots.writeAppStorageBytes
import com.pusios.pots.writeAppStorageFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The one owner of saved mini-apps.
 *
 * Apps live in a document store on SQLite: the manifest, the screens and the
 * declared schema are one JSON document per app, records are rows keyed by
 * (app, collection, record), and history is an append-only tree of snapshots.
 * On web the same interface is backed by one settings value, so the UI never
 * asks which platform it is on.
 *
 * Every save also leaves a readable folder under the app files directory
 * (`mini-apps/<slug>/`), which is what the user finds when they go looking for
 * their apps in a file manager or a backup.
 */
@OptIn(ExperimentalUuidApi::class, ExperimentalEncodingApi::class)
class MiniAppRepository(
    private val appSettings: AppSettings,
    private val persistence: MiniAppPersistence,
) {
    private val _apps = MutableStateFlow<List<MiniApp>>(emptyList())
    val apps: StateFlow<List<MiniApp>> = _apps.asStateFlow()

    private val _toolbarIds = MutableStateFlow<List<String>?>(null)
    val toolbarIds: StateFlow<List<String>?> = _toolbarIds.asStateFlow()

    private val drafts: MutableMap<String, DraftMeta>
        get() = cachedDrafts ?: loadDrafts().also { cachedDrafts = it }
    private var cachedDrafts: MutableMap<String, DraftMeta>? = null

    init {
        reload()
        _toolbarIds.value = loadToolbarIds()
    }

    fun reload() {
        _apps.value = persistence.loadApps().sortedByDescending { it.updatedAt }
    }

    fun get(id: String): MiniApp? = persistence.loadApps().firstOrNull { it.id == id }

    fun versionCount(id: String): Int = persistence.loadVersions(id).size

    fun versions(id: String): List<MiniAppVersion> = persistence.loadVersions(id)

    fun records(appId: String, collection: String): List<MiniAppRecord> = persistence.loadRecords(appId, collection)

    fun allRecords(appId: String): List<MiniAppRecord> = persistence.loadAllRecords(appId)

    fun draftMeta(appId: String): DraftMeta = drafts[appId] ?: DraftMeta()

    /** The agent declares what the app's data looks like while it is still a draft. */
    fun setDraftCollections(appId: String, collections: List<MiniAppCollection>) {
        val app = get(appId)
        if (app != null) {
            persistence.saveApp(app.copy(collections = collections, updatedAt = nowMs()))
            reload()
            return
        }
        updateDraft(appId) { it.copy(collections = collections) }
    }

    fun setDraftAutomations(appId: String, automations: List<MiniAppAutomation>) {
        val app = get(appId)
        if (app != null) {
            persistence.saveApp(app.copy(automations = automations, updatedAt = nowMs()))
            reload()
            return
        }
        updateDraft(appId) { it.copy(automations = automations) }
    }

    fun setDraftCapabilities(appId: String, capabilities: List<String>) {
        val app = get(appId)
        if (app != null) {
            persistence.saveApp(app.copy(capabilities = capabilities.distinct(), updatedAt = nowMs()))
            reload()
            return
        }
        updateDraft(appId) { it.copy(capabilities = capabilities.distinct()) }
    }

    /** Declares (or replaces) a single collection, merging by name. */
    fun declareCollection(appId: String, collection: MiniAppCollection) {
        val current = get(appId)?.collections ?: draftMeta(appId).collections
        setDraftCollections(appId, current.filterNot { it.name == collection.name } + collection)
    }

    fun deleteCollection(appId: String, collection: String) {
        val app = get(appId)
        if (app != null) {
            persistence.saveApp(app.copy(collections = app.collections.filterNot { it.name == collection }, updatedAt = nowMs()))
            reload()
        } else {
            updateDraft(appId) { it.copy(collections = it.collections.filterNot { c -> c.name == collection }) }
        }
        persistence.deleteCollection(appId, collection)
    }

    fun addAutomation(appId: String, automation: MiniAppAutomation) {
        val current = get(appId)?.automations ?: draftMeta(appId).automations
        setDraftAutomations(appId, current.filterNot { it.id == automation.id } + automation)
    }

    private fun updateDraft(appId: String, change: (DraftMeta) -> DraftMeta) {
        val map = drafts
        map[appId] = change(map[appId] ?: DraftMeta())
        persistDrafts(map)
    }

    /** Saves the current interactive screens as a new app or a new version of one. */
    fun saveDraft(draft: MiniAppDraft): MiniApp {
        val now = nowMs()
        val existing = get(draft.id)
        val meta = drafts[draft.id] ?: draft.sourceConversationId?.let { drafts[it] } ?: DraftMeta()
        val app = MiniApp(
            id = draft.id,
            name = draft.name.trim().ifEmpty { "Mini-app" },
            description = draft.description.trim(),
            icon = draft.icon,
            accent = draft.accent,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            screens = draft.screens,
            collections = draft.collections.ifEmpty { meta.collections },
            automations = draft.automations.ifEmpty { meta.automations },
            capabilities = draft.capabilities.ifEmpty { meta.capabilities },
            sourceConversationId = draft.sourceConversationId,
            visible = existing?.visible ?: true,
            kind = if (draft.entryPoint != null) draft.kind else meta.kind,
            entryPoint = draft.entryPoint ?: meta.entryPoint,
            standaloneReady = draft.standaloneReady || meta.standaloneReady || (draft.screens.isNotEmpty() || (draft.entryPoint ?: meta.entryPoint) != null),
        )
        // A new app starts life in the drafts folder; move the files the agent
        // wrote there into the app's own folder before recording them.
        val draftFolder = draftFolder(draft.id)
        val appFolder = folderFor(app)
        if (draftFolder != appFolder) {
            if (listAppStorageFiles(draftFolder).isNotEmpty() || readAppStorageBytes("$draftFolder/app.json") != null) {
                moveAppStorageFolder(draftFolder, appFolder)
            }
        }
        val withFiles = app.copy(files = fileInfos(app.id))
        persistence.saveApp(withFiles)
        // The agent writes draft records under the conversation id it is talking
        // in; when the user saves, they belong to the app.
        val source = draft.sourceConversationId
        if (source != null && source != app.id) {
            persistence.loadAllRecords(source).forEach { record ->
                persistence.upsertRecord(record.copy(appId = app.id))
            }
        }
        appendVersion(withFiles, draft.message.ifBlank { if (existing == null) "Created" else "Updated" })
        reload()
        val oldFolder = existing?.let { folderFor(it) }
        if (oldFolder != null && oldFolder != folderFor(withFiles)) deleteAppStorageFolder(oldFolder)
        writeFolder(withFiles)
        return withFiles
    }

    fun updateMetadata(id: String, name: String, description: String, icon: String? = null) {
        val existing = get(id) ?: return
        val updated = existing.copy(
            name = name.trim().ifEmpty { existing.name },
            description = description.trim(),
            icon = icon ?: existing.icon,
            updatedAt = nowMs(),
        )
        persistence.saveApp(updated)
        appendVersion(updated, "Edited metadata")
        reload()
        val oldFolder = folderFor(existing)
        if (oldFolder != folderFor(updated)) deleteAppStorageFolder(oldFolder)
        writeFolder(updated)
    }

    fun setVisible(id: String, visible: Boolean) {
        val existing = get(id) ?: return
        val updated = existing.copy(visible = visible)
        persistence.saveApp(updated)
        reload()
        val current = _toolbarIds.value
        if (current != null) {
            val ids = if (visible) (current + id).distinct() else current - id
            setToolbarIds(ids)
        }
    }

    fun setToolbarIds(ids: List<String>?) {
        val normalized = ids?.distinct()
        _toolbarIds.value = normalized
        appSettings.setMiniAppToolbarIds(SharedJson.encodeToString(normalized))
    }

    fun pinnedApps(): List<MiniApp> {
        val ids = _toolbarIds.value ?: return _apps.value.filter { it.visible }
        val byId = _apps.value.associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }

    fun delete(id: String) {
        val app = get(id)
        persistence.deleteApp(id)
        if (drafts.remove(id) != null) persistDrafts(drafts)
        val current = _toolbarIds.value
        if (current != null) setToolbarIds(current - id)
        if (app != null) deleteAppStorageFolder(folderFor(app))
        reload()
    }

    fun restoreVersion(appId: String, version: Int) {
        val snapshot = persistence.loadVersions(appId).firstOrNull { it.version == version }?.snapshot ?: return
        val restored = snapshot.copy(updatedAt = nowMs())
        persistence.saveApp(restored)
        appendVersion(restored, "Restored v$version")
        reload()
        writeFolder(restored)
    }

    fun putRecord(
        appId: String,
        collection: String,
        recordId: String?,
        value: JsonObject,
    ): MiniAppRecord {
        val now = nowMs()
        val id = recordId?.takeIf { it.isNotBlank() } ?: Uuid.random().toString()
        val existing = persistence.loadRecords(appId, collection).firstOrNull { it.recordId == id }
        val record = MiniAppRecord(
            appId = appId,
            collection = collection,
            recordId = id,
            value = value,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        persistence.upsertRecord(record)
        return record
    }

    fun deleteRecord(appId: String, collection: String, recordId: String) {
        persistence.deleteRecord(appId, collection, recordId)
    }

    fun clearCollection(appId: String, collection: String) {
        persistence.deleteCollection(appId, collection)
    }

    fun export(id: String): String? {
        val app = get(id) ?: return null
        val bundle = MiniAppBundle(
            app = app,
            records = persistence.loadAllRecords(id),
            versions = persistence.loadVersions(id),
            files = fileAssets(folderFor(app)),
        )
        return SharedJson.encodeToString(bundle)
    }

    fun import(json: String): Result<MiniApp> = runCatching {
        val bundle = SharedJson.decodeFromString<MiniAppBundle>(json)
        var app = bundle.app
        if (get(app.id) != null) {
            app = app.copy(id = Uuid.random().toString(), updatedAt = nowMs())
        }
        val now = nowMs()
        val imported = app.copy(createdAt = if (app.createdAt == 0L) now else app.createdAt, updatedAt = now)
        persistence.saveApp(imported)
        if (bundle.versions.isEmpty()) {
            appendVersion(imported, "Imported")
        } else {
            bundle.versions.sortedBy { it.version }.forEach { version ->
                persistence.appendVersion(
                    imported.id,
                    version.copy(snapshot = version.snapshot.copy(id = imported.id)),
                )
            }
        }
        bundle.records.forEach { persistence.upsertRecord(it.copy(appId = imported.id)) }
        writeFileAssets(folderFor(imported), bundle.files)
        persistence.saveApp(imported.copy(files = fileInfos(imported.id)))
        reload()
        writeFolder(imported)
        imported
    }

    // --- files in the app's folder -------------------------------------------

    /** The folder an app (or an unsaved draft) owns. */
    fun appFolder(appId: String): String = get(appId)?.let { folderFor(it) } ?: draftFolder(appId)

    fun files(appId: String): List<MiniAppFileInfo> = fileInfos(appId)

    fun readFile(appId: String, path: String): String? {
        val safe = safePath(path) ?: return null
        return readAppStorageBytes("${appFolder(appId)}/$safe")?.decodeToString()
    }

    fun readFileBytes(appId: String, path: String): ByteArray? {
        val safe = safePath(path) ?: return null
        return readAppStorageBytes("${appFolder(appId)}/$safe")
    }

    fun writeFile(appId: String, path: String, content: String): Boolean {
        val safe = safePath(path) ?: return false
        return writeAppStorageBytes("${appFolder(appId)}/$safe", content.encodeToByteArray())
    }

    fun writeFileBytes(appId: String, path: String, bytes: ByteArray): Boolean {
        val safe = safePath(path) ?: return false
        return writeAppStorageBytes("${appFolder(appId)}/$safe", bytes)
    }

    fun deleteFile(appId: String, path: String): Boolean {
        val safe = safePath(path) ?: return false
        return deleteAppStorageFolder("${appFolder(appId)}/$safe")
    }

    /** Web apps only: the file the browser opens, and the app kind that makes it run. */
    fun setEntry(appId: String, entryPoint: String?, kind: MiniAppKind) {
        val app = get(appId) ?: run {
            updateDraft(appId) { it.copy(entryPoint = entryPoint, kind = kind) }
            return
        }
        val updated = app.copy(entryPoint = entryPoint, kind = kind, updatedAt = nowMs())
        persistence.saveApp(updated)
        appendVersion(updated, "Set web entry")
        reload()
        writeFolder(updated)
    }

    private fun fileInfos(appId: String): List<MiniAppFileInfo> {
        val folder = appFolder(appId)
        return listAppStorageFiles(folder)
            .mapNotNull { full ->
                val relative = full.removePrefix("$folder/")
                if (isMetadataPath(relative)) return@mapNotNull null
                val bytes = readAppStorageBytes(full) ?: return@mapNotNull null
                MiniAppFileInfo(path = relative, mimeType = mimeType(relative), sizeBytes = bytes.size.toLong())
            }
            .sortedBy { it.path }
    }

    private fun fileAssets(folder: String): List<MiniAppFileAsset> = listAppStorageFiles(folder).mapNotNull { full ->
        val relative = full.removePrefix("$folder/")
        if (isMetadataPath(relative)) return@mapNotNull null
        val bytes = readAppStorageBytes(full) ?: return@mapNotNull null
        MiniAppFileAsset(
            path = relative,
            mimeType = mimeType(relative),
            dataBase64 = Base64.encode(bytes),
        )
    }

    private fun writeFileAssets(folder: String, assets: List<MiniAppFileAsset>) {
        assets.forEach { asset ->
            val safe = safePath(asset.path) ?: return@forEach
            val bytes = runCatching { Base64.decode(asset.dataBase64) }.getOrNull() ?: return@forEach
            writeAppStorageBytes("$folder/$safe", bytes)
        }
    }

    private fun isMetadataPath(path: String): Boolean = path == "app.json" ||
        path == "data.json" ||
        path == "README.md" ||
        path.startsWith("versions/") ||
        path.startsWith(".drafts/")

    private fun safePath(path: String): String? {
        val normalized = path.replace('\\', '/').trim().trimStart('/')
        if (normalized.isEmpty()) return null
        if (normalized.split('/').any { it == ".." || it.isEmpty() }) return null
        return normalized
    }

    private fun mimeType(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html"
        "css" -> "text/css"
        "js", "mjs" -> "text/javascript"
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
        "txt", "md", "csv" -> "text/plain"
        "wasm" -> "application/wasm"
        "sqlite", "db" -> "application/x-sqlite3"
        else -> "application/octet-stream"
    }

    private fun draftFolder(appId: String): String = "mini-apps/.drafts/$appId"

    private fun appendVersion(app: MiniApp, message: String) {
        val versions = persistence.loadVersions(app.id)
        val next = (versions.maxOfOrNull { it.version } ?: 0) + 1
        persistence.appendVersion(
            app.id,
            MiniAppVersion(
                version = next,
                parentVersion = versions.maxOfOrNull { it.version },
                message = message,
                createdAt = nowMs(),
                snapshot = app,
            ),
        )
    }

    /**
     * Leaves the readable copy of the app beside the database. Best effort: the
     * database is the source of truth, so a filesystem that refuses the write
     * only means no backup folder, never a lost app.
     */
    private fun writeFolder(app: MiniApp) {
        val folder = folderFor(app)
        val bundle = MiniAppBundle(
            app = app,
            records = persistence.loadAllRecords(app.id),
            versions = persistence.loadVersions(app.id),
            files = fileAssets(folder),
        )
        writeAppStorageFile("$folder/app.json", SharedJson.encodeToString(bundle))
        writeAppStorageFile(
            "$folder/data.json",
            SharedJson.encodeToString(bundle.records),
        )
        bundle.versions.forEach { version ->
            writeAppStorageFile("$folder/versions/${version.version}.json", SharedJson.encodeToString(version))
        }
        writeAppStorageFile("$folder/README.md", readme(app))
    }

    private fun readme(app: MiniApp): String = buildString {
        append("# ").append(app.name).append("\n\n")
        if (app.description.isNotBlank()) append(app.description).append("\n\n")
        append("- id: `").append(app.id).append("`\n")
        append("- screens: ").append(app.screens.size).append("\n")
        append("- collections: ").append(app.collections.joinToString { it.name }.ifEmpty { "none" }).append("\n")
        append("- capabilities: ").append(app.capabilities.joinToString().ifEmpty { "none" }).append("\n")
        append("\n`app.json` is the whole app; `data.json` is its saved data; `versions/` is its history.\n")
    }

    private fun folderFor(app: MiniApp?): String {
        val id = app?.id ?: return "mini-apps"
        val slug = (app?.name ?: "app").lowercase()
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .trim('-')
            .ifEmpty { "app" }
        return "mini-apps/$slug-${id.take(8)}"
    }

    private fun loadToolbarIds(): List<String>? {
        val json = appSettings.getMiniAppToolbarIds() ?: return null
        return runCatching { SharedJson.decodeFromString<List<String>>(json) }.getOrNull()
    }

    private fun loadDrafts(): MutableMap<String, DraftMeta> {
        val json = appSettings.getMiniAppDraftsJson() ?: return mutableMapOf()
        return runCatching {
            SharedJson.decodeFromString<Map<String, DraftMeta>>(json).toMutableMap()
        }.getOrDefault(mutableMapOf())
    }

    private fun persistDrafts(map: Map<String, DraftMeta>) {
        appSettings.setMiniAppDraftsJson(SharedJson.encodeToString(map))
    }
}

/** Data design the agent declared for an unsaved interactive draft. */
@Serializable
data class DraftMeta(
    val collections: List<MiniAppCollection> = emptyList(),
    val automations: List<MiniAppAutomation> = emptyList(),
    val capabilities: List<String> = emptyList(),
    val kind: MiniAppKind = MiniAppKind.NATIVE,
    val entryPoint: String? = null,
    val standaloneReady: Boolean = false,
)
