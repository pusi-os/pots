@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.pusios.pots.data.miniapp

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock

/** How a saved app is presented when it is launched. */
@Serializable
enum class MiniAppKind {
    /** Rendered natively from the saved pots-ui screens. */
    NATIVE,

    /** Served from the app's folder and opened in the device browser. */
    WEB,
}

/** A file that lives in the app's folder. */
@Immutable
@Serializable
data class MiniAppFileInfo(
    val path: String,
    val mimeType: String = "application/octet-stream",
    val sizeBytes: Long = 0L,
)

/** One file in a portable export. Bytes travel as base64 inside the JSON bundle. */
@Immutable
@Serializable
data class MiniAppFileAsset(
    val path: String,
    val mimeType: String = "application/octet-stream",
    val dataBase64: String,
)

/** One screen of a saved app: the raw pots-ui JSON the AI produced. */
@Immutable
@Serializable
data class MiniAppScreen(
    val id: String,
    val title: String = "",
    val potsUiJson: String,
    val createdAt: Long = 0L,
)

/** A field of a declared collection. Types are advisory, the store keeps JSON. */
@Immutable
@Serializable
data class MiniAppField(
    val name: String,
    val type: String = "string",
)

/**
 * A collection the app's agent designed. SQLite stores each record as a JSON
 * document; the schema is what the model and the UI agree on.
 */
@Immutable
@Serializable
data class MiniAppCollection(
    val name: String,
    val fields: List<MiniAppField> = emptyList(),
    val description: String = "",
)

/**
 * A standing job the app may run. The prompt is handed to the chat agent with
 * the app's own data as context; [cron] is optional because a task can also be
 * run on demand from the runner.
 */
@Immutable
@Serializable
data class MiniAppAutomation(
    val id: String,
    val title: String,
    val prompt: String,
    val cron: String? = null,
)

/**
 * A saved mini-app. Screens are the "final working version"; collections are
 * the data design; capabilities are what the app declared it may use.
 */
@Immutable
@Serializable
data class MiniApp(
    val id: String,
    val name: String,
    val description: String = "",
    val icon: String = "🧩",
    val accent: String = "#7C5CFF",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val screens: List<MiniAppScreen> = emptyList(),
    val collections: List<MiniAppCollection> = emptyList(),
    val automations: List<MiniAppAutomation> = emptyList(),
    val capabilities: List<String> = emptyList(),
    val sourceConversationId: String? = null,
    val visible: Boolean = true,
    /** Native screens or a web app served from this folder. */
    val kind: MiniAppKind = MiniAppKind.NATIVE,
    /** Web apps only: the file the browser opens, e.g. `web/index.html`. */
    val entryPoint: String? = null,
    /** Files in the folder, refreshed on save and on demand. */
    val files: List<MiniAppFileInfo> = emptyList(),
    /** Whether this saved version is standalone-ready (no LLM-dependent callbacks). */
    val standaloneReady: Boolean = false,
) {
    val latestScreen: MiniAppScreen? get() = screens.lastOrNull()
}

/** A node of the app's history tree. */
@Immutable
@Serializable
data class MiniAppVersion(
    val version: Int,
    val parentVersion: Int? = null,
    val message: String = "",
    val createdAt: Long = 0L,
    val snapshot: MiniApp,
)

/** One row of an app's data store. */
@Immutable
@Serializable
data class MiniAppRecord(
    val appId: String,
    val collection: String,
    val recordId: String,
    val value: JsonObject,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

/** The portable form used by the export/import buttons. */
@Immutable
@Serializable
data class MiniAppBundle(
    val format: Int = 1,
    val app: MiniApp,
    val records: List<MiniAppRecord> = emptyList(),
    val versions: List<MiniAppVersion> = emptyList(),
    val files: List<MiniAppFileAsset> = emptyList(),
)

/** What the interactive screen gathered when the user pressed Save. */
@Immutable
data class MiniAppDraft(
    val id: String,
    val name: String,
    val description: String = "",
    val icon: String = "🧩",
    val accent: String = "#7C5CFF",
    val screens: List<MiniAppScreen>,
    val collections: List<MiniAppCollection> = emptyList(),
    val automations: List<MiniAppAutomation> = emptyList(),
    val capabilities: List<String> = emptyList(),
    val sourceConversationId: String? = null,
    val message: String = "",
    val kind: MiniAppKind = MiniAppKind.NATIVE,
    val entryPoint: String? = null,
    val standaloneReady: Boolean = false,
)

/** Small list model; keeps the toolbar and sheets from carrying every screen. */
@Immutable
data class MiniAppSummary(
    val id: String,
    val name: String,
    val description: String,
    val icon: String,
    val accent: String,
    val updatedAt: Long,
    val visible: Boolean,
    val screenCount: Int,
    val collectionCount: Int,
    val versionCount: Int,
) {
    companion object {
        fun of(app: MiniApp, versionCount: Int = 0): MiniAppSummary = MiniAppSummary(
            id = app.id,
            name = app.name,
            description = app.description,
            icon = app.icon,
            accent = app.accent,
            updatedAt = app.updatedAt,
            visible = app.visible,
            screenCount = app.screens.size,
            collectionCount = app.collections.size,
            versionCount = versionCount,
        )
    }
}

internal fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()
