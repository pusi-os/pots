package com.pusios.pots

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import com.pusios.pots.data.AppSettings
import com.pusios.pots.data.MemoryStore
import com.pusios.pots.data.TaskStore
import com.pusios.pots.data.miniapp.MiniAppRepository
import com.pusios.pots.mcp.McpServerManager
import com.pusios.pots.network.tools.Tool
import com.pusios.pots.network.tools.ToolInfo
import com.pusios.pots.tools.CommonTools
import com.pusios.pots.tools.buildAgentToolSet
import com.pusios.pots.ui.potsik.PotsikStage
import com.russhwolf.settings.Settings
import com.russhwolf.settings.StorageSettings
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.download
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.js.Js
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

actual fun httpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient(Js) {
    config(this)
}

actual fun getBackgroundDispatcher(): CoroutineContext = EmptyCoroutineContext

actual fun onDragAndDropEventDropped(event: DragAndDropEvent): PlatformFile? = null

actual val BackIcon: ImageVector = Icons.AutoMirrored.Filled.ArrowBack

actual val currentPlatform: Platform = Platform.Web

actual val defaultUiScale: Float = 1.0f

actual val isEmailSupported: Boolean = false

actual val isSmsSupported: Boolean = false

actual val isNotificationsSupported: Boolean = false

actual val isSplinterlandsSupported: Boolean = false

actual suspend fun compressImageBytes(bytes: ByteArray, mimeType: String): ByteArray = bytes

actual fun getAppFilesDirectory(): String {
    // Web uses localStorage, return empty string as no file path is needed
    return ""
}

actual fun createSecureSettings(): Settings {
    // Web has no secure storage - using localStorage
    return StorageSettings()
}

actual fun createLegacySettings(): Settings? = null // Same storage location, no migration needed

actual fun getPlatformToolDefinitions(): List<ToolInfo> = CommonTools.commonToolDefinitions

private object WebKoinHelper : KoinComponent {
    val appSettings: AppSettings by inject()
    val memoryStore: MemoryStore by inject()
    val taskStore: TaskStore by inject()
    val mcpServerManager: McpServerManager by inject()
    val potsikStage: PotsikStage by inject()
    val miniAppRepository: MiniAppRepository by inject()
}

actual fun getAvailableTools(): List<Tool> = buildAgentToolSet(
    appSettings = WebKoinHelper.appSettings,
    memoryStore = WebKoinHelper.memoryStore,
    taskStore = WebKoinHelper.taskStore,
    mcpServerManager = WebKoinHelper.mcpServerManager,
    potsikStage = WebKoinHelper.potsikStage,
    miniAppRepository = WebKoinHelper.miniAppRepository,
)

actual fun openUrl(url: String): Boolean = try {
    kotlinx.browser.window.open(url, "_blank")
    true
} catch (_: Exception) {
    false
}

actual fun decodeToImageBitmap(bytes: ByteArray): ImageBitmap? = try {
    org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
} catch (_: Exception) {
    null
}

@androidx.compose.runtime.Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // No system back gesture on web
}

actual suspend fun saveFileToDevice(bytes: ByteArray, baseName: String, extension: String) {
    FileKit.download(bytes = bytes, fileName = "$baseName.$extension")
}

// Web notifications API isn't wired up; stub.
actual fun sendHeartbeatNotification(title: String, body: String) = Unit

private const val APP_STORAGE_FILE_PREFIX = "pots.app-file."

private fun normalizedStoragePath(path: String): String? {
    val parts = path.replace('\\', '/').split('/').filter { it.isNotBlank() }
    if (parts.isEmpty() || parts.any { it == "." || it == ".." }) return null
    return parts.joinToString("/")
}

private fun storageKeys(): List<String> {
    val storage = kotlinx.browser.window.localStorage
    return (0 until storage.length).mapNotNull { storage.key(it) }.filter { it.startsWith(APP_STORAGE_FILE_PREFIX) }
}

actual fun writeAppStorageFile(relativePath: String, content: String): Boolean = try {
    val path = normalizedStoragePath(relativePath) ?: return false
    kotlinx.browser.window.localStorage.setItem(APP_STORAGE_FILE_PREFIX + path, content)
    true
} catch (_: Exception) {
    false
}

actual fun deleteAppStorageFolder(relativePath: String): Boolean = try {
    val path = normalizedStoragePath(relativePath) ?: return false
    val storage = kotlinx.browser.window.localStorage
    val prefix = "$APP_STORAGE_FILE_PREFIX$path/"
    for (key in storageKeys()) {
        if (key == APP_STORAGE_FILE_PREFIX + path || key.startsWith(prefix)) storage.removeItem(key)
    }
    true
} catch (_: Exception) {
    false
}

@OptIn(ExperimentalEncodingApi::class)
actual fun writeAppStorageBytes(relativePath: String, bytes: ByteArray): Boolean = writeAppStorageFile(relativePath, "base64:${Base64.encode(bytes)}")

@OptIn(ExperimentalEncodingApi::class)
actual fun readAppStorageBytes(relativePath: String): ByteArray? = try {
    val path = normalizedStoragePath(relativePath) ?: return null
    val content = kotlinx.browser.window.localStorage.getItem(APP_STORAGE_FILE_PREFIX + path) ?: return null
    if (!content.startsWith("base64:")) content.encodeToByteArray() else Base64.decode(content.removePrefix("base64:"))
} catch (_: Exception) {
    null
}

actual fun listAppStorageFiles(relativePath: String): List<String> {
    val path = normalizedStoragePath(relativePath)?.let { "$it/" } ?: return emptyList()
    return storageKeys()
        .map { it.removePrefix(APP_STORAGE_FILE_PREFIX) }
        .filter { it.startsWith(path) }
        .sorted()
}

actual fun moveAppStorageFolder(fromRelativePath: String, toRelativePath: String): Boolean = try {
    val from = normalizedStoragePath(fromRelativePath) ?: return false
    val to = normalizedStoragePath(toRelativePath) ?: return false
    val storage = kotlinx.browser.window.localStorage
    val sourcePrefix = "$APP_STORAGE_FILE_PREFIX$from/"
    val entries = storageKeys().filter { it.startsWith(sourcePrefix) }
    for (key in entries) {
        val suffix = key.removePrefix(sourcePrefix)
        val value = storage.getItem(key) ?: continue
        storage.setItem("$APP_STORAGE_FILE_PREFIX$to/$suffix", value)
        storage.removeItem(key)
    }
    true
} catch (_: Exception) {
    false
}
