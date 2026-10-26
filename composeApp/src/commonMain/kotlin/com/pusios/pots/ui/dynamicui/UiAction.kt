package com.pusios.pots.ui.dynamicui

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

@Immutable
@Serializable
sealed interface UiAction

@Immutable
@Serializable
@SerialName("callback")
data class CallbackAction(
    val event: String = "",
    val data: Map<String, JsonPrimitive>? = null,
    val collectFrom: List<String>? = null,
) : UiAction {
    /** Returns data values coerced to strings (handles booleans/numbers from LLMs). */
    val dataAsStrings: Map<String, String>?
        get() = data?.mapValues { it.value.content }
}

@Immutable
@Serializable
@SerialName("toggle")
data class ToggleAction(
    val targetId: String = "",
) : UiAction

@Immutable
@Serializable
@SerialName("open_url")
data class OpenUrlAction(
    val url: String = "",
) : UiAction

@Immutable
@Serializable
@SerialName("copy_to_clipboard")
data class CopyToClipboardAction(
    val text: String = "",
) : UiAction

/**
 * Writes one record into the app's persistent store. Values come from the form
 * (the `collectFrom` ids) and from the literal `data` map; both are resolved in
 * the app at press time, so a button can persist exactly what was typed.
 */
@Immutable
@Serializable
@SerialName("store_put")
data class StorePutAction(
    val collection: String = "",
    val recordId: String? = null,
    val data: Map<String, JsonPrimitive>? = null,
    val collectFrom: List<String>? = null,
    /** Optional screen id to switch to after the write. */
    val navigateTo: String? = null,
) : UiAction

/** Removes one record, or every record of a collection when no id is given. */
@Immutable
@Serializable
@SerialName("store_delete")
data class StoreDeleteAction(
    val collection: String = "",
    val recordId: String? = null,
    val collectFrom: List<String>? = null,
) : UiAction

/**
 * Calls a host service and (optionally) stores the result in a collection so a
 * records node can show it. This is how a native app reads the calendar or the
 * user's files without an LLM.
 */
@Immutable
@Serializable
@SerialName("host_call")
data class HostCallAction(
    /** `calendar.list`, `calendar.create`, `files.list`, `files.read`, `files.write`, `notes.list`. */
    val service: String = "",
    val params: Map<String, JsonPrimitive>? = null,
    val collectFrom: List<String>? = null,
    /** Collection the result is written to (cleared first for list services). */
    val storeTo: String? = null,
) : UiAction

/** Switches to another saved screen without an AI round-trip. */
@Immutable
@Serializable
@SerialName("navigate")
data class NavigateAction(
    val screenId: String = "",
) : UiAction
