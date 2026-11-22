package com.pusios.pots.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The balance endpoints disagree on the payload type (string vs number) and on the field name, so
 * the parsing rules are pinned here rather than discovered in production.
 */
class ProviderBalanceClientTest {

    @Test
    fun `polza string amounts prefer available over amount`() {
        val balance = parseProviderBalance(
            """{"amount":"1250.50","available":"900.25","reservedAmount":"350.25"}""",
            listOf("available", "amount"),
            "RUB",
        )
        assertEquals(900.25, balance?.amount)
        assertEquals("RUB", balance?.currency)
    }

    @Test
    fun `polza falls back to amount when available is missing`() {
        val balance = parseProviderBalance("""{"amount":"1250.50"}""", listOf("available", "amount"), "RUB")
        assertEquals(1250.50, balance?.amount)
    }

    @Test
    fun `gptunnel number amount keeps a negative balance`() {
        val balance = parseProviderBalance(
            """{"object":"balance","balance":-51.54,"creditLimit":15000}""",
            listOf("balance"),
            "RUB",
        )
        assertEquals(-51.54, balance?.amount)
    }

    @Test
    fun `currency from the response wins over the spec default`() {
        val balance = parseProviderBalance("""{"balance_usd":12.5,"currency":"EUR"}""", listOf("balance_usd"), "USD")
        assertEquals(12.5, balance?.amount)
        assertEquals("EUR", balance?.currency)
    }

    @Test
    fun `missing amount yields null instead of zero`() {
        assertNull(parseProviderBalance("""{"balance_rub":null}""", listOf("available", "amount"), "RUB"))
        assertNull(parseProviderBalance("""{"object":"balance"}""", listOf("balance"), "RUB"))
        assertNull(parseProviderBalance("""not json""", listOf("balance"), "RUB"))
    }
}
