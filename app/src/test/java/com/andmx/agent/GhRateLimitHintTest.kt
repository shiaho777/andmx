package com.andmx.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GhRateLimitHintTest {

    @Test
    fun hintsOnGhRateLimitedOutput() {
        assertTrue(
            GhRateLimitHint.shouldHint(
                command = "gh pr list",
                output = "API rate limit exceeded for installation.",
                nowMs = 0,
            ),
        )
    }

    @Test
    fun noHintForNonGhCommandOrNormalOutput() {
        assertFalse(GhRateLimitHint.shouldHint("git status", "API rate limit exceeded", nowMs = 0))
        assertFalse(GhRateLimitHint.shouldHint("gh pr list", "all good", nowMs = 0))
    }

    @Test
    fun ghAuthCallsAreExempt() {
        assertFalse(
            GhRateLimitHint.shouldHint(
                command = "gh auth status",
                output = "API rate limit exceeded",
                nowMs = 0,
            ),
        )
    }

    @Test
    fun cooldownSuppressesRepeatHintsForAMinute() {
        assertFalse(GhRateLimitHint.shouldHint("gh pr list", "RATE_LIMITED", nowMs = 0).let { hint ->
            GhRateLimitHint.shouldHint("gh pr list", "RATE_LIMITED", nowMs = 1_000)
        })
    }

    @Test
    fun secondaryRateLimitMessageMatches() {
        assertTrue(
            GhRateLimitHint.shouldHint(
                command = "gh api /x",
                output = "You have exceeded a secondary rate limit",
                nowMs = 10_000_000,
            ),
        )
    }
}
