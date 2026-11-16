package com.andmx.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

object McpOAuthDiscovery {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class Metadata(
        val issuer: String = "",
        @kotlinx.serialization.SerialName("authorization_endpoint") val authorizationEndpoint: String = "",
        @kotlinx.serialization.SerialName("token_endpoint") val tokenEndpoint: String = "",
        @kotlinx.serialization.SerialName("registration_endpoint") val registrationEndpoint: String = "",
        @kotlinx.serialization.SerialName("scopes_supported") val scopesSupported: List<String> = emptyList(),
    )

    fun candidateUrls(endpoint: String): List<String> {
        val uri = runCatching { URI(endpoint.trim()) }.getOrNull() ?: return emptyList()
        val scheme = uri.scheme?.lowercase() ?: return emptyList()
        if (scheme != "http" && scheme != "https") return emptyList()
        val authority = uri.rawAuthority ?: return emptyList()
        val origin = "$scheme://$authority"
        val path = uri.rawPath.orEmpty().trimEnd('/')
        val urls = mutableListOf("$origin/.well-known/oauth-authorization-server")
        if (path.isNotEmpty() && path != "/") {
            urls += "$origin/.well-known/oauth-authorization-server$path"
            urls += "$origin$path/.well-known/oauth-authorization-server"
        }
        urls += "$origin/.well-known/openid-configuration"
        return urls.distinct()
    }

    fun defaultMetadata(endpoint: String): Metadata? {
        val uri = runCatching { URI(endpoint.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val authority = uri.rawAuthority ?: return null
        val origin = "$scheme://$authority"
        return Metadata(
            issuer = origin,
            authorizationEndpoint = "$origin/authorize",
            tokenEndpoint = "$origin/token",
        )
    }

    fun parseMetadata(body: String): Metadata? {
        val metadata = runCatching { json.decodeFromString<Metadata>(body) }.getOrNull() ?: return null
        return metadata.takeIf {
            it.authorizationEndpoint.isNotBlank() || it.tokenEndpoint.isNotBlank() || it.registrationEndpoint.isNotBlank()
        }
    }

    fun resolve(
        endpoint: String,
        fetch: (String) -> String?,
        allowDefaults: Boolean = true,
    ): Metadata? {
        for (url in candidateUrls(endpoint)) {
            val body = runCatching { fetch(url) }.getOrNull() ?: continue
            val parsed = parseMetadata(body) ?: continue
            return parsed
        }
        return if (allowDefaults) defaultMetadata(endpoint) else null
    }

    suspend fun fetchMetadata(endpoint: String): Metadata? = withContext(Dispatchers.IO) {
        resolve(endpoint, ::fetch)
    }

    private fun fetch(url: String): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "application/json")
        val code = connection.responseCode
        if (code !in 200..299) {
            connection.errorStream?.close()
            return null
        }
        return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}