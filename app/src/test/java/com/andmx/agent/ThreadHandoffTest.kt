package com.andmx.agent

import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadHandoffTest {

    @Test
    fun handoffIncludesStateActivityChangesAndNextSteps() {
        val text = threadHandoffText(
            ThreadHandoffContext(
                project = "andmx",
                model = "gpt-test",
                approvalModeLabel = "按需",
                goalText = "复刻 Codex 工作台",
                goalPhaseLabel = "运行中",
                goalNote = "正在补交接摘要",
                tokenEstimate = 55_000,
                contextPressureLabel = "偏高",
                messageCount = 12,
                toolCount = 9,
                mcpServerCount = 1,
                changedFiles = listOf("/root/app.kt · 修改 · +2 / -1"),
                sourceLinks = listOf("/root/app.kt", "https://example.com"),
                recentActivity = listOf(HandoffRunLogItem("工具 · read_file", "完成", "/root/app.kt")),
                planItems = listOf(
                    TaskPlanItem("读取上下文", "已读取项目结构", PlanItemStatus.DONE),
                    TaskPlanItem("审查变更", "1 个文件需要审查", PlanItemStatus.ACTIVE),
                ),
                runtimeEnvironment = listOf(
                    "执行环境: Android/proot Alpine · proot",
                    "环境健康: Linux 沙箱就绪",
                    "诊断入口: /diag",
                ),
                instructionBoundaries = listOf(
                    "应用边界: Android/proot Alpine guest rootfs",
                    "工具注册: 9 个内置工具, 1 个 MCP 服务器",
                    "内部系统提示词和运行时安全策略不会作为普通文本暴露",
                ),
                verifications = listOf(
                    "通过 · ./gradlew test: BUILD SUCCESSFUL",
                    "失败 · ./gradlew assembleDebug: BUILD FAILED",
                ),
                resumePrompt = "继续 AndMX: 先审查 /root/app.kt",
            ),
        )

        assertTrue(text.contains("## 线程交接"))
        assertTrue(text.contains("`andmx`"))
        assertTrue(text.contains("`gpt-test`"))
        assertTrue(text.contains("~55000 tokens"))
        assertTrue(text.contains("**偏高**"))
        assertTrue(text.contains("复刻 Codex 工作台"))
        assertTrue(text.contains("### 执行环境"))
        assertTrue(text.contains("Android/proot Alpine · proot"))
        assertTrue(text.contains("Linux 沙箱就绪"))
        assertTrue(text.contains("### 指令边界"))
        assertTrue(text.contains("工具注册: 9 个内置工具, 1 个 MCP 服务器"))
        assertTrue(text.contains("内部系统提示词和运行时安全策略不会作为普通文本暴露"))
        assertTrue(text.contains("### 验证"))
        assertTrue(text.contains("通过 · ./gradlew test: BUILD SUCCESSFUL"))
        assertTrue(text.contains("失败 · ./gradlew assembleDebug: BUILD FAILED"))
        assertTrue(text.contains("### 当前计划"))
        assertTrue(text.contains("完成 · 读取上下文"))
        assertTrue(text.contains("进行中 · 审查变更"))
        assertTrue(text.contains("工具 · read_file"))
        assertTrue(text.contains("`/root/app.kt · 修改 · +2 / -1`"))
        assertTrue(text.contains("https://example.com"))
        assertTrue(text.contains("### 建议下一步"))
        assertTrue(text.contains("### 恢复提示"))
        assertTrue(text.contains("继续 AndMX: 先审查 /root/app.kt"))
    }

    @Test
    fun handoffShowsEmptyFallbacks() {
        val text = threadHandoffText(
            ThreadHandoffContext(
                project = "andmx",
                model = "gpt-test",
                approvalModeLabel = "只读",
                goalText = "",
                goalPhaseLabel = "未开始",
                goalNote = "",
                tokenEstimate = 0,
                contextPressureLabel = "轻量",
                messageCount = 0,
                toolCount = 0,
                mcpServerCount = 0,
                changedFiles = emptyList(),
                sourceLinks = emptyList(),
                recentActivity = emptyList(),
            ),
        )

        assertTrue(text.contains("目标: (未设置)"))
        assertTrue(text.contains("上下文: ~0 tokens"))
        assertTrue(text.contains("暂无计划快照"))
        assertTrue(text.contains("暂无环境快照"))
        assertTrue(text.contains("暂无指令边界快照"))
        assertTrue(text.contains("暂无测试、构建或诊断记录"))
        assertTrue(text.contains("暂无运行记录"))
        assertTrue(text.contains("暂无待审文件"))
        assertTrue(text.contains("暂无来源链接"))
        assertTrue(text.contains("继续这个 AndMX 线程"))
    }
}
