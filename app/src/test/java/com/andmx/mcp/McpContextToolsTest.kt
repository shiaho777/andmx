package com.andmx.mcp

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.andmx.agent.ToolRisk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class McpContextToolsTest {

    @Test
    fun exposesFourReadOnlyContextTools() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tools = McpContextTools.build(McpManager(context))

        assertEquals(
            listOf("mcp_list_resources", "mcp_read_resource", "mcp_list_prompts", "mcp_get_prompt"),
            tools.map { it.name },
        )
        assertTrue(tools.all { it.risk == ToolRisk.READ })
    }

    @Test
    fun unconnectedManagerReturnsHelpfulMessages() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = McpManager(context)
        val tools = McpContextTools.build(manager).associateBy { it.name }

        val resources = tools.getValue("mcp_list_resources").execute(buildJsonObject {})
        assertTrue(resources.output.contains("没有已连接"))

        val prompts = tools.getValue("mcp_list_prompts").execute(buildJsonObject {})
        assertTrue(prompts.output.contains("没有已连接"))

        val read = tools.getValue("mcp_read_resource").execute(
            buildJsonObject {
                put("server", "demo")
                put("uri", "file:///x")
            },
        )
        assertTrue(read.isError)
        assertTrue(read.output.contains("未连接"))
    }
}
