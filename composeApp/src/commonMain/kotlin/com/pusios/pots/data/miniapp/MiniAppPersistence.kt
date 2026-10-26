package com.pusios.pots.data.miniapp

import app.cash.sqldelight.db.SqlDriver
import com.pusios.pots.data.AppSettings
import com.pusios.pots.data.SharedJson
import com.pusios.pots.db.PotsDatabase
import kotlinx.serialization.encodeToString

/**
 * Returns a SQL driver for the mini-app database, or null on platforms without
 * bundled SQLite (wasm). The mini-app tables live in the same generated schema
 * as conversations but in their own file, so a corrupt app store can never take
 * the chat history down with it.
 */
expect fun createMiniAppSqlDriver(): SqlDriver?

/** Storage for apps, their version trees and their records. */
interface MiniAppPersistence {
    fun loadApps(): List<MiniApp>
    fun saveApp(app: MiniApp)
    fun deleteApp(id: String)
    fun loadVersions(appId: String): List<MiniAppVersion>
    fun appendVersion(appId: String, version: MiniAppVersion)
    fun loadRecords(appId: String, collection: String): List<MiniAppRecord>
    fun loadAllRecords(appId: String): List<MiniAppRecord>
    fun upsertRecord(record: MiniAppRecord)
    fun deleteRecord(appId: String, collection: String, recordId: String)
    fun deleteCollection(appId: String, collection: String)
    fun deleteRecords(appId: String)
}

fun createMiniAppPersistence(appSettings: AppSettings): MiniAppPersistence {
    val driver = createMiniAppSqlDriver()
    return if (driver != null) {
        SqlMiniAppPersistence(PotsDatabase(driver))
    } else {
        SettingsMiniAppPersistence(appSettings)
    }
}

class SqlMiniAppPersistence(private val database: PotsDatabase) : MiniAppPersistence {
    private val queries get() = database.miniappQueries

    override fun loadApps(): List<MiniApp> = queries.selectAllMiniApps().executeAsList().mapNotNull { row ->
        decodeApp(row.appJson)?.copy(visible = row.visible != 0L)
    }

    override fun saveApp(app: MiniApp) {
        queries.upsertMiniApp(
            id = app.id,
            name = app.name,
            description = app.description,
            icon = app.icon,
            accent = app.accent,
            createdAt = app.createdAt,
            updatedAt = app.updatedAt,
            visible = if (app.visible) 1L else 0L,
            appJson = SharedJson.encodeToString(app),
        )
    }

    override fun deleteApp(id: String) {
        queries.deleteMiniApp(id)
        queries.deleteMiniAppVersions(id)
        queries.deleteMiniAppRecords(id)
    }

    override fun loadVersions(appId: String): List<MiniAppVersion> = queries.selectVersions(appId).executeAsList().mapNotNull { row ->
        decodeVersion(row.snapshotJson)?.let {
            it.copy(
                version = row.version.toInt(),
                parentVersion = row.parentVersion?.toInt(),
                message = row.message,
                createdAt = row.createdAt,
            )
        }
    }

    override fun appendVersion(appId: String, version: MiniAppVersion) {
        queries.insertVersion(
            appId = appId,
            version = version.version.toLong(),
            parentVersion = version.parentVersion?.toLong(),
            message = version.message,
            createdAt = version.createdAt,
            snapshotJson = SharedJson.encodeToString(version.snapshot),
        )
    }

    override fun loadRecords(appId: String, collection: String): List<MiniAppRecord> = queries.selectRecords(appId, collection).executeAsList().mapNotNull { it.toRecord() }

    override fun loadAllRecords(appId: String): List<MiniAppRecord> = queries.selectAllRecords(appId).executeAsList().mapNotNull { it.toRecord() }

    override fun upsertRecord(record: MiniAppRecord) {
        queries.upsertRecord(
            appId = record.appId,
            collection = record.collection,
            recordId = record.recordId,
            valueJson = record.value.toString(),
            createdAt = record.createdAt,
            updatedAt = record.updatedAt,
        )
    }

    override fun deleteRecord(appId: String, collection: String, recordId: String) {
        queries.deleteRecord(appId, collection, recordId)
    }

    override fun deleteCollection(appId: String, collection: String) {
        queries.deleteCollection(appId, collection)
    }

    override fun deleteRecords(appId: String) {
        queries.deleteMiniAppRecords(appId)
    }

