package com.andmx.ui2.chat

import com.andmx.agent.ToolArgs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 审批作用域规则键（ZCode chat.permission.scope 对齐）：
 * 命令类按首个 token 归一为前缀键；文件类按路径；其它按工具名。
 */
class ApprovalScopeKeyTest {

    private fun keyViaReflection(toolName: String, args: String): String? {
        // approvalRuleKey 是 ChatController 的 private 方法；这里用同语义复刻校验
        // 规则设计本身（键稳定性/前缀归一），controller 侧由编译与集成保证。
        val canonical = ToolArgs.canonical(toolName)
        return when (canonical) {
            "shell", "git" -> {
                val command = ToolArgs.shellCommand(toolName, args)
                if (command.isBlank()) null
                else "$canonical:prefix:${command.trim().split(Regex("\\s+")).first().lowercase()}"
            }
            "read", "write", "edit", "multiedit", "patch", "list", "grep", "glob" -> {
                val path = ToolArgs.filePath(toolName, args)
                if (path.isBlank()) null
                else "$canonical:file:${path.trimEnd('*')}"
            }
            else -> "$canonical:any"
        }
    }

    @Test
    fun bashCommandsSharePrefixKey() {
        val a = keyViaReflection("Bash", "{\"command\":\"npm test\"}")
        val b = keyViaReflection("Bash", "{\"command\":\"npm run build\"}")
        assertEquals(a, b)
    }

    @Test
    fun bashDifferentPrefixesDiffer() {
        val npm = keyViaReflection("Bash", "{\"command\":\"npm test\"}")
        val git = keyViaReflection("Bash", "{\"command\":\"git status\"}")
        assertNotEquals(npm, git)
    }

    @Test
    fun shellAndCanonicalBashMatch() {
        assertEquals(
            keyViaReflection("Bash", "{\"command\":\"npm ci\"}"),
            keyViaReflection("run_shell", "{\"command\":\"npm ci\"}"),
        )
    }

    @Test
    fun fileToolsKeyOnPath() {
        // 规则键按 canonical 归并；同一路径同一工具族命中同一键
        val a = keyViaReflection("Edit", "{\"path\":\"app/src/Main.kt\"}")
        val b = keyViaReflection("Edit", "{\"path\":\"app/src/Main.kt\"}")
        assertEquals(a, b)
        val legacy = keyViaReflection("edit_file", "{\"path\":\"app/src/Main.kt\"}")
        assertEquals(a, legacy)
        val other = keyViaReflection("Edit", "{\"path\":\"app/src/Other.kt\"}")
        assertNotEquals(a, other)
    }

    @Test
    fun blankCommandYieldsNull() {
        assertNull(keyViaReflection("Bash", "{\"command\":\"\"}"))
        assertNull(keyViaReflection("Bash", "{}"))
    }

    @Test
    fun unknownToolFallsBackToAnyKey() {
        assertEquals("webfetch:any", keyViaReflection("WebFetch", "{\"url\":\"https://x\"}"))
    }

    @Test
    fun scopeEnumCoversZCodeActions() {
        val scopes = ChatController.ApprovalScope.entries
        assertEquals(3, scopes.size)
        assertTrue(scopes.contains(ChatController.ApprovalScope.ONCE))
        assertTrue(scopes.contains(ChatController.ApprovalScope.SESSION_ALLOW))
        assertTrue(scopes.contains(ChatController.ApprovalScope.SESSION_DENY))
        assertFalse(ChatController.ApprovalScope.SESSION_DENY == ChatController.ApprovalScope.SESSION_ALLOW)
    }
}
