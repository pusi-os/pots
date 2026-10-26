package com.pusios.pots.network.dtos.anthropic

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class AnthropicChatResponseDto(
    val content: List<ContentBlock> = emptyList(),
    val stop_reason: String? = null,
    /** Token accounting for the request. Absent on providers that do not report it. */
    val usage: Usage? = null,
) {
    /** Token usage as reported by Anthropic. */
    @Serializable
    data class Usage(
        @SerialName("input_tokens")
        val inputTokens: Int = 0,
        @SerialName("output_tokens")
        val outputTokens: Int = 0,
    )

    @Serializable
    data class ContentBlock(
        val type: String,
        val text: String? = null,
        val id: String? = null,
        val name: String? = null,
        val input: JsonObject? = null,
    )
}

fun AnthropicChatResponseDto.extractText(): String = content.filter { it.type == "text" }.mapNotNull { it.text }.joinToString("\n")
