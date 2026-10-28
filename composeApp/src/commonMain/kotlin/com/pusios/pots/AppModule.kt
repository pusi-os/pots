package com.pusios.pots

import com.pusios.pots.data.AppSettings
import com.pusios.pots.data.ConversationStorage
import com.pusios.pots.data.DataRepository
import com.pusios.pots.data.EmailStore
import com.pusios.pots.data.HeartbeatManager
import com.pusios.pots.data.MemoryStore
import com.pusios.pots.data.NotificationStore
import com.pusios.pots.data.RemoteDataRepository
import com.pusios.pots.data.SmsDraftStore
import com.pusios.pots.data.SmsStore
import com.pusios.pots.data.TaskScheduler
import com.pusios.pots.data.TaskStore
import com.pusios.pots.data.ToolExecutor
import com.pusios.pots.data.createConversationPersistence
import com.pusios.pots.data.miniapp.MiniAppRepository
import com.pusios.pots.data.miniapp.MiniAppServer
import com.pusios.pots.data.miniapp.MiniAppServerController
import com.pusios.pots.data.miniapp.createMiniAppPersistence
import com.pusios.pots.data.miniapp.createMiniAppServer
import com.pusios.pots.data.miniapp.createMiniAppServerController
import com.pusios.pots.data.runMigrations
import com.pusios.pots.email.EmailPoller
import com.pusios.pots.inference.createLocalInferenceEngine
import com.pusios.pots.inference.createVectorizationManager
import com.pusios.pots.mcp.McpServerManager
import com.pusios.pots.network.Requests
import com.pusios.pots.notifications.NotificationReader
import com.pusios.pots.skills.SkillManager
import com.pusios.pots.sms.SmsPoller
import com.pusios.pots.sms.SmsReader
import com.pusios.pots.sms.SmsSender
import com.pusios.pots.splinterlands.SplinterlandsApi
import com.pusios.pots.splinterlands.SplinterlandsBattleRunner
import com.pusios.pots.splinterlands.SplinterlandsStore
import com.pusios.pots.tools.AppPermission
import com.pusios.pots.tools.NotificationListenerController
import com.pusios.pots.tools.PermissionController
import com.pusios.pots.ui.build.PotsBuildViewModel
import com.pusios.pots.ui.chat.ChatViewModel
import com.pusios.pots.ui.potsik.PotsikStage
import com.pusios.pots.ui.sandbox.SandboxFileBrowserViewModel
import com.pusios.pots.ui.sandbox.SandboxPackagesViewModel
import com.pusios.pots.ui.sandbox.SandboxSessionViewModel
import com.pusios.pots.ui.settings.MiniAppServerViewModel
import com.pusios.pots.ui.settings.SandboxViewModel
import com.pusios.pots.ui.settings.SettingsViewModel
import com.pusios.pots.ui.settings.SplinterlandsViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module

/** Picks the file browser bound to Pots Build's Debian rather than the chat sandbox. */
val POTS_BUILD_FILES = named("potsBuildFiles")

/** Qualifier for the [PermissionController] singleton handling [permission]. */
fun permissionQualifier(permission: AppPermission) = named(permission.name)

