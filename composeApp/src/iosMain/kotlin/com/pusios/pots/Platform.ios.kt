@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.pusios.pots

import androidx.compose.material.icons.Icons
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import com.pusios.pots.data.AppSettings
import com.pusios.pots.data.EmailStore
import com.pusios.pots.data.MemoryStore
import com.pusios.pots.data.TaskStore
import com.pusios.pots.data.miniapp.MiniAppRepository
import com.pusios.pots.mcp.McpServerManager
import com.pusios.pots.network.tools.Tool
import com.pusios.pots.network.tools.ToolInfo
import com.pusios.pots.tools.CommonTools
import com.pusios.pots.tools.buildAgentToolSet
import com.pusios.pots.ui.icons.ArrowBackIos
import com.pusios.pots.ui.potsik.PotsikStage
import com.russhwolf.settings.ExperimentalSettingsImplementation
import com.russhwolf.settings.KeychainSettings
import com.russhwolf.settings.NSUserDefaultsSettings
import com.russhwolf.settings.Settings
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.openFileSaver
import io.github.vinceglb.filekit.write
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.darwin.Darwin
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import platform.Foundation.NSData
import platform.Foundation.dataWithBytes
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import kotlin.coroutines.CoroutineContext

actual fun httpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient(Darwin) {
    config(this)
}

actual fun getBackgroundDispatcher(): CoroutineContext = Dispatchers.IO

actual fun onDragAndDropEventDropped(event: DragAndDropEvent): PlatformFile? = null

actual val BackIcon: ImageVector = Icons.AutoMirrored.Filled.ArrowBackIos

actual val currentPlatform: Platform = Platform.Mobile.Ios

actual val defaultUiScale: Float = 1.0f

actual val isEmailSupported: Boolean = true

actual val isSmsSupported: Boolean = false

actual val isNotificationsSupported: Boolean = false

actual val isSplinterlandsSupported: Boolean = false

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
actual suspend fun compressImageBytes(bytes: ByteArray, mimeType: String): ByteArray {
    if (!mimeType.startsWith("image/")) return bytes
    return try {
        val nsData = bytes.usePinned { pinned ->
            NSData.dataWithBytes(pinned.addressOf(0), bytes.size.toULong())
        }
        val image = platform.UIKit.UIImage(data = nsData)
        val maxDim = 1024.0
        val imgWidth = image.size.useContents { width }
        val imgHeight = image.size.useContents { height }
        val scaled = if (imgWidth > maxDim || imgHeight > maxDim) {
            val scale = maxDim / maxOf(imgWidth, imgHeight)
            val newWidth = imgWidth * scale
            val newHeight = imgHeight * scale
            val newSize = kotlinx.cinterop.cValue<platform.CoreGraphics.CGSize> {
                width = newWidth
                height = newHeight
            }
            platform.UIKit.UIGraphicsBeginImageContextWithOptions(newSize, false, 1.0)
            image.drawInRect(
                kotlinx.cinterop.cValue<platform.CoreGraphics.CGRect> {
                    origin.x = 0.0
                    origin.y = 0.0
                    size.width = newWidth
                    size.height = newHeight
                },
            )
            val resized = platform.UIKit.UIGraphicsGetImageFromCurrentImageContext()
            platform.UIKit.UIGraphicsEndImageContext()
            resized ?: image
        } else {
            image
        }
        val jpegData = platform.UIKit.UIImageJPEGRepresentation(scaled, 0.8) ?: return bytes
        jpegData.toByteArray()
    } catch (_: Exception) {
        bytes
    }
}

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    val result = ByteArray(size)
    result.usePinned { pinned ->
        platform.posix.memcpy(pinned.addressOf(0), bytes, length)
    }
    return result
}

actual fun getAppFilesDirectory(): String {
    val paths = platform.Foundation.NSSearchPathForDirectoriesInDomains(
        platform.Foundation.NSDocumentDirectory,
        platform.Foundation.NSUserDomainMask,
        true,
    )
    return paths.first() as String
}

@OptIn(ExperimentalSettingsImplementation::class)
actual fun createSecureSettings(): Settings = KeychainSettings(service = "com.pusios.pots")

actual fun createLegacySettings(): Settings? = NSUserDefaultsSettings(platform.Foundation.NSUserDefaults.standardUserDefaults)

actual fun getPlatformToolDefinitions(): List<ToolInfo> = CommonTools.commonToolDefinitions

private object IosKoinHelper : KoinComponent {
    val appSettings: AppSettings by inject()
    val memoryStore: MemoryStore by inject()
    val taskStore: TaskStore by inject()
    val emailStore: EmailStore by inject()
    val mcpServerManager: McpServerManager by inject()
    val potsikStage: PotsikStage by inject()
    val miniAppRepository: MiniAppRepository by inject()
}

actual fun getAvailableTools(): List<Tool> = buildAgentToolSet(
    appSettings = IosKoinHelper.appSettings,
    memoryStore = IosKoinHelper.memoryStore,
    taskStore = IosKoinHelper.taskStore,
    mcpServerManager = IosKoinHelper.mcpServerManager,
    potsikStage = IosKoinHelper.potsikStage,
    emailStore = IosKoinHelper.emailStore,
    miniAppRepository = IosKoinHelper.miniAppRepository,
)

