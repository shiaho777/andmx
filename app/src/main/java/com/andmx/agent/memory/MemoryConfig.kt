package com.andmx.agent.memory

import com.andmx.config.AndmxConfig

/**
 * Memory system configuration — mirrors Codex's [memories] TOML section.
 *
 * Extracted from [AndmxConfig] so MemorySystem doesn't depend on the full
 * config object. Values are loaded once at startup and can be refreshed.
 */
data class MemoryConfig(
    val generateMemories: Boolean = true,
    val useMemories: Boolean = true,
    val dedicatedTools: Boolean = false,
    val maxRawMemoriesForConsolidation: Int = 20,
    val maxUnusedDays: Int = 30,
    val maxRolloutAgeDays: Int = 14,
    val maxRolloutsPerStartup: Int = 3,
    val minRolloutIdleHours: Int = 1,
    val minRateLimitRemainingPercent: Int = 10,
    val extractModel: String = "",
    val consolidationModel: String = "",
    val disableOnExternalContext: Boolean = false,
) {
    companion object {
        val DEFAULT = MemoryConfig()

        fun fromConfig(config: AndmxConfig): MemoryConfig = MemoryConfig(
            generateMemories = config.generateMemories,
            useMemories = config.useMemories,
            dedicatedTools = config.memoryDedicatedTools,
            maxRawMemoriesForConsolidation = config.maxRawMemoriesForConsolidation,
            maxUnusedDays = config.maxUnusedDays,
            maxRolloutAgeDays = config.maxRolloutAgeDays,
            maxRolloutsPerStartup = config.maxRolloutsPerStartup,
            minRolloutIdleHours = config.minRolloutIdleHours,
            minRateLimitRemainingPercent = config.minRateLimitRemainingPercent,
            extractModel = config.extractModel,
            consolidationModel = config.consolidationModel,
            disableOnExternalContext = config.disableOnExternalContext,
        )
    }
}
