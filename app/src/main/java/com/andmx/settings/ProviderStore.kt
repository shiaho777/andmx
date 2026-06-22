package com.andmx.settings

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import com.andmx.data.AndmxDatabase
import com.andmx.data.ProviderEntity
import com.andmx.llm.provider.ModelDefinition
import com.andmx.llm.provider.ProviderDefinition
import com.andmx.llm.provider.ProviderKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Persists the catalogue of configured [ProviderDefinition]s in the Room
 * `providers` table, and exposes the currently-selected ("primary") provider.
 *
 * On first access, if the table is empty the store seeds it: built-in presets
 * are inserted, and a legacy single-provider config (pre-v8 DataStore values)
 * is migrated into one custom row marked primary — so existing users keep
 * their endpoint/key.
 */
class ProviderStore(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    private val dao = AndmxDatabase.get(context).dao()
    private val prefs = context.getSharedPreferences(SEED_PREFS, Context.MODE_PRIVATE)

    /** All configured providers, ordered with primary first. */
    val providers: Flow<List<ProviderDefinition>> =
        dao.observeProviders().map { rows -> rows.map { it.toDefinition() } }

    /** The currently-selected provider, or null if none is usable yet. */
    val primary: Flow<ProviderDefinition?> =
        dao.observePrimaryProvider().map { row -> row?.toDefinition() }

    /** Providers + the primary in one emission (UI convenience). */
    val state: Flow<Pair<List<ProviderDefinition>, ProviderDefinition?>> =
        combine(providers, primary) { list, p -> list to p }

    /** Seed built-in presets and migrate legacy DataStore config if needed. Idempotent. */
    suspend fun ensureSeeded(legacy: LegacyProvider? = null) {
        if (prefs.getBoolean(KEY_SEEDED, false)) return
        val existing = dao.allProviders()
        if (existing.isEmpty()) {
            val now = System.currentTimeMillis()
            // Insert the built-in presets as disabled, non-primary rows.
            ProviderDefinition.BUILTIN_PROVIDERS.forEach { def ->
                dao.upsertProvider(def.copy(enabled = false).toEntity(createdAtMs = now, isPrimary = false))
            }
            // Migrate the legacy single-provider config into the primary row.
            val primary = legacy?.toProviderDefinition()
                ?: ProviderDefinition.BUILTIN_PROVIDERS.first().copy(apiKey = "")
            dao.upsertProvider(primary.copy(enabled = true).toEntity(createdAtMs = now, isPrimary = true))
        }
        prefs.edit().putBoolean(KEY_SEEDED, true).apply()
    }

    /** Insert or update a provider by id. */
    suspend fun upsert(def: ProviderDefinition) {
        val now = System.currentTimeMillis()
        dao.upsertProvider(def.toEntity(createdAtMs = now, isPrimary = false))
    }

    /** Delete a provider. Falls back to the first remaining provider as primary. */
    suspend fun delete(id: String) {
        dao.deleteProvider(id)
        // If we just removed the primary, promote the next available provider.
        if (dao.allProviders().isNotEmpty() && primary.first() == null) {
            dao.allProviders().firstOrNull()?.let { dao.setPrimary(it.id) }
        }
    }

    /** Mark a provider as the active one (clears the previous primary). */
    suspend fun setPrimary(id: String) {
        val rows = dao.allProviders()
        if (rows.none { it.id == id }) return
        dao.clearPrimary()
        dao.setPrimary(id)
    }

    // ── Entity ↔ Definition ───────────────────────────────────────────────────

    private fun ProviderEntity.toDefinition(): ProviderDefinition = ProviderDefinition(
        id = id,
        name = name,
        kind = runCatching { ProviderKind.valueOf(kind) }.getOrDefault(ProviderKind.OPENAI),
        baseUrl = baseUrl,
        apiKey = apiKey,
        apiKeyRequired = apiKeyRequired,
        enabled = enabled,
        source = source,
        requestMaxRetries = requestMaxRetries,
        streamMaxRetries = streamMaxRetries,
        streamIdleTimeoutMs = streamIdleTimeoutMs,
        httpHeaders = decodeMap(httpHeadersJson),
        models = decodeModels(modelsJson),
    )

    private fun ProviderDefinition.toEntity(createdAtMs: Long, isPrimary: Boolean): ProviderEntity = ProviderEntity(
        id = id,
        name = name,
        kind = kind.name,
        baseUrl = baseUrl,
        apiKey = apiKey,
        apiKeyRequired = apiKeyRequired,
        enabled = enabled,
        source = source,
        requestMaxRetries = requestMaxRetries,
        streamMaxRetries = streamMaxRetries,
        streamIdleTimeoutMs = streamIdleTimeoutMs,
        httpHeadersJson = encodeMap(httpHeaders),
        modelsJson = encodeModels(models),
        isPrimary = isPrimary,
        createdAtMs = createdAtMs,
        updatedAtMs = createdAtMs,
    )

    private fun encodeMap(m: Map<String, String>): String =
        json.encodeToString(MapSerializer(String.serializer(), String.serializer()), m)

    private fun decodeMap(s: String): Map<String, String> =
        runCatching { json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), s) }.getOrDefault(emptyMap())

    private fun encodeModels(m: Map<String, ModelDefinition>): String =
        json.encodeToString(MapSerializer(String.serializer(), ModelDefinition.serializer()), m)

    private fun decodeModels(s: String): Map<String, ModelDefinition> =
        runCatching { json.decodeFromString(MapSerializer(String.serializer(), ModelDefinition.serializer()), s) }.getOrDefault(emptyMap())

    companion object {
        private const val SEED_PREFS = "andmx_provider_seed"
        private const val KEY_SEEDED = "seeded_v1"
    }
}

/**
 * Snapshot of the legacy pre-v8 single-provider config (from DataStore), used
 * only to seed the new multi-provider table on first run.
 */
data class LegacyProvider(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val wireApi: String,
)

/** Heuristically turn a legacy config into a [ProviderDefinition]. */
private fun LegacyProvider.toProviderDefinition(): ProviderDefinition {
    val kind = ProviderKind.from(wireApi)
    val id = "migrated"
    return ProviderDefinition(
        id = id,
        name = "迁移配置",
        kind = kind,
        baseUrl = baseUrl,
        apiKey = apiKey,
        models = if (model.isBlank()) emptyMap() else mapOf(model to ModelDefinition()),
    )
}
