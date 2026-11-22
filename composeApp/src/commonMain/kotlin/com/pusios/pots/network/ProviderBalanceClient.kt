package com.pusios.pots.network

import com.pusios.pots.data.ApiKeyAuthStyle
import com.pusios.pots.data.ProviderBalance
import com.pusios.pots.data.Service
import com.pusios.pots.httpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Reads the prepaid balance of providers that expose an account endpoint. Balance is
 * informational: any failure yields null so the settings screen and the chat path never break.
 */
class ProviderBalanceClient {

    private val client = httpClient()

    suspend fun fetch(service: Service, apiKey: String): ProviderBalance? {
        if (apiKey.isBlank()) return null
        val spec = balanceSpec(service) ?: return null
        return runCatching {
            val response = client.get(spec.url) {
                when (service.apiKeyAuthStyle) {
                    ApiKeyAuthStyle.BEARER -> bearerAuth(apiKey)
                    ApiKeyAuthStyle.RAW -> header("Authorization", apiKey)
                }
            }
            if (!response.status.isSuccess()) return@runCatching null
            parseProviderBalance(response.bodyAsText(), spec.amountFields, spec.currency)
        }.getOrNull()
    }

    private class BalanceSpec(
        val url: String,
        /** Tried in order; the first field present in the response wins. */
        val amountFields: List<String>,
        val currency: String,
    )

    private fun balanceSpec(service: Service): BalanceSpec? = when (service) {
        Service.PlusVibe -> BalanceSpec("https://plusvibeapi.ru/v1/balance", listOf("balance_rub"), "RUB")
        Service.PlusVibeReserve -> BalanceSpec("https://gw.plusvibeapi.ru/v1/balance", listOf("balance_rub"), "RUB")
        /**
         * `balance_usd` is a guess: the path exists (401 without a key, 404 on an invented one)
         * but the provider documents no response shape. A missing field yields null, not zero.
         */
        Service.LlmRouter -> BalanceSpec("https://llm-router.org/v1/balance", listOf("balance_usd"), "USD")
        /** `available` is spendable money; `amount` includes what is reserved for running jobs. */
        Service.Polza -> BalanceSpec("https://polza.ai/api/v2/balance", listOf("available", "amount"), "RUB")
        Service.Gptunnel -> BalanceSpec("https://gptunnel.ru/v1/balance", listOf("balance"), "RUB")
        Service.AiTunnel -> BalanceSpec("https://api.aitunnel.ru/v1/aitunnel/balance", listOf("balance"), "RUB")
        else -> null
    }
}

/**
 * Reads the first present amount field from a balance response.
 *
 * Providers disagree on the type: PlusVibe, AITUNNEL and GPTunnel send a number, Polza sends a
 * decimal string. GPTunnel may report a negative balance (credit line) — that is real data, so it
 * is passed through unchanged. A missing field yields null so the card shows no balance at all
 * instead of a fake zero.
 */
internal fun parseProviderBalance(
    body: String,
    amountFields: List<String>,
    defaultCurrency: String,
): ProviderBalance? {
    val root = runCatching { balanceJson.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
    val amount = amountFields.firstNotNullOfOrNull { field ->
        val primitive = root[field] as? JsonPrimitive
        if (primitive == null || primitive is JsonNull) {
            null
        } else {
            primitive.doubleOrNull ?: primitive.contentOrNull?.toDoubleOrNull()
        }
    } ?: return null
    val currency = (root["currency"] as? JsonPrimitive)
        ?.takeIf { it !is JsonNull }
        ?.contentOrNull
        ?: defaultCurrency
    return ProviderBalance(amount = amount, currency = currency)
}

private val balanceJson = Json { ignoreUnknownKeys = true }
