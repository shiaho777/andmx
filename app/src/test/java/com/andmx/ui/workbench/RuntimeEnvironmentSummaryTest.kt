package com.andmx.ui.workbench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeEnvironmentSummaryTest {

    @Test
    fun liteFlavorShowsLimitedAndroidSurface() {
        val summary = buildRuntimeEnvironmentSummary(
            flavor = "lite",
            targetSdk = 34,
            abi = "arm64-v8a",
            prootBundled = false,
            rootfsInstalled = false,
            prootBinExists = false,
            loaderBinExists = false,
            rootfsPath = "/data/rootfs",
            usrPath = "/data/usr",
            tmpPath = "/data/tmp",
        )

        assertEquals(RuntimeEnvironmentLevel.LIMITED, summary.level)
        assertEquals("轻量执行面", summary.healthLabel)
        assertEquals("Android lite · lite", summary.executionSurface)
        assertEquals("不适用", summary.rootfsStatus)
        assertEquals("不适用", summary.binaryStatus)
        assertTrue(summary.healthDetail.contains("未捆绑 proot"))
    }

    @Test
    fun completeProotInstallShowsReadyLinuxSandbox() {
        val summary = buildRuntimeEnvironmentSummary(
            flavor = "proot",
            targetSdk = 28,
            abi = "arm64-v8a",
            prootBundled = true,
            rootfsInstalled = true,
            prootBinExists = true,
            loaderBinExists = true,
            rootfsPath = "/data/rootfs",
            usrPath = "/data/usr",
            tmpPath = "/data/tmp",
        )

        assertEquals(RuntimeEnvironmentLevel.READY, summary.level)
        assertEquals("Linux 沙箱就绪", summary.healthLabel)
        assertEquals("Android/proot Alpine · proot", summary.executionSurface)
        assertEquals("已安装", summary.rootfsStatus)
        assertEquals("proot + loader 就绪", summary.binaryStatus)
    }

    @Test
    fun statusTextIncludesSharedRuntimeFields() {
        val summary = buildRuntimeEnvironmentSummary(
            flavor = "proot",
            targetSdk = 28,
            abi = "arm64-v8a",
            prootBundled = true,
            rootfsInstalled = true,
            prootBinExists = true,
            loaderBinExists = true,
            rootfsPath = "/data/rootfs",
            usrPath = "/data/usr",
            tmpPath = "/data/tmp",
        )

        val text = runtimeEnvironmentStatusText(summary)

        assertTrue(text.contains("- 执行环境: `Android/proot Alpine · proot`"))
        assertTrue(text.contains("- 环境健康: **Linux 沙箱就绪**"))
        assertTrue(text.contains("- 建议入口: `/diag`"))
    }

    @Test
    fun missingBootstrapBinaryRequestsDiag() {
        val summary = buildRuntimeEnvironmentSummary(
            flavor = "proot",
            targetSdk = 28,
            abi = "arm64-v8a",
            prootBundled = true,
            rootfsInstalled = true,
            prootBinExists = true,
            loaderBinExists = false,
            rootfsPath = "/data/rootfs",
            usrPath = "/data/usr",
            tmpPath = "/data/tmp",
        )

        assertEquals(RuntimeEnvironmentLevel.WATCH, summary.level)
        assertEquals("引导文件待恢复", summary.healthLabel)
        assertEquals("loader 缺失", summary.binaryStatus)
        assertEquals("/diag", summary.primaryCommand)
    }

    @Test
    fun missingRootfsShowsBootstrapSurface() {
        val summary = buildRuntimeEnvironmentSummary(
            flavor = "proot",
            targetSdk = 28,
            abi = "",
            prootBundled = true,
            rootfsInstalled = false,
            prootBinExists = true,
            loaderBinExists = true,
            rootfsPath = "/data/rootfs",
            usrPath = "/data/usr",
            tmpPath = "/data/tmp",
        )

        assertEquals(RuntimeEnvironmentLevel.WATCH, summary.level)
        assertEquals("等待 rootfs", summary.healthLabel)
        assertEquals("未安装", summary.rootfsStatus)
        assertEquals("未知 ABI", summary.abiStatus)
    }
}
