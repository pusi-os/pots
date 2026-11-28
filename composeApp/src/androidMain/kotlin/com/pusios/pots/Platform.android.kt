package com.pusios.pots

import android.content.Context
import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.graphics.scale
import androidx.core.net.toUri
import com.pusios.pots.adb.AdbApkCache
import com.pusios.pots.adb.AdbBackend
import com.pusios.pots.adb.AdbDeviceRegistry
import com.pusios.pots.adb.AdbGate
import com.pusios.pots.adb.AdbToolDependencies
import com.pusios.pots.adb.AdbTools
import com.pusios.pots.data.AppSettings
import com.pusios.pots.data.DurableSettings
import com.pusios.pots.data.EmailStore
import com.pusios.pots.data.MemoryStore
import com.pusios.pots.data.NotificationStore
import com.pusios.pots.data.SmsDraftStore
import com.pusios.pots.data.SmsStore
import com.pusios.pots.data.TaskStore
import com.pusios.pots.data.miniapp.MiniAppRepository
import com.pusios.pots.data.vault.VaultFormBridge
import com.pusios.pots.data.vault.VaultGate
import com.pusios.pots.data.vault.VaultRevealBridge
import com.pusios.pots.data.vault.VaultSession
import com.pusios.pots.data.vault.VaultStore
import com.pusios.pots.data.vault.VaultToolDependencies
import com.pusios.pots.data.vault.VaultTools
import com.pusios.pots.mcp.McpServerManager
import com.pusios.pots.mcp.mcpLog
import com.pusios.pots.network.tools.Tool
import com.pusios.pots.network.tools.ToolInfo
import com.pusios.pots.notifications.NotificationReader
import com.pusios.pots.notifications.declaresNotificationListener
import com.pusios.pots.sandbox.LinuxSandboxManager
import com.pusios.pots.sandbox.SandboxState
import com.pusios.pots.sandbox.openFileWithIntent
import com.pusios.pots.sandbox.resolveSandboxFile
import com.pusios.pots.sms.SmsReader
import com.pusios.pots.sms.SmsSender
import com.pusios.pots.sms.declaresReadSms
import com.pusios.pots.tools.AppPermission
import com.pusios.pots.tools.AskSandboxAgentTool
import com.pusios.pots.tools.CalendarRepository
import com.pusios.pots.tools.CommonTools
import com.pusios.pots.tools.ContactCreateTool
import com.pusios.pots.tools.ContactDialTool
import com.pusios.pots.tools.ContactsSearchTool
import com.pusios.pots.tools.CreateCalendarEventTool
import com.pusios.pots.tools.DeleteCalendarEventTool
import com.pusios.pots.tools.ImportPhotoTool
import com.pusios.pots.tools.ListCalendarEventsTool
import com.pusios.pots.tools.ListDownloadsTool
import com.pusios.pots.tools.NotificationHelper
import com.pusios.pots.tools.NotificationTools
import com.pusios.pots.tools.ObsidianListNotesTool
import com.pusios.pots.tools.ObsidianReadNoteTool
import com.pusios.pots.tools.ObsidianSearchNotesTool
import com.pusios.pots.tools.ObsidianWriteNoteTool
import com.pusios.pots.tools.OpenFileTool
import com.pusios.pots.tools.PermissionController
import com.pusios.pots.tools.ProcessManagerTool
import com.pusios.pots.tools.SaveToDownloadsTool
import com.pusios.pots.tools.SearchPhotosTool
import com.pusios.pots.tools.SendNotificationTool
import com.pusios.pots.tools.SetAlarmTool
import com.pusios.pots.tools.ShellCommandTool
import com.pusios.pots.tools.SmsTools
import com.pusios.pots.tools.SshConfigureHostTool
import com.pusios.pots.tools.UpdateCalendarEventTool
import com.pusios.pots.tools.VectorSearchTool
import com.pusios.pots.tools.buildAgentToolSet
import com.pusios.pots.ui.potsik.PotsikStage
import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings
import dev.spght.encryptedprefs.EncryptedSharedPreferences
import dev.spght.encryptedprefs.MasterKey
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.openFileSaver
import io.github.vinceglb.filekit.write
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.Dispatchers
import org.koin.java.KoinJavaComponent
import org.koin.java.KoinJavaComponent.inject
import kotlin.coroutines.CoroutineContext

