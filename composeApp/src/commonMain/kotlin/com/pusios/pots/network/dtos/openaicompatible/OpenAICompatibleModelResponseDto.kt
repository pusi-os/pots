package com.pusios.pots.network.dtos.openaicompatible

import kotlinx.serialization.Serializable

@Serializable
data class OpenAICompatibleModelResponseDto(
    val data: List<Model>,
) {
    @Serializable
    data class Model(
        val id: String,
        /** Some providers (notably OpenRouter) include a human-readable name. */
        val name: String? = null,
        val owned_by: String? = null,
        val description: String? = null,
        val isActive: Boolean? = true,
        val created: Long? = null,
        /** Groq-style. */
        val context_window: Long? = null,
        /** OpenRouter-style. */
        val context_length: Long? = null,
        val isSelected: Boolean = false,
        val type: String? = null,
        /** OpenRouter-style: request parameters the model accepts, e.g. `tools`, `tool_choice`. */
        val supported_parameters: List<String>? = null,
        val architecture: Architecture? = null,
        val pricing: Pricing? = null,
        val top_provider: TopProvider? = null,
    ) {
        @Serializable
        data class Architecture(
            /** e.g. `text`, `image`, `audio` — `image` means the model reads pictures. */
            val input_modalities: List<String>? = null,
        )

        @Serializable
        data class Pricing(
            val prompt: String? = null,
            val completion: String? = null,
            val prompt_per_million: String? = null,
            val completion_per_million: String? = null,
        )

        @Serializable
        data class TopProvider(
            val pricing: Pricing? = null,
            /** Polza repeats the parameter list here instead of on the model itself. */
            val supported_parameters: List<String>? = null,
        )
    }
}
