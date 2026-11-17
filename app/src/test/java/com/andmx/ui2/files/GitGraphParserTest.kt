package com.andmx.ui2.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitGraphParserTest {

    private fun row(
        hash: String,
        parents: String = "",
        subject: String = "subject",
        author: String = "dev",
        ts: Long = 1000L,
    ) = "$hash|$parents|$subject|$author|$ts"

    private val a = "a".repeat(40)
    private val b = "b".repeat(40)
    private val c = "c".repeat(40)
    private val d = "d".repeat(40)

    @Test
    fun emptyOutputYieldsEmptyList() {
        assertTrue(GitGraphParser.parse("").isEmpty())
        assertTrue(GitGraphParser.parse("  \n \n").isEmpty())
    }

    @Test
    fun parsesFieldsAndTrimsWhitespace() {
        val nodes = GitGraphParser.parse("  ${row(a, b, "fix bug", "alice", 42L)}  ")
        assertEquals(1, nodes.size)
        val node = nodes[0]
        assertEquals(a, node.hash)
        assertEquals(listOf(b), node.parents)
        assertEquals("fix bug", node.subject)
        assertEquals("alice", node.author)
        assertEquals(42L, node.timestamp)
        assertEquals(a.take(7), node.shortHash)
        assertFalse(node.isMerge)
    }

    @Test
    fun rootCommitHasNoParents() {
        val nodes = GitGraphParser.parse(row(a))
        assertEquals(emptyList<String>(), nodes[0].parents)
        assertFalse(nodes[0].isMerge)
    }

    @Test
    fun linearCommitsShareLaneZero() {
        val output = listOf(row(a, b), row(b, c), row(c)).joinToString("\n")
        val nodes = GitGraphParser.parse(output)
        assertEquals(listOf(0, 0, 0), nodes.map { it.lane })
    }

    @Test
    fun mergeCommitOpensSecondLane() {
        val output = listOf(
            row(a, "$b $c"),
            row(b, d),
            row(c, d),
            row(d),
        ).joinToString("\n")
        val nodes = GitGraphParser.parse(output)
        assertTrue(nodes[0].isMerge)
        assertEquals(listOf(b, c), nodes[0].parents)
        assertEquals(0, nodes[0].lane)
        assertEquals(0, nodes[1].lane)
        assertEquals(1, nodes[2].lane)
        assertEquals(0, nodes[3].lane)
    }

    @Test
    fun divergentRootsGetSeparateLanes() {
        val output = listOf(row(a, b), row(c, d), row(b), row(d)).joinToString("\n")
        val nodes = GitGraphParser.parse(output)
        assertEquals(listOf(0, 1, 0, 1), nodes.map { it.lane })
    }

    @Test
    fun laneIsReusedAfterBranchEnds() {
        val output = listOf(row(a, b), row(c), row(b)).joinToString("\n")
        val nodes = GitGraphParser.parse(output)
        assertEquals(listOf(0, 1, 0), nodes.map { it.lane })
    }

    @Test
    fun malformedLinesAreSkipped() {
        val output = listOf(
            "not-a-commit-line",
            "short|${""}|s|a|1",
            row("g".repeat(40)),
            row(a, "xyz"),
            "$a||s|a|not-a-number",
            "$a||s|a",
            row(b),
        ).joinToString("\n")
        val nodes = GitGraphParser.parse(output)
        assertEquals(1, nodes.size)
        assertEquals(b, nodes[0].hash)
    }

    @Test
    fun allMalformedOutputYieldsEmptyList() {
        val output = listOf("garbage", "x|y|z", "$a||s|a|abc").joinToString("\n")
        assertTrue(GitGraphParser.parse(output).isEmpty())
    }

    @Test
    fun limitCapsRowCount() {
        val output = listOf(row(a, b), row(b, c), row(c)).joinToString("\n")
        assertEquals(2, GitGraphParser.parse(output, limit = 2).size)
        assertEquals(1, GitGraphParser.parse(output, limit = 0).size)
        assertEquals(1, GitGraphParser.parse(output, limit = -5).size)
    }

    @Test
    fun subjectWithPipesFallsBackToSkip() {
        val output = listOf("$a||feat|part two|dev|7", row(b)).joinToString("\n")
        val nodes = GitGraphParser.parse(output)
        assertEquals(1, nodes.size)
        assertEquals(b, nodes[0].hash)
    }

    @Test
    fun blankLinesBetweenRowsAreIgnored() {
        val output = "${row(a, b)}\n\n${row(b)}\n"
        assertEquals(2, GitGraphParser.parse(output).size)
    }
}