@Suppress("CAST_NEVER_SUCCEEDS")
actual fun openUrl(url: String): Boolean = try {
    val nsUrl = platform.Foundation.NSURL.URLWithString(url)
    if (nsUrl != null) {
        platform.UIKit.UIApplication.sharedApplication.openURL(nsUrl)
    } else {
        false
    }
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
    // iOS swipe-back is handled by the navigation controller
}

actual suspend fun saveFileToDevice(bytes: ByteArray, baseName: String, extension: String) {
    val file = FileKit.openFileSaver(suggestedName = baseName, defaultExtension = extension)
    file?.write(bytes)
}

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
actual fun sendHeartbeatNotification(title: String, body: String) {
    // The authorization completion runs asynchronously on a system queue, so it's outside the
    // outer try/catch's scope and needs its own guard. Heartbeat delivery must never throw.
    try {
        val center = platform.UserNotifications.UNUserNotificationCenter.currentNotificationCenter()
        val options = platform.UserNotifications.UNAuthorizationOptionAlert or
            platform.UserNotifications.UNAuthorizationOptionSound or
            platform.UserNotifications.UNAuthorizationOptionBadge
        center.requestAuthorizationWithOptions(options) { granted, _ ->
            if (!granted) return@requestAuthorizationWithOptions
            try {
                val content = platform.UserNotifications.UNMutableNotificationContent().apply {
                    setTitle(title)
                    setBody(body)
                    setSound(platform.UserNotifications.UNNotificationSound.defaultSound())
                }
                // iOS rejects nil triggers for non-scheduled notifications and 0 for time-interval
                // triggers, so use a tiny delay to fire effectively immediately.
                val trigger = platform.UserNotifications.UNTimeIntervalNotificationTrigger
                    .triggerWithTimeInterval(timeInterval = 0.1, repeats = false)
                val request = platform.UserNotifications.UNNotificationRequest.requestWithIdentifier(
                    identifier = platform.Foundation.NSUUID().UUIDString,
                    content = content,
                    trigger = trigger,
                )
                center.addNotificationRequest(request, null)
            } catch (_: Throwable) {
            }
        }
    } catch (_: Throwable) {
    }
}

actual fun writeAppStorageFile(relativePath: String, content: String): Boolean = try {
    val path = getAppFilesDirectory() + "/" + relativePath
    val directory = path.substringBeforeLast('/')
    platform.Foundation.NSFileManager.defaultManager.createDirectoryAtPath(
        path = directory,
        withIntermediateDirectories = true,
        attributes = null,
        error = null,
    )
    val bytes = content.encodeToByteArray()
    val data = bytes.usePinned { pinned ->
        NSData.dataWithBytes(pinned.addressOf(0), bytes.size.toULong())
    }
    data.writeToFile(path, atomically = true)
} catch (_: Throwable) {
    false
}

actual fun deleteAppStorageFolder(relativePath: String): Boolean = try {
    platform.Foundation.NSFileManager.defaultManager.removeItemAtPath(
        path = getAppFilesDirectory() + "/" + relativePath,
        error = null,
    )
} catch (_: Throwable) {
    false
}

actual fun writeAppStorageBytes(relativePath: String, bytes: ByteArray): Boolean = try {
    val path = getAppFilesDirectory() + "/" + relativePath
    val directory = path.substringBeforeLast('/')
    platform.Foundation.NSFileManager.defaultManager.createDirectoryAtPath(
        path = directory,
        withIntermediateDirectories = true,
        attributes = null,
        error = null,
    )
    val data = bytes.usePinned { pinned ->
        NSData.dataWithBytes(pinned.addressOf(0), bytes.size.toULong())
    }
    data.writeToFile(path, atomically = true)
} catch (_: Throwable) {
    false
}

actual fun readAppStorageBytes(relativePath: String): ByteArray? = try {
    platform.Foundation.NSData.dataWithContentsOfFile(getAppFilesDirectory() + "/" + relativePath)?.toByteArray()
} catch (_: Throwable) {
    null
}

actual fun listAppStorageFiles(relativePath: String): List<String> {
    val root = getAppFilesDirectory()
    val base = "$root/$relativePath"
    val entries = platform.Foundation.NSFileManager.defaultManager.subpathsAtPath(base) ?: return emptyList()
    return entries.mapNotNull { it as? String }.map { "$relativePath/$it" }
}

actual fun moveAppStorageFolder(fromRelativePath: String, toRelativePath: String): Boolean = try {
    val root = getAppFilesDirectory()
    val from = "$root/$fromRelativePath"
    val to = "$root/$toRelativePath"
    val manager = platform.Foundation.NSFileManager.defaultManager
    manager.createDirectoryAtPath(
        path = to.substringBeforeLast('/'),
        withIntermediateDirectories = true,
        attributes = null,
        error = null,
    )
    if (manager.fileExistsAtPath(to)) manager.removeItemAtPath(to, null)
    manager.moveItemAtPath(from, to, null)
} catch (_: Throwable) {
    false
}
