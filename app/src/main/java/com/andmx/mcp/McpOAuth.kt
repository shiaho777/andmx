package com.andmx.mcp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * MCP OAuth support for token-bearing HTTP servers.
 *
 * Browser authorization is not implemented on mobile yet, but pre-provisioned
 * access tokens and refresh-token / client-credentials exchanges are. Refreshed
 * tokens are kept per server so they survive settings reloads and process death.
 */
data class McpOAuthConfig(
    val accessToken: String = "",
    val refreshToken: String = "",
    val tokenUrl: String = "",
    val clientId: String = "",
    val clientSecret: String = "",
    val scopes: List<String> = emptyList(),
    val expiresAtMs: Long = 0L,
    val tokenType: String = "Bearer",
) {
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean =
        accessToken.isNotBlank() && expiresAtMs > 0L && now >= expiresAtMs - 60_000L

    fun toJson(): String = buildJsonObject {
        if (accessToken.isNotBlank()) put("accessToken", accessToken)
        if (refreshToken.isNotBlank()) put("refreshToken", refreshToken)
        if (tokenUrl.isNotBlank()) put("tokenUrl", tokenUrl)
        if (clientId.isNotBlank()) put("clientId", clientId)
        if (clientSecret.isNotBlank()) put("clientSecret", clientSecret)
        if (scopes.isNotEmpty()) put("scopes", JsonArray(scopes.map { JsonPrimitive(it) }))
        if (expiresAtMs > 0L) put("expiresAtMs", expiresAtMs)
        if (tokenType.isNotBlank()) put("tokenType", tokenType)
    }.toString()

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(raw: String): McpOAuthConfig? {
            if (raw.isBlank()) return null
            val obj = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
            val nested = obj["oauth"] as? JsonObject
            val body = nested ?: obj
            val now = System.currentTimeMillis()
            val explicitExpiry = body.long("expiresAtMs") ?: body.long("expiresAt")?.let {
                if (it < 10_000_000_000L) it * 1000L else it
            }
            val expiresIn = body.long("expiresIn") ?: body.long("expires_in")
            val expiresAt = explicitExpiry ?: expiresIn?.let { now + it * 1000L } ?: 0L
            val config = McpOAuthConfig(
                accessToken = body.string("accessToken", "access_token"),
                refreshToken = body.string("refreshToken", "refresh_token"),
                tokenUrl = body.string("tokenUrl", "token_url", "tokenEndpoint", "token_endpoint"),
                clientId = body.string("clientId", "client_id"),
                clientSecret = body.string("clientSecret", "client_secret"),
                scopes = body.scopes(),
                expiresAtMs = expiresAt,
                tokenType = body.string("tokenType", "token_type").ifBlank { "Bearer" },
            )
            return config.takeIf {
                it.accessToken.isNotBlank() || it.refreshToken.isNotBlank() || it.tokenUrl.isNotBlank()
            }
        }

        private fun JsonObject.string(vararg keys: String): String {
            keys.forEach { key ->
                val text = (this[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                if (text.isNotBlank()) return text
            }
            return ""
        }

        private fun JsonObject.long(key: String): Long? =
            (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

        private fun JsonObject.scopes(): List<String> {
            (this["scopes"] as? JsonArray)?.let { arr ->
                return arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    .filter { it.isNotBlank() }
            }
            val raw = (this["scope"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            return raw.split(Regex("[\\s,]+")).filter { it.isNotBlank() }
        }
    }
}

/**
 * Token cache identity. Tokens are pinned to the server endpoint as well as the
 * name, so renaming/repointing a server never reuses credentials on a new host.
 */
object McpOAuthKeys {
    fun of(name: String, endpoint: String): String = name.trim() + "\u0000" + endpoint.trim()
    fun of(server: McpServerConfig): String = of(server.name, server.command)
}

/** Per-server token persistence, separate from the user's MCP config text. */
class McpOAuthStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("andmx_mcp_oauth", Context.MODE_PRIVATE)

    fun load(serverName: String): McpOAuthConfig? {
        val raw = prefs.getString(key(serverName), null) ?: return null
        return McpOAuthConfig.parse(raw)
    }

    fun save(serverName: String, config: McpOAuthConfig) {
        prefs.edit().putString(key(serverName), config.toJson()).apply()
    }

    fun clear(serverName: String) {
        prefs.edit().remove(key(serverName)).apply()
    }

    @androidx.annotation.VisibleForTesting
    fun clearAll() {
        prefs.edit().clear().apply()
    }

    private fun key(serverName: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(serverName.toByteArray(Charsets.UTF_8))
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
            .take(32)
    }
}

class McpOAuth(context: Context) {
    private val store = McpOAuthStore(context)

    /** Test seam; production uses the real HTTP POST below. */
    @androidx.annotation.VisibleForTesting
    internal var poster: ((String, String) -> String?)? = null

    suspend fun accessToken(server: McpServerConfig): String? = withContext(Dispatchers.IO) {
        val configured = McpOAuthConfig.parse(server.oauthJson) ?: return@withContext null
        val identity = McpOAuthKeys.of(server)
        val stored = store.load(identity)
        val merged = merge(configured, stored)
        if (merged.accessToken.isNotBlank() && !merged.isExpired()) {
            return@withContext merged.accessToken
        }
        val refreshed = refresh(merged) ?: return@withContext null
        store.save(identity, refreshed)
        refreshed.accessToken.takeIf { it.isNotBlank() }
    }

    fun clear(server: McpServerConfig) {
        store.clear(McpOAuthKeys.of(server))
    }

    private fun merge(configured: McpOAuthConfig, stored: McpOAuthConfig?): McpOAuthConfig {
        if (stored == null) return configured
        return configured.copy(
            accessToken = configured.accessToken.ifBlank { stored.accessToken },
            refreshToken = configured.refreshToken.ifBlank { stored.refreshToken },
            tokenUrl = configured.tokenUrl.ifBlank { stored.tokenUrl },
            clientId = configured.clientId.ifBlank { stored.clientId },
            clientSecret = configured.clientSecret.ifBlank { stored.clientSecret },
            scopes = configured.scopes.ifEmpty { stored.scopes },
            expiresAtMs = if (configured.accessToken.isNotBlank()) {
                configured.expiresAtMs
            } else {
                stored.expiresAtMs
            },
            tokenType = configured.tokenType.ifBlank { stored.tokenType },
        )
    }

    private fun refresh(config: McpOAuthConfig): McpOAuthConfig? {
        if (config.tokenUrl.isBlank()) return null
        val grant = when {
            config.refreshToken.isNotBlank() -> "refresh_token"
            config.clientId.isNotBlank() && config.clientSecret.isNotBlank() -> "client_credentials"
            else -> return null
        }
        val form = buildList {
            add("grant_type" to grant)
            if (grant == "refresh_token") add("refresh_token" to config.refreshToken)
            if (config.clientId.isNotBlank()) add("client_id" to config.clientId)
            if (config.clientSecret.isNotBlank()) add("client_secret" to config.clientSecret)
            if (config.scopes.isNotEmpty()) add("scope" to config.scopes.joinToString(" "))
        }.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }
        val body = runCatching {
            (poster ?: ::defaultPost)(config.tokenUrl, form)
        }.getOrNull() ?: return null
        return parseTokenResponse(body, config)
    }

    private fun defaultPost(url: String, form: String): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 30_000
        connection.readTimeout = 30_000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        connection.setRequestProperty("Accept", "application/json")
        OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(form) }
        val code = connection.responseCode
        if (code !in 200..299) {
            connection.errorStream?.close()
            return null
        }
        return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun parseTokenResponse(body: String, config: McpOAuthConfig): McpOAuthConfig? {
        val objective = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        val access = objective?.string("access_token") ?: formValue(body, "access_token")
        if (access.isNullOrBlank()) return null
        val refresh = objective?.string("refresh_token") ?: formValue(body, "refresh_token")
        val expiresIn = objective?.string("expires_in")?.toLongOrNull()
            ?: formValue(body, "expires_in")?.toLongOrNull()
        val tokenType = objective?.string("token_type") ?: formValue(body, "token_type")
        return config.copy(
            accessToken = access,
            refreshToken = refresh?.takeIf { it.isNotBlank() } ?: config.refreshToken,
            expiresAtMs = expiresIn?.let { System.currentTimeMillis() + it * 1000L } ?: 0L,
            tokenType = tokenType?.takeIf { it.isNotBlank() } ?: config.tokenType,
        )
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun formValue(body: String, key: String): String? =
        body.split('&').firstOrNull { it.substringBefore('=') == key }
            ?.substringAfter('=', "")
            ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
            ?.takeIf { it.isNotBlank() }
}
