package com.andmx.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MergeConflictsTest {

    @Test
    fun parsesAllUnmergedCodes() {
        val output = """
            |UU both-modified.txt
            |AA both-added.txt
            |DD both-deleted.txt
            |AU added-by-us.txt
            |UA added-by-them.txt
            |DU deleted-by-us.txt
            |UD deleted-by-them.txt
            |
        """.trimMargin()
        val files = MergeConflicts.parsePorcelain(output)
        assertEquals(7, files.size)
        assertEquals(
            listOf("UU", "AA", "DD", "AU", "UA", "DU", "UD"),
            files.map { it.status },
        )
        assertEquals("both-modified.txt", files[0].path)
        assertEquals("deleted-by-them.txt", files[6].path)
    }

    @Test
    fun ignoresNonConflictStatuses() {
        val output = """
            | M modified.txt
            |M  staged.txt
            |A  added.txt
            |?? untracked.txt
            |R  old.txt -> new.txt
            | D deleted.txt
            |
        """.trimMargin()
        assertTrue(MergeConflicts.parsePorcelain(output).isEmpty())
    }

    @Test
    fun mixedOutputKeepsOnlyConflicts() {
        val output = " M a.txt\nUU b.txt\n?? c.txt\nAA d.txt\n"
        val files = MergeConflicts.parsePorcelain(output)
        assertEquals(listOf("b.txt", "d.txt"), files.map { it.path })
    }

    @Test
    fun stripsQuotesAndHandlesRenameArrow() {
        val output = "UU \"path with spaces.txt\"\nUU old.txt -> new.txt\n"
        val files = MergeConflicts.parsePorcelain(output)
        assertEquals(listOf("path with spaces.txt", "new.txt"), files.map { it.path })
    }

    @Test
    fun shortOrBlankLinesAreSkipped() {
        assertTrue(MergeConflicts.parsePorcelain("").isEmpty())
        assertTrue(MergeConflicts.parsePorcelain("UU\n\n   \n").isEmpty())
    }

    @Test
    fun emptyPathIsSkipped() {
        assertTrue(MergeConflicts.parsePorcelain("UU \n").isEmpty())
    }

    @Test
    fun isUnmergedRecognizesOnlyConflictCodes() {
        listOf("UU", "AA", "DD", "AU", "UA", "DU", "UD").forEach {
            assertTrue(MergeConflicts.isUnmerged(it))
        }
        listOf("M", "A", "D", "R", "??", "MM", "AM", "").forEach {
            assertFalse(MergeConflicts.isUnmerged(it))
        }
    }

    @Test
    fun statusLabelsAndDeleteSides() {
        assertEquals("双方修改", ConflictFile("a", "UU").statusLabel)
        assertEquals("双方新增", ConflictFile("a", "AA").statusLabel)
        assertEquals("双方删除", ConflictFile("a", "DD").statusLabel)
        assertEquals("传入删除", ConflictFile("a", "UD").statusLabel)
        assertTrue(ConflictFile("a", "DU").deletedByUs)
        assertFalse(ConflictFile("a", "DU").deletedByThem)
        assertTrue(ConflictFile("a", "UD").deletedByThem)
        assertFalse(ConflictFile("a", "UD").deletedByUs)
        assertTrue(ConflictFile("a", "DD").deletedByUs)
        assertTrue(ConflictFile("a", "DD").deletedByThem)
        assertFalse(ConflictFile("a", "UU").deletedByUs)
        assertFalse(ConflictFile("a", "UU").deletedByThem)
    }

    @Test
    fun markResolvedRequiresMarkerFreeText() {
        assertTrue(ConflictParser.canMarkResolved("clean content\n"))
        assertTrue(ConflictParser.canMarkResolved(""))
        assertFalse(
            ConflictParser.canMarkResolved("<<<<<<< HEAD\na\n=======\nb\n>>>>>>> t\n"),
        )
        assertFalse(ConflictParser.canMarkResolved("<<<<<<< HEAD\na\n=======\nb\n"))
    }
}

class GitSafetyConflictContinueTest {

    @Test
    fun continueCommandsPassSafetyCheck() {
        listOf(
            "merge --continue --no-edit",
            "rebase --continue",
            "cherry-pick --continue --no-edit",
            "revert --continue --no-edit",
            "add -- file.txt",
            "rm -- file.txt",
        ).forEach { args ->
            assertEquals(null, com.andmx.agent.GitSafety.check(args))
        }
    }

    @Test
    fun forcedVariantsAreRejected() {
        assertTrue(com.andmx.agent.GitSafety.check("merge --force") != null)
        assertTrue(com.andmx.agent.GitSafety.check("rebase --force-rebase") != null)
        assertTrue(com.andmx.agent.GitSafety.check("add -- a; rm b") != null)
    }
}