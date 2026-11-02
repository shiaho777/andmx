package com.andmx.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class CustomCommandExpanderTest {

    @Test
    fun argumentsPlaceholderReceivesFullTail() {
        assertEquals(
            "Summarize: foo bar",
            CustomCommandExpander.expand("Summarize: \$ARGUMENTS", "foo bar"),
        )
    }

    @Test
    fun positionalPlaceholdersAreOneBased() {
        assertEquals(
            "Rename foo to bar",
            CustomCommandExpander.expand("Rename \$1 to \$2", "foo bar"),
        )
        assertEquals(
            "Rename foo to bar",
            CustomCommandExpander.expand("Rename \${1} to \${2}", "foo bar"),
        )
    }

    @Test
    fun noPlaceholderAppendsArguments() {
        assertEquals(
            "Do the thing\n\nwith details",
            CustomCommandExpander.expand("Do the thing", "with details"),
        )
    }

    @Test
    fun missingPositionalKeepsPlaceholder() {
        assertEquals(
            "Use \$2",
            CustomCommandExpander.expand("Use \$2", "only-one"),
        )
    }

    @Test
    fun blankArgumentsLeavePromptUntouched() {
        assertEquals("Review the diff", CustomCommandExpander.expand("Review the diff", "  "))
    }
}
