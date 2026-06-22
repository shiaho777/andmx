package com.andmx.agent.plugins

import android.util.Log
import com.andmx.exec.files.GuestFs
import com.andmx.exec.PersistentShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Installs skills from git repositories or local directories — mirrors Codex's
 * skill installation flow.
 *
 * Skills are installed to `/root/.andmx/skills/<skill_name>/` and must contain
 * a SKILL.md file. The installer:
 * 1. Clones the source (git URL or local path)
 * 2. Validates the SKILL.md via [SkillValidator]
 * 3. Registers the skill in the skills directory
 */
class SkillInstaller(
    private val fs: GuestFs,
    private val shell: PersistentShell? = null,
) {
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

    /** Install a skill from a git URL. */
    suspend fun installFromGit(url: String, name: String? = null): InstallResult = withContext(Dispatchers.IO) {
        val skillName = name ?: url.substringAfterLast('/').removeSuffix(".git").ifBlank { "unnamed-skill" }
        val destDir = "$SKILLS_DIR/$skillName"

        if (shell == null) {
            return@withContext InstallResult(false, skillName, destDir, listOf("No shell available for git clone"))
        }

        // Ensure skills directory exists
        runCatching { fs.writeText("$SKILLS_DIR/.gitkeep", "") }

        // Clone the repository
        val result = runCatching {
            shell.exec("git clone --depth 1 '$url' '$destDir' 2>&1")
        }.getOrElse {
            return@withContext InstallResult(false, skillName, destDir, listOf("Git clone failed: ${it.message}"))
        }

        if (result.stdout.contains("fatal:") || result.stdout.contains("error:")) {
            return@withContext InstallResult(false, skillName, destDir, listOf("Git clone failed: ${result.stdout.take(500)}"))
        }

        // Validate SKILL.md
        val skillMdPath = "$destDir/SKILL.md"
        val validator = SkillValidator(fs)
        val validation = validator.validateSkillMd(skillMdPath)

        if (!validation.valid) {
            // Clean up failed install
            runCatching { shell.exec("rm -rf '$destDir'") }
            return@withContext InstallResult(false, skillName, destDir, validation.errors, validation.warnings)
        }

        Log.i(TAG, "Installed skill '$skillName' from $url")
        InstallResult(true, skillName, destDir, emptyList(), validation.warnings)
    }

    /** Install a skill from a local directory (copy). */
    suspend fun installFromLocal(sourcePath: String, name: String? = null): InstallResult = withContext(Dispatchers.IO) {
        val skillName = name ?: sourcePath.trimEnd('/').substringAfterLast('/').ifBlank { "unnamed-skill" }
        val destDir = "$SKILLS_DIR/$skillName"

        if (shell == null) {
            return@withContext InstallResult(false, skillName, destDir, listOf("No shell available for copy"))
        }

        runCatching { fs.writeText("$SKILLS_DIR/.gitkeep", "") }

        val result = runCatching {
            shell.exec("cp -r '$sourcePath' '$destDir' 2>&1")
        }.getOrElse {
            return@withContext InstallResult(false, skillName, destDir, listOf("Copy failed: ${it.message}"))
        }

        if (result.stdout.contains("No such file") || result.stdout.contains("error:")) {
            return@withContext InstallResult(false, skillName, destDir, listOf("Copy failed: ${result.stdout.take(500)}"))
        }

        // Validate SKILL.md
        val validator = SkillValidator(fs)
        val validation = validator.validateSkillMd("$destDir/SKILL.md")

        if (!validation.valid) {
            runCatching { shell.exec("rm -rf '$destDir'") }
            return@withContext InstallResult(false, skillName, destDir, validation.errors, validation.warnings)
        }

        InstallResult(true, skillName, destDir, emptyList(), validation.warnings)
    }

    /** Remove an installed skill. */
    suspend fun uninstall(skillName: String): Boolean = withContext(Dispatchers.IO) {
        if (shell == null) return@withContext false
        val destDir = "$SKILLS_DIR/$skillName"
        val result = runCatching { shell.exec("rm -rf '$destDir'") }.getOrDefault(null)
        return@withContext result != null
    }

    /** List all installed skills. */
    suspend fun listInstalled(): List<InstalledSkill> {
        if (!fs.exists(SKILLS_DIR)) return emptyList()
        val entries = runCatching { fs.list(SKILLS_DIR) }.getOrDefault(emptyList())
        return entries
            .filter { it.endsWith("/") }
            .map { dir ->
                val name = dir.trimEnd('/')
                val skillMdPath = "$SKILLS_DIR/$name/SKILL.md"
                val hasSkillMd = fs.exists(skillMdPath)
                InstalledSkill(name = name, path = "$SKILLS_DIR/$name", hasSkillMd = hasSkillMd)
            }
    }

    data class InstalledSkill(
        val name: String,
        val path: String,
        val hasSkillMd: Boolean,
    )
}
