package com.pusios.pots.data

/**
 * How much reasoning the provider should spend on a turn. [AUTO] leaves the choice to the
 * provider/model; the other values are sent as `reasoning_effort` where supported.
 */
enum class ReasoningEffort(val apiValue: String?) {
    AUTO(null),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
}

/**
 * User-tunable limits for the agent loop. Persisted in [AppSettings] and read once per turn so
 * changing a setting affects the next run without rebuilding the repository.
 */
data class AgentTuning(
    val maxToolIterations: Int = 30,
    val maxRepeatedToolCalls: Int = 3,
    val maxParallelToolCalls: Int = 4,
    /** Tool calls allowed per rolling minute; 0 disables the limiter. */
    val toolCallsPerMinute: Int = 0,
    val maxApiRetries: Int = 2,
    val autoCompact: Boolean = true,
    /** Compact once history reaches this percentage of the active model's context window. */
    val compactThresholdPercent: Int = 70,
    /** Try to leave the compacted conversation at or below this percentage. */
    val compactTargetPercent: Int = 35,
    val compactKeepRecentTurns: Int = 4,
    val reasoningEffort: ReasoningEffort = ReasoningEffort.AUTO,
) {
    companion object {
        val Default = AgentTuning()
    }
}
