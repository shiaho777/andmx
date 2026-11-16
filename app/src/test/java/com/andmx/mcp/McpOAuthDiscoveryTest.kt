package com.andmx.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpOAuthDiscoveryTest {

    @Test
    fun rootEndpointCandidatesStartWithWellKnown() {
        val urls = McpOAuthDiscovery.candidateUrls("https://mcp.example/mcp")
        assertEquals("https://mcp.example/.well-known/oauth-authorization-server", urls.first())
        assertTrue(urls.contains("https://mcp.example/.well-known/oauth-authorization-server/mcp"))
        assertTrue(urls.contains("https://mcp.example/mcp/.well-known/oauth-authorization-server"))
        assertTrue(urls.last() == "https://mcp.example/.well-known/openid-configuration")
        assertEquals(urls.distinct(), urls)
    }

    @Test
    fun bareOriginHasNoPathVariants() {
        val urls = McpOAuthDiscovery.candidateUrls("https://mcp.example")
        assertEquals(
            listOf(
                "https://mcp.example/.well-known/oauth-authorization-server",
                "https://mcp.example/.well-known/openid-configuration",
            ),
            urls,
        )
    }

    @Test
    fun nonHttpEndpointsYieldNoCandidates() {
        assertTrue(McpOAuthDiscovery.candidateUrls("npx some-server").isEmpty())
        assertTrue(McpOAuthDiscovery.candidateUrls("ws://mcp.example").isEmpty())
        assertTrue(McpOAuthDiscovery.candidateUrls("").isEmpty())
        assertNull(McpOAuthDiscovery.defaultMetadata("npx some-server"))
    }

    @Test
    fun resolveUsesFirstSuccessfulCandidate() {
        val tried = mutableListOf<String>()
        val metadata = McpOAuthDiscovery.resolve("https://mcp.example/mcp", fetch = { url ->
            tried += url
            if (url == "https://mcp.example/.well-known/oauth-authorization-server") {
                """{"authorization_endpoint":"https://auth.example/authorize","token_endpoint":"https://auth.example/token","registration_endpoint":"https://auth.example/register"}"""
            } else {
                null
            }
        })!!
        assertEquals(1, tried.size)
        assertEquals("https://auth.example/register", metadata.registrationEndpoint)
        assertEquals("https://auth.example/token", metadata.tokenEndpoint)
    }

    @Test
    fun notFoundFallsThroughToDefaults() {
        val tried = mutableListOf<String>()
        val metadata = McpOAuthDiscovery.resolve("https://mcp.example/mcp", fetch = { url ->
            tried += url
            null
        })!!
        assertEquals(McpOAuthDiscovery.candidateUrls("https://mcp.example/mcp"), tried)
        assertEquals("https://mcp.example/authorize", metadata.authorizationEndpoint)
        assertEquals("https://mcp.example/token", metadata.tokenEndpoint)
        assertEquals("", metadata.registrationEndpoint)
    }

    @Test
    fun invalidBodiesAreSkippedAndDefaultsCanBeDisabled() {
        val metadata = McpOAuthDiscovery.resolve(
            "https://mcp.example/mcp",
            { "<html>404</html>" },
            allowDefaults = false,
        )
        assertNull(metadata)

        val throwing = McpOAuthDiscovery.resolve(
            "https://mcp.example/mcp",
            { error("network down") },
            allowDefaults = false,
        )
        assertNull(throwing)
    }

    @Test
    fun parseRequiresAtLeastOneEndpoint() {
        assertNull(McpOAuthDiscovery.parseMetadata("""{"issuer":"https://mcp.example"}"""))
        val registrationOnly = McpOAuthDiscovery.parseMetadata(
            """{"registration_endpoint":"https://mcp.example/register"}""",
        )!!
        assertEquals("https://mcp.example/register", registrationOnly.registrationEndpoint)
    }
}