actual fun httpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient(OkHttp) {
    config(this)
}

actual fun getBackgroundDispatcher(): CoroutineContext = Dispatchers.IO

actual fun onDragAndDropEventDropped(event: DragAndDropEvent): PlatformFile? = null

actual val BackIcon: ImageVector = Icons.AutoMirrored.Filled.ArrowBack

actual val currentPlatform: Platform = Platform.Mobile.Android

actual val defaultUiScale: Float = 1.0f

actual val isEmailSupported: Boolean = true

// Evaluated lazily because we need the Koin-injected Context. Whether READ_SMS
// is declared in the merged manifest is a build-time property (foss flavor adds
// it, playStore does not), so caching the first result is safe for the process
// lifetime. The try/catch guards screenshot / unit-test environments that may
// call `getPlatformToolDefinitions()` before Koin has been started.
actual val isSmsSupported: Boolean by lazy {
    try {
        val context: Context by inject(Context::class.java)
        context.declaresReadSms()
    } catch (_: Throwable) {
        false
    }
}

// Same lazy pattern as `isSmsSupported`: probe the merged manifest for the listener
// service. Foss flavor declares it, playStore does not.
actual val isNotificationsSupported: Boolean by lazy {
    try {
        val context: Context by inject(Context::class.java)
        context.declaresNotificationListener()
    } catch (_: Throwable) {
        false
    }
}

actual val isSplinterlandsSupported: Boolean = true

actual suspend fun compressImageBytes(bytes: ByteArray, mimeType: String): ByteArray {
    if (!mimeType.startsWith("image/")) return bytes
    return try {
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
        val maxDim = 1024
        val scaled = if (bitmap.width > maxDim || bitmap.height > maxDim) {
            val scale = maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)
            val newWidth = (bitmap.width * scale).toInt()
            val newHeight = (bitmap.height * scale).toInt()
            bitmap.scale(newWidth, newHeight)
        } else {
            bitmap
        }
        val outputStream = java.io.ByteArrayOutputStream()
        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, outputStream)
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        outputStream.toByteArray()
    } catch (_: Exception) {
        bytes
    }
}

actual fun getAppFilesDirectory(): String {
    val context: Context by inject(Context::class.java)
    return context.filesDir.absolutePath
}

// Uses dev.spght:encryptedprefs-ktx — a maintained community fork of the deprecated
// androidx.security:security-crypto. We keep application-level encryption because
// secure settings store API keys, email passwords, and conversation encryption keys.
// One SharedPreferences instance for the whole process: opening the encrypted store builds a
// Keystore master key and reads the keyset file, and a second instance would redo both.
private var cachedSecurePrefs: android.content.SharedPreferences? = null

actual fun createSecureSettings(): Settings {
    val context: Context by inject(Context::class.java)
    return SecurePrefsSettings(securePrefs(context))
}

/**
 * Encrypted settings that can also flush a single key before returning.
 *
 * Token records need the flush: the authorization server rotates an OAuth refresh token and
 * invalidates the old one, so a rotation that only reached `apply()` and was lost to a process kill
 * costs the owner a full sign-in. Every other setting keeps the asynchronous write — `commit()` on
 * the main thread is an ANR risk on slow flash.
 */
private class SecurePrefsSettings(
    private val prefs: android.content.SharedPreferences,
) : Settings by SharedPreferencesSettings(prefs),
    DurableSettings {
    override fun putStringDurable(key: String, value: String) {
        prefs.edit().putString(key, value).commit()
    }

    override fun removeDurable(key: String) {
        prefs.edit().remove(key).commit()
    }
}

private fun securePrefs(context: Context): android.content.SharedPreferences = cachedSecurePrefs ?: openSecurePrefs(context).also { cachedSecurePrefs = it }

private fun openSecurePrefs(context: Context): android.content.SharedPreferences = try {
    createEncryptedPrefs(context)
} catch (e: Exception) {
    // The Keystore key can be invalidated for good (a backup restored from a build that did not
    // exclude this file, a lock-screen change, a vendor update) and nothing in the file decrypts
    // any more. The old code deleted it — and it holds every non-secret setting as well. Move the
    // unreadable files aside so a human can still recover them, and start from an empty store.
    preserveUnreadableSecurePrefs(context, e)
    createEncryptedPrefs(context)
}

