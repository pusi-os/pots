package com.pusios.pots.data

import com.pusios.pots.inference.approximateTokenCount
import kotlin.test.Test
import kotlin.test.assertEquals

class TurnMetricsTest {
    @Test
    fun `context percent uses the current prompt not the session total`() {
        val metrics = TurnMetrics(
            sessionTokens = 250_000,
            sessionPromptTokens = 240_000,
            sessionCompletionTokens = 10_000,
            contextWindowTokens = 100_000,
            promptTokens = 20_000,
            completionTokens = 2_000,
            totalTokens = 22_000,
        )

        assertEquals(20, metrics.contextUsagePercent)
    }

    @Test
    fun `context percent is zero when the window is unknown`() {
        val metrics = TurnMetrics(promptTokens = 5_000, contextWindowTokens = 0)

        assertEquals(0, metrics.contextUsagePercent)
    }

    @Test
    fun `approximate token fallback never returns zero for visible text`() {
        assertEquals(0, approximateTokenCount(""))
        assertEquals(1, approximateTokenCount("a"))
    }
}
