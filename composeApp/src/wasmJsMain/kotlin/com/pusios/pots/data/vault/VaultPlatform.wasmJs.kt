// Vault platform seam on the web: not implemented in v1 (the vault would have
// to live in localStorage, which is not acceptable storage for credentials).

package com.pusios.pots.data.vault

actual fun createVaultCryptoEngine(): VaultCryptoEngine? = null
actual fun createVaultBiometricUnlock(): VaultBiometricUnlock? = null

actual fun createVaultFileAccess(): VaultFileAccess? = null
