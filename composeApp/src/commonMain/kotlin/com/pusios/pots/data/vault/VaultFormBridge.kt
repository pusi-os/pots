// Encrypted vault: suspend bridge between the agent's `vault_request_form` tool and the
// chat UI. The tool publishes a form *description* (keys, labels, input kinds — never
// values); the UI renders it from this typed model, and [VaultFormBridge.submit] — the only
// place user-entered values touch the vault — writes them straight into the store. A value
// never enters the conversation: no History entry, no messageJson, no provider payload.

package com.pusios.pots.data.vault

import com.pusios.pots.data.currentConversationIdOrNull
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/** How the request sheet presents a field to the user. */
enum class VaultFieldInput { TEXT, SECRET, MULTILINE, SECRET_MULTILINE, URL }

/** One field of the request form: a description only — no value ever passes through here. */
@Serializable
data class VaultFormFieldSpec(
    val key: String,
    val label: String,
    val input: VaultFieldInput = VaultFieldInput.TEXT,
    val required: Boolean = false,
    val placeholder: String? = null,
)

/** The form the agent asked the UI to show. Serialized for the sheet, values excluded by design. */
@Serializable
data class VaultFormRequest(
    val id: String,
    val mode: String,
    val itemId: String? = null,
    val itemType: String = VaultItemKind.LOGIN.id,
    val name: String = "",
    val url: String = "",
    val username: String = "",
    val tags: List<String> = emptyList(),
    val notes: String = "",
    // Why the agent needs the secret. Always visible to the human — the main defense
    // against an injection asking for a password under an innocent label.
    val reason: String,
    val fields: List<VaultFormFieldSpec> = emptyList(),
    val allowGenerate: Boolean = true,
    // Scope: the sheet lives in this conversation only; a submit from another one cancels.
    val conversationId: String? = null,
)

/** What the user did with the sheet. */
sealed interface VaultFormOutcome {
    data class Saved(val itemId: String, val savedKeys: List<String>, val created: Boolean) : VaultFormOutcome
    data object Cancelled : VaultFormOutcome
    data object Locked : VaultFormOutcome
    data class Failed(val reason: String) : VaultFormOutcome
}

/**
 * Suspend bridge modeled on [com.pusios.pots.tools.PermissionController]: the tool side
 * waits on a deferred, the Compose side completes it. [pending] is what the chat screen
 * collects to show the sheet; [submit] is the only write path for entered values.
 */
class VaultFormBridge(private val session: VaultSession) {

    private val _pending = MutableStateFlow<VaultFormRequest?>(null)

    /** The form to render in the chat UI, or null when nothing is requested. */
    val pending: StateFlow<VaultFormRequest?> = _pending.asStateFlow()

    private var deferred: CompletableDeferred<VaultFormOutcome>? = null

    /** Called by the `vault_request_form` tool; suspends until submit/cancel/cancel-scope. */
    suspend fun request(form: VaultFormRequest): VaultFormOutcome {
        _pending.value = form
        val waiting = CompletableDeferred<VaultFormOutcome>()
        deferred = waiting
        try {
            return waiting.await()
        } finally {
            // Cancellation (user pressed stop) or timeout must leave no stale sheet.
            if (_pending.value?.id == form.id) _pending.value = null
            deferred = null
        }
    }

    /** Called from the sheet: writes the entered values directly into the vault. */
    suspend fun submit(formId: String, values: Map<String, String>): VaultFormOutcome {
        val form = _pending.value ?: return VaultFormOutcome.Cancelled
        if (form.id != formId) return VaultFormOutcome.Cancelled
        // Conversation scope: a sheet left open from another chat must not answer
        // this conversation's tool call.
        if (form.conversationId != currentConversationIdOrNull()) {
            _pending.value = null
            deferred?.complete(VaultFormOutcome.Cancelled)
            deferred = null
            return VaultFormOutcome.Cancelled
        }
        val outcome = try {
            write(form, values)
        } catch (_: VaultLockedException) {
            VaultFormOutcome.Locked
        } catch (e: IllegalArgumentException) {
            VaultFormOutcome.Failed(e.message ?: "could not save the form")
        }
        deferred?.complete(outcome)
        return outcome
    }

    /** Called from the sheet's Cancel button or when the conversation switches. */
    fun cancel(formId: String) {
        if (_pending.value?.id != formId) return
        _pending.value = null
        deferred?.complete(VaultFormOutcome.Cancelled)
        deferred = null
    }

    private suspend fun write(form: VaultFormRequest, values: Map<String, String>): VaultFormOutcome {
        val specs = form.fields.map { spec ->
            VaultField(key = spec.key, label = spec.label, type = spec.input.toFieldKind().id)
        }
        val item: VaultItem
        val created: Boolean
        if (form.mode == MODE_UPDATE && !form.itemId.isNullOrBlank()) {
            val existing = session.document()?.items?.firstOrNull { it.id == form.itemId }
                ?: return VaultFormOutcome.Failed("no vault item with id ${form.itemId}")
            created = false
            item = existing.copy(
                type = form.itemType.takeIf { it.isNotBlank() } ?: existing.type,
                name = form.name.takeIf { it.isNotBlank() } ?: existing.name,
                url = form.url.ifBlank { existing.url },
                username = form.username.ifBlank { existing.username },
                notes = form.notes.ifBlank { existing.notes },
                tags = form.tags.ifEmpty { existing.tags },
            )
        } else {
            created = true
            item = VaultItem(
                id = "",
                type = form.itemType,
                name = form.name.ifBlank { "Untitled" },
                url = form.url,
                username = form.username,
                notes = form.notes,
                tags = form.tags,
            )
        }
        // Requested fields replace same-key fields and keep the rest (an update may add
        // a password to an existing card without touching its other fields).
        val merged = item.fields.filterNot { existing -> specs.any { it.key == existing.key } } + specs
        val saved = session.upsert(item.copy(fields = merged), values)
        return VaultFormOutcome.Saved(saved.id, values.keys.toList(), created)
    }

    private fun VaultFieldInput.toFieldKind(): VaultFieldKind = when (this) {
        VaultFieldInput.TEXT -> VaultFieldKind.TEXT
        VaultFieldInput.SECRET -> VaultFieldKind.SECRET
        VaultFieldInput.MULTILINE -> VaultFieldKind.MULTILINE
        VaultFieldInput.SECRET_MULTILINE -> VaultFieldKind.SECRET_MULTILINE
        VaultFieldInput.URL -> VaultFieldKind.URL
    }

    companion object {
        const val MODE_UPDATE = "update"
    }
}

/** New id for a form request. Internal to the vault channel. */
@OptIn(ExperimentalUuidApi::class)
internal fun newVaultFormId(): String = Uuid.random().toString()