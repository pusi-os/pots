package com.pusios.pots.tools

/**
 * Phone numbers and the shape of a contact as the agent is allowed to see it.
 *
 * Everything here is platform-free on purpose: the Android tools query
 * `ContactsContract`, but what counts as "the same number" and what a tool may
 * return to the model are decisions the tests can pin down without a device.
 */
object PhoneNumbers {

    /**
     * Hard cap on how many contacts `contacts_search` returns. The address book is
     * personal data; the model only ever sees the matches of an explicit query.
     */
    const val MAX_SEARCH_RESULTS = 10

    /**
     * How many rows the Android queries fetch: one more than the model may see, so
     * [contactsSearchResponse] can tell "exactly ten matches" from "ten and there are more".
     * Fetching exactly [MAX_SEARCH_RESULTS] would make its `has_more` flag dead code.
     */
    const val SEARCH_FETCH_LIMIT = MAX_SEARCH_RESULTS + 1

    /**
     * Strips formatting and turns the Russian trunk prefix into the country code:
     * `+7 (912) 345-67-89`, `8 912 345 67 89` and `89123456789` all become
     * `+79123456789`. A number with no country code (`9123456789`) is returned as
     * bare digits — guessing the country would silently dial the wrong person.
     *
     * Returns null when there is nothing dialable in [raw].
     */
    fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val hasPlus = trimmed.startsWith("+")
        val digits = trimmed.filter { it in '0'..'9' }
        if (digits.isEmpty()) return null
        return when {
            hasPlus -> "+$digits"
            digits.length == 11 && digits[0] == '8' -> "+7" + digits.substring(1)
            digits.length == 11 && digits[0] == '7' -> "+$digits"
            else -> digits
        }
    }

    /** True when [raw] looks like a number and not a name: mostly digits and phone punctuation. */
    fun looksLikePhone(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return false
        val allowed = trimmed.all { it in '0'..'9' || it in " +-()" }
        return allowed && trimmed.count { it in '0'..'9' } >= 3
    }

    /** True when both strings are the same dialable number, formatting ignored. */
    fun sameNumber(a: String, b: String): Boolean {
        val left = normalize(a) ?: return false
        return left == normalize(b)
    }
}

/**
 * A `LIKE` pattern for "the name contains [query]", with the wildcards escaped so that a
 * query is a query: without this, `query = "%"` would match the whole address book and
 * `_` would match anything. Pair it with `ESCAPE '\'` in the selection.
 */
internal fun likePattern(query: String): String {
    val escaped = query
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")
    return "%$escaped%"
}

/**
 * One contact, reduced to what the agent needs to answer "what is X's number" and
 * to dial it. No emails, no addresses, no notes — those never leave the device.
 */
data class ContactSummary(
    val id: Long,
    val name: String,
    val phone: String?,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "name" to name,
        "phone" to phone,
    )
}

/** Returned instead of data whenever the contacts permission is missing or was denied. */
const val CONTACTS_PERMISSION_DENIED =
    "No access to contacts. Ask the user to allow contact access for Pots, then try again."

/**
 * Null when a contact tool may run; the tool-shaped refusal otherwise. The tools call this
 * before touching `ContactsContract`, so a missing permission is an answer the model can
 * act on instead of an exception.
 */
suspend fun contactsPermissionBlock(controller: PermissionController): Map<String, Any?>? {
    if (controller.hasPermission()) return null
    if (controller.requestPermission()) return null
    return mapOf("success" to false, "error" to CONTACTS_PERMISSION_DENIED)
}

/** The `contacts_search` payload: a bounded list of [ContactSummary] maps and nothing else. */
fun contactsSearchResponse(contacts: List<ContactSummary>): Map<String, Any?> {
    val limited = contacts.take(PhoneNumbers.MAX_SEARCH_RESULTS)
    return mapOf(
        "success" to true,
        "count" to limited.size,
        "has_more" to (contacts.size > limited.size),
        "contacts" to limited.map { it.toMap() },
    )
}

/**
 * The contact already holding [phone], if any. `contact_create` calls this before
 * writing: an agent asked twice for the same person must not leave two of them.
 */
fun findExistingContact(contacts: List<ContactSummary>, phone: String): ContactSummary? = contacts.firstOrNull { existing -> existing.phone != null && PhoneNumbers.sameNumber(existing.phone, phone) }
