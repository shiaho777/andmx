package com.andmx.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * User-level preferences for the agent. Provider connection details (base URL,
 * API key, protocol, model catalogue) now live in [ProviderStore] / Room; this
 * struct keeps only cross-provider UI/behavior settings plus the id of the
 * currently-selected provider and model.
 */
data class ProviderSettings(
    /** Id of the active provider (row in the providers table). */
    val activeProviderId: String = "",
    /** The model id the user has selected within the active provider. */
    val model: String = "",
    /** "ask" | "auto" | "yolo" — gates tool execution. */
    val approvalMode: String = "ask",
    /** Extra system-prompt instructions appended by the user. */
    val customInstructions: String = "",
    /** "system" | "light" | "dark" */
    val themeMode: String = "system",
    /** accent color hex, e.g. "#339CFF" */
    val accent: String = "#339CFF",
    /** persona/tone, e.g. "务实" | "友好" | "简洁" | "幽默" */
    val persona: String = "务实",
    /** reasoning effort for capable models: "off" | "low" | "medium" | "high" */
    val reasoningEffort: String = "off",
    /** newline list of MCP servers as "name|command". */
    val mcpServers: String = "",
) {
    /**
     * Whether the agent can take a turn. Provider readiness (key present etc.)
     * is owned by [ProviderStore]/[ProviderDefinition]; this only checks that a
     * provider and model have been chosen.
     */
    val hasSelection: Boolean get() = activeProviderId.isNotBlank() && model.isNotBlank()
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "andmx_settings")

class SettingsStore(private val context: Context) {
    private val activeProviderKey = stringPreferencesKey("active_provider_id")
    private val modelKey = stringPreferencesKey("model")
    private val approvalKey = stringPreferencesKey("approval_mode")
    private val instructionsKey = stringPreferencesKey("custom_instructions")
    private val themeModeKey = stringPreferencesKey("theme_mode")
    private val accentKey = stringPreferencesKey("accent")
    private val personaKey = stringPreferencesKey("persona")
    private val reasoningKey = stringPreferencesKey("reasoning_effort")
    private val mcpKey = stringPreferencesKey("mcp_servers")

    // Legacy keys retained only for one-time migration into the providers table.
    internal val legacyBaseUrlKey = stringPreferencesKey("base_url")
    internal val legacyApiKeyKey = stringPreferencesKey("api_key")
    internal val legacyModelProviderKey = stringPreferencesKey("model_provider")
    internal val legacyWireApiKey = stringPreferencesKey("wire_api")

    val settings: Flow<ProviderSettings> = context.dataStore.data.map { p ->
        val def = ProviderSettings()
        ProviderSettings(
            activeProviderId = p[activeProviderKey] ?: def.activeProviderId,
            model = p[modelKey] ?: def.model,
            approvalMode = p[approvalKey] ?: def.approvalMode,
            customInstructions = p[instructionsKey] ?: def.customInstructions,
            themeMode = p[themeModeKey] ?: def.themeMode,
            accent = p[accentKey] ?: def.accent,
            persona = p[personaKey] ?: def.persona,
            reasoningEffort = p[reasoningKey] ?: def.reasoningEffort,
            mcpServers = p[mcpKey] ?: def.mcpServers,
        )
    }

    suspend fun update(settings: ProviderSettings) {
        context.dataStore.edit { p ->
            p[activeProviderKey] = settings.activeProviderId
            p[modelKey] = settings.model
            p[approvalKey] = settings.approvalMode
            p[instructionsKey] = settings.customInstructions
            p[themeModeKey] = settings.themeMode
            p[accentKey] = settings.accent
            p[personaKey] = settings.persona
            p[reasoningKey] = settings.reasoningEffort
            p[mcpKey] = settings.mcpServers
        }
    }

    /** Snapshot legacy provider fields, for one-time seeding of the providers table. */
    suspend fun legacyProvider(): LegacyProvider? {
        val p = context.dataStore.data.first()
        val baseUrl = p[legacyBaseUrlKey]
        val apiKey = p[legacyApiKeyKey]
        // Only treat as migratable if there's a real base URL or key.
        return if (!baseUrl.isNullOrBlank() || !apiKey.isNullOrBlank()) {
            LegacyProvider(
                baseUrl = baseUrl.orEmpty(),
                apiKey = apiKey.orEmpty(),
                model = p[modelKey].orEmpty(),
                wireApi = p[legacyWireApiKey].orEmpty(),
            )
        } else null
    }

    // ---- Automations (saved prompts) ----
    private val automationsKey = stringPreferencesKey("automations")
    private val automationJson = Json { ignoreUnknownKeys = true }

    val automations: Flow<List<Automation>> = context.dataStore.data.map { p ->
        p[automationsKey]?.let {
            runCatching { automationJson.decodeFromString(ListSerializer(Automation.serializer()), it) }.getOrNull()
        } ?: emptyList()
    }

    suspend fun saveAutomations(list: List<Automation>) {
        context.dataStore.edit { p ->
            p[automationsKey] = automationJson.encodeToString(ListSerializer(Automation.serializer()), list)
        }
    }
}

@Serializable
data class Automation(val title: String, val prompt: String)
