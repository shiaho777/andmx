package com.andmx.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class AndroidKeystoreCredentialKeys : CredentialKeySource {
    override fun encryptionKey(): SecretKey = synchronized(lock) {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (store.containsAlias(ALIAS)) {
            store.getKey(ALIAS, null) as? SecretKey ?: throw CredentialStorageException()
        } else {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generator.generateKey()
        }
    }

    override fun decryptionKey(): SecretKey = synchronized(lock) {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        store.getKey(ALIAS, null) as? SecretKey ?: throw CredentialStorageException()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "andmx_provider_api_key"
        val lock = Any()
    }
}