private const val SECURE_PREFS_NAME = "pots_secure_prefs"

/** Default keyset file of EncryptedSharedPreferences; also excluded from backup. */
private const val KEYSET_PREFS_NAME = "__androidx_security_crypto_encrypted_prefs__"

private fun preserveUnreadableSecurePrefs(context: Context, error: Exception) {
    val dir = java.io.File(context.applicationInfo.dataDir, "shared_prefs")
    val stamp = System.currentTimeMillis()
    for (name in listOf(SECURE_PREFS_NAME, KEYSET_PREFS_NAME)) {
        val file = java.io.File(dir, "$name.xml")
        if (!file.exists()) continue
        val target = java.io.File(dir, "$name.unreadable-$stamp.xml")
        val moved = file.renameTo(target)
        mcpLog(
            "securePrefs",
            name,
            "unreadable (${error::class.simpleName}): " +
                if (moved) "preserved as ${target.name}" else "could not be preserved",
        )
    }
}

private fun createEncryptedPrefs(context: Context): android.content.SharedPreferences {
    val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    val prefs = EncryptedSharedPreferences.create(
        context,
        SECURE_PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    return prefs
}

actual fun createLegacySettings(): Settings? = null

// Tool definitions for Android platform. Every tool Android can execute is listed, including the
// ones no per-tool switch controls — chat resolves display names from here, and an omission shows
// the raw tool id instead. Tools driven by a master toggle carry `userToggleable = false`, which
// is what keeps them out of the Tools tab.
actual fun getPlatformToolDefinitions(): List<ToolInfo> = CommonTools.commonToolDefinitions +
    listOf(
        SendNotificationTool.toolInfo,
        CreateCalendarEventTool.toolInfo,
        ListCalendarEventsTool.toolInfo,
        UpdateCalendarEventTool.toolInfo,
        DeleteCalendarEventTool.toolInfo,
        SetAlarmTool.toolInfo,
        ObsidianListNotesTool.toolInfo,
        ObsidianReadNoteTool.toolInfo,
        ObsidianWriteNoteTool.toolInfo,
        ObsidianSearchNotesTool.toolInfo,
        VectorSearchTool.toolInfo,
        SearchPhotosTool.toolInfo,
        ImportPhotoTool.toolInfo,
        ContactsSearchTool.toolInfo,
        ContactCreateTool.toolInfo,
        ContactDialTool.toolInfo,
        SaveToDownloadsTool.toolInfo,
        ListDownloadsTool.toolInfo,
        OpenFileTool.toolInfo,
        ShellCommandTool.toolInfo,
        AskSandboxAgentTool.toolInfo,
        ProcessManagerTool.toolInfo,
        SshConfigureHostTool.toolInfo,
    ) + VaultTools.toolDefinitions + AdbTools.toolDefinitions

actual fun getAvailableTools(): List<Tool> {
    val context: Context by inject(Context::class.java)
    val appSettings: AppSettings by inject(AppSettings::class.java)
    val memoryStore: MemoryStore by inject(MemoryStore::class.java)
    val taskStore: TaskStore by inject(TaskStore::class.java)
    val emailStore: EmailStore by inject(EmailStore::class.java)
    val mcpServerManager: McpServerManager by inject(McpServerManager::class.java)
    val potsikStage: PotsikStage by inject(PotsikStage::class.java)
    val miniAppRepository: MiniAppRepository by inject(MiniAppRepository::class.java)
    val dataRepository: com.pusios.pots.data.DataRepository by inject(com.pusios.pots.data.DataRepository::class.java)
    // The same delegate style as the lines above: the property type carries T.
    val vaultSession: VaultSession by inject(VaultSession::class.java)
    val vaultStore: VaultStore by inject(VaultStore::class.java)
    val vaultFormBridge: VaultFormBridge by inject(VaultFormBridge::class.java)
    val vaultRevealBridge: VaultRevealBridge by inject(VaultRevealBridge::class.java)
    val vaultGate: VaultGate by inject(VaultGate::class.java)
    val vault = VaultToolDependencies(vaultSession, vaultStore, vaultFormBridge, vaultRevealBridge, vaultGate)

    // ADB: the backend, the device registry and the per-action gate. The tools are Android-only;
    // every other platform's getAvailableTools passes no `adb`, so they never appear there.
    val adbBackend: AdbBackend by inject(AdbBackend::class.java)
    val adbDevices: AdbDeviceRegistry by inject(AdbDeviceRegistry::class.java)
    val adbGate: AdbGate by inject(AdbGate::class.java)
    val adbApkCache: AdbApkCache by inject(AdbApkCache::class.java)
    val adb = AdbToolDependencies(adbBackend, adbDevices, adbGate, adbApkCache)

    return buildAgentToolSet(
        appSettings = appSettings,
        memoryStore = memoryStore,
        taskStore = taskStore,
        mcpServerManager = mcpServerManager,
        potsikStage = potsikStage,
        emailStore = emailStore,
        miniAppRepository = miniAppRepository,
        vault = vault,
        adb = adb,
        attachmentProvider = {
            // The last user message's attachments in the active chat.
            dataRepository.chatHistory.value
                .lastOrNull { it.role == com.pusios.pots.ui.chat.History.Role.USER && it.attachments.isNotEmpty() }
                ?.attachments.orEmpty()
        },
    ) {
        if (appSettings.isToolEnabled(SendNotificationTool.ID)) {
            val notificationPermissionController: PermissionController =
                KoinJavaComponent.get(PermissionController::class.java, permissionQualifier(AppPermission.POST_NOTIFICATIONS))
            add(SendNotificationTool.create(NotificationHelper(context, notificationPermissionController)))
        }

        // The four calendar tools share one repository, and therefore one permission
        // controller: the read, list, update and delete tools need the same access as the
        // create tool.
        if (
            appSettings.isToolEnabled(CreateCalendarEventTool.ID) ||
            appSettings.isToolEnabled(ListCalendarEventsTool.ID) ||
            appSettings.isToolEnabled(UpdateCalendarEventTool.ID) ||
            appSettings.isToolEnabled(DeleteCalendarEventTool.ID)
        ) {
            val calendarPermissionController: PermissionController =
                KoinJavaComponent.get(PermissionController::class.java, permissionQualifier(AppPermission.CALENDAR))
            val calendarRepository = CalendarRepository(context, calendarPermissionController)
            if (appSettings.isToolEnabled(CreateCalendarEventTool.ID)) {
                add(CreateCalendarEventTool.create(calendarRepository))
            }
            if (appSettings.isToolEnabled(ListCalendarEventsTool.ID)) {
                add(ListCalendarEventsTool.create(calendarRepository))
            }
            if (appSettings.isToolEnabled(UpdateCalendarEventTool.ID)) {
                add(UpdateCalendarEventTool.create(calendarRepository))
            }
            if (appSettings.isToolEnabled(DeleteCalendarEventTool.ID)) {
                add(DeleteCalendarEventTool.create(calendarRepository))
            }
        }

        if (appSettings.isToolEnabled(SetAlarmTool.ID)) {
            add(SetAlarmTool.create(context))
        }

        if (appSettings.isToolEnabled(ObsidianListNotesTool.ID)) {
            add(ObsidianListNotesTool.create(context, appSettings))
        }
        if (appSettings.isToolEnabled(ObsidianReadNoteTool.ID)) {
            add(ObsidianReadNoteTool.create(context, appSettings))
        }
        if (appSettings.isToolEnabled(ObsidianWriteNoteTool.ID)) {
            add(ObsidianWriteNoteTool.create(context, appSettings))
        }
        if (appSettings.isToolEnabled(ObsidianSearchNotesTool.ID)) {
            add(ObsidianSearchNotesTool.create(context, appSettings))
        }

        if (appSettings.isToolEnabled(VectorSearchTool.ID)) {
            add(VectorSearchTool.create(context))
        }

        if (appSettings.isToolEnabled(SaveToDownloadsTool.ID)) {
            val sandboxManager: LinuxSandboxManager by inject(LinuxSandboxManager::class.java)
            add(SaveToDownloadsTool.create(context, sandboxManager))
        }

        if (appSettings.isToolEnabled(ListDownloadsTool.ID)) {
            add(ListDownloadsTool.create(context))
        }

        if (appSettings.isToolEnabled(SearchPhotosTool.ID)) {
            val photoPermissionController: PermissionController =
                KoinJavaComponent.get(PermissionController::class.java, permissionQualifier(AppPermission.READ_MEDIA_IMAGES))
            add(SearchPhotosTool.create(context, photoPermissionController))
        }

        // The contact tools share one permission controller: search needs READ_CONTACTS,
        // create needs WRITE_CONTACTS, and AppPermission.CONTACTS asks for both.
        if (
            appSettings.isToolEnabled(ContactsSearchTool.ID) ||
            appSettings.isToolEnabled(ContactCreateTool.ID) ||
            appSettings.isToolEnabled(ContactDialTool.ID)
        ) {
            val contactsPermissionController: PermissionController =
                KoinJavaComponent.get(PermissionController::class.java, permissionQualifier(AppPermission.CONTACTS))
            if (appSettings.isToolEnabled(ContactsSearchTool.ID)) {
                add(ContactsSearchTool.create(context, contactsPermissionController))
            }
            if (appSettings.isToolEnabled(ContactCreateTool.ID)) {
                add(ContactCreateTool.create(context, contactsPermissionController))
            }
            // Dialing goes through ACTION_DIAL, which no permission guards.
            if (appSettings.isToolEnabled(ContactDialTool.ID)) {
                add(ContactDialTool.create(context))
            }
        }

        if (appSettings.isToolEnabled(OpenFileTool.schema.name)) {
            add(OpenFileTool)
        }

        if (appSettings.isSandboxEnabled()) {
            val sandboxManager: LinuxSandboxManager by inject(LinuxSandboxManager::class.java)
            if (sandboxManager.state.value is SandboxState.Ready) {
                if (appSettings.isToolEnabled(ImportPhotoTool.ID)) {
                    val photoPermissionController: PermissionController =
                        KoinJavaComponent.get(PermissionController::class.java, permissionQualifier(AppPermission.READ_MEDIA_IMAGES))
                    add(ImportPhotoTool.create(context, sandboxManager, photoPermissionController))
                }
                add(ShellCommandTool)
                // The same gate as the shell it hands work to: the agents live in the
                // same rootfs, and there is nothing to ask without one.
                add(AskSandboxAgentTool)
                add(ProcessManagerTool)
                add(SshConfigureHostTool)
            }
        }

        // SMS read tools: triple-gated. `isSmsSupported` is only true on FOSS builds
        // (READ_SMS declared in merged manifest). `isSmsEnabled()` is the user toggle.
        // `hasPermission()` catches runtime revocation.
        val smsReaderForTools: SmsReader? = if (isSmsSupported) {
            val smsReader: SmsReader by inject(SmsReader::class.java)
            smsReader
        } else {
            null
        }
        if (smsReaderForTools != null && appSettings.isSmsEnabled() && smsReaderForTools.hasPermission()) {
            val smsStore: SmsStore by inject(SmsStore::class.java)
            addAll(SmsTools.getSmsReadTools(smsStore, smsReaderForTools))
        }

        // SMS send tools: independently gated on the Send toggle + SEND_SMS permission.
        // These only *stage* drafts — actual sending is user-triggered via the review banner.
        if (smsReaderForTools != null && appSettings.isSmsSendEnabled()) {
            val smsSender: SmsSender by inject(SmsSender::class.java)
            if (smsSender.hasPermission()) {
                val smsDraftStore: SmsDraftStore by inject(SmsDraftStore::class.java)
                addAll(SmsTools.getSmsSendTools(smsDraftStore, smsReaderForTools, smsSender))
            }
        }

        // Notification tools: triple-gated. `isNotificationsSupported` is FOSS-only
        // (listener service declared in merged manifest). `isNotificationsEnabled()`
        // is the user toggle. `hasAccess()` catches system-level revocation.
        if (isNotificationsSupported && appSettings.isNotificationsEnabled()) {
            val notificationReader: NotificationReader by inject(NotificationReader::class.java)
            if (notificationReader.hasAccess()) {
                val notificationStore: NotificationStore by inject(NotificationStore::class.java)
                addAll(NotificationTools.getNotificationTools(notificationStore, notificationReader))
            }
        }
    }
}

actual fun openUrl(url: String): Boolean = try {
    val context: Context by inject(Context::class.java)
    val parsedUri = url.toUri()
    if (parsedUri.scheme == "file") {
        // Only sandbox files (the guest's /root), resolved exactly like open_file. A raw path would
        // let the model hand any app-private file — settings with API keys, databases — to an
        // external app through a FileProvider read grant.
        val sandboxManager: LinuxSandboxManager by inject(LinuxSandboxManager::class.java)
        val rel = parsedUri.path?.removePrefix("/root")?.takeIf { it.startsWith("/") }?.trimStart('/')
        val file = rel?.let { resolveSandboxFile(sandboxManager.homePath, it) }
        file != null && file.isFile && openFileWithIntent(context, file).success
    } else {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, parsedUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
        true
    }
} catch (_: Exception) {
    false
}

actual fun decodeToImageBitmap(bytes: ByteArray): ImageBitmap? = try {
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
} catch (_: Exception) {
    null
}

@androidx.compose.runtime.Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    androidx.activity.compose.BackHandler(enabled = enabled, onBack = onBack)
}

