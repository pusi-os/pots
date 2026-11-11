package com.pusios.pots.network.dtos.llmrouter

import kotlinx.serialization.Serializable

@Serializable
data class LlmRouterPricingResponseDto(
    val data: List<Entry> = emptyList(),
) {
    @Serializable
    data class Entry(
        val id: String,
        val modality: String? = null,
        val prices: List<Price> = emptyList(),
    )

    @Serializable
    data class Price(
        val op: String,
        val unit: String,
        val usd: Double,
    )
}
