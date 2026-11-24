// Vault platform seam on Android: JVM crypto plus file access under filesDir,
// so the vault file is private storage and excluded from Auto Backup via
// backup_rules.xml (domain "file", path "vault/").

package com.pusios.pots.data.vault

import android.content.Context
import android.os.Build
import com.pusios.pots.createSecureSettings
import org.koin.java.KoinJavaComponent.inject
import java.io.File

actual fun createVaultCryptoEngine(): VaultCryptoEngine? = JvmVaultCryptoEngine()

actual fun createVaultFileAccess(): VaultFileAccess? {
    val context: Context by inject(Context::class.java)
    return JvmVaultFileAccess(File(context.filesDir, "vault"))
}

// Quick unlock (card t_6b300431) is an Android 11+ feature: setUserAuthenticationParameters
// with AUTH_DEVICE_CREDENTIAL is what gives the PIN fallback over biometry.
actual fun createVaultBiometricUnlock(): VaultBiometricUnlock? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
    val context: Context by inject(Context::class.java)
    return AndroidVaultBiometricUnlock(createSecureSettings(), context)
}
