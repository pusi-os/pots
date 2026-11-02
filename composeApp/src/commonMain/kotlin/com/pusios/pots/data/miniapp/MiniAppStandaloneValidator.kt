package com.pusios.pots.data.miniapp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Result of checking whether saved UI can run without an agent round-trip. */
data class MiniAppStandaloneCheck(
    val ready: Boolean,
    val reasons: List<String> = emptyList(),
)

/**
 * Checks action declarations in saved pots-ui JSON. The check intentionally works
 * on the persisted JSON rather than on renderer internals, so every save path
 * (editor and repository callers) gets the same safety gate. This does not
 * prove availability of platform permissions or external resources.
 */
object MiniAppStandaloneValidator {
    private val supportedHostServices = setOf(
        "calendar.list",
        "calendar.create",
        "files.list",
        "files.read",
        "files.write",
        "files.delete",
        "notes.list",
        "notes.write",
    )

    fun check(screens: List<MiniAppScreen>): MiniAppStandaloneCheck {
        val reasons = buildList {
            screens.forEach { screen ->
                val root = runCatching { Json.parseToJsonElement(screen.potsUiJson) }.getOrNull()
                if (root !is JsonObject || com.pusios.pots.ui.dynamicui.PotsUiParser.parseUiBlockBody(screen.potsUiJson) !is com.pusios.pots.ui.dynamicui.PotsUiParser.UiBlockResult.Ui) {
                    add("${screen.title.ifBlank { screen.id }} contains invalid UI JSON")
                } else {
                    inspect(root, screen.title.ifBlank { screen.id }, this)
                }
            }
        }.distinct()
        return MiniAppStandaloneCheck(ready = reasons.isEmpty(), reasons = reasons)
    }

    private fun inspect(element: JsonElement, screen: String, reasons: MutableList<String>) {
        when (element) {
            is JsonObject -> {
                listOf("action", "recordAction").forEach { key ->
                    element[key]?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.let { inspectAction(it, screen, reasons) }
                }
                element.filterKeys { it != "action" && it != "recordAction" }.values.forEach { inspect(it, screen, reasons) }
            }

            is kotlinx.serialization.json.JsonArray -> element.forEach { inspect(it, screen, reasons) }

            is JsonPrimitive -> Unit
        }
    }

    private fun inspectAction(element: JsonElement, screen: String, reasons: MutableList<String>) {
        val action = element as? JsonObject
        if (action == null) {
            reasons += "$screen has an agent callback action"
            return
        }
        val type = action["type"]?.let { (it as? JsonPrimitive)?.content }
        val inferred = type ?: when {
            "event" in action -> "callback"
            "targetId" in action -> "toggle"
            "url" in action -> "open_url"
            "collection" in action && ("data" in action || "collectFrom" in action) -> "store_put"
            "collection" in action && "recordId" in action -> "store_delete"
            "screenId" in action -> "navigate"
            else -> "callback"
        }
        when (inferred) {
            "callback" -> reasons += "$screen has an agent callback: ${action["event"] ?: "unknown"}"

            "host_call" -> {
                val service = (action["service"] as? JsonPrimitive)?.content.orEmpty().lowercase()
                if (service !in supportedHostServices) {
                    reasons += "$screen uses unsupported host service: ${service.ifBlank { "unknown" }}"
                }
            }

            "toggle", "open_url", "copy_to_clipboard", "store_put", "store_delete", "navigate" -> Unit

            else -> reasons += "$screen uses unknown action: $inferred"
        }
    }
}

internal fun MiniApp.withValidatedStandaloneStatus(): MiniApp = copy(
    standaloneReady = kind == MiniAppKind.NATIVE && screens.isNotEmpty() && automations.isEmpty() &&
        MiniAppStandaloneValidator.check(screens).ready,
)
