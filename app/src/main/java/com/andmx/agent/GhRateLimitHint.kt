package com.andmx.agent

/**
 * ZCode-style gh CLI rate-limit hint: when a Bash command's output shows a
 * GitHub API secondary rate limit, inject a one-per-minute system-reminder
 * telling the model to sleep until reset instead of retrying in a loop.
 */
object GhRateLimitHint {
    val GH_CALL = Regex(
        "(?:^|[;&|]|\\b(?:then|do)\\b)\\s*gh\\s+(?!auth\\b|help\\b|version\\b|alias\\b|completion\\b|config\\b)",
    )
    val RATE_LIMITED = Regex(
        "API rate limit (?:already )?exceeded|exceeded a secondary rate limit|\\bRATE_LIMITED\\b",
        RegexOption.IGNORE_CASE,
    )
    const val HINT =
        "<system-reminder>GitHub API rate limit exceeded (5,000/hr shared across all tools and agents). " +
            "Run `gh api rate_limit --jq .resources` and sleep until reset before further gh calls. " +
            "If polling in a loop, use ScheduleWakeup instead of retrying.</system-reminder>"

    private var cooldownUntilMs: Long = 0

    /** True when the command targets gh and the output signals rate limiting. */
    fun shouldHint(command: String, output: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!GH_CALL.containsMatchIn(command)) return false
        if (!RATE_LIMITED.containsMatchIn(output)) return false
        if (nowMs < cooldownUntilMs) return false
        cooldownUntilMs = nowMs + 60_000
        return true
    }
}