actual suspend fun saveFileToDevice(bytes: ByteArray, baseName: String, extension: String) {
    val file = FileKit.openFileSaver(suggestedName = baseName, defaultExtension = extension)
    file?.write(bytes)
}

actual fun writeAppStorageFile(relativePath: String, content: String): Boolean = try {
    val context: Context by inject(Context::class.java)
    val base = context.getExternalFilesDir(null)?.absolutePath ?: getAppFilesDirectory()
    val file = java.io.File(base, relativePath)
    file.parentFile?.mkdirs()
    file.writeText(content)
    true
} catch (_: Exception) {
    false
}

actual fun deleteAppStorageFolder(relativePath: String): Boolean = try {
    val context: Context by inject(Context::class.java)
    val base = context.getExternalFilesDir(null)?.absolutePath ?: getAppFilesDirectory()
    java.io.File(base, relativePath).deleteRecursively()
} catch (_: Exception) {
    false
}

private fun appStorageBase(): java.io.File {
    val context: Context by inject(Context::class.java)
    return java.io.File(context.getExternalFilesDir(null)?.absolutePath ?: getAppFilesDirectory())
}

actual fun writeAppStorageBytes(relativePath: String, bytes: ByteArray): Boolean = try {
    val file = java.io.File(appStorageBase(), relativePath)
    file.parentFile?.mkdirs()
    file.writeBytes(bytes)
    true
} catch (_: Exception) {
    false
}

