// Vault platform seam on desktop: JVM crypto plus file access under the app
// files directory (~/.pots on Linux, same place the settings file lives).

package com.pusios.pots.data.vault

import com.pusios.pots.getAppFilesDirectory
import java.io.File

actual fun createVaultCryptoEngine(): VaultCryptoEngine? = JvmVaultCryptoEngine()

actual fun createVaultBiometricUnlock(): VaultBiometricUnlock? = null

actual fun createVaultFileAccess(): VaultFileAccess? = JvmVaultFileAccess(File(getAppFilesDirectory(), "vault"))
