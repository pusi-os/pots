// Vault manager UI state on top of the encrypted vault session (card 2/5).
// The plaintext document lives only inside the session; this model never logs
// values, never persists UI state, and returns to the lock screen the moment
// the session locks — including the session's own auto-lock timeout.

package com.pusios.pots.ui.vault

import com.pusios.pots.data.vault.VaultBiometricUnlock
import com.pusios.pots.data.vault.VaultItem
import com.pusios.pots.data.vault.VaultSession
import com.pusios.pots.data.vault.VaultUnlockResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Why the last unlock attempt failed; null when nothing failed yet. */
enum class VaultUnlockError {
    WRONG_PASSWORD,
    TAMPERED,
    CORRUPTED,
    UNSUPPORTED_VERSION,
}

/** One screen of the password manager; the sealed cases map 1:1 to screens. */
sealed interface VaultUiState {
    /** Platform has no vault implementation (iOS / web in v1). */
    data object Unavailable : VaultUiState

    /** No vault file yet: the first-run master password setup. */
    data object Setup : VaultUiState

    /**
     * Vault exists but the key is not in memory; [error] names the last
     * failure. [biometricEnabled] is true when a device-bound quick-unlock
     * blob exists, so the lock screen offers the fingerprint/PIN button.
     */
    data class Locked(val error: VaultUnlockError?, val biometricEnabled: Boolean = false) : VaultUiState

    /** Session open; [items] already filtered by [query]. [biometricEnabled] drives the settings toggle. */
    data class Unlocked(
        val query: String,
        val items: List<VaultItem>,
        val biometricEnabled: Boolean = false,
    ) : VaultUiState
}

class VaultViewModel(
    private val session: VaultSession,
    scope: CoroutineScope,
    private val biometric: VaultBiometricUnlock? = null,
) {
    /** Platform offers quick unlock at all (Android 11+ only in v1). */
    val biometricAvailable: Boolean
        get() = biometric?.isAvailable == true

    private val _state = MutableStateFlow<VaultUiState>(VaultUiState.Unavailable)

    /** Current screen to render; collect in the composable. */
    val state: StateFlow<VaultUiState> = _state.asStateFlow()

    init {
        scope.launch {
            session.locked.collect { isLocked ->
                // Auto-lock or an explicit lock() from anywhere sends the UI
                // back to the unlock screen; plaintext is gone with the key.
                if (isLocked && _state.value is VaultUiState.Unlocked) {
                    _state.value = locked(null)
                }
            }
        }
        scope.launch { refresh() }
    }

    /** Recompute the screen from the session (initial entry and after mutations). */
    suspend fun refresh() {
        _state.value = when {
            !session.isSupported -> VaultUiState.Unavailable
            !session.hasVaultFile() -> VaultUiState.Setup
            session.locked.value -> locked(null)
            else -> unlockedState("")
        }
    }

    /** Filter the card list; only meaningful while unlocked. */
    suspend fun setQuery(query: String) {
        if (_state.value is VaultUiState.Unlocked) {
            _state.value = unlockedState(query)
        }
    }

    /** Unlock with the master password; a failure returns to the lock screen with an error. */
    suspend fun unlock(masterPassword: String) {
        when (session.unlock(masterPassword)) {
            VaultUnlockResult.Success -> _state.value = unlockedState("")
            VaultUnlockResult.WrongPassword -> _state.value = locked(VaultUnlockError.WRONG_PASSWORD)
            VaultUnlockResult.Tampered -> _state.value = locked(VaultUnlockError.TAMPERED)
            VaultUnlockResult.Corrupted -> _state.value = locked(VaultUnlockError.CORRUPTED)
            is VaultUnlockResult.UnsupportedVersion -> _state.value = locked(VaultUnlockError.UNSUPPORTED_VERSION)
            VaultUnlockResult.NotCreated -> _state.value = VaultUiState.Setup
            VaultUnlockResult.NotSupported -> _state.value = VaultUiState.Unavailable
        }
    }

    /** First-run setup: create the vault file and open the empty list. A fresh
     * vault invalidates any stale quick-unlock blob from a previous vault. */
    suspend fun setup(masterPassword: String) {
        session.createVault(masterPassword)
        biometric?.disable()
        _state.value = unlockedState("")
    }

    /**
     * Quick unlock (card t_6b300431): confirm with biometry/PIN and open the
     * session from the stored wrap blob. A declined prompt leaves the screen
     * untouched; a blob that no longer matches the vault is dropped.
     */
    suspend fun unlockBiometric(host: VaultBiometricHost?, promptTitle: String) {
        val bio = biometric ?: return
        if (!bio.isEnabled) return
        val key = bio.promptForVaultKey(host, promptTitle) ?: return
        when (session.unlockWithKey(key)) {
            VaultUnlockResult.Success -> _state.value = unlockedState("")

            // The wrap blob is stale (vault recreated, device key invalidated):
            // quick unlock is dead until the master password re-enables it.
            VaultUnlockResult.Tampered, VaultUnlockResult.NotCreated -> {
                bio.disable()
                _state.value = locked(null)
            }

            else -> _state.value = locked(null)
        }
    }

    /** Toggle quick unlock. Enabling wraps the current vault key under the
     * device key after one confirmation; disabling drops the blob. */
    suspend fun setBiometricEnabled(enabled: Boolean, host: VaultBiometricHost?, promptTitle: String) {
        val bio = biometric ?: return
        if (enabled) {
            if (_state.value !is VaultUiState.Unlocked) return
            session.withKey { key -> bio.enable(key, host, promptTitle) }
        } else {
            bio.disable()
        }
        refreshUnlocked()
    }

    /** Lock now; the [session.locked] flow moves the UI back to the unlock screen. */
    fun lock() = session.lock()

    /** Insert or replace a card and refresh the visible list. */
    suspend fun save(item: VaultItem) {
        session.upsert(item)
        refreshUnlocked()
    }

    /** Remove a card and refresh the visible list. */
    suspend fun delete(itemId: String) {
        session.delete(itemId)
        refreshUnlocked()
    }

    private fun locked(error: VaultUnlockError?): VaultUiState = VaultUiState.Locked(error, biometric?.isEnabled == true)

    private fun refreshUnlocked() {
        val current = _state.value
        if (current is VaultUiState.Unlocked) {
            _state.value = unlockedState(current.query)
        }
    }

    /** The screen to show after a session action: still unlocked, or locked again. */
    private fun unlockedState(query: String): VaultUiState {
        // search() runs the lazy auto-lock check, so read the flag after it:
        // a timed-out session reports empty results but has not locked yet.
        val items = session.search(query)
        if (session.locked.value) {
            return locked(null)
        }
        return VaultUiState.Unlocked(query, items, biometric?.isEnabled == true)
    }
}
