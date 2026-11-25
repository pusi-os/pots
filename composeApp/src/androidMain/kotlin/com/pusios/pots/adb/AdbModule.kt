package com.pusios.pots.adb

import com.pusios.pots.permissionQualifier
import com.pusios.pots.tools.AppPermission
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import java.io.File

/**
 * ADB wiring: the host key, the device registry, the backend behind [AdbBackend], the per-action
 * confirmation gate, the APK downloader and the master-switch watcher.
 *
 * The local-network permission controller is injected rather than looked up, because the mDNS scan
 * has to ask for it before it runs on Android 17+. The registry is exposed both as itself (the
 * settings screen) and as [AdbDeviceStore] (the agent tools) — one object, two views.
 */
val adbModule = module {
    single { AdbKeyStore(File(androidContext().filesDir, ADB_DIRECTORY)) }
    single { AdbDeviceRegistry(File(androidContext().filesDir, ADB_DIRECTORY)) }
    single<AdbDeviceStore> { get<AdbDeviceRegistry>() }
    single<AdbBackend> {
        LibAdbBackend(
            context = androidContext(),
            keys = get(),
            localNetworkPermission = get(permissionQualifier(AppPermission.LOCAL_NETWORK)),
        )
    }
    single { AdbGate() }
    single<AdbApkCache> { AndroidAdbApkCache(androidContext()) }
    single { AdbMasterSwitch(appSettings = get(), backend = get()) }
}
