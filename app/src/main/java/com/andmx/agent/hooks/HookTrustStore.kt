package com.andmx.agent.hooks

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest

/**
 * Trust store for workspace-provided hooks (`.zcode/hooks.json`).
 *
 * A workspace can be cloned from anywhere, so its hooks must never run just
 * because the file exists. Trust is pinned to the SHA-256 of the exact file
 * contents: any later edit invalidates the grant and requires a fresh review.
 */
object HookTrustStore {
    private const val PREFS = "andmx_hook_trust"
    private const val KEY_PREFIX = "trusted:"

    @Volatile private var prefs: SharedPreferences? = null

    private val _revision = MutableStateFlow(0L)

    /** Bumped whenever a grant changes; callers use it to invalidate hook caches. */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    fun install(context: Context) {
        if (prefs == null) {
            synchronized(this) {
                if (prefs == null) {
                    prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                }
            }
        }
    }

    fun fingerprint(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    fun trustedHash(workspaceKey: String): String? =
        store()?.getString(KEY_PREFIX + key(workspaceKey), null)

    fun isTrusted(workspaceKey: String, fingerprint: String): Boolean =
        fingerprint.isNotBlank() && trustedHash(workspaceKey) == fingerprint

    fun trust(workspaceKey: String, fingerprint: String) {
        if (workspaceKey.isBlank() || fingerprint.isBlank()) return
        store()?.edit()?.putString(KEY_PREFIX + key(workspaceKey), fingerprint)?.apply()
        _revision.value += 1
    }

    fun revoke(workspaceKey: String) {
        if (workspaceKey.isBlank()) return
        store()?.edit()?.remove(KEY_PREFIX + key(workspaceKey))?.apply()
        _revision.value += 1
    }

    fun clearAll() {
        store()?.edit()?.clear()?.apply()
        _revision.value += 1
    }

    private fun store(): SharedPreferences? = prefs

    /** SharedPreferences keys must be stable and free of path separator surprises. */
    private fun key(workspaceKey: String): String = fingerprint(workspaceKey).take(32)
}
