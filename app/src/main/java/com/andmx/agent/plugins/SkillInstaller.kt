package com.andmx.agent.plugins

import android.util.Log
import com.andmx.exec.files.GuestFs
import com.andmx.exec.PersistentShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SkillInstaller(
    private val fs: GuestFs,
    private val shell: PersistentShell? = null,
) {
    private suspend fun ensureShell(): PersistentShell? {
        val s = shell ?: return null
        s.start()
        return s
    }

    companion object {
        private const val TAG = "SkillInstaller"
        const val SKILLS_DIR = "/root/.andmx/skills"
    }

    data class InstallResult(
        val success: Boolean,
        val skillName: String,
        val path: String,
        val errors: List<String> = emptyList(),
        val warnings: List<String> = emptyList(),
    )

    suspend fun installFromGit(url: String, name: String? = null): InstallResult = withContext(Dispatchers.IO) {
        val skillName = name ?: url.substringAfterLast('/').removeSuffix(".git").ifBlank { "unnamed-skill" }
        val destDir = "$SKILLS_DIR/$skillName"

        val activeShell = ensureShell()
        if (activeShell == null) {
            return@withContext InstallResult(false, skillName, destDir, listOf("No shell available for git clone"))
        }

        runCatching { fs.writeText("$SKILLS_DIR/.gitkeep", "") }

        val result = runCatching {
            activeShell.exec("git clone --depth 1 '$url' '$destDir' 2>&1")
        }.getOrElse {
            return@withContext InstallResult(false, skillName, destDir, listOf("Git clone failed: ${it.message}"))
        }

        if (result.stdout.contains("fatal:") || result.stdout.contains("error:")) {
            return@withContext InstallResult(false, skillName, destDir, listOf("Git clone failed: ${result.stdout.take(500)}"))
        }

        val skillMdPath = "$destDir/SKILL.md"
        val validator = SkillValidator(fs)
        val validation = validator.validateSkillMd(skillMdPath)

        if (!validation.valid) {
            runCatching { ensureShell()?.exec("rm -rf '$destDir'") }
            return@withContext InstallResult(false, skillName, destDir, validation.errors, validation.warnings)
        }

        Log.i(TAG, "Installed skill '$skillName' from $url")
        InstallResult(true, skillName, destDir, emptyList(), validation.warnings)
    }

    suspend fun installFromLocal(sourcePath: String, name: String? = null): InstallResult = withContext(Dispatchers.IO) {
        val skillName = name ?: sourcePath.trimEnd('/').substringAfterLast('/').ifBlank { "unnamed-skill" }
        val destDir = "$SKILLS_DIR/$skillName"

        val activeShell = ensureShell()
        if (activeShell == null) {
            return@withContext InstallResult(false, skillName, destDir, listOf("No shell available for copy"))
        }

        runCatching { fs.writeText("$SKILLS_DIR/.gitkeep", "") }

        val result = runCatching {
            activeShell.exec("cp -r '$sourcePath' '$destDir' 2>&1")
        }.getOrElse {
            return@withContext InstallResult(false, skillName, destDir, listOf("Copy failed: ${it.message}"))
        }

        if (result.stdout.contains("No such file") || result.stdout.contains("error:")) {
            return@withContext InstallResult(false, skillName, destDir, listOf("Copy failed: ${result.stdout.take(500)}"))
        }

        val validator = SkillValidator(fs)
        val validation = validator.validateSkillMd("$destDir/SKILL.md")

        if (!validation.valid) {
            runCatching { ensureShell()?.exec("rm -rf '$destDir'") }
            return@withContext InstallResult(false, skillName, destDir, validation.errors, validation.warnings)
        }

        InstallResult(true, skillName, destDir, emptyList(), validation.warnings)
    }

    suspend fun createSkill(
        name: String,
        description: String,
        body: String = "",
    ): InstallResult = withContext(Dispatchers.IO) {
        val skillName = sanitizeSkillName(name)
        if (skillName.isBlank()) {
            return@withContext InstallResult(false, name, "", listOf("技能名称无效"))
        }
        val destDir = "$SKILLS_DIR/$skillName"
        if (fs.exists(destDir)) {
            return@withContext InstallResult(false, skillName, destDir, listOf("技能「$skillName」已存在"))
        }

        val desc = description.trim().ifBlank {
            "Custom AndMX skill. Use when the user asks for $skillName related help."
        }
        val contentBody = body.trim().ifBlank {
            defaultSkillBody(skillName)
        }
        val md = buildString {
            appendLine("---")
            appendLine("name: $skillName")
            appendLine("description: ${yamlEscape(desc)}")
            appendLine("---")
            appendLine()
            appendLine(contentBody)
        }

        runCatching {
            fs.writeText("$destDir/SKILL.md", md)
        }.getOrElse {
            return@withContext InstallResult(false, skillName, destDir, listOf("写入失败: ${it.message}"))
        }

        val validation = SkillValidator(fs).validateSkillMd("$destDir/SKILL.md")
        if (!validation.valid) {
            runCatching { fs.resolve(destDir).deleteRecursively() }
            return@withContext InstallResult(false, skillName, destDir, validation.errors, validation.warnings)
        }

        Log.i(TAG, "Created skill '$skillName'")
        InstallResult(true, skillName, destDir, emptyList(), validation.warnings)
    }

    suspend fun uninstall(skillName: String): Boolean = withContext(Dispatchers.IO) {
        val destDir = "$SKILLS_DIR/$skillName"
        val host = fs.resolve(destDir)
        if (!host.exists()) return@withContext true
        val activeShell = ensureShell()
        if (activeShell != null) {
            runCatching { activeShell.exec("rm -rf '$destDir'") }
        }
        if (host.exists()) {
            runCatching { host.deleteRecursively() }
        }
        return@withContext !fs.exists(destDir)
    }

    suspend fun listInstalled(): List<InstalledSkill> {
        if (!fs.exists(SKILLS_DIR)) return emptyList()
        val entries = runCatching { fs.list(SKILLS_DIR) }.getOrDefault(emptyList())
        return entries
            .filter { it.endsWith("/") && !it.startsWith(".") }
            .map { it.removeSuffix("/") }
            .map { name ->
                val skillMdPath = "$SKILLS_DIR/$name/SKILL.md"
                val hasSkillMd = fs.exists(skillMdPath)
                val description = if (hasSkillMd) {
                    runCatching {
                        val content = fs.readText(skillMdPath)
                        parseFrontmatter(content)["description"].orEmpty()
                    }.getOrDefault("")
                } else ""
                InstalledSkill(
                    name = name,
                    path = "$SKILLS_DIR/$name",
                    hasSkillMd = hasSkillMd,
                    description = description,
                )
            }
    }

    data class InstalledSkill(
        val name: String,
        val path: String,
        val hasSkillMd: Boolean,
        val description: String = "",
    )

    private fun sanitizeSkillName(raw: String): String {
        return raw.trim()
            .lowercase()
            .replace(Regex("[^a-z0-9_-]+"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')
            .take(64)
    }

    private fun yamlEscape(value: String): String {
        val v = value.replace("\n", " ").trim()
        return if (v.contains(':') || v.contains('#') || v.contains('"') || v.contains('\'') || v.startsWith('{') || v.startsWith('[')) {
            "\"${v.replace("\"", "\\\"")}\""
        } else v
    }

    private fun defaultSkillBody(name: String): String = """
        # $name

        ## 用途
        描述该技能要完成的任务与适用场景。

        ## 步骤
        1. 澄清用户目标与约束
        2. 按最佳实践执行
        3. 回报结果与后续建议

        ## 注意事项
        - 优先使用工作区内现有约定
        - 变更前确认影响范围
    """.trimIndent()

    private fun parseFrontmatter(content: String): Map<String, String> {
        if (!content.startsWith("---")) return emptyMap()
        val end = content.indexOf("\n---", 3)
        if (end < 0) return emptyMap()
        val block = content.substring(3, end).trim()
        val map = linkedMapOf<String, String>()
        for (line in block.lines()) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val key = line.substring(0, idx).trim()
            var value = line.substring(idx + 1).trim()
            if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith('\'') && value.endsWith('\''))) {
                value = value.substring(1, value.length - 1)
            }
            map[key] = value
        }
        return map
    }
}
