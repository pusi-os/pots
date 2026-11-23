// Encrypted vault: bridge for `vault_reveal`. The tool asks for a value, the owner
// confirms, and the value is handed to the UI overlay / clipboard through
// [VaultRevealBridge.takeValue] — never through the tool result, the chat history or a
// provider payload.

package com.pusios.pots.data.vault

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the overlay shows: metadata only. The value itself never lives in this class's flows. */
data class VaultRevealRequest(
    val id: String,
    val itemId: String,
    val itemName: String,
    val field: String,
    val fieldLabel: String,
    val copy: Boolean,
)

sealed interface VaultRevealOutcome {
    data object Shown : VaultRevealOutcome
    data object Denied : VaultRevealOutcome
    data object Locked : VaultRevealOutcome
    data class Failed(val reason: String) : VaultRevealOutcome
}

class VaultRevealBridge(private val session: VaultSession) {

    private val _pending = MutableStateFlow<VaultRevealRequest?>(null)

    /** The reveal to confirm in the UI overlay, or null. */
    val pending: StateFlow<VaultRevealRequest?> = _pending.asStateFlow()

    private var deferred: CompletableDeferred<VaultRevealOutcome>? = null
    private var revealedValue: String? = null

    /** Called by the `vault_reveal` tool; suspends until the owner confirms or denies. */
    suspend fun request(itemId: String, field: String, copy: Boolean): VaultRevealOutcome {
        val item = session.document()?.items?.firstOrNull { it.id == itemId }
            ?: return if (session.locked.value) VaultRevealOutcome.Locked else VaultRevealOutcome.Failed("no vault item with id $itemId")
        val fieldSpec = item.fields.firstOrNull { it.key == field }
            ?: return VaultRevealOutcome.Failed("item '${item.name}' has no field '$field'")
        val value = try {
            readValue(itemId, field)
        } catch (_: VaultLockedException) {
            return VaultRevealOutcome.Locked
        }
        val request = VaultRevealRequest(
            id = newVaultFormId(),
            itemId = itemId,
            itemName = item.name,
            field = field,
            fieldLabel = fieldSpec.label,
            copy = copy,
        )
        revealedValue = value
        _pending.value = request
        val waiting = CompletableDeferred<VaultRevealOutcome>()
        deferred = waiting
        try {
            return waiting.await()
        } finally {
            revealedValue = null
            if (_pending.value?.id == request.id) _pending.value = null
            deferred = null
        }
    }

    /** The overlay or the clipboard takes the value here; the tool path never does. */
    fun takeValue(): String? = revealedValue

    /** Owner confirmed — the overlay shows / copies the value. */
    fun confirm() = finish(VaultRevealOutcome.Shown)

    /** Owner declined or dismissed the overlay. */
    fun deny() = finish(VaultRevealOutcome.Denied)

    private fun finish(outcome: VaultRevealOutcome) {
        deferred?.complete(outcome)
    }

    private suspend fun readValue(itemId: String, field: String): String = session.withKey {
        val item = session.document()?.items?.firstOrNull { it.id == itemId }
            ?: throw VaultLockedException()
        item.fields.firstOrNull { it.key == field }?.value ?: throw VaultLockedException()
    }
}