val appModule = module {
    AppPermission.entries.forEach { permission ->
        single(permissionQualifier(permission)) { PermissionController(permission) }
    }
    single<SmsReader> { SmsReader() }
    single<SmsSender> { SmsSender() }
    single<NotificationListenerController> { NotificationListenerController() }
    single<NotificationReader> { NotificationReader() }
    single<AppSettings> {
        AppSettings(createSecureSettings()).also {
            it.runMigrations(createLegacySettings())
        }
    }
    single<Requests> {
        Requests()
    }
    single<ConversationStorage> {
        ConversationStorage(get(), createConversationPersistence(get()))
    }
    single<MiniAppRepository> {
        MiniAppRepository(get(), createMiniAppPersistence(get()))
    }
    single<MiniAppServer> {
        createMiniAppServer(get(), get())
    }
    single<MiniAppServerController> {
        createMiniAppServerController()
    }
    single<ToolExecutor> {
        ToolExecutor()
    }
    single<PotsikStage> {
        PotsikStage()
    }
    single<MemoryStore> {
        MemoryStore(get())
    }
    single<TaskStore> {
        TaskStore(get())
    }
    single<EmailStore> {
        EmailStore(get())
    }
    single<EmailPoller> {
        EmailPoller(get<EmailStore>())
    }
    single<SmsStore> {
        SmsStore(get())
    }
    single<SmsPoller> {
        SmsPoller(get<SmsStore>(), get<SmsReader>())
    }
    single<SmsDraftStore> {
        SmsDraftStore(get())
    }
    single<NotificationStore> {
        NotificationStore(get())
    }
    single<SplinterlandsStore> {
        SplinterlandsStore(get())
    }
    single<SplinterlandsApi> {
        SplinterlandsApi()
    }
    single<HeartbeatManager> {
        HeartbeatManager(get(), get(), get(), get())
    }
    single<McpServerManager> {
        McpServerManager(get())
    }
    single<SkillManager> {
        SkillManager(get<SandboxController>())
    }
    single<com.pusios.pots.data.VectorizationManager> { createVectorizationManager(createLocalInferenceEngine(), get()) }
    single<RemoteDataRepository> {
        RemoteDataRepository(
            requests = get(),
            appSettings = get(),
            conversationStorage = get(),
            toolExecutor = get(),
            memoryStore = get(),
            taskStore = get(),
            heartbeatManager = get(),
            emailStore = get(),
            emailPoller = get(),
            smsStore = get(),
            smsPoller = get(),
            smsReader = get(),
            smsPermissionController = get(permissionQualifier(AppPermission.READ_SMS)),
            smsSendPermissionController = get(permissionQualifier(AppPermission.SEND_SMS)),
            smsSender = get(),
            smsDraftStore = get(),
            notificationStore = get(),
            notificationListenerController = get(),
            mcpServerManager = get(),
            skillManager = get(),
            sandboxController = get(),
            localInferenceEngine = createLocalInferenceEngine(),
        )
    }
    single<DataRepository> { get<RemoteDataRepository>() }
    single<SplinterlandsBattleRunner> {
        SplinterlandsBattleRunner(get(), get(), get<DataRepository>(), get<DaemonController>())
    }
    single<TaskScheduler> {
        TaskScheduler(
            get<DataRepository>(),
            get(),
            get(),
            get(),
            get(),
            get<EmailPoller>(),
            get<SmsStore>(),
            get<SmsPoller>(),
            get<NotificationStore>(),
        )
    }
    single<DaemonController> { createDaemonController() }
    single<SandboxController> { createSandboxController() }
    single<PotsBuildController> { createPotsBuildController() }
    viewModel { SettingsViewModel(get<DataRepository>(), get<DaemonController>(), get(permissionQualifier(AppPermission.POST_NOTIFICATIONS)), get<TaskScheduler>(), localNetworkPermissionController = get(permissionQualifier(AppPermission.LOCAL_NETWORK))) }
    viewModel { SandboxViewModel(get<DataRepository>(), get<SandboxController>()) }
    viewModel { SandboxFileBrowserViewModel(get<SandboxController>()) }
    viewModel { SandboxPackagesViewModel(get<SandboxController>()) }
    viewModel { SandboxSessionViewModel(get<SandboxController>(), get<DataRepository>()) }
    viewModel { PotsBuildViewModel(get<PotsBuildController>(), get<DataRepository>()) }
    // Same browser, second environment: Pots Build's Debian instead of the chat sandbox.
    viewModel(POTS_BUILD_FILES) { SandboxFileBrowserViewModel(get<PotsBuildController>().files) }
    viewModel { SplinterlandsViewModel(get<DataRepository>(), get(), get(), get<SplinterlandsApi>()) }
    viewModel { MiniAppServerViewModel(get(), get(), get(), get()) }
    viewModel { ChatViewModel(get<DataRepository>(), get<TaskScheduler>(), localNetworkPermissionController = get(permissionQualifier(AppPermission.LOCAL_NETWORK)), miniAppRepository = get(), taskStore = get()) }
}
