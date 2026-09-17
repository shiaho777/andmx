package com.andmx.settings

import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CredentialCipherTest {
    private class Keys : CredentialKeySource {
        var available = true
        private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        override fun encryptionKey(): SecretKey = if (available) key else throw CredentialStorageException()
        override fun decryptionKey(): SecretKey = if (available) key else throw CredentialStorageException()
    }

    private val keys = Keys()
    private val cipher = AesGcmCredentialCipher(keys)
    private val persistence = CredentialPersistence(cipher)
    private val scope = CredentialPersistence.providerScope("provider-a")

    @Test
    fun roundTripUsesAuthenticatedVersionedRandomCiphertext() {
        val plaintext = "sk-private-密钥"
        val first = cipher.encrypt(plaintext, scope)
        val second = cipher.encrypt(plaintext, scope)
        assertTrue(first.startsWith(AesGcmCredentialCipher.VERSION_PREFIX))
        assertFalse(first.contains(plaintext))
        assertNotEquals(first, second)
        assertEquals(plaintext, cipher.decrypt(first, scope))
    }

    @Test
    fun emptyKeyIsEncryptedToo() {
        val stored = persistence.forSave("", null, scope)
        assertTrue(persistence.isEncrypted(stored))
        assertEquals("", cipher.decrypt(stored, scope))
    }

    @Test
    fun wrongKeyAndProviderScopeAreRejected() {
        val stored = cipher.encrypt("secret", scope)
        rejected { cipher.decrypt(stored, CredentialPersistence.providerScope("provider-b")) }
        rejected { AesGcmCredentialCipher(Keys()).decrypt(stored, scope) }
        rejected { cipher.decrypt(stored, CredentialPersistence.LEGACY_SCOPE) }
    }

    @Test
    fun corruptedNonceCiphertextAndTagAreRejected() {
        val stored = cipher.encrypt("secret", scope)
        val bytes = Base64.getDecoder().decode(stored.removePrefix(AesGcmCredentialCipher.VERSION_PREFIX))
        for (index in listOf(0, 12, bytes.lastIndex)) {
            val corrupted = bytes.copyOf()
            corrupted[index] = (corrupted[index].toInt() xor 1).toByte()
            rejected {
                cipher.decrypt(AesGcmCredentialCipher.VERSION_PREFIX + Base64.getEncoder().encodeToString(corrupted), scope)
            }
        }
    }

    @Test
    fun malformedAndFutureVersionValuesAreNotTreatedAsPlaintext() = runBlocking {
        for (stored in listOf("andmx-credential:v2:unknown", "andmx-credential:v1:!", "andmx-credential:v1:AA==")) {
            var writes = 0
            try {
                persistence.readAndMigrate(stored, scope) { writes++ }
                fail("Expected protected credential rejection")
            } catch (_: CredentialStorageException) {
                assertEquals(0, writes)
            }
        }
    }

    @Test
    fun roomPlaintextMigrationPersistsBeforeReturningRuntimeKey() = runBlocking {
        var stored = "sk-existing"
        val runtime = persistence.readAndMigrate(stored, scope) { stored = it }
        assertTrue(persistence.isEncrypted(stored))
        assertEquals("sk-existing", runtime)
        assertEquals(runtime, cipher.decrypt(stored, scope))
        var writes = 0
        assertEquals(runtime, persistence.readAndMigrate(stored, scope) { writes++ })
        assertEquals(0, writes)
    }

    @Test
    fun legacyMigrationReencryptsIntoProviderScopeBeforeCleanup() = runBlocking {
        var legacy: String? = "legacy-secret"
        val runtime = persistence.readAndMigrate(legacy!!, CredentialPersistence.LEGACY_SCOPE) { legacy = it }
        assertTrue(persistence.isEncrypted(legacy!!))
        val room = persistence.forSave(runtime, null, scope)
        assertEquals("legacy-secret", cipher.decrypt(room, scope))
        assertEquals(runtime, cipher.decrypt(legacy!!, CredentialPersistence.LEGACY_SCOPE))
        legacy = null
        assertEquals(null, legacy)
    }

    @Test
    fun encryptionFailurePreservesPlaintextAndDoesNotReturnIt() = runBlocking {
        val stored = "old-secret"
        keys.available = false
        var writes = 0
        try {
            persistence.readAndMigrate(stored, scope) { writes++ }
            fail("Expected migration rejection")
        } catch (_: CredentialStorageException) {
            assertEquals("old-secret", stored)
            assertEquals(0, writes)
        }
    }

    @Test
    fun writeFailureDoesNotReturnPlaintextAndAllowsRetry() = runBlocking {
        var stored = "old-secret"
        try {
            persistence.readAndMigrate(stored, scope) { throw java.io.IOException("write failed") }
            fail("Expected persistence failure")
        } catch (_: java.io.IOException) {
            assertEquals("old-secret", stored)
        }
        assertEquals("old-secret", persistence.readAndMigrate(stored, scope) { stored = it })
        assertTrue(persistence.isEncrypted(stored))
    }

    @Test
    fun unreadableCiphertextIsPreservedAndBlocksEvenBlankOrReplacementSaves() = runBlocking {
        val stored = cipher.encrypt("secret", scope)
        keys.available = false
        var writes = 0
        try {
            persistence.readAndMigrate(stored, scope) { writes++ }
            fail("Expected missing key rejection")
        } catch (_: CredentialStorageException) {
            assertEquals(0, writes)
        }
        rejected { persistence.forSave("", stored, scope) }
        rejected { persistence.forSave("replacement", stored, scope) }
        keys.available = true
        assertEquals("secret", cipher.decrypt(stored, scope))
    }

    @Test
    fun saveAlwaysEncryptsIncludingAfterPlaintextMigration() {
        val saved = persistence.forSave("replacement", "old-plaintext", scope)
        val resaved = persistence.forSave("replacement", saved, scope)
        assertTrue(persistence.isEncrypted(saved))
        assertNotEquals(saved, resaved)
        assertEquals("replacement", cipher.decrypt(resaved, scope))
    }

    @Test
    fun ciphertextCannotAccidentallyBeSavedAsRuntimeKey() {
        val stored = cipher.encrypt("secret", scope)
        rejected { persistence.forSave(stored, stored, scope) }
    }

    @Test
    fun errorMessagesDoNotExposeCredentialValues() {
        try {
            cipher.decrypt("private-input", scope)
            fail("Expected invalid ciphertext rejection")
        } catch (failure: CredentialStorageException) {
            assertFalse(failure.message.orEmpty().contains("private-input"))
            assertEquals(null, failure.cause)
        }
    }

    private fun rejected(block: () -> Unit) {
        try {
            block()
            fail("Expected credential rejection")
        } catch (_: CredentialStorageException) {
            Unit
        }
    }
}
