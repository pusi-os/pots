package com.pusios.pots.tools

import android.content.ActivityNotFoundException
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.PhoneLookup
import com.pusios.pots.network.tools.ParameterSchema
import com.pusios.pots.network.tools.Tool
import com.pusios.pots.network.tools.ToolInfo
import com.pusios.pots.network.tools.ToolSchema
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.tool_contact_create_description
import pots.composeapp.generated.resources.tool_contact_create_name
import pots.composeapp.generated.resources.tool_contact_dial_description
import pots.composeapp.generated.resources.tool_contact_dial_name
import pots.composeapp.generated.resources.tool_contacts_search_description
import pots.composeapp.generated.resources.tool_contacts_search_name
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// The three contact tools. They read and write through `ContactsContract`, which has
// existed since API 5 — well below this app's minSdk — so no version gate is needed;
// the runtime `READ_CONTACTS`/`WRITE_CONTACTS` prompts are the only gate.
//
// Nothing here logs a name or a number: both are other people's personal data.

/** Reads up to [limit] contacts whose display name contains [query]. */
private fun searchByName(context: Context, query: String, limit: Int): List<ContactSummary> {
    val projection = arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME, Phone.NUMBER)
    val contacts = LinkedHashMap<Long, ContactSummary>()
    context.contentResolver.query(
        Phone.CONTENT_URI,
        projection,
        "${Phone.DISPLAY_NAME} LIKE ?",
        arrayOf("%$query%"),
        "${Phone.DISPLAY_NAME} ASC",
    )?.use { cursor ->
        val idIndex = cursor.getColumnIndexOrThrow(Phone.CONTACT_ID)
        val nameIndex = cursor.getColumnIndexOrThrow(Phone.DISPLAY_NAME)
        val numberIndex = cursor.getColumnIndexOrThrow(Phone.NUMBER)
        while (cursor.moveToNext() && contacts.size < limit) {
            val id = cursor.getLong(idIndex)
            if (contacts.containsKey(id)) continue
            contacts[id] = ContactSummary(
                id = id,
                name = cursor.getString(nameIndex).orEmpty(),
                phone = cursor.getString(numberIndex)?.let(PhoneNumbers::normalize),
            )
        }
    }
    return contacts.values.toList()
}

/**
 * Looks a number up through [PhoneLookup], which matches the stored formatting
 * ("8 (912) 345-67-89") against a plain "+79123456789".
 */
private fun searchByNumber(context: Context, number: String, limit: Int): List<ContactSummary> {
    val projection = arrayOf(PhoneLookup.CONTACT_ID, PhoneLookup.DISPLAY_NAME, PhoneLookup.NUMBER)
    val uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
    val contacts = LinkedHashMap<Long, ContactSummary>()
    context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
        val idIndex = cursor.getColumnIndexOrThrow(PhoneLookup.CONTACT_ID)
        val nameIndex = cursor.getColumnIndexOrThrow(PhoneLookup.DISPLAY_NAME)
        val numberIndex = cursor.getColumnIndexOrThrow(PhoneLookup.NUMBER)
        while (cursor.moveToNext() && contacts.size < limit) {
            val id = cursor.getLong(idIndex)
            if (contacts.containsKey(id)) continue
            contacts[id] = ContactSummary(
                id = id,
                name = cursor.getString(nameIndex).orEmpty(),
                phone = cursor.getString(numberIndex)?.let(PhoneNumbers::normalize),
            )
        }
    }
    return contacts.values.toList()
}

/** `Contacts._ID` of the contact a freshly inserted raw contact ended up in. */
private fun contactIdForRawContact(context: Context, rawContactId: Long): Long? = context.contentResolver.query(
    ContactsContract.RawContacts.CONTENT_URI,
    arrayOf(ContactsContract.RawContacts.CONTACT_ID),
    "${ContactsContract.RawContacts._ID} = ?",
    arrayOf(rawContactId.toString()),
    null,
)?.use { cursor ->
    if (cursor.moveToFirst()) cursor.getLong(0) else null
}

object ContactsSearchTool {

    const val ID = "contacts_search"

    val toolInfo = ToolInfo(
        id = ID,
        name = "Search Contacts",
        description = "Search the phone contacts by name or number",
        nameRes = Res.string.tool_contacts_search_name,
        descriptionRes = Res.string.tool_contacts_search_description,
    )

