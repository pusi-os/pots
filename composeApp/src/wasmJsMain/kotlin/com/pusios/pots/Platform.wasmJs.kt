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

actual fun writeAppStorageFile(relativePath: String, content: String): Boolean = false

actual fun deleteAppStorageFolder(relativePath: String): Boolean = false

actual fun writeAppStorageBytes(relativePath: String, bytes: ByteArray): Boolean = false

actual fun readAppStorageBytes(relativePath: String): ByteArray? = null

actual fun listAppStorageFiles(relativePath: String): List<String> = emptyList()

actual fun moveAppStorageFolder(fromRelativePath: String, toRelativePath: String): Boolean = false