actual fun readAppStorageBytes(relativePath: String): ByteArray? = try {
    java.io.File(appStorageBase(), relativePath).takeIf { it.isFile }?.readBytes()
} catch (_: Exception) {
    null
}

actual fun listAppStorageFiles(relativePath: String): List<String> {
    val root = appStorageBase()
    val base = java.io.File(root, relativePath)
    if (!base.isDirectory) return emptyList()
    return base.walkTopDown()
        .filter { it.isFile }
        .map { it.relativeTo(root).invariantSeparatorsPath }
        .toList()
}

actual fun moveAppStorageFolder(fromRelativePath: String, toRelativePath: String): Boolean = try {
    val root = appStorageBase()
    val from = java.io.File(root, fromRelativePath)
    val to = java.io.File(root, toRelativePath)
    if (!from.isDirectory) return false
    to.parentFile?.mkdirs()
    if (to.exists()) to.deleteRecursively()
    if (from.renameTo(to)) return true
    from.copyRecursively(to, overwrite = true)
    from.deleteRecursively()
    true
} catch (_: Exception) {
    false
}

actual suspend fun compressImageToJpeg(bytes: ByteArray, maxDim: Int, quality: Int): ByteArray? = try {
    val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    val scale = minOf(1f, maxDim.toFloat() / maxOf(bitmap.width, bitmap.height))
    val scaled = if (scale < 1f) bitmap.scale((bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1)) else bitmap
    val out = java.io.ByteArrayOutputStream()
    scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, out)
    if (scaled !== bitmap) scaled.recycle()
    bitmap.recycle()
    out.toByteArray()
} catch (_: Exception) {
    null
}

actual val isImageCompressionSupported: Boolean = true
