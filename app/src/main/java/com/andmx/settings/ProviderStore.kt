package com.andmx.settings

import android.content.Context
import androidx.room.withTransaction
import com.andmx.data.AndmxDatabase
import com.andmx.data.ProviderEntity
import com.andmx.llm.provider.ClaudeModelMapping
import com.andmx.llm.provider.ModelDefinition
import com.andmx.llm.provider.ProviderDefinition
import com.andmx.llm.provider.ProviderKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

class ProviderStore(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
    cipher: CredentialCipher = AesGcmCredentialCipher(AndroidKeystoreCredentialKeys()),
) {
    private val database = AndmxDatabase.get(context)
    private val dao = database.dao()
    private val prefs = context.getSharedPreferences(SEED_PREFS, Context.MODE_PRIVATE)
    private val credentials = CredentialPersistence(cipher)
    private val settingsStore = SettingsStore(context, cipher)
    private val errors = MutableStateFlow<Map<String, String>>(emptyMap())
    val credentialErrors: StateFlow<Map<String, String>> = errors.asStateFlow()

    private val decoded: Flow<List<Pair<ProviderEntity, ProviderDefinition>>> =
        dao.observeProviders().map {
            database.withTransaction {
                dao.allProviders().map { row -> row to row.toDefinition() }
            }
        }

    val state: Flow<Pair<List<ProviderDefinition>, ProviderDefinition?>> = decoded.map { rows ->
        val definitions = rows.map { it.second }
        val ordered = applyProviderOrder(definitions.map { it.id }, readProviderOrder())
            .mapNotNull { id -> definitions.firstOrNull { it.id == id } }
        ordered to rows.firstOrNull { it.first.isPrimary }?.second
    }
    val providers: Flow<List<ProviderDefinition>> = state.map { it.first }
    val primary: Flow<ProviderDefinition?> = state.map { it.second }

    suspend fun ensureSeeded(legacy: LegacyProvider? = null) {
        database.withTransaction {
            val existing = dao.allProviders()
            existing.forEach { row ->
                credentials.readAndMigrate(row.apiKey, CredentialPersistence.providerScope(row.id)) {
                    dao.upsertProvider(row.copy(apiKey = it))
                }
            }
            if (!prefs.getBoolean(KEY_SEEDED, false) && existing.isEmpty() && legacy != null) {
                val now = System.currentTimeMillis()
                val definition = legacy.toProviderDefinition()
                val encrypted = credentials.forSave(definition.apiKey, null, CredentialPersistence.providerScope(definition.id))
                dao.upsertProvider(definition.toEntity(now, true, encrypted))
            }
        }
        if (legacy != null) {
            val migrated = dao.allProviders().firstOrNull { it.id == "migrated" }
            if (migrated != null && migrated.baseUrl == legacy.baseUrl &&
                credentials.decryptStored(migrated.apiKey, CredentialPersistence.providerScope(migrated.id)) == legacy.apiKey
            ) {
                settingsStore.clearLegacyProvider(legacy)
            }
        }
        prefs.edit().putBoolean(KEY_SEEDED, true).apply()
    }

    suspend fun upsert(def: ProviderDefinition) {
        database.withTransaction {
            val now = System.currentTimeMillis()
            val existing = dao.allProviders().firstOrNull { it.id == def.id }
            val encrypted = credentials.forSave(def.apiKey, existing?.apiKey, CredentialPersistence.providerScope(def.id))
            dao.upsertProvider(def.toEntity(now, existing?.isPrimary ?: false, encrypted)
                .copy(createdAtMs = existing?.createdAtMs ?: now))
        }
        errors.update { it - def.id }
    }

    suspend fun delete(id: String) {
        database.withTransaction {
            dao.deleteProvider(id)
            val remaining = dao.allProviders()
            if (remaining.none { it.isPrimary }) remaining.firstOrNull()?.let { dao.setPrimary(it.id) }
        }
        errors.update { it - id }
    }

    suspend fun reorderProviders(activeId: String, overId: String) {
        val current = dao.observeProviders().first().map { it.id }
        val next = reorderProviderIds(current, activeId, overId)
        if (next != current) writeProviderOrder(next)
    }

    private fun readProviderOrder(): List<String> {
        val raw = prefs.getString(KEY_PROVIDER_ORDER, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(String.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    private fun writeProviderOrder(ids: List<String>) {
        val raw = json.encodeToString(ListSerializer(String.serializer()), ids)
        prefs.edit().putString(KEY_PROVIDER_ORDER, raw).apply()
    }

    suspend fun setPrimary(id: String) {
        database.withTransaction {
            if (dao.allProviders().any { it.id == id }) {
                dao.clearPrimary()
                dao.setPrimary(id)
            }
        }
    }

    private suspend fun ProviderEntity.toDefinition(): ProviderDefinition {
        var unreadable = false
        val runtimeKey = try {
            credentials.readAndMigrate(apiKey, CredentialPersistence.providerScope(id)) {
                dao.upsertProvider(copy(apiKey = it))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            unreadable = true
            errors.update { it + (id to ERROR_UNREADABLE) }
            ""
        }
        if (!unreadable) errors.update { it - id }
        return ProviderDefinition(
            id = id,
            name = name,
            kind = runCatching { ProviderKind.valueOf(kind) }.getOrDefault(ProviderKind.OPENAI),
            baseUrl = baseUrl,
            apiKey = runtimeKey,
            apiKeyRequired = apiKeyRequired,
            enabled = enabled && !unreadable,
            source = source,
            requestMaxRetries = requestMaxRetries,
            streamMaxRetries = streamMaxRetries,
            streamIdleTimeoutMs = streamIdleTimeoutMs,
            httpHeaders = decodeMap(httpHeadersJson),
            models = decodeModels(modelsJson),
            claudeMapping = decodeClaudeMapping(claudeMappingJson),
        )
    }

    private fun ProviderDefinition.toEntity(now: Long, isPrimary: Boolean, encryptedKey: String): ProviderEntity = ProviderEntity(
        id = id,
        name = name,
        kind = kind.name,
        baseUrl = baseUrl,
        apiKey = encryptedKey,
        apiKeyRequired = apiKeyRequired,
        enabled = enabled,
        source = source,
        requestMaxRetries = requestMaxRetries,
        streamMaxRetries = streamMaxRetries,
        streamIdleTimeoutMs = streamIdleTimeoutMs,
        httpHeadersJson = encodeMap(httpHeaders),
        modelsJson = encodeModels(models),
        claudeMappingJson = encodeClaudeMapping(claudeMapping),
        isPrimary = isPrimary,
        createdAtMs = now,
        updatedAtMs = now,
    )

    private fun encodeMap(m: Map<String, String>): String =
        json.encodeToString(MapSerializer(String.serializer(), String.serializer()), m)

    private fun decodeMap(s: String): Map<String, String> =
        runCatching { json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), s) }.getOrDefault(emptyMap())

    private fun encodeModels(m: Map<String, ModelDefinition>): String =
        json.encodeToString(MapSerializer(String.serializer(), ModelDefinition.serializer()), m)

    private fun decodeModels(s: String): Map<String, ModelDefinition> =
        runCatching { json.decodeFromString(MapSerializer(String.serializer(), ModelDefinition.serializer()), s) }.getOrDefault(emptyMap())

    private fun encodeClaudeMapping(m: ClaudeModelMapping?): String =
        m?.let { json.encodeToString(ClaudeModelMapping.serializer(), it) }.orEmpty()

    private fun decodeClaudeMapping(s: String): ClaudeModelMapping? =
        if (s.isBlank()) null else runCatching { json.decodeFromString(ClaudeModelMapping.serializer(), s) }.getOrNull()

    companion object {
        private const val SEED_PREFS = "andmx_provider_seed"
        private const val KEY_SEEDED = "seeded_v1"
        private const val KEY_PROVIDER_ORDER = "provider_order_v1"
        private const val ERROR_UNREADABLE =
            "API Key 无法安全读取或迁移，原始数据已保留。请稍后重试；凭据永久丢失时请删除并重新添加此供应商。"
    }
}

data class LegacyProvider(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val wireApi: String,
)

private fun LegacyProvider.toProviderDefinition(): ProviderDefinition = ProviderDefinition(
    id = "migrated",
    name = "",
    kind = ProviderKind.from(wireApi),
    baseUrl = baseUrl,
    apiKey = apiKey,
    models = if (model.isBlank()) emptyMap() else mapOf(model to ModelDefinition()),
)
