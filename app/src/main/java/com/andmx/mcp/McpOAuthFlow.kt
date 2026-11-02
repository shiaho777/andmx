package com.andmx.mcp

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.SecureRandom
import android.util.Base64

/**
 * Browser authorization-code + PKCE flow for MCP HTTP servers.
 *
 * Only the redirect and code exchange live here; the authorization page is the
 * user's browser. Pending state is short-lived and single-use.
 */
object McpOAuthFlow {
    const val SCHEME = "andmx"
    const val HOST = "mcp-oauth"
    const val REDIRECT_URI = "$SCHEME://$HOST"
    private const val PREFS = "andmx_mcp_oauth_flow"
    private const val KEY_PENDING = "pending"
    private const val MAX_AGE_MS = 10 * 60 * 1000L
    private val json = Json { ignoreUnknownKeys = true }

    data class Result(val ok: Boolean, val message: String)

    @Serializable
    data class Pending(
        val serverLine: String,
        val verifier: String,
        val state: String,
        val redirectUri: String,
        val createdAt: Long,
    )

    private val _lastResult = MutableStateFlow<Result?>(null)
    val lastResult: StateFlow<Result?> = _lastResult.asStateFlow()

    fun start(context: Context, server: McpServerConfig): Result {
        val config = McpOAuthConfig.parse(server.oauthJson)
            ?: return fail("OAuth 配置不是有效 JSON")
        if (config.authorizationUrl.isBlank()) return fail("OAuth 配置缺少 authorizationUrl")
        if (config.tokenUrl.isBlank()) return fail("OAuth 配置缺少 tokenUrl")
        if (config.clientId.isBlank()) return fail("OAuth 配置缺少 clientId")

        val verifier = randomToken(32)
        val state = randomToken(16)
        val redirectUri = config.redirectUri.ifBlank { REDIRECT_URI }
        val challenge = codeChallenge(verifier)
        val url = McpOAuth(context).authorizationUrl(server, state, challenge, redirectUri)
            ?: return fail("无法生成授权地址")
        val pending = Pending(
            serverLine = server.toLine(),
            verifier = verifier,
            state = state,
            redirectUri = redirectUri,
            createdAt = System.currentTimeMillis(),
        )
        savePending(context, pending)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            Result(true, "已打开浏览器授权")
        }.getOrElse {
            fail("没有可用的浏览器：${it.message ?: it.javaClass.simpleName}")
        }
    }

    suspend fun complete(
        context: Context,
        uri: Uri,
        oauth: McpOAuth = McpOAuth(context),
    ): Result {
        val pending = loadPending(context) ?: return fail("没有待完成的 MCP 授权")
        clearPending(context)
        if (System.currentTimeMillis() - pending.createdAt > MAX_AGE_MS) {
            return fail("授权已过期，请重新发起")
        }
        val error = uri.getQueryParameter("error")
        if (!error.isNullOrBlank()) {
            val description = uri.getQueryParameter("error_description").orEmpty()
            return fail("授权被拒绝：$error${if (description.isBlank()) "" else " · $description"}")
        }
        val returnedState = uri.getQueryParameter("state").orEmpty()
        if (!MessageDigest.isEqual(returnedState.toByteArray(), pending.state.toByteArray())) {
            return fail("授权 state 不匹配，已拒绝")
        }
        val code = uri.getQueryParameter("code").orEmpty()
        if (code.isBlank()) return fail("授权回调缺少 code")
        val server = McpServerConfig.parse(pending.serverLine).firstOrNull()
            ?: return fail("无法恢复 MCP 服务器配置")
        val token = oauth.exchangeCode(server, code, pending.verifier, pending.redirectUri)
            ?: return fail("换取 access token 失败")
        val result = Result(true, "MCP 授权成功，已保存 token")
        _lastResult.value = result
        return result
    }

    fun codeChallenge(verifier: String): String = base64Url(
        MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
    )

    private fun randomToken(bytes: Int): String {
        val raw = ByteArray(bytes)
        SecureRandom().nextBytes(raw)
        return base64Url(raw)
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun fail(message: String): Result {
        val result = Result(false, message)
        _lastResult.value = result
        return result
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @androidx.annotation.VisibleForTesting
    fun savePending(context: Context, pending: Pending) {
        prefs(context).edit().putString(KEY_PENDING, json.encodeToString(pending)).apply()
    }

    @androidx.annotation.VisibleForTesting
    fun loadPending(context: Context): Pending? {
        val raw = prefs(context).getString(KEY_PENDING, null) ?: return null
        return runCatching { json.decodeFromString<Pending>(raw) }.getOrNull()
    }

    private fun clearPending(context: Context) {
        prefs(context).edit().remove(KEY_PENDING).apply()
    }
}
