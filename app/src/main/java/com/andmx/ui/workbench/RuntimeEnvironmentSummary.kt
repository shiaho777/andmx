package com.andmx.ui.workbench

internal enum class RuntimeEnvironmentLevel {
    READY,
    WATCH,
    LIMITED,
}

internal data class RuntimeEnvironmentSummary(
    val level: RuntimeEnvironmentLevel,
    val healthLabel: String,
    val healthDetail: String,
    val executionSurface: String,
    val bootstrapStatus: String,
    val rootfsStatus: String,
    val binaryStatus: String,
    val abiStatus: String,
    val rootfsPath: String,
    val usrPath: String,
    val tmpPath: String,
    val primaryCommand: String = "/diag",
)

internal fun buildRuntimeEnvironmentSummary(
    flavor: String,
    targetSdk: Int,
    abi: String,
    prootBundled: Boolean,
    rootfsInstalled: Boolean,
    prootBinExists: Boolean,
    loaderBinExists: Boolean,
    rootfsPath: String,
    usrPath: String,
    tmpPath: String,
): RuntimeEnvironmentSummary {
    val abiStatus = abi.ifBlank { "未知 ABI" }
    val binaryStatus = when {
        !prootBundled -> "不适用"
        prootBinExists && loaderBinExists -> "proot + loader 就绪"
        prootBinExists -> "loader 缺失"
        loaderBinExists -> "proot 缺失"
        else -> "尚未解包"
    }

    return when {
        !prootBundled -> RuntimeEnvironmentSummary(
            level = RuntimeEnvironmentLevel.LIMITED,
            healthLabel = "轻量执行面",
            healthDetail = "当前 APK 未捆绑 proot, 适合 Android/Kotlin 内置工具；需要 Linux 用户态时使用 proot 变体。",
            executionSurface = "Android lite · $flavor",
            bootstrapStatus = "未捆绑 · targetSdk $targetSdk",
            rootfsStatus = "不适用",
            binaryStatus = binaryStatus,
            abiStatus = abiStatus,
            rootfsPath = rootfsPath,
            usrPath = usrPath,
            tmpPath = tmpPath,
        )

        rootfsInstalled && prootBinExists && loaderBinExists -> RuntimeEnvironmentSummary(
            level = RuntimeEnvironmentLevel.READY,
            healthLabel = "Linux 沙箱就绪",
            healthDetail = "proot 引导文件与 Alpine rootfs 已在本机可见, 终端、文件和差异面板可以进入同一工作环境。",
            executionSurface = "Android/proot Alpine · $flavor",
            bootstrapStatus = "已捆绑 · targetSdk $targetSdk",
            rootfsStatus = "已安装",
            binaryStatus = binaryStatus,
            abiStatus = abiStatus,
            rootfsPath = rootfsPath,
            usrPath = usrPath,
            tmpPath = tmpPath,
        )

        !prootBinExists || !loaderBinExists -> RuntimeEnvironmentSummary(
            level = RuntimeEnvironmentLevel.WATCH,
            healthLabel = "引导文件待恢复",
            healthDetail = "当前变体包含 proot 资源, 但本地可执行文件尚未解包或已缺失；运行 /diag 可重新安装并验证。",
            executionSurface = "Android/proot bootstrap · $flavor",
            bootstrapStatus = "已捆绑 · targetSdk $targetSdk",
            rootfsStatus = if (rootfsInstalled) "已安装" else "未安装",
            binaryStatus = binaryStatus,
            abiStatus = abiStatus,
            rootfsPath = rootfsPath,
            usrPath = usrPath,
            tmpPath = tmpPath,
        )

        else -> RuntimeEnvironmentSummary(
            level = RuntimeEnvironmentLevel.WATCH,
            healthLabel = "等待 rootfs",
            healthDetail = "proot 引导文件已就绪, 但 Alpine rootfs 哨兵不存在；首次启动流程或 /diag 可继续初始化。",
            executionSurface = "Android/proot bootstrap · $flavor",
            bootstrapStatus = "已捆绑 · targetSdk $targetSdk",
            rootfsStatus = "未安装",
            binaryStatus = binaryStatus,
            abiStatus = abiStatus,
            rootfsPath = rootfsPath,
            usrPath = usrPath,
            tmpPath = tmpPath,
        )
    }
}

internal fun runtimeEnvironmentStatusText(summary: RuntimeEnvironmentSummary): String = buildString {
    appendLine("- 执行环境: `${summary.executionSurface}`")
    appendLine("- 环境健康: **${summary.healthLabel}**")
    appendLine("- 引导: ${summary.bootstrapStatus}")
    appendLine("- rootfs: ${summary.rootfsStatus}")
    appendLine("- 二进制: ${summary.binaryStatus}")
    appendLine("- ABI: ${summary.abiStatus}")
    appendLine("- rootfs 路径: `${summary.rootfsPath}`")
    appendLine("- usr 路径: `${summary.usrPath}`")
    appendLine("- tmp 路径: `${summary.tmpPath}`")
    append("- 建议入口: `${summary.primaryCommand}`")
}
