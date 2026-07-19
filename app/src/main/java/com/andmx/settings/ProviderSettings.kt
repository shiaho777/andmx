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

    // ── 常规设置（对标 ZCode General）──
    /** 界面语言: "system" | "zh" | "en" */
    val locale: String = "system",
    /** 终端字体覆盖；留空时自动继承系统终端字体。 */
    val terminalFontFamily: String = "",
    /** 任务完成/失败/需确认时发送通知。 */
    val notification: Boolean = true,
    /** 通知提示音（notification 开启时生效）。 */
    val notificationSound: Boolean = true,
    /** 运行时后续操作的处理: "queue" | "guide" */
    val interactionBehavior: String = "queue",
    /** 在消息流中展示模型思考内容。 */
    val showReasoning: Boolean = true,
    /** 在消息流中展示 Todo 工具卡片。 */
    val showTodos: Boolean = true,
    /** 自动归档超过保留期的已完成任务。 */
    val taskAutoArchive: Boolean = false,
    /** 归档保留时长（天）: 3 | 7 | 14 | 30 */
    val taskAutoArchiveDays: Int = 7,

    // ── 代码预览（对标 ZCode Code Preview）──
    /** 浅色模式下代码块主题 id。 */
    val lightCodeTheme: String = "github-light",
    /** 深色模式下代码块主题 id。 */
    val darkCodeTheme: String = "one-dark-pro",
    /** 在代码块中显示行号。 */
    val showLineNumbers: Boolean = false,
    /** 长行自动换行（否则横向滚动）。 */
    val wrapLongLines: Boolean = false,
    /** 代码块字号（sp）。 */
    val codeFontSize: Int = 13,

    val indexNewFolders: Boolean = false,
    val indexNewFoldersUserConfigured: Boolean = false,
    val instantGrep: Boolean = false,
    val indexFileLimit: Int = 50_000,
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
    private val localeKey = stringPreferencesKey("locale")
    private val terminalFontKey = stringPreferencesKey("terminal_font_family")
    private val notificationKey = androidx.datastore.preferences.core.booleanPreferencesKey("notification")
    private val notificationSoundKey = androidx.datastore.preferences.core.booleanPreferencesKey("notification_sound")
    private val interactionKey = stringPreferencesKey("interaction_behavior")
    private val showReasoningKey = androidx.datastore.preferences.core.booleanPreferencesKey("show_reasoning")
    private val showTodosKey = androidx.datastore.preferences.core.booleanPreferencesKey("show_todos")
    private val autoArchiveKey = androidx.datastore.preferences.core.booleanPreferencesKey("task_auto_archive")
    private val autoArchiveDaysKey = androidx.datastore.preferences.core.intPreferencesKey("task_auto_archive_days")
    private val lightCodeThemeKey = stringPreferencesKey("light_code_theme")
    private val darkCodeThemeKey = stringPreferencesKey("dark_code_theme")
    private val showLineNumbersKey = androidx.datastore.preferences.core.booleanPreferencesKey("show_line_numbers")
    private val wrapLongLinesKey = androidx.datastore.preferences.core.booleanPreferencesKey("wrap_long_lines")
    private val codeFontSizeKey = androidx.datastore.preferences.core.intPreferencesKey("code_font_size")
    private val indexNewFoldersKey = androidx.datastore.preferences.core.booleanPreferencesKey("index_new_folders")
    private val indexNewFoldersUserConfiguredKey = androidx.datastore.preferences.core.booleanPreferencesKey("index_new_folders_user_configured")
    private val instantGrepKey = androidx.datastore.preferences.core.booleanPreferencesKey("instant_grep")
    private val indexFileLimitKey = androidx.datastore.preferences.core.intPreferencesKey("index_file_limit")

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
            locale = p[localeKey] ?: def.locale,
            terminalFontFamily = p[terminalFontKey] ?: def.terminalFontFamily,
            notification = p[notificationKey] ?: def.notification,
            notificationSound = p[notificationSoundKey] ?: def.notificationSound,
            interactionBehavior = p[interactionKey] ?: def.interactionBehavior,
            showReasoning = p[showReasoningKey] ?: def.showReasoning,
            showTodos = p[showTodosKey] ?: def.showTodos,
            taskAutoArchive = p[autoArchiveKey] ?: def.taskAutoArchive,
            taskAutoArchiveDays = p[autoArchiveDaysKey] ?: def.taskAutoArchiveDays,
            lightCodeTheme = p[lightCodeThemeKey] ?: def.lightCodeTheme,
            darkCodeTheme = p[darkCodeThemeKey] ?: def.darkCodeTheme,
            showLineNumbers = p[showLineNumbersKey] ?: def.showLineNumbers,
            wrapLongLines = p[wrapLongLinesKey] ?: def.wrapLongLines,
            codeFontSize = p[codeFontSizeKey] ?: def.codeFontSize,
            indexNewFolders = p[indexNewFoldersKey] ?: def.indexNewFolders,
            indexNewFoldersUserConfigured = p[indexNewFoldersUserConfiguredKey] ?: def.indexNewFoldersUserConfigured,
            instantGrep = p[instantGrepKey] ?: def.instantGrep,
            indexFileLimit = p[indexFileLimitKey] ?: def.indexFileLimit,
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
            p[localeKey] = settings.locale
            p[terminalFontKey] = settings.terminalFontFamily
            p[notificationKey] = settings.notification
            p[notificationSoundKey] = settings.notificationSound
            p[interactionKey] = settings.interactionBehavior
            p[showReasoningKey] = settings.showReasoning
            p[showTodosKey] = settings.showTodos
            p[autoArchiveKey] = settings.taskAutoArchive
            p[autoArchiveDaysKey] = settings.taskAutoArchiveDays
            p[lightCodeThemeKey] = settings.lightCodeTheme
            p[darkCodeThemeKey] = settings.darkCodeTheme
            p[showLineNumbersKey] = settings.showLineNumbers
            p[wrapLongLinesKey] = settings.wrapLongLines
            p[codeFontSizeKey] = settings.codeFontSize
            p[indexNewFoldersKey] = settings.indexNewFolders
            p[indexNewFoldersUserConfiguredKey] = settings.indexNewFoldersUserConfigured
            p[instantGrepKey] = settings.instantGrep
            p[indexFileLimitKey] = settings.indexFileLimit
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

    private val settingsJson = Json { ignoreUnknownKeys = true }

    // ---- Custom commands（用户自定义 /command）----
    private val commandsKey = stringPreferencesKey("custom_commands")

    val customCommands: Flow<List<CustomCommand>> = context.dataStore.data.map { p ->
        p[commandsKey]?.let {
            runCatching { settingsJson.decodeFromString(ListSerializer(CustomCommand.serializer()), it) }.getOrNull()
        } ?: emptyList()
    }

    suspend fun saveCommands(list: List<CustomCommand>) {
        context.dataStore.edit { p ->
            p[commandsKey] = settingsJson.encodeToString(ListSerializer(CustomCommand.serializer()), list)
        }
    }

    private val subAgentsKey = stringPreferencesKey("custom_subagents")
    private val subAgentStateKey = stringPreferencesKey("subagents_state")

    val customSubAgents: Flow<List<CustomSubAgent>> = context.dataStore.data.map { p ->
        p[subAgentsKey]?.let {
            runCatching { settingsJson.decodeFromString(ListSerializer(CustomSubAgent.serializer()), it) }.getOrNull()
        } ?: emptyList()
    }

    suspend fun saveSubAgents(list: List<CustomSubAgent>) {
        context.dataStore.edit { p ->
            p[subAgentsKey] = settingsJson.encodeToString(ListSerializer(CustomSubAgent.serializer()), list)
        }
    }

    val subagentState: Flow<SubagentStateFile> = context.dataStore.data.map { p ->
        p[subAgentStateKey]?.let {
            runCatching { settingsJson.decodeFromString(SubagentStateFile.serializer(), it) }.getOrNull()
        } ?: SubagentStateFile()
    }

    suspend fun saveSubagentState(state: SubagentStateFile) {
        context.dataStore.edit { p ->
            p[subAgentStateKey] = settingsJson.encodeToString(SubagentStateFile.serializer(), state)
        }
    }
}

@Serializable
data class CustomCommand(
    val id: String,
    val name: String,
    val description: String = "",
    val argumentHint: String = "",
    val prompt: String
)

@Serializable
data class CustomSubAgent(
    val id: String,
    val name: String,
    val description: String = "",
    val systemPrompt: String = "",
    val model: String = "inherit",
    val permissionMode: String = "default",
    val color: String = "blue",
    val background: Boolean = false,
    val enabled: Boolean = true,
    val tools: List<String> = listOf("*"),
    val disallowedTools: List<String> = emptyList(),
    val skills: List<String> = emptyList(),
    val maxTurns: Int? = null,
    val mcpServers: List<String> = emptyList(),
    val scope: String = "user",
    val source: String = "user",
    val path: String = "",
    val readOnly: Boolean = false,
)

@Serializable
data class SubagentStateFile(
    val builtInModelOverrides: Map<String, String> = emptyMap(),
    val disabledAgentIds: List<String> = emptyList(),
)