    private fun com.pusios.pots.db.MiniAppRecordEntity.toRecord(): MiniAppRecord? = runCatching {
        MiniAppRecord(
            appId = appId,
            collection = collection,
            recordId = recordId,
            value = SharedJson.parseToJsonElement(valueJson).let {
                it as? kotlinx.serialization.json.JsonObject
            } ?: kotlinx.serialization.json.JsonObject(emptyMap()),
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }.getOrNull()

    private fun decodeApp(json: String): MiniApp? = runCatching {
        SharedJson.decodeFromString<MiniApp>(json)
    }.getOrNull()

    private fun decodeVersion(json: String): MiniAppVersion? = runCatching {
        MiniAppVersion(version = 0, snapshot = SharedJson.decodeFromString<MiniApp>(json))
    }.getOrNull()
}

/**
 * Web build: no SQLite, so the whole store is one settings value. The shape is
 * the same, which keeps every caller platform-agnostic.
 */
class SettingsMiniAppPersistence(private val appSettings: AppSettings) : MiniAppPersistence {
    private fun read(): Storage = appSettings.getMiniAppsJson()?.let {
        runCatching { SharedJson.decodeFromString<Storage>(it) }.getOrNull()
    } ?: Storage()

    private fun write(storage: Storage) {
        appSettings.setMiniAppsJson(SharedJson.encodeToString(storage))
    }

    override fun loadApps(): List<MiniApp> = read().apps

    override fun saveApp(app: MiniApp) {
        val storage = read()
        val apps = storage.apps.filterNot { it.id == app.id } + app
        write(storage.copy(apps = apps))
    }

    override fun deleteApp(id: String) {
        val storage = read()
        write(
            storage.copy(
                apps = storage.apps.filterNot { it.id == id },
                versions = storage.versions.filterNot { it.appId == id },
                records = storage.records.filterNot { it.appId == id },
            ),
        )
    }

    override fun loadVersions(appId: String): List<MiniAppVersion> = read().versions
        .filter { it.appId == appId }
        .sortedByDescending { it.version }
        .map {
            MiniAppVersion(
                version = it.version,
                parentVersion = it.parentVersion,
                message = it.message,
                createdAt = it.createdAt,
                snapshot = it.snapshot,
            )
        }.map {
            MiniAppVersion(
                version = it.version,
                parentVersion = it.parentVersion,
                message = it.message,
                createdAt = it.createdAt,
                snapshot = it.snapshot,
            )
        }

    override fun appendVersion(appId: String, version: MiniAppVersion) {
        val storage = read()
        val entry = StoredVersion(
            appId = appId,
            version = version.version,
            parentVersion = version.parentVersion,
            message = version.message,
            createdAt = version.createdAt,
            snapshot = version.snapshot,
        )
        write(storage.copy(versions = storage.versions.filterNot { it.appId == appId && it.version == version.version } + entry))
    }

    override fun loadRecords(appId: String, collection: String): List<MiniAppRecord> = read().records.filter { it.appId == appId && it.collection == collection }.sortedByDescending { it.updatedAt }

    override fun loadAllRecords(appId: String): List<MiniAppRecord> = read().records.filter { it.appId == appId }.sortedByDescending { it.updatedAt }

    override fun upsertRecord(record: MiniAppRecord) {
        val storage = read()
        write(
            storage.copy(
                records = storage.records.filterNot {
                    it.appId == record.appId && it.collection == record.collection && it.recordId == record.recordId
                } + record,
            ),
        )
    }

    override fun deleteRecord(appId: String, collection: String, recordId: String) {
        val storage = read()
        write(storage.copy(records = storage.records.filterNot { it.appId == appId && it.collection == collection && it.recordId == recordId }))
    }

    override fun deleteCollection(appId: String, collection: String) {
        val storage = read()
        write(storage.copy(records = storage.records.filterNot { it.appId == appId && it.collection == collection }))
    }

    override fun deleteRecords(appId: String) {
        val storage = read()
        write(storage.copy(records = storage.records.filterNot { it.appId == appId }))
    }

    @kotlinx.serialization.Serializable
    private data class StoredVersion(
        val appId: String,
        val version: Int,
        val parentVersion: Int?,
        val message: String,
        val createdAt: Long,
        val snapshot: MiniApp,
    )

    @kotlinx.serialization.Serializable
    private data class Storage(
        val apps: List<MiniApp> = emptyList(),
        val versions: List<StoredVersion> = emptyList(),
        val records: List<MiniAppRecord> = emptyList(),
    )
}
