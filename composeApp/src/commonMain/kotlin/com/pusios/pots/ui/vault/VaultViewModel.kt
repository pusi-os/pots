// Vault manager UI state on top of the encrypted vault session (card 2/5).
// The plaintext document lives only inside the session; this model never logs
// values, never persists UI state, and returns to the lock screen the moment
// the session locks — including the session's own auto-lock timeout.

package com.pusios.pots.ui.vault

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

    /** Vault exists but the key is not in memory; [error] names the last failure. */
    data class Locked(val error: VaultUnlockError?) : VaultUiState

    /** Session open; [items] already filtered by [query]. */
    data class Unlocked(val query: String, val items: List<VaultItem>) : VaultUiState
}

class VaultViewModel(
    private val session: VaultSession,
    scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<VaultUiState>(VaultUiState.Unavailable)

    /** Current screen to render; collect in the composable. */
    val state: StateFlow<VaultUiState> = _state.asStateFlow()

    init {
        scope.launch {
            session.locked.collect { locked ->
                // Auto-lock or an explicit lock() from anywhere sends the UI
                // back to the unlock screen; plaintext is gone with the key.
                if (locked && _state.value is VaultUiState.Unlocked) {
                    _state.value = VaultUiState.Locked(null)
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
            session.locked.value -> VaultUiState.Locked(null)
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
            VaultUnlockResult.WrongPassword -> locked(VaultUnlockError.WRONG_PASSWORD)
            VaultUnlockResult.Tampered -> locked(VaultUnlockError.TAMPERED)
            VaultUnlockResult.Corrupted -> locked(VaultUnlockError.CORRUPTED)
            is VaultUnlockResult.UnsupportedVersion -> locked(VaultUnlockError.UNSUPPORTED_VERSION)
            VaultUnlockResult.NotCreated -> _state.value = VaultUiState.Setup
            VaultUnlockResult.NotSupported -> _state.value = VaultUiState.Unavailable
        }
    }

    /** First-run setup: create the vault file and open the empty list. */
    suspend fun setup(masterPassword: String) {
        session.createVault(masterPassword)
        _state.value = unlockedState("")
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

    private fun locked(error: VaultUnlockError) {
        _state.value = VaultUiState.Locked(error)
    }

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
            return VaultUiState.Locked(null)
        }
        return VaultUiState.Unlocked(query, items)
    }
}
