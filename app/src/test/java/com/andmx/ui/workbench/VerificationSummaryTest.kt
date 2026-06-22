package com.andmx.ui.workbench

import com.andmx.ui.conversation.ChatItem
import org.junit.Assert.assertEquals
import org.junit.Test

class VerificationSummaryTest {

    @Test
    fun extractsVerificationCommandsNewestFirst() {
        val entries = verificationEntries(
            listOf(
                ChatItem.ToolUse(
                    key = 1,
                    callId = "read",
                    name = "run_shell",
                    args = """{"command":"ls -la"}""",
                    output = "ok",
                    running = false,
                ),
                ChatItem.ToolUse(
                    key = 2,
                    callId = "test",
                    name = "run_shell",
                    args = """{"command":"./gradlew test"}""",
                    output = "> Task :test\nBUILD SUCCESSFUL in 1s",
                    running = false,
                ),
                ChatItem.ToolUse(
                    key = 3,
                    callId = "assemble",
                    name = "run_shell",
                    args = """{"command":"./gradlew assembleDebug"}""",
                    output = "BUILD FAILED in 2s",
                    running = false,
                    error = true,
                ),
            ),
        )

        assertEquals(listOf("./gradlew assembleDebug", "./gradlew test"), entries.map { it.command })
        assertEquals(VerificationState.FAILED, entries[0].state)
        assertEquals("BUILD FAILED in 2s", entries[0].detail)
        assertEquals(VerificationState.PASSED, entries[1].state)
        assertEquals("BUILD SUCCESSFUL in 1s", entries[1].detail)
    }

    @Test
    fun extractsDiagAssistantMessage() {
        val entries = verificationEntries(
            listOf(
                ChatItem.Assistant(
                    key = 1,
                    text = """
                        ## 执行环境摘要
                        - 环境健康: **Linux 沙箱就绪**

                        ## 完整探针
                        # 结论
                        proot ptrace 虚拟化: ✓ 工作正常
                    """.trimIndent(),
                ),
            ),
        )

        assertEquals("/diag", entries.single().command)
        assertEquals(VerificationState.PASSED, entries.single().state)
    }

    @Test
    fun handoffLinesSummarizeVerificationState() {
        val lines = verificationHandoffLines(
            listOf(
                VerificationEntry(1, "./gradlew test", VerificationState.PASSED, "BUILD SUCCESSFUL"),
                VerificationEntry(2, "./gradlew lint", VerificationState.RUNNING, ""),
            ),
        )

        assertEquals(
            listOf(
                "通过 · ./gradlew test: BUILD SUCCESSFUL",
                "运行中 · ./gradlew lint: (无输出摘要)",
            ),
            lines,
        )
    }
}
