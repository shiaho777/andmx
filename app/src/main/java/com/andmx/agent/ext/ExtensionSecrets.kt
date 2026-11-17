package com.andmx.agent.ext

import android.content.Context
import android.content.SharedPreferences
import com.andmx.settings.AesGcmCredentialCipher
import com.andmx.settings.AndroidKeystoreCredentialKeys
import com.andmx.settings.CredentialCipher
import java.security.MessageDigest

class ExtensionSecrets(
    private val prefs: SharedPreferences,
    private val cipher: CredentialCipher = AesGcmCredentialCipher(AndroidKeystoreCredentialKeys()),
) {
    fun scope(pluginId: String, key: String): String = "plugin:$pluginId:$key"

    fun handle(pluginId: String, key: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(scope(pluginId, key).toByteArray(Charsets.UTF_8))
        return "sec_" + digest.take(8).joinToString("") { b -> (b.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    fun set(pluginId: String, key: String, plaintext: String) {
        val encrypted = cipher.encrypt(plaintext, scope(pluginId, key))
        prefs.edit()
            .putString(valueKey(pluginId, key), encrypted)
            .putString(handleKey(pluginId, key), handle(pluginId, key))
            .apply()
    }

    fun exists(pluginId: String, key: String): Boolean =
        prefs.getString(valueKey(pluginId, key), null) != null

    fun existsHandle(pluginId: String, handle: String): Boolean =
        prefs.all.any { (k, v) -> k.startsWith("$pluginId|h:") && v == handle }

    fun decryptForHost(pluginId: String, handle: String): String? {
        val key = prefs.all.entries.firstOrNull { (k, v) ->
            k.startsWith("$pluginId|h:") && v == handle
        }?.key?.removePrefix("$pluginId|h:") ?: return null
        val stored = prefs.getString(valueKey(pluginId, key), null) ?: return null
        return cipher.decrypt(stored, scope(pluginId, key))
    }

    fun remove(pluginId: String, key: String) {
        prefs.edit()
            .remove(valueKey(pluginId, key))
            .remove(handleKey(pluginId, key))
            .apply()
    }

    private fun valueKey(pluginId: String, key: String) = "$pluginId|v:$key"

    private fun handleKey(pluginId: String, key: String) = "$pluginId|h:$key"

    companion object {
        const val PREFS = "andmx_extension_secrets"

        fun create(context: Context): ExtensionSecrets = ExtensionSecrets(
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
        )
    }
}
