package com.andmx.settings

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import com.andmx.data.AndmxDatabase
import com.andmx.data.ProviderEntity
import com.andmx.llm.provider.ClaudeModelMapping
import com.andmx.llm.provider.ModelDefinition
import com.andmx.llm.provider.ProviderDefinition
import com.andmx.llm.provider.ProviderKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
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

    /**
     * All configured providers, in the user's display order.
     *
     * The order lives in [prefs] as a standalone id list (see [ProviderOrder]),
     * so the table itself stays unordered and a provider that has never been
     * dragged simply falls to the end.
     */
    val providers: Flow<List<ProviderDefinition>> =
        dao.observeProviders().map { rows ->
            val defs = rows.map { it.toDefinition() }
            applyProviderOrder(defs.map { it.id }, readProviderOrder())
                .mapNotNull { id -> defs.firstOrNull { it.id == id } }
        }

    /** The currently-selected provider, or null if none is usable yet. */
    val primary: Flow<ProviderDefinition?> =
        dao.observePrimaryProvider().map { row -> row?.toDefinition() }

    /** Providers + the primary in one emission (UI convenience). */
    val state: Flow<Pair<List<ProviderDefinition>, ProviderDefinition?>> =
        combine(providers, primary) { list, p -> list to p }

    /**
     * Migrate a legacy DataStore single-provider config into the providers table
     * on first run, if present. Idempotent.
     *
     * No built-in presets are seeded — the table starts empty and the user adds
     * each provider by hand (name, protocol, URL, key), then fetches the model
     * list from the endpoint.
     */
    suspend fun ensureSeeded(legacy: LegacyProvider? = null) {
        if (prefs.getBoolean(KEY_SEEDED, false)) return
        val existing = dao.allProviders()
        if (existing.isEmpty() && legacy != null) {
            val now = System.currentTimeMillis()
            dao.upsertProvider(legacy.toProviderDefinition().copy(enabled = true).toEntity(createdAtMs = now, isPrimary = true))
        }
        prefs.edit().putBoolean(KEY_SEEDED, true).apply()
    }

    /** Insert or update a provider by id, preserving its existing primary flag. */
    suspend fun upsert(def: ProviderDefinition) {
        val now = System.currentTimeMillis()
        // Preserve the existing isPrimary state so editing a provider (e.g.
        // adding a model) doesn't accidentally demote the active provider.
        val existingPrimary = dao.allProviders().firstOrNull { it.id == def.id }?.isPrimary ?: false
        dao.upsertProvider(def.toEntity(createdAtMs = now, isPrimary = existingPrimary))
    }

    /** Delete a provider. Falls back to the first remaining provider as primary. */
    suspend fun delete(id: String) {
        dao.deleteProvider(id)
        // If we just removed the primary, promote the next available provider.
        if (dao.allProviders().isNotEmpty() && primary.first() == null) {
            dao.allProviders().firstOrNull()?.let { dao.setPrimary(it.id) }
        }
    }

    /**
     * Move the provider [activeId] onto [overId]'s slot, then persist the
     * resulting order. Unknown or identical ids leave the order untouched.
     */
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
        val raw = runCatching {
            json.encodeToString(ListSerializer(String.serializer()), ids)
        }.getOrNull() ?: return
        prefs.edit().putString(KEY_PROVIDER_ORDER, raw).apply()
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
        claudeMapping = decodeClaudeMapping(claudeMappingJson),
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
        claudeMappingJson = encodeClaudeMapping(claudeMapping),
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

    private fun encodeClaudeMapping(m: ClaudeModelMapping?): String =
        m?.let { runCatching { json.encodeToString(ClaudeModelMapping.serializer(), it) }.getOrNull() }.orEmpty()

    private fun decodeClaudeMapping(s: String): ClaudeModelMapping? =
        if (s.isBlank()) null
        else runCatching { json.decodeFromString(ClaudeModelMapping.serializer(), s) }.getOrNull()

    companion object {
        private const val SEED_PREFS = "andmx_provider_seed"
        private const val KEY_SEEDED = "seeded_v1"
        private const val KEY_PROVIDER_ORDER = "provider_order_v1"
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
        name = "",
        kind = kind,
        baseUrl = baseUrl,
        apiKey = apiKey,
        models = if (model.isBlank()) emptyMap() else mapOf(model to ModelDefinition()),
    )
}
