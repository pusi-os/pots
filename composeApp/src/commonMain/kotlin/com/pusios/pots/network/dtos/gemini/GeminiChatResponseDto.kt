package com.pusios.pots.network.dtos.gemini

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class GeminiChatResponseDto(
    val candidates: List<Candidate>,
    /** Token accounting for the request. Absent on providers that do not report it. */
    val usageMetadata: UsageMetadata? = null,
) {
    /** Token usage as reported by Gemini. */
    @Serializable
    data class UsageMetadata(
        @SerialName("promptTokenCount")
        val promptTokens: Int = 0,
        @SerialName("candidatesTokenCount")
        val completionTokens: Int = 0,
        @SerialName("totalTokenCount")
        val totalTokens: Int = 0,
    )

    @Serializable
    data class Candidate(val content: Content? = null)

    @Serializable
    data class Content(val parts: List<Part>? = null)

    @Serializable
    data class Part(
        val text: String? = null,
        val functionCall: FunctionCall? = null,
        val thoughtSignature: String? = null,
        val thought: Boolean? = null,
    ) {
        val isThought: Boolean get() = thought == true
    }

    @Serializable
    data class FunctionCall(
        val name: String,
        val args: Map<String, JsonElement>? = null,
    )
}

fun GeminiChatResponseDto.extractText(): String = candidates.firstOrNull()?.content?.parts
    ?.filterNot { it.isThought }
    ?.joinToString("\n") { it.text ?: "" }
    ?: ""
