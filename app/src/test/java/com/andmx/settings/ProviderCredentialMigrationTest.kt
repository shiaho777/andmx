package com.andmx.settings

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.andmx.data.AndmxDatabase
import com.andmx.data.ProviderEntity
import com.andmx.llm.provider.ProviderDefinition
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ProviderCredentialMigrationTest {
    private class Keys : CredentialKeySource {
        var available = true
        private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        override fun encryptionKey(): SecretKey = if (available) key else throw CredentialStorageException()
        override fun decryptionKey(): SecretKey = if (available) key else throw CredentialStorageException()
    }

    private lateinit var context: Context
    private lateinit var database: AndmxDatabase
    private val keys = Keys()
    private val cipher = AesGcmCredentialCipher(keys)
    private val apiKey = stringPreferencesKey("api_key")
    private val baseUrl = stringPreferencesKey("base_url")

    @Before
    fun setup() = runBlocking {
        context = RuntimeEnvironment.getApplication()
        database = AndmxDatabase.get(context)
        database.dao().allProviders().forEach { database.dao().deleteProvider(it.id) }
        context.dataStore.edit { it.clear() }
        context.getSharedPreferences("andmx_provider_seed", Context.MODE_PRIVATE).edit().clear().commit()
        Unit
    }

    @Test
    fun roomPlaintextMigratesEvenAfterSeedFlagAndPreservesMetadata() = runBlocking {
        val original = row("existing", "plain-secret")
        database.dao().upsertProvider(original)
        context.getSharedPreferences("andmx_provider_seed", Context.MODE_PRIVATE)
            .edit().putBoolean("seeded_v1", true).commit()
        val store = ProviderStore(context, cipher = cipher)
        store.ensureSeeded()
        val stored = database.dao().allProviders().single()
        assertTrue(stored.apiKey.startsWith(AesGcmCredentialCipher.VERSION_PREFIX))
        assertEquals(original, stored.copy(apiKey = original.apiKey))
        assertEquals("plain-secret", store.providers.first().single().apiKey)
        assertEquals("existing", store.primary.first()?.id)
    }

    @Test
    fun legacyIsEncryptedThenRemovedOnlyAfterVerifiedRoomSeed() = runBlocking {
        context.dataStore.edit {
            it[apiKey] = "legacy-secret"
            it[baseUrl] = "https://example.test"
            it[stringPreferencesKey("model")] = "model-a"
        }
        val settings = SettingsStore(context, cipher)
        val legacy = settings.legacyProvider()!!
        val encryptedLegacy = context.dataStore.data.first()[apiKey]!!
        assertEquals("legacy-secret", legacy.apiKey)
        assertTrue(encryptedLegacy.startsWith(AesGcmCredentialCipher.VERSION_PREFIX))
        val store = ProviderStore(context, cipher = cipher)
        store.ensureSeeded(legacy)
        assertNull(context.dataStore.data.first()[apiKey])
        assertNull(context.dataStore.data.first()[baseUrl])
        assertEquals("model-a", context.dataStore.data.first()[stringPreferencesKey("model")])
        val stored = database.dao().allProviders().single()
        assertEquals("legacy-secret", cipher.decrypt(stored.apiKey, CredentialPersistence.providerScope("migrated")))
        store.ensureSeeded(legacy)
        assertEquals(1, database.dao().allProviders().size)
    }

    @Test
    fun failedLegacyEncryptionPreservesSourceAndRetryWorks() = runBlocking {
        context.dataStore.edit {
            it[apiKey] = "legacy-secret"
            it[baseUrl] = "https://example.test"
        }
        val settings = SettingsStore(context, cipher)
        keys.available = false
        rejected { settings.legacyProvider() }
        assertEquals("legacy-secret", context.dataStore.data.first()[apiKey])
        assertTrue(database.dao().allProviders().isEmpty())
        keys.available = true
        assertEquals("legacy-secret", settings.legacyProvider()?.apiKey)
    }

    @Test
    fun failedRoomSeedPreservesEncryptedLegacyAndDoesNotMarkSeeded() = runBlocking {
        context.dataStore.edit {
            it[apiKey] = "legacy-secret"
            it[baseUrl] = "https://example.test"
        }
        val settings = SettingsStore(context, cipher)
        val legacy = settings.legacyProvider()!!
        val saved = context.dataStore.data.first()[apiKey]
        val store = ProviderStore(context, cipher = cipher)
        keys.available = false
        rejected { store.ensureSeeded(legacy) }
        assertEquals(saved, context.dataStore.data.first()[apiKey])
        assertFalse(context.getSharedPreferences("andmx_provider_seed", Context.MODE_PRIVATE).getBoolean("seeded_v1", false))
        keys.available = true
        store.ensureSeeded(legacy)
        assertNull(context.dataStore.data.first()[apiKey])
    }

    @Test
    fun unreadableRowIsDisabledAndCannotBeOverwrittenByEdit() = runBlocking {
        val encrypted = cipher.encrypt("secret", CredentialPersistence.providerScope("unreadable"))
        database.dao().upsertProvider(row("unreadable", encrypted))
        val store = ProviderStore(context, cipher = cipher)
        keys.available = false
        val runtime = store.providers.first().single()
        assertFalse(runtime.enabled)
        assertEquals("", runtime.apiKey)
        assertTrue(store.credentialErrors.value.containsKey("unreadable"))
        rejected { store.upsert(runtime.copy(name = "edited", apiKey = "replacement")) }
        assertEquals(encrypted, database.dao().allProviders().single().apiKey)
        assertEquals("original", database.dao().allProviders().single().name)
        keys.available = true
        assertEquals("secret", store.providers.first().single().apiKey)
    }

    @Test
    fun everyUpsertEncryptsAndKeepsPrimaryAndCreationTime() = runBlocking {
        val store = ProviderStore(context, cipher = cipher)
        val definition = ProviderDefinition("new", "name", baseUrl = "https://example.test", apiKey = "secret")
        store.upsert(definition)
        store.setPrimary("new")
        val first = database.dao().allProviders().single()
        store.upsert(definition.copy(name = "renamed", apiKey = ""))
        val second = database.dao().allProviders().single()
        assertTrue(second.apiKey.startsWith(AesGcmCredentialCipher.VERSION_PREFIX))
        assertEquals("", cipher.decrypt(second.apiKey, CredentialPersistence.providerScope("new")))
        assertTrue(second.isPrimary)
        assertEquals(first.createdAtMs, second.createdAtMs)
    }

    @Test
    fun mismatchedOrUnreadableLegacyIsNeverDeleted() = runBlocking {
        context.dataStore.edit {
            it[apiKey] = "legacy-secret"
            it[baseUrl] = "https://example.test"
        }
        val settings = SettingsStore(context, cipher)
        val legacy = settings.legacyProvider()!!
        val saved = context.dataStore.data.first()[apiKey]
        settings.clearLegacyProvider(legacy.copy(apiKey = "different"))
        assertEquals(saved, context.dataStore.data.first()[apiKey])
        keys.available = false
        rejected { settings.clearLegacyProvider(legacy) }
        rejected { settings.legacyProvider() }
        assertEquals(saved, context.dataStore.data.first()[apiKey])
    }

    private suspend fun rejected(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected secure storage failure")
        } catch (_: CredentialStorageException) {
            Unit
        }
    }

    private fun row(id: String, key: String) = ProviderEntity(
        id = id,
        name = "original",
        kind = "OPENAI",
        baseUrl = "https://example.test",
        apiKey = key,
        apiKeyRequired = true,
        enabled = true,
        source = "custom",
        requestMaxRetries = 2,
        streamMaxRetries = 1,
        streamIdleTimeoutMs = 120_000,
        httpHeadersJson = "{}",
        modelsJson = "{}",
        isPrimary = true,
        createdAtMs = 123,
        updatedAtMs = 456,
    )
}
