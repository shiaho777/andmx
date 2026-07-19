package com.andmx.workspace

import com.andmx.llm.ApiMessage
import com.andmx.llm.ChatRequest
import com.andmx.llm.LlmClient
import com.andmx.llm.provider.ProviderDefinition

class CommitMessageGenerator {
    suspend fun generate(
        provider: ProviderDefinition,
        model: String,
        branch: String,
        files: List<GitBaseline.ChangedFile>,
        diffStat: String,
        targetBranch: String? = null,
    ): Result<String> {
        if (!provider.isUsable) return Result.failure(IllegalStateException("当前提供商不可用，请检查 API Key"))
        if (model.isBlank()) return Result.failure(IllegalStateException("请先选择模型"))

        val fileList = files.take(40).joinToString("\n") {
            "${it.status.ifBlank { if (it.untracked) "??" else "M" }} ${it.path}"
        }.ifBlank { "(no file list)" }

        val purpose = if (targetBranch.isNullOrBlank()) {
            "写一条简洁的 Git 提交说明。"
        } else {
            "用户即将切换到分支 $targetBranch，请为暂存当前工作写一条简洁提交说明。"
        }

        val prompt = buildString {
            appendLine(purpose)
            appendLine("要求：")
            appendLine("1. 只输出提交说明本身，不要解释、不要代码块、不要引号包裹。")
            appendLine("2. 优先一行 subject（≤72 字）；必要时再加最多 3 行 body。")
            appendLine("3. 使用简体中文或 Conventional Commits（如 feat/fix/chore），选更贴切的一种。")
            appendLine("4. 根据变更文件推断意图，不要编造未出现的功能。")
            appendLine()
            appendLine("当前分支: ${branch.ifBlank { "(unknown)" }}")
            if (!targetBranch.isNullOrBlank()) appendLine("目标分支: $targetBranch")
            appendLine("变更文件:")
            appendLine(fileList)
            appendLine()
            appendLine("diff/stat 摘要:")
            appendLine(diffStat.take(3500).ifBlank { "(empty)" })
        }

        val client = LlmClient(provider)
        val request = ChatRequest(
            model = model,
            messages = listOf(
                ApiMessage(
                    role = "system",
                    content = "你是资深工程师，擅长写准确、克制的 git commit message。",
                ),
                ApiMessage(role = "user", content = prompt),
            ),
            temperature = 0.2,
            stream = false,
        )
        return client.chat(request).mapCatching { msg ->
            cleanMessage(msg.content.orEmpty()).ifBlank {
                throw IllegalStateException("模型未返回可用的提交说明")
            }
        }
    }

    private fun cleanMessage(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("```")) {
            s = s.lineSequence()
                .dropWhile { it.trim().startsWith("```") }
                .takeWhile { !it.trim().startsWith("```") }
                .joinToString("\n")
                .trim()
        }
        s = s.trim().trim('"', '“', '”', '\'')
        return s.lines()
            .map { it.trimEnd() }
            .dropWhile { it.isBlank() }
            .joinToString("\n")
            .trim()
    }
}
