// Vault quick unlock host on iOS: no biometric prompt in v1, no anchor.

package com.pusios.pots.ui.vault

import androidx.compose.runtime.Composable

actual class VaultBiometricHost actual constructor()

@Composable
actual fun rememberVaultBiometricHost(): VaultBiometricHost? = null
