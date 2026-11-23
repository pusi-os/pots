// Vault platform seam on Android: JVM crypto plus file access under filesDir,
// so the vault file is private storage and excluded from Auto Backup via
// backup_rules.xml (domain "file", path "vault/").

package com.pusios.pots.data.vault

import android.content.Context
import java.io.File
import org.koin.java.KoinJavaComponent.inject

actual fun createVaultCryptoEngine(): VaultCryptoEngine? = JvmVaultCryptoEngine()

actual fun createVaultFileAccess(): VaultFileAccess? {
    val context: Context by inject(Context::class.java)
    return JvmVaultFileAccess(File(context.filesDir, "vault"))
}
