package com.andmx.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpDynamicRegistrationTest {

    @Test
    fun requestEncodesRfc7591Fields() {
        val body = McpDynamicRegistration.encodeRequest(
            McpDynamicRegistration.RegistrationRequest(
                clientName = "andmx",
                redirectUris = listOf("andmx://mcp-oauth"),
            ),
        )
        assertTrue(body.contains("\"client_name\":\"andmx\""))
        assertTrue(body.contains("\"redirect_uris\":[\"andmx://mcp-oauth\"]"))
        assertTrue(body.contains("\"grant_types\":[\"authorization_code\",\"refresh_token\"]"))
        assertTrue(body.contains("\"response_types\":[\"code\"]"))
        assertTrue(body.contains("\"token_endpoint_auth_method\":\"none\""))
        assertFalse(body.contains("clientName"))
    }

    @Test
    fun responseParsesClientIdAndOptionalSecret() {
        val withSecret = McpDynamicRegistration.decodeResponse(
            """{"client_id":"abc","client_secret":"shh","client_secret_expires_at":0,"unknown":true}""",
        )!!
        assertEquals("abc", withSecret.clientId)
        assertEquals("shh", withSecret.clientSecret)

        val publicClient = McpDynamicRegistration.decodeResponse("""{"client_id":"pub"}""")!!
        assertEquals("pub", publicClient.clientId)
        assertNull(publicClient.clientSecret)
    }

    @Test
    fun malformedOrEmptyResponsesAreRejected() {
        assertNull(McpDynamicRegistration.decodeResponse("not json"))
        assertNull(McpDynamicRegistration.decodeResponse("""{"client_secret":"only-secret"}"""))
        assertNull(McpDynamicRegistration.decodeResponse("""{"client_id":""}"""))
        assertNull(McpDynamicRegistration.decodeResponse("""[]"""))
    }

    @Test
    fun applyToConfigKeepsOtherFields() {
        val base = McpOAuthConfig(
            authorizationUrl = "https://auth.example/authorize",
            tokenUrl = "https://auth.example/token",
            scopes = listOf("read"),
        )
        val merged = McpDynamicRegistration.applyToConfig(
            base,
            McpDynamicRegistration.RegistrationResponse(clientId = "id-1", clientSecret = "sec-1"),
        )
        assertEquals("id-1", merged.clientId)
        assertEquals("sec-1", merged.clientSecret)
        assertEquals(base.authorizationUrl, merged.authorizationUrl)
        assertEquals(base.tokenUrl, merged.tokenUrl)
        assertEquals(listOf("read"), merged.scopes)

        val publicMerged = McpDynamicRegistration.applyToConfig(
            base,
            McpDynamicRegistration.RegistrationResponse(clientId = "id-2"),
        )
        assertEquals("id-2", publicMerged.clientId)
        assertEquals("", publicMerged.clientSecret)
    }

    @Test
    fun registeredClientIdSurvivesConfigRoundTrip() {
        val config = McpDynamicRegistration.applyToConfig(
            McpOAuthConfig(tokenUrl = "https://auth.example/token"),
            McpDynamicRegistration.RegistrationResponse(clientId = "id-3", clientSecret = "sec-3"),
        )
        val parsed = McpOAuthConfig.parse(config.toJson())!!
        assertEquals("id-3", parsed.clientId)
        assertEquals("sec-3", parsed.clientSecret)
        assertEquals("https://auth.example/token", parsed.tokenUrl)
    }
}