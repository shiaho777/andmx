package com.andmx.mcp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object McpDynamicRegistration {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Serializable
    data class RegistrationRequest(
        @SerialName("client_name") val clientName: String,
        @SerialName("redirect_uris") val redirectUris: List<String>,
        @SerialName("grant_types") val grantTypes: List<String> = listOf("authorization_code", "refresh_token"),
        @SerialName("response_types") val responseTypes: List<String> = listOf("code"),
        @SerialName("token_endpoint_auth_method") val tokenEndpointAuthMethod: String = "none",
    )

    @Serializable
    data class RegistrationResponse(
        @SerialName("client_id") val clientId: String,
        @SerialName("client_secret") val clientSecret: String? = null,
        @SerialName("client_id_issued_at") val clientIdIssuedAt: Long = 0L,
        @SerialName("client_secret_expires_at") val clientSecretExpiresAt: Long = 0L,
    )

    data class Outcome(val ok: Boolean, val message: String)

    fun encodeRequest(request: RegistrationRequest): String = json.encodeToString(request)

    fun decodeResponse(body: String): RegistrationResponse? {
        val response = runCatching { json.decodeFromString<RegistrationResponse>(body) }.getOrNull()
            ?: return null
        return response.takeIf { it.clientId.isNotBlank() }
    }

    fun applyToConfig(config: McpOAuthConfig, response: RegistrationResponse): McpOAuthConfig =
        config.copy(clientId = response.clientId, clientSecret = response.clientSecret.orEmpty())

    suspend fun register(context: Context, server: McpServerConfig): Outcome {
        if (server.transport == McpTransport.STDIO) return Outcome(false, "仅 HTTP/SSE MCP 服务器支持 OAuth 注册")
        val configured = McpOAuthConfig.parse(server.oauthJson)
        if (configured != null && configured.clientId.isNotBlank()) {
            return Outcome(false, "已配置 clientId，无需动态注册；如需重新注册请先清空 clientId")
        }
        val metadata = McpOAuthDiscovery.fetchMetadata(server.command)
            ?: return Outcome(false, "无法获取服务器 OAuth 元数据：请手动填写 authorizationUrl / tokenUrl / clientId")
        val registrationEndpoint = metadata.registrationEndpoint
        if (registrationEndpoint.isBlank()) {
            return Outcome(false, "服务器不支持动态客户端注册（缺少 registration_endpoint）：请手动填写 clientId")
        }
        val redirectUri = configured?.redirectUri?.takeIf { it.isNotBlank() } ?: McpOAuthFlow.REDIRECT_URI
        val request = RegistrationRequest(
            clientName = server.name.ifBlank { "andmx" },
            redirectUris = listOf(redirectUri),
        )
        val body = withContext(Dispatchers.IO) {
            runCatching { postJson(registrationEndpoint, encodeRequest(request)) }.getOrNull()
        } ?: return Outcome(false, "动态客户端注册失败：服务器未响应注册请求")
        val response = decodeResponse(body)
            ?: return Outcome(false, "动态客户端注册失败：响应缺少 client_id")
        val base = (configured ?: McpOAuthConfig()).copy(
            authorizationUrl = configured?.authorizationUrl?.takeIf { it.isNotBlank() } ?: metadata.authorizationEndpoint,
            tokenUrl = configured?.tokenUrl?.takeIf { it.isNotBlank() } ?: metadata.tokenEndpoint,
            scopes = configured?.scopes?.takeIf { it.isNotEmpty() } ?: metadata.scopesSupported,
            redirectUri = redirectUri,
        )
        McpOAuth(context).saveRegisteredClient(server, applyToConfig(base, response))
        return Outcome(true, "动态注册成功，client_id 已存入凭据存储；可直接发起浏览器授权")
    }

    private fun postJson(url: String, body: String): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 30_000
        connection.readTimeout = 30_000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "application/json")
        OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body) }
        val code = connection.responseCode
        if (code !in 200..299) {
            connection.errorStream?.close()
            return null
        }
        return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}