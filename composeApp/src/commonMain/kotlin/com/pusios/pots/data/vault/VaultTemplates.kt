// Encrypted vault: templates as data (delta plan §5.3). A template fills the
// fields of a new card; afterwards the user edits freely and the template is
// just a hint. UI and the agent's request form read the same list.

package com.pusios.pots.data.vault

/** One default field a template adds to a new card. */
data class VaultTemplateField(val key: String, val label: String, val kind: VaultFieldKind)

/** A card template: id + default field layout. */
data class VaultTemplate(val id: String, val label: String, val fields: List<VaultTemplateField>)

object VaultTemplates {
    val login = VaultTemplate(
        id = "login",
        label = "Login",
        fields = listOf(
            VaultTemplateField("url", "URL", VaultFieldKind.URL),
            VaultTemplateField("username", "Username", VaultFieldKind.TEXT),
            VaultTemplateField("password", "Password", VaultFieldKind.SECRET),
        ),
    )

    val card = VaultTemplate(
        id = "card",
        label = "Credit card",
        fields = listOf(
            VaultTemplateField("cardholder", "Cardholder", VaultFieldKind.TEXT),
            VaultTemplateField("number", "Card number", VaultFieldKind.SECRET),
            VaultTemplateField("expiryMonth", "Expiry month", VaultFieldKind.TEXT),
            VaultTemplateField("expiryYear", "Expiry year", VaultFieldKind.TEXT),
            VaultTemplateField("cvv", "CVV", VaultFieldKind.SECRET),
        ),
    )

    val identity = VaultTemplate(
        id = "identity",
        label = "Identity",
        fields = listOf(
            VaultTemplateField("firstName", "First name", VaultFieldKind.TEXT),
            VaultTemplateField("middleName", "Middle name", VaultFieldKind.TEXT),
            VaultTemplateField("lastName", "Last name", VaultFieldKind.TEXT),
            VaultTemplateField("address1", "Address line 1", VaultFieldKind.TEXT),
            VaultTemplateField("address2", "Address line 2", VaultFieldKind.TEXT),
            VaultTemplateField("address3", "Address line 3", VaultFieldKind.TEXT),
            VaultTemplateField("city", "City", VaultFieldKind.TEXT),
            VaultTemplateField("state", "State", VaultFieldKind.TEXT),
            VaultTemplateField("postalCode", "Postal code", VaultFieldKind.TEXT),
            VaultTemplateField("country", "Country", VaultFieldKind.TEXT),
            VaultTemplateField("company", "Company", VaultFieldKind.TEXT),
            VaultTemplateField("email", "Email", VaultFieldKind.TEXT),
            VaultTemplateField("phone", "Phone", VaultFieldKind.TEXT),
            VaultTemplateField("ssn", "SSN", VaultFieldKind.SECRET),
            VaultTemplateField("passportNumber", "Passport number", VaultFieldKind.TEXT),
            VaultTemplateField("licenseNumber", "License number", VaultFieldKind.TEXT),
        ),
    )

    val note = VaultTemplate(
        id = "note",
        label = "Secure note",
        fields = listOf(
            VaultTemplateField("notes", "Notes", VaultFieldKind.MULTILINE),
        ),
    )

    val sshKey = VaultTemplate(
        id = "ssh_key",
        label = "SSH key",
        fields = listOf(
            VaultTemplateField("private_key", "Private key", VaultFieldKind.SECRET_MULTILINE),
            VaultTemplateField("public_key", "Public key", VaultFieldKind.MULTILINE),
            VaultTemplateField("passphrase", "Passphrase", VaultFieldKind.SECRET),
            VaultTemplateField("key_type", "Key type", VaultFieldKind.TEXT),
            VaultTemplateField("fingerprint", "Fingerprint", VaultFieldKind.TEXT),
            VaultTemplateField("comment", "Comment", VaultFieldKind.TEXT),
        ),
    )

    val host = VaultTemplate(
        id = "host",
        label = "Host",
        fields = listOf(
            VaultTemplateField("protocol", "Protocol", VaultFieldKind.TEXT),
            VaultTemplateField("host", "Host", VaultFieldKind.TEXT),
            VaultTemplateField("port", "Port", VaultFieldKind.TEXT),
            VaultTemplateField("username", "Username", VaultFieldKind.TEXT),
            VaultTemplateField("domain", "Domain", VaultFieldKind.TEXT),
            VaultTemplateField("auth", "Auth method", VaultFieldKind.TEXT),
            VaultTemplateField("password", "Password", VaultFieldKind.SECRET),
            VaultTemplateField("private_key", "Private key", VaultFieldKind.SECRET_MULTILINE),
            VaultTemplateField("jump_host", "Jump host", VaultFieldKind.TEXT),
        ),
    )

    val token = VaultTemplate(
        id = "token",
        label = "API token",
        fields = listOf(
            VaultTemplateField("token", "Token", VaultFieldKind.SECRET),
            VaultTemplateField("header", "Header", VaultFieldKind.TEXT),
            VaultTemplateField("prefix", "Prefix", VaultFieldKind.TEXT),
            VaultTemplateField("expires_at", "Expires at", VaultFieldKind.TEXT),
            VaultTemplateField("scopes", "Scopes", VaultFieldKind.TEXT),
        ),
    )

    val all = listOf(login, card, identity, note, sshKey, host, token)

    /** By template id; an unknown id (e.g. from a newer version) yields null. */
    fun byId(id: String): VaultTemplate? = all.firstOrNull { it.id == id }

    /**
     * A blank card laid out per the template: id-less (the store assigns one on
     * upsert) and value-less (values come from the caller, never pre-serialized).
     */
    fun draft(
        templateId: String,
        name: String,
        url: String = "",
        username: String = "",
        notes: String = "",
        tags: List<String> = emptyList(),
    ): VaultItem? {
        val template = byId(templateId) ?: return null
        return VaultItem(
            id = "",
            type = template.id,
            name = name,
            url = url,
            username = username,
            notes = notes,
            fields = template.fields.map { VaultField(key = it.key, label = it.label, type = it.kind.id) },
            tags = tags,
        )
    }
}
