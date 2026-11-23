package com.pusios.pots.network.dtos.aitunnel

import kotlinx.serialization.Serializable

/**
 * One entry of AITUNNEL's public chat catalog (`GET /public/aitunnel/models/chat`, no key
 * needed). The response is a JSON object keyed by model id. The `/v1/models` endpoint only
 * returns `id/created/object/owned_by`, so prices, context and vision come from here.
 * Prices are ₽ per 1M tokens. There is no function-calling flag in this catalog
 * (`native_tools` is server-side tooling, not function calling).
 */
@Serializable
data class AiTunnelCatalogEntryDto(
    val prompt_cost: Double? = null,
    val completion_cost: Double? = null,
    val context_size: Long? = null,
    val modalities: Modalities? = null,
) {
    @Serializable
    data class Modalities(
        val input: List<String> = emptyList(),
    )
}
