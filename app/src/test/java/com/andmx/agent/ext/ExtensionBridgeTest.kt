package com.andmx.agent.ext

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.andmx.settings.CredentialCipher
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ExtensionBridgeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val json = Json { ignoreUnknownKeys = true }
    private val pluginId = "demo-plugin"

    private val fakeCipher = object : CredentialCipher {
        override fun encrypt(plaintext: String, scope: String): String = "enc[$scope]{$plaintext}"
        override fun decrypt(ciphertext: String, scope: String): String =
            ciphertext.removePrefix("enc[$scope]{").removeSuffix("}")
    }

    private lateinit var secrets: ExtensionSecrets
    private lateinit var storage: ExtensionStorage
    private lateinit var bridge: ExtensionBridge

    @Before
    fun setUp() {
        ExtensionSlotRegistry.clear()
        secrets = ExtensionSecrets(
            context.getSharedPreferences("ext_bridge_test_secrets_${System.nanoTime()}", Context.MODE_PRIVATE),
            fakeCipher,
        )
        storage = ExtensionStorage(
            context.getSharedPreferences("ext_bridge_test_storage_${System.nanoTime()}", Context.MODE_PRIVATE),
        )
        bridge = ExtensionBridge(pluginId, secrets, storage)
    }

    private suspend fun call(method: String, params: JsonObject = JsonObject(emptyMap()), id: Int = 1): JsonObject? {
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        val raw = bridge.handleMessage(json.encodeToString(JsonObject.serializer(), request)) ?: return null
        return json.parseToJsonElement(raw).jsonObject
    }

    private fun errorCode(response: JsonObject?): Int? =
        response?.get("error")?.jsonObject?.get("code")?.jsonPrimitive?.contentOrNull?.toIntOrNull()

    @Test
    fun unregisteredMethodIsRejectedWith32601() = runBlocking {
        val response = call("ext.doesNotExist")
        assertEquals(-32601, errorCode(response))
        assertEquals("2.0", response!!["jsonrpc"]!!.jsonPrimitive.content)
        assertEquals("1", response["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun fileUrlArgumentIsRejectedAndLogged() = runBlocking {
        val params = buildJsonObject { put("key", "file:///etc/passwd") }
        assertEquals(-32001, errorCode(call("ext.storage.get", params)))
        assertNull(storage.get(pluginId, "file:///etc/passwd"))
    }

    @Test
    fun absolutePathArgumentIsRejected() = runBlocking {
        val params = buildJsonObject { put("key", "k"); put("value", "/root/.ssh/id_rsa") }
        assertEquals(-32001, errorCode(call("ext.storage.set", params)))
        val traversal = buildJsonObject { put("key", "k"); put("value", "../../secret") }
        assertEquals(-32001, errorCode(call("ext.storage.set", traversal)))
    }

    @Test
    fun networkUrlArgumentIsRejected() = runBlocking {
        val params = buildJsonObject { put("url", "https://evil.example/steal") }
        assertEquals(-32001, errorCode(call("ext.storage.set", params)))
        val ws = buildJsonObject { put("key", "k"); put("value", "wss://evil.example") }
        assertEquals(-32001, errorCode(call("ext.storage.set", ws)))
    }

    @Test
    fun oversizedMessageIsRejected() = runBlocking {
        val huge = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ext.log\",\"params\":{\"message\":\"" +
            "a".repeat(ExtensionBridge.MAX_MESSAGE_BYTES) + "\"}}"
        val raw = bridge.handleMessage(huge)!!
        val obj = json.parseToJsonElement(raw).jsonObject
        assertEquals(-32600, errorCode(obj))
        assertTrue(obj["error"]!!.jsonObject["message"]!!.jsonPrimitive.content.contains("1MB"))
    }

    @Test
    fun malformedJsonReturnsParseError() = runBlocking {
        val raw = bridge.handleMessage("{not json")!!
        assertEquals(-32700, errorCode(json.parseToJsonElement(raw).jsonObject))
    }

    @Test
    fun missingVersionReturnsInvalidRequest() = runBlocking {
        val raw = bridge.handleMessage("{\"id\":1,\"method\":\"ext.log\"}")!!
        assertEquals(-32600, errorCode(json.parseToJsonElement(raw).jsonObject))
    }

    @Test
    fun notificationProducesNoResponse() = runBlocking {
        val raw = bridge.handleMessage("{\"jsonrpc\":\"2.0\",\"method\":\"ext.ready\"}")
        assertNull(raw)
    }

    @Test
    fun jsonRpcRoundTripPreservesIdAndResult() = runBlocking {
        val response = call("ext.ready")!!
        assertEquals("2.0", response["jsonrpc"]!!.jsonPrimitive.content)
        assertEquals("1", response["id"]!!.jsonPrimitive.content)
        assertTrue(response["result"]!!.jsonPrimitive.booleanOrNull == true)
        assertNull(response["error"])
        val request = json.parseToJsonElement(
            bridge.successResponse(response["id"]!!, response["result"]!!),
        ).jsonObject
        assertEquals(response["id"], request["id"])
        assertEquals(response["result"], request["result"])
    }

    @Test
    fun getSecretReturnsHandleWithoutPlaintext() = runBlocking {
        val secret = "sk-super-secret-token-123"
        val setParams = buildJsonObject { put("key", "api"); put("value", secret) }
        val setResponse = call("ext.setSecret", setParams)!!
        val handle = setResponse["result"]!!.jsonObject["handle"]!!.jsonPrimitive.content
        assertTrue(handle.startsWith("sec_"))
        assertFalse(setResponse.toString().contains(secret))

        val getResponse = call("ext.getSecret", buildJsonObject { put("key", "api") })!!
        val result = getResponse["result"]!!.jsonObject
        assertEquals(handle, result["handle"]!!.jsonPrimitive.content)
        assertTrue(result["exists"]!!.jsonPrimitive.booleanOrNull == true)
        assertFalse(getResponse.toString().contains(secret))
        assertEquals(secret, secrets.decryptForHost(pluginId, handle))
    }

    @Test
    fun getSecretForMissingKeyReportsExistsFalse() = runBlocking {
        val response = call("ext.getSecret", buildJsonObject { put("key", "absent") })!!
        val result = response["result"]!!.jsonObject
        assertFalse(result["exists"]!!.jsonPrimitive.booleanOrNull == true)
        assertTrue(result["handle"]!!.jsonPrimitive.content.startsWith("sec_"))
    }

    @Test
    fun callWithSecretRejectsUnknownHandle() = runBlocking {
        val params = buildJsonObject { put("handle", "sec_deadbeef"); put("method", "POST") }
        assertEquals(-32602, errorCode(call("ext.callWithSecret", params)))
    }

    @Test
    fun callWithSecretIsReservedWithoutInvoker() = runBlocking {
        call("ext.setSecret", buildJsonObject { put("key", "api"); put("value", "token") })
        val handle = secrets.handle(pluginId, "api")
        val params = buildJsonObject { put("handle", handle); put("method", "POST") }
        assertEquals(-32601, errorCode(call("ext.callWithSecret", params)))
    }

    @Test
    fun callWithSecretUsesHostInvokerWithoutExposingPlaintext() = runBlocking {
        val secret = "host-only-secret"
        call("ext.setSecret", buildJsonObject { put("key", "api"); put("value", secret) })
        val handle = secrets.handle(pluginId, "api")
        var decrypted: String? = null
        val withInvoker = ExtensionBridge(
            pluginId,
            secrets,
            storage,
            secretInvoker = ExtensionBridge.SecretInvoker { pid, h, _ ->
                decrypted = secrets.decryptForHost(pid, h)
                buildJsonObject { put("status", 200) }
            },
        )
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 9)
            put("method", "ext.callWithSecret")
            put("params", buildJsonObject { put("handle", handle); put("method", "POST") })
        }
        val raw = withInvoker.handleMessage(json.encodeToString(JsonObject.serializer(), request))!!
        assertEquals(secret, decrypted)
        assertFalse(raw.contains(secret))
        assertEquals("200", json.parseToJsonElement(raw).jsonObject["result"]!!.jsonObject["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun registerSlotPublishesDeclaration() = runBlocking {
        val params = buildJsonObject {
            put("slotId", ExtensionSlotRegistry.COMPOSER_STATUS)
            put("title", "Demo Status")
            put("htmlAsset", "assets/status.html")
        }
        val response = call("ext.ui.registerSlot", params)!!
        assertTrue(response["result"]!!.jsonObject["registered"]!!.jsonPrimitive.booleanOrNull == true)
        val declared = ExtensionSlotRegistry.forSlot(ExtensionSlotRegistry.COMPOSER_STATUS)
        assertEquals(1, declared.size)
        assertEquals(SlotDeclaration(pluginId, "composer.status", "Demo Status", "assets/status.html"), declared[0])
    }

    @Test
    fun registerSlotRejectsUnknownSlotAndUnsafeAsset() = runBlocking {
        val unknown = buildJsonObject {
            put("slotId", "toolbar.hack")
            put("title", "x")
            put("htmlAsset", "assets/x.html")
        }
        assertEquals(-32602, errorCode(call("ext.ui.registerSlot", unknown)))
        val unsafe = buildJsonObject {
            put("slotId", ExtensionSlotRegistry.NAV_PRIMARY)
            put("title", "x")
            put("htmlAsset", "../../etc/passwd")
        }
        assertEquals(-32001, errorCode(call("ext.ui.registerSlot", unsafe)))
        assertTrue(ExtensionSlotRegistry.declarations.value.isEmpty())
    }

    @Test
    fun storageRoundTripIsScopedPerPlugin() = runBlocking {
        val setParams = buildJsonObject { put("key", "counter"); put("value", "42") }
        assertTrue(call("ext.storage.set", setParams)!!["result"]!!.jsonPrimitive.booleanOrNull == true)
        val got = call("ext.storage.get", buildJsonObject { put("key", "counter") })!!
        assertEquals("42", got["result"]!!.jsonPrimitive.content)

        val other = ExtensionBridge("other-plugin", secrets, storage)
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 2)
            put("method", "ext.storage.get")
            put("params", buildJsonObject { put("key", "counter") })
        }
        val raw = other.handleMessage(json.encodeToString(JsonObject.serializer(), request))!!
        assertEquals("null", json.parseToJsonElement(raw).jsonObject["result"].toString())
    }

    @Test
    fun secretScopesAreIsolatedBetweenPlugins() {
        secrets.set(pluginId, "api", "value-a")
        secrets.set("other-plugin", "api", "value-b")
        assertEquals("value-a", secrets.decryptForHost(pluginId, secrets.handle(pluginId, "api")))
        assertEquals("value-b", secrets.decryptForHost("other-plugin", secrets.handle("other-plugin", "api")))
        assertNotEquals(secrets.handle(pluginId, "api"), secrets.handle("other-plugin", "api"))
        assertNull(secrets.decryptForHost("other-plugin", secrets.handle(pluginId, "api")))
    }

    @Test
    fun oversizedStorageValueIsRejected() = runBlocking {
        val params = buildJsonObject {
            put("key", "blob")
            put("value", "x".repeat(ExtensionBridge.MAX_STORAGE_VALUE_BYTES + 1))
        }
        assertEquals(-32602, errorCode(call("ext.storage.set", params)))
    }

    @Test
    fun opaqueSecretValueMayContainUrls() = runBlocking {
        val response = call(
            "ext.setSecret",
            buildJsonObject { put("key", "endpoint"); put("value", "https://api.example/token?key=abc") },
        )
        assertNull(response!!["error"])
    }
}
