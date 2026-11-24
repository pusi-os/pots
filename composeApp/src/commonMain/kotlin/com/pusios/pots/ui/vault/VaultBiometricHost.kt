// Vault quick unlock (card t_6b300431): the UI anchor a platform biometric
// prompt needs. Android binds [VaultBiometricHost] to the FragmentActivity in
// composition; desktop / iOS / web have no anchor and always return null.

package com.pusios.pots.ui.vault

import androidx.compose.runtime.Composable

/** Opaque platform anchor for a biometric prompt; see the actuals. */
expect class VaultBiometricHost()

/** The current prompt anchor, or null when the platform has none. */
@Composable
expect fun rememberVaultBiometricHost(): VaultBiometricHost?
