package com.pusios.pots.network

import kotlinx.serialization.Serializable

/**
 * Token accounting a provider reports for a single request.
 *
 * Providers name the fields differently (OpenAI uses `prompt_tokens`, Anthropic uses
 * `input_tokens`, Gemini uses `promptTokenCount`), so each response DTO maps its own shape
 * onto this one type and the rest of the app works with a single vocabulary.
 *
 * All fields default to zero: a provider that omits the block entirely still produces a
 * usable instance, and a missing block is represented by `null` on the response DTO rather
 * than by a zeroed usage.
 */
@Serializable
data class TokenUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
)
