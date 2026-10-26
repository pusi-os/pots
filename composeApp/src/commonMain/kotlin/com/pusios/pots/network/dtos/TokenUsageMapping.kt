package com.pusios.pots.network.dtos

import com.pusios.pots.network.TokenUsage
import com.pusios.pots.network.dtos.anthropic.AnthropicChatResponseDto
import com.pusios.pots.network.dtos.gemini.GeminiChatResponseDto
import com.pusios.pots.network.dtos.openaicompatible.OpenAICompatibleChatResponseDto
import com.pusios.pots.network.dtos.openairesponses.OpenAIResponsesResponseDto

/**
 * Maps each provider's own usage block onto the shared [TokenUsage] type.
 *
 * The mappers return `null` for a missing block rather than a zeroed usage, so the caller can
 * tell "the provider did not report usage" apart from "the provider reported zero tokens".
 */

internal fun OpenAICompatibleChatResponseDto.Usage?.toTokenUsage(): TokenUsage? = this?.let { TokenUsage(promptTokens = it.promptTokens, completionTokens = it.completionTokens, totalTokens = it.totalTokens) }

internal fun OpenAIResponsesResponseDto.Usage?.toTokenUsage(): TokenUsage? = this?.let { TokenUsage(promptTokens = it.inputTokens, completionTokens = it.outputTokens, totalTokens = it.totalTokens) }

internal fun GeminiChatResponseDto.UsageMetadata?.toTokenUsage(): TokenUsage? = this?.let { TokenUsage(promptTokens = it.promptTokens, completionTokens = it.completionTokens, totalTokens = it.totalTokens) }

internal fun AnthropicChatResponseDto.Usage?.toTokenUsage(): TokenUsage? = this?.let {
    TokenUsage(
        promptTokens = it.inputTokens,
        completionTokens = it.outputTokens,
        totalTokens = it.inputTokens + it.outputTokens,
    )
}
