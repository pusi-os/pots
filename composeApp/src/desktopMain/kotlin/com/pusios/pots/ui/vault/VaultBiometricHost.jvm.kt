// Vault quick unlock host on desktop: no biometric prompt, no anchor.

package com.pusios.pots.ui.vault

import androidx.compose.runtime.Composable

actual class VaultBiometricHost actual constructor()

@Composable
actual fun rememberVaultBiometricHost(): VaultBiometricHost? = null
