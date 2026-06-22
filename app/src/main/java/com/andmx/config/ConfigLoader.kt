package com.andmx.config

import android.content.Context
import android.util.Log
import com.andmx.exec.files.GuestFs
import com.andmx.settings.ProviderSettings
import com.andmx.settings.SettingsStore
import kotlinx.coroutines.flow.first

/**
 * Loads [AndmxConfig] from the guest filesystem TOML file, with DataStore fallback.
 *
 * Mirrors Codex's config.toml loading: the TOML file at `/root/.andmx/config.toml`
 * is the primary source. If it doesn't exist or fails to parse, the system falls
 * back to DataStore preferences (which power the settings UI).
 *
 * The TOML parser is a lightweight hand-rolled implementation — Android doesn't
 * have a TOML library in the standard dependency set, and we avoid adding one
 * to keep the APK small. Only the subset of TOML we need is supported:
 * - [section] headers (nested via dots)
 * - key = "value" (string)
 * - key = 123 (integer)
 * - key = true (boolean)
 * - key = ["a", "b"] (string array)
 */
class ConfigLoader(
    private val context: Context,
    private val fs: GuestFs? = null,
    private val settingsStore: SettingsStore? = null,
) {
    companion object {
        private const val TAG = "ConfigLoader"
        const val CONFIG_PATH = "/root/.andmx/config.toml"
    }

    /** Load config, trying TOML first, then DataStore. */
    suspend fun load(): AndmxConfig {
        val tomlConfig = tryToml()
        if (tomlConfig != null) return tomlConfig

        // Fallback: build from DataStore
        return tryDataStore() ?: AndmxConfig()
    }

    /** Load and merge with DataStore overrides (for UI-edited settings). */
    suspend fun loadMerged(): AndmxConfig {
        val base = load()
        val ds = tryDataStore() ?: return base
        // DataStore values override TOML where present
        return base.copy(
            model = ds.model.ifBlank { base.model },
            modelProvider = ds.modelProvider.ifBlank { base.modelProvider },
            modelReasoningEffort = ds.modelReasoningEffort.ifBlank { base.modelReasoningEffort },
            personality = ds.personality.ifBlank { base.personality },
            developerInstructions = ds.developerInstructions.ifBlank { base.developerInstructions },
            modelContextWindow = if (ds.modelContextWindow > 0) ds.modelContextWindow else base.modelContextWindow,
            reviewModel = ds.reviewModel.ifBlank { base.reviewModel },
            modelReasoningSummary = ds.modelReasoningSummary || base.modelReasoningSummary,
            modelVerbosity = ds.modelVerbosity.ifBlank { base.modelVerbosity },
            disableResponseStorage = ds.disableResponseStorage || base.disableResponseStorage,
        )
    }

    /** Save config to TOML file in guest filesystem. */
    suspend fun save(config: AndmxConfig): Boolean {
        val guestFs = fs ?: return false
        return try {
            val toml = TomlWriter.write(config)
            guestFs.writeText(CONFIG_PATH, toml)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save config", e)
            false
        }
    }

    /** Get trust level for a project path. */
    suspend fun trustLevelFor(projectPath: String): String =
        load().projects[projectPath]?.trustLevel ?: "untrusted"

    /** Set trust level for a project path. */
    suspend fun setProjectTrust(projectPath: String, trustLevel: String): Boolean {
        val config = load()
        val updated = config.copy(
            projects = config.projects + (projectPath to ProjectTrust(trustLevel))
        )
        return save(updated)
    }

    private fun tryToml(): AndmxConfig? {
        val guestFs = fs ?: return null
        if (!guestFs.exists(CONFIG_PATH)) return null
        return try {
            val text = guestFs.readText(CONFIG_PATH)
            TomlParser.parse(text)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse config.toml, falling back to DataStore", e)
            null
        }
    }

    private suspend fun tryDataStore(): AndmxConfig? {
        val store = settingsStore ?: return null
        return try {
            val ps = store.settings.first()
            AndmxConfig(
                model = ps.model,
                // Provider-scoped fields (reviewModel, modelProvider, context window,
                // verbosity, …) now live in ProviderDefinition; under the DataStore
                // fallback they fall back to AndmxConfig defaults.
                modelReasoningEffort = ps.reasoningEffort,
                personality = ps.persona,
                developerInstructions = ps.customInstructions,
                sandboxMode = SandboxMode.from(ps.approvalMode),
                approvalPolicy = ApprovalPolicy.from(ps.approvalMode),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read DataStore", e)
            null
        }
    }
}
