// Encrypted vault: document model. The file format keeps item and field types
// as raw strings — `VaultItemKind`/`VaultFieldKind` live only in the domain
// layer, so a file written by a newer app version (e.g. type "ssh_key") still
// opens on an older one: the unknown kind degrades to a plain card / text
// field and the raw string survives rewrites.

package com.pusios.pots.data.vault

import kotlinx.serialization.Serializable

/** Domain view of the raw `type` string stored in `vault.pots`. */
enum class VaultItemKind(val id: String) {
    LOGIN("login"),
    CARD("card"),
    IDENTITY("identity"),
    NOTE("note"),
    SSH_KEY("ssh_key"),
    HOST("host"),
    TOKEN("token"),
    ;

    companion object {
        fun fromId(id: String): VaultItemKind? = entries.firstOrNull { it.id == id }
    }
}

/** Domain view of the raw field `type` string. Unknown kinds read as [TEXT]. */
enum class VaultFieldKind(val id: String, val masked: Boolean, val multiline: Boolean) {
    TEXT("text", masked = false, multiline = false),
    SECRET("secret", masked = true, multiline = false),
    MULTILINE("multiline", masked = false, multiline = true),
    SECRET_MULTILINE("secret_multiline", masked = true, multiline = true),
    URL("url", masked = false, multiline = false),
    ;

    companion object {
        fun fromId(id: String): VaultFieldKind? = entries.firstOrNull { it.id == id }
    }
}

/** One vault card: a flat entry with fields, like a KeePass entry. */
@Serializable
data class VaultItem(
    val id: String,
    // Raw string on purpose (forward compatibility), see file header.
    val type: String = VaultItemKind.LOGIN.id,
    val name: String,
    val url: String = "",
    val username: String = "",
    val notes: String = "",
    val fields: List<VaultField> = emptyList(),
    val tags: List<String> = emptyList(),
    val favorite: Boolean = false,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

/** One field of a vault card; `value` may hold a secret. */
@Serializable
data class VaultField(
    val key: String,
    val label: String,
    // Raw string on purpose (forward compatibility), see file header.
    val type: String = VaultFieldKind.TEXT.id,
    val value: String = "",
)

/** The plaintext document sealed inside the vault payload. */
@Serializable
data class VaultDocument(
    val items: List<VaultItem> = emptyList(),
)

/** Known kind of [VaultItem.type], or null when the app does not know it. */
val VaultItem.kind: VaultItemKind?
    get() = VaultItemKind.fromId(type)

/** Known kind of [VaultField.type]; unknown kinds degrade to [VaultFieldKind.TEXT]. */
val VaultField.kind: VaultFieldKind
    get() = VaultFieldKind.fromId(type) ?: VaultFieldKind.TEXT

/** True when the field should be masked in UI (secret or unknown-with-mask is never assumed). */
val VaultField.isSecret: Boolean
    get() = VaultFieldKind.fromId(type)?.masked ?: false
