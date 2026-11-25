package com.pusios.pots.adb

import com.pusios.pots.permissionQualifier
import com.pusios.pots.tools.AppPermission
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import java.io.File

/**
 * ADB wiring: the host key, the device registry and the backend behind [AdbBackend].
 *
 * The local-network permission controller is injected rather than looked up, because the mDNS scan
 * has to ask for it before it runs on Android 17+. Handing the backend to the agent is the next
 * card's job (Platform.android.kt); this module only makes the objects available.
 */
val adbModule = module {
    single { AdbKeyStore(File(androidContext().filesDir, ADB_DIRECTORY)) }
    single { AdbDeviceRegistry(File(androidContext().filesDir, ADB_DIRECTORY)) }
    single<AdbBackend> {
        LibAdbBackend(
            context = androidContext(),
            keys = get(),
            localNetworkPermission = get(permissionQualifier(AppPermission.LOCAL_NETWORK)),
        )
    }
}
