package com.pusios.pots.network

import com.pusios.pots.data.ProviderBalance
import com.pusios.pots.data.Service
import com.pusios.pots.httpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reads the prepaid balance of providers that expose an account endpoint. Balance is
 * informational: any failure yields null so the settings screen and the chat path never break.
 */
class ProviderBalanceClient {

    private val client = httpClient()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetch(service: Service, apiKey: String): ProviderBalance? {
        if (apiKey.isBlank()) return null
        val spec = balanceSpec(service) ?: return null
        return runCatching {
            val response = client.get(spec.url) { bearerAuth(apiKey) }
            if (!response.status.isSuccess()) return@runCatching null
            val root = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val amount = root[spec.amountField]?.jsonPrimitive?.doubleOrNull ?: return@runCatching null
            val currency = root["currency"]?.jsonPrimitive?.contentOrNull ?: spec.currency
            ProviderBalance(amount = amount, currency = currency)
        }.getOrNull()
    }

    private class BalanceSpec(
        val url: String,
        val amountField: String,
        val currency: String,
    )

    private fun balanceSpec(service: Service): BalanceSpec? = when (service) {
        Service.PlusVibe -> BalanceSpec("https://plusvibeapi.ru/v1/balance", "balance_rub", "RUB")
        Service.PlusVibeReserve -> BalanceSpec("https://gw.plusvibeapi.ru/v1/balance", "balance_rub", "RUB")
        Service.LlmRouter -> BalanceSpec("https://llm-router.org/v1/balance", "balance_usd", "USD")
        else -> null
    }
}
