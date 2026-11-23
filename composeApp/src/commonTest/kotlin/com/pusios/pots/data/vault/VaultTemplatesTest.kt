// Vault template tests: the seven v1 templates are data (id + default fields),
// so the UI and the agent's request form read the same list.

package com.pusios.pots.data.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VaultTemplatesTest {

    @Test
    fun `all seven v1 templates exist`() {
        assertEquals(
            listOf("login", "card", "identity", "note", "ssh_key", "host", "token"),
            VaultTemplates.all.map { it.id },
        )
    }

    @Test
    fun `login template lays out url username password`() {
        val login = VaultTemplates.byId("login")
        assertEquals(
            listOf(
                Pair("url", VaultFieldKind.URL),
                Pair("username", VaultFieldKind.TEXT),
                Pair("password", VaultFieldKind.SECRET),
            ),
            login?.fields?.map { Pair(it.key, it.kind) },
        )
    }

    @Test
    fun `ssh key template keeps the key material secret and multiline`() {
        val sshKey = VaultTemplates.byId("ssh_key")
        assertTrue(sshKey != null)
        val privateKey = sshKey.fields.first { it.key == "private_key" }
        assertEquals(VaultFieldKind.SECRET_MULTILINE, privateKey.kind)
        assertEquals(6, sshKey.fields.size)
    }

    @Test
    fun `host template carries protocol auth and key fields`() {
        val host = VaultTemplates.byId("host")
        assertTrue(host != null)
        assertEquals(
            listOf("protocol", "host", "port", "username", "domain", "auth", "password", "private_key", "jump_host"),
            host.fields.map { it.key },
        )
    }

    @Test
    fun `unknown template id yields null`() {
        assertNull(VaultTemplates.byId("future_template"))
        assertNull(VaultTemplates.draft("future_template", name = "X"))
    }

    @Test
    fun `draft is a valueless id-less card from template data`() {
        val draft = VaultTemplates.draft(
            "login",
            name = "GitHub",
            url = "https://github.com",
            tags = listOf("dev"),
        )
        assertTrue(draft != null)
        assertEquals("", draft.id)
        assertEquals("login", draft.type)
        assertEquals("GitHub", draft.name)
        assertEquals(3, draft.fields.size)
        assertTrue(draft.fields.all { it.value.isEmpty() })
    }
}
