// Vault platform seam on iOS: not implemented in v1. The app builds, and the
// vault reports as unsupported; master-password crypto for iOS would need
// CommonCrypto (PBKDF2 + AES-GCM) behind the same [VaultCryptoEngine] seam.

package com.pusios.pots.data.vault

actual fun createVaultCryptoEngine(): VaultCryptoEngine? = null
actual fun createVaultBiometricUnlock(): VaultBiometricUnlock? = null

actual fun createVaultFileAccess(): VaultFileAccess? = null
