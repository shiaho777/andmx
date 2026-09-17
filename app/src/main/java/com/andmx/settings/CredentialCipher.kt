package com.andmx.settings

import java.security.GeneralSecurityException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class CredentialStorageException : GeneralSecurityException(
    "The saved API key could not be accessed securely. The stored key has been preserved."
)

interface CredentialCipher {
    fun encrypt(plaintext: String, scope: String): String
    fun decrypt(ciphertext: String, scope: String): String
}

interface CredentialKeySource {
    fun encryptionKey(): SecretKey
    fun decryptionKey(): SecretKey
}

class AesGcmCredentialCipher(private val keys: CredentialKeySource) : CredentialCipher {
    override fun encrypt(plaintext: String, scope: String): String = securely {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keys.encryptionKey())
        cipher.updateAAD(aad(scope))
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        require(cipher.iv.size == NONCE_BYTES)
        VERSION_PREFIX + Base64.getEncoder().encodeToString(cipher.iv + encrypted)
    }

    override fun decrypt(ciphertext: String, scope: String): String = securely {
        require(ciphertext.startsWith(VERSION_PREFIX))
        val payload = Base64.getDecoder().decode(ciphertext.removePrefix(VERSION_PREFIX))
        require(payload.size >= NONCE_BYTES + TAG_BITS / 8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            keys.decryptionKey(),
            GCMParameterSpec(TAG_BITS, payload.copyOfRange(0, NONCE_BYTES)),
        )
        cipher.updateAAD(aad(scope))
        String(cipher.doFinal(payload, NONCE_BYTES, payload.size - NONCE_BYTES), Charsets.UTF_8)
    }

    private fun aad(scope: String): ByteArray = (VERSION_PREFIX + scope).toByteArray(Charsets.UTF_8)

    private inline fun <T> securely(block: () -> T): T = try {
        block()
    } catch (_: Exception) {
        throw CredentialStorageException()
    }

    companion object {
        const val PREFIX = "andmx-credential:"
        const val VERSION_PREFIX = "${PREFIX}v1:"
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
    }
}

internal class CredentialPersistence(private val cipher: CredentialCipher) {
    fun isEncrypted(value: String): Boolean = value.startsWith(AesGcmCredentialCipher.PREFIX)


    suspend fun readAndMigrate(
        stored: String,
        scope: String,
        persist: suspend (String) -> Unit,
    ): String {
        if (isEncrypted(stored)) return cipher.decrypt(stored, scope)
        val encrypted = cipher.encrypt(stored, scope)
        val plaintext = cipher.decrypt(encrypted, scope)
        persist(encrypted)
        return plaintext
    }

    fun decryptStored(stored: String, scope: String): String {
        if (!isEncrypted(stored)) throw CredentialStorageException()
        return cipher.decrypt(stored, scope)
    }

    fun forSave(plaintext: String, previous: String?, scope: String): String {
        if (previous != null && isEncrypted(previous)) cipher.decrypt(previous, scope)
        if (isEncrypted(plaintext)) throw CredentialStorageException()
        return cipher.encrypt(plaintext, scope)
    }

    companion object {
        const val LEGACY_SCOPE = "datastore:andmx_settings:api_key"
        fun providerScope(id: String): String = "room:providers:$id:apiKey"
    }
}
