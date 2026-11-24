// Encrypted vault: device-bound quick unlock (card t_6b300431). The vault key
// is re-wrapped under a hardware-backed device key that only opens after a
// BiometricPrompt/PIN confirmation. The master password is never stored and
// never touches this path: after a lock() the in-memory key is wiped, and
// biometry can only restore access by decrypting the wrap blob — a fresh
// unlock, not a resurrection of the wiped key. Desktop / iOS / web register
// [VaultBiometricUnlockUnavailable] and the UI hides the toggle.

package com.pusios.pots.data.vault

import com.pusios.pots.ui.vault.VaultBiometricHost

/** Device-bound quick unlock over the master password. Android 11+ only. */
interface VaultBiometricUnlock {
    /** False on platforms without a BiometricPrompt/PIN implementation. */
    val isAvailable: Boolean

    /** True when a quick-unlock wrap blob exists on this device. */
    val isEnabled: Boolean

    /**
     * Wrap [vaultKey] under the device key after one confirmation. False when
     * the user declined, the prompt failed, or no [VaultBiometricHost] exists.
     */
    suspend fun enable(vaultKey: ByteArray, host: VaultBiometricHost?, promptTitle: String): Boolean

    /** Drop the wrap blob; the master password becomes the only way in again. */
    fun disable()

    /**
     * Confirm with biometry/PIN and unwrap the vault key from the stored blob.
     * Null when declined, failed, or the blob no longer matches the device key
     * (in that case the implementation also calls [disable]).
     */
    suspend fun promptForVaultKey(host: VaultBiometricHost?, promptTitle: String): ByteArray?
}

/** Platform without a quick-unlock implementation; the UI renders no toggle. */
object VaultBiometricUnlockUnavailable : VaultBiometricUnlock {
    override val isAvailable: Boolean = false
    override val isEnabled: Boolean = false

    override suspend fun enable(vaultKey: ByteArray, host: VaultBiometricHost?, promptTitle: String): Boolean = false

    override suspend fun promptForVaultKey(host: VaultBiometricHost?, promptTitle: String): ByteArray? = null

    override fun disable() {}
}

/** Platform seam: the real implementation on Android, null elsewhere. */
expect fun createVaultBiometricUnlock(): VaultBiometricUnlock?
