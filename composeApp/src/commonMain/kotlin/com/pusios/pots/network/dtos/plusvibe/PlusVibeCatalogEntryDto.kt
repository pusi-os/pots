package com.pusios.pots.network.dtos.plusvibe

import kotlinx.serialization.Serializable

/**
 * One entry of PlusVibe's public catalog (`GET /api/catalog`, no key needed). The `/v1/models`
 * endpoint only returns `id` + `object`, so prices, context window and tools/vision come from
 * here. Prices are ₽ per 1M tokens; the default variant is what a plain chat request is billed at.
 */
@Serializable
data class PlusVibeCatalogEntryDto(
    /** Model id as it appears in `/v1/models`, e.g. `z-ai/glm-5.3-flash`. */
    val apiModel: String,
    val variants: List<Variant> = emptyList(),
    val caps: Caps? = null,
) {
    @Serializable
    data class Variant(
        val inputRub: Double? = null,
        val outputRub: Double? = null,
        val cachedInputRub: Double? = null,
        val maxContext: Long? = null,
        val vision: Boolean? = null,
        val isDefault: Boolean? = null,
    )

    @Serializable
    data class Caps(
        val toolCalling: Boolean? = null,
        val vision: Boolean? = null,
        val contextWindow: Long? = null,
    )
}
