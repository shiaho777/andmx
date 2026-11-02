package com.andmx.agent.plugins

import org.junit.Assert.assertEquals
import org.junit.Test

class PluginVersionTest {
    @Test
    fun newerSemverIsAnUpdate() {
        assertEquals(
            PluginVersion.Status.UPDATE_AVAILABLE,
            PluginVersion.compare("1.2.0", "1.3.0"),
        )
    }

    @Test
    fun equalOrOlderIsNone() {
        assertEquals(PluginVersion.Status.NONE, PluginVersion.compare("1.3.0", "1.3.0"))
        assertEquals(PluginVersion.Status.NONE, PluginVersion.compare("2.0.0", "1.9.0"))
    }

    @Test
    fun unparseableDifferenceIsVersionChanged() {
        assertEquals(PluginVersion.Status.VERSION_CHANGED, PluginVersion.compare("main", "dev"))
    }

    @Test
    fun shaDifferenceIsAnUpdateWhenVersionMissing() {
        assertEquals(
            PluginVersion.Status.UPDATE_AVAILABLE,
            PluginVersion.compareUpdate(null, "aaa", null, "bbb"),
        )
        assertEquals(
            PluginVersion.Status.NONE,
            PluginVersion.compareUpdate(null, "aaa", null, "aaa"),
        )
    }
}
