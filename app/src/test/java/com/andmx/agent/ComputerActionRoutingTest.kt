package com.andmx.agent

import com.andmx.computeruse.isClearAllNode
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComputerActionRoutingTest {
    @Test
    fun routesAllSupportedActions() {
        assertEquals(ComputerAction.SCREENSHOT, routeComputerAction("screenshot"))
        assertEquals(ComputerAction.CLICK, routeComputerAction("click"))
        assertEquals(ComputerAction.LONG_PRESS, routeComputerAction("long_press"))
        assertEquals(ComputerAction.DOUBLE_TAP, routeComputerAction("double_tap"))
        assertEquals(ComputerAction.TYPE, routeComputerAction("type"))
        assertEquals(ComputerAction.SCROLL, routeComputerAction("scroll"))
        assertEquals(ComputerAction.SWIPE, routeComputerAction("swipe"))
        assertEquals(ComputerAction.KEY, routeComputerAction("key"))
        assertEquals(ComputerAction.WAIT, routeComputerAction("wait"))
    }

    @Test
    fun routingNormalizesCaseAndWhitespace() {
        assertEquals(ComputerAction.LONG_PRESS, routeComputerAction(" Long_Press "))
        assertEquals(ComputerAction.DOUBLE_TAP, routeComputerAction("DOUBLE_TAP"))
        assertNull(routeComputerAction("triple_tap"))
        assertNull(routeComputerAction(""))
    }

    @Test
    fun schemaEnumMatchesRouting() {
        val schema = computerUseParameters()
        val props = schema["properties"]!!.jsonObject
        val enum = props["action"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(
            listOf("screenshot", "click", "long_press", "double_tap", "type", "scroll", "key", "wait", "swipe"),
            enum,
        )
        enum.forEach { assertTrue(it, routeComputerAction(it) != null) }
        val required = schema["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("action"), required)
        assertTrue(props.containsKey("coordinate"))
        assertTrue(props.containsKey("duration"))
    }

    @Test
    fun clearAllNodeMatchesKnownLabelsAndIds() {
        assertTrue(isClearAllNode("清除全部", "", ""))
        assertTrue(isClearAllNode("", "Clear all notifications", ""))
        assertTrue(isClearAllNode("", "", "com.android.systemui:id/dismiss_all"))
        assertTrue(isClearAllNode("全部清除", "", ""))
        assertTrue(isClearAllNode("", "", "com.android.systemui:id/clear_all"))
    }

    @Test
    fun clearAllNodeRejectsUnrelatedNodes() {
        assertFalse(isClearAllNode("设置", "", "com.android.systemui:id/settings"))
        assertFalse(isClearAllNode("清除", "", ""))
        assertFalse(isClearAllNode("", "", ""))
        assertFalse(isClearAllNode("通知", "查看通知", "com.android.systemui:id/notification_stack"))
    }
}