    fun create(context: Context, permissionController: PermissionController): Tool = object : Tool {
        // The system permission dialog can sit on screen longer than the 30 s default.
        override val timeout: Duration = 90.seconds

        override val schema = ToolSchema(
            name = ID,
            description = "Search the user's phone contacts by a name fragment or a phone number. " +
                "Returns at most ${PhoneNumbers.MAX_SEARCH_RESULTS} matches, each with id, name and one " +
                "phone number. There is no way to list the whole address book: always pass a specific query.",
            parameters = mapOf(
                "query" to ParameterSchema("string", "Name fragment or phone number to look for", true),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val query = (args["query"] as? String)?.trim().orEmpty()
            if (query.isBlank()) {
                return mapOf("success" to false, "error" to "query is required")
            }
            contactsPermissionBlock(permissionController)?.let { return it }
            return try {
                val contacts = if (PhoneNumbers.looksLikePhone(query)) {
                    searchByNumber(context, PhoneNumbers.normalize(query) ?: query, PhoneNumbers.MAX_SEARCH_RESULTS)
                } else {
                    searchByName(context, query, PhoneNumbers.MAX_SEARCH_RESULTS)
                }
                contactsSearchResponse(contacts)
            } catch (_: SecurityException) {
                mapOf("success" to false, "error" to CONTACTS_PERMISSION_DENIED)
            } catch (e: Exception) {
                mapOf("success" to false, "error" to "Contact search failed: ${e.message}")
            }
        }
    }
}

object ContactCreateTool {

    const val ID = "contact_create"

    val toolInfo = ToolInfo(
        id = ID,
        name = "Create Contact",
        description = "Create a phone contact with a name and a number",
        nameRes = Res.string.tool_contact_create_name,
        descriptionRes = Res.string.tool_contact_create_description,
    )

    fun create(context: Context, permissionController: PermissionController): Tool = object : Tool {
        // The system permission dialog can sit on screen longer than the 30 s default.
        override val timeout: Duration = 90.seconds

        override val schema = ToolSchema(
            name = ID,
            description = "Create a contact with one name and one phone number. If a contact with the " +
                "same number already exists, that contact is returned instead and nothing is created.",
            parameters = mapOf(
                "name" to ParameterSchema("string", "Display name of the contact", true),
                "phone" to ParameterSchema("string", "Phone number; formatting is normalized", true),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val name = (args["name"] as? String)?.trim().orEmpty()
            if (name.isBlank()) {
                return mapOf("success" to false, "error" to "name is required")
            }
            val phone = PhoneNumbers.normalize((args["phone"] as? String).orEmpty())
                ?: return mapOf("success" to false, "error" to "phone is not a valid number")
            contactsPermissionBlock(permissionController)?.let { return it }
            return try {
                val existing = findExistingContact(
                    searchByNumber(context, phone, PhoneNumbers.MAX_SEARCH_RESULTS),
                    phone,
                )
                if (existing != null) {
                    return mapOf(
                        "success" to true,
                        "created" to false,
                        "message" to "A contact with this number already exists; nothing was created.",
                        "contact" to existing.toMap(),
                    )
                }

                // One batch, not two inserts: a failure between them would leave a contact
                // with a name and no number behind.
                val operations = arrayListOf(
                    ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                        .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                        .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                        .build(),
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(ContactsContract.Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
                        .withValue(StructuredName.DISPLAY_NAME, name)
                        .build(),
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(ContactsContract.Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
                        .withValue(Phone.NUMBER, phone)
                        .withValue(Phone.TYPE, Phone.TYPE_MOBILE)
                        .build(),
                )
                val results = context.contentResolver.applyBatch(ContactsContract.AUTHORITY, operations)
                val rawContactId = results.firstOrNull()?.uri?.let(ContentUris::parseId)
                    ?: return mapOf("success" to false, "error" to "Contact was not created")
                val contactId = contactIdForRawContact(context, rawContactId) ?: rawContactId
                mapOf(
                    "success" to true,
                    "created" to true,
                    "contact" to ContactSummary(contactId, name, phone).toMap(),
                )
            } catch (_: SecurityException) {
                mapOf("success" to false, "error" to CONTACTS_PERMISSION_DENIED)
            } catch (e: Exception) {
                mapOf("success" to false, "error" to "Contact creation failed: ${e.message}")
            }
        }
    }
}

object ContactDialTool {

    const val ID = "contact_dial"

    val toolInfo = ToolInfo(
        id = ID,
        name = "Dial Number",
        description = "Open the dialer with a number filled in; the user confirms the call",
        nameRes = Res.string.tool_contact_dial_name,
        descriptionRes = Res.string.tool_contact_dial_description,
    )

    fun create(context: Context): Tool = object : Tool {
        override val schema = ToolSchema(
            name = ID,
            description = "Open the system dialer with a number already filled in. The call is not " +
                "placed — the user taps the call button. Get the number from contacts_search first.",
            parameters = mapOf(
                "number" to ParameterSchema("string", "Phone number to dial", true),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val number = PhoneNumbers.normalize((args["number"] as? String).orEmpty())
                ?: return mapOf("success" to false, "error" to "number is required and has to be a phone number")
            val intent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                context.startActivity(intent)
                mapOf(
                    "success" to true,
                    "number" to number,
                    "message" to "Dialer opened with the number filled in; the call starts only when the user taps it.",
                )
            } catch (_: ActivityNotFoundException) {
                mapOf("success" to false, "error" to "No dialer app on this device")
            }
        }
    }
}
