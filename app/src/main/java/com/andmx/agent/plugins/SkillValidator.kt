package com.andmx.agent.plugins

import android.util.Log
import com.andmx.exec.files.GuestFs

/**
 * Validates skill files and plugin manifests — mirrors Codex's skill validation.
 *
 * SKILL.md validation:
 * - Frontmatter must have at least `name` and `description`
 * - Name must be alphanumeric + hyphens, max 64 chars
 * - Description must be non-empty
 *
 * plugin.json validation (mirrors Codex's .codex-plugin/plugin.json rules):
 * - `name` must be non-empty string
 * - `version` must be valid semver
 * - `interface.capabilities` must be array of strings (if present)
 * - `interface.screenshots` must be array (if present)
 * - `interface.brandColor` must be #RRGGBB (if present)
 */
class SkillValidator(
    private val fs: GuestFs,
) {
    companion object {
        private const val TAG = "SkillValidator"
        private const val MAX_SKILL_NAME_LENGTH = 64
        private val SKILL_NAME_PATTERN = Regex("^[a-zA-Z0-9_-]+$")
        private val SEMVER_PATTERN = Regex("""^\d+\.\d+\.\d+(?:-[a-zA-Z0-9.]+)?(?:\+[a-zA-Z0-9.]+)?$""")
        private val BRAND_COLOR_PATTERN = Regex("^#[0-9A-Fa-f]{6}$")
    }

    data class ValidationResult(
        val valid: Boolean,
        val errors: List<String> = emptyList(),
        val warnings: List<String> = emptyList(),
    )

    /** Validate a SKILL.md file at the given path. */
    suspend fun validateSkillMd(path: String): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (!fs.exists(path)) {
            return ValidationResult(false, listOf("SKILL.md not found at $path"))
        }

        val content = runCatching { fs.readText(path) }.getOrElse {
            return ValidationResult(false, listOf("Failed to read $path: ${it.message}"))
        }

        // Parse frontmatter (--- delimited YAML-like block)
        val frontmatter = parseFrontmatter(content)
        if (frontmatter.isEmpty()) {
            warnings.add("SKILL.md has no frontmatter — name and description recommended")
        }

        // Validate name
        val name = frontmatter["name"]
        if (name.isNullOrBlank()) {
            warnings.add("SKILL.md frontmatter missing 'name'")
        } else if (name.length > MAX_SKILL_NAME_LENGTH) {
            errors.add("Skill name '$name' is too long (${name.length} chars, max $MAX_SKILL_NAME_LENGTH)")
        } else if (!SKILL_NAME_PATTERN.matches(name)) {
            errors.add("Skill name '$name' contains invalid characters (only a-z, A-Z, 0-9, _, - allowed)")
        }

        // Validate description
        val description = frontmatter["description"]
        if (description.isNullOrBlank()) {
            warnings.add("SKILL.md frontmatter missing 'description'")
        }

        return ValidationResult(errors.isEmpty(), errors, warnings)
    }

    /** Validate a plugin.json manifest at the given path. */
    suspend fun validatePluginJson(path: String): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (!fs.exists(path)) {
            return ValidationResult(false, listOf("plugin.json not found at $path"))
        }

        val content = runCatching { fs.readText(path) }.getOrElse {
            return ValidationResult(false, listOf("Failed to read $path: ${it.message}"))
        }

        val json = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(content) as? kotlinx.serialization.json.JsonObject
        }.getOrElse {
            return ValidationResult(false, listOf("plugin.json is not valid JSON"))
        } ?: return ValidationResult(false, listOf("plugin.json root is not a JSON object"))

        // name
        val name = (json["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        if (name.isNullOrBlank()) {
            errors.add("plugin.json field 'name' must be a non-empty string")
        }

        // version
        val version = (json["version"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        if (version.isNullOrBlank()) {
            warnings.add("plugin.json field 'version' is missing")
        } else if (!SEMVER_PATTERN.matches(version)) {
            errors.add("plugin.json field 'version' must be strict semver (got '$version')")
        }

        // interface (optional but validated if present)
        val interfaceObj = json["interface"] as? kotlinx.serialization.json.JsonObject
        if (interfaceObj != null) {
            // capabilities
            val capabilities = interfaceObj["capabilities"] as? kotlinx.serialization.json.JsonArray
            if (capabilities != null) {
                capabilities.forEach { cap ->
                    if (cap !is kotlinx.serialization.json.JsonPrimitive || !cap.isString) {
                        errors.add("plugin.json field 'interface.capabilities' must be an array of strings")
                    }
                }
            }

            // screenshots
            val screenshots = interfaceObj["screenshots"] as? kotlinx.serialization.json.JsonArray
            if (screenshots != null) {
                screenshots.forEach { s ->
                    val sStr = (s as? kotlinx.serialization.json.JsonPrimitive)?.content
                    if (sStr != null && !sStr.endsWith(".png")) {
                        warnings.add("Screenshot '$sStr' should be a PNG filename")
                    }
                }
            }

            // brandColor
            val brandColor = (interfaceObj["brandColor"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            if (brandColor != null && !BRAND_COLOR_PATTERN.matches(brandColor)) {
                errors.add("plugin.json field 'interface.brandColor' must use #RRGGBB format (got '$brandColor')")
            }
        }

        return ValidationResult(errors.isEmpty(), errors, warnings)
    }

    /** Parse YAML-like frontmatter from markdown. */
    private fun parseFrontmatter(content: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val lines = content.lines()
        if (lines.isEmpty() || lines[0].trim() != "---") return result

        for (i in 1 until lines.size) {
            val line = lines[i].trim()
            if (line == "---") break
            val colonIdx = line.indexOf(':')
            if (colonIdx < 0) continue
            val key = line.substring(0, colonIdx).trim()
            val value = line.substring(colonIdx + 1).trim().removeSurrounding("\"")
            if (key.isNotBlank() && value.isNotBlank()) {
                result[key] = value
            }
        }
        return result
    }
}
