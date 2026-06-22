package com.andmx.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.andmx.exec.proot.ProotRuntime
import com.andmx.exec.proot.RootfsInstaller
import com.andmx.ui.theme.AndmxCodeTextStyle
import com.andmx.ui.theme.AndmxTheme
import com.andmx.ui.theme.Radii
import com.andmx.ui.theme.Spacing

/**
 * First-run gate: bootstraps proot + the Alpine rootfs with a visible progress
 * log, then reveals the workbench. If the environment is already set up (or the
 * flavor has no proot), it falls through immediately.
 */
@Composable
fun SetupGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val runtime = remember { ProotRuntime(context) }
    val installer = remember { RootfsInstaller(runtime) }

    // No proot in this flavor, or already installed → straight to the workbench.
    var ready by remember { mutableStateOf(!runtime.isBundled() || installer.isInstalled()) }
    val log = remember { mutableStateListOf<String>() }
    var failed by remember { mutableStateOf(false) }

    if (ready) { content(); return }

    LaunchedEffect(Unit) {
        log += "初始化 AndMX 运行环境…"
        val ok = installer.install { line -> log += line }
        if (ok) ready = true else failed = true
    }

    val colors = AndmxTheme.colors
    val scroll = rememberScrollState()
    LaunchedEffect(log.size) { scroll.animateScrollTo(scroll.maxValue) }

    Box(Modifier.fillMaxSize().background(colors.canvas), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(Spacing.xxl),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!failed) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.accent)
                    Spacer(Modifier.width(Spacing.md))
                }
                Text(
                    if (failed) "环境初始化失败" else "正在准备 Linux 沙箱",
                    style = AndmxTheme.typography.titleLarge,
                    color = colors.textPrimary,
                )
            }
            Spacer(Modifier.height(Spacing.xs))
            Text(
                "首次启动需下载并安装一个无 root 的 Alpine 用户态(约 4 MB)。",
                style = AndmxTheme.typography.bodySmall, color = colors.textTertiary,
            )
            Spacer(Modifier.height(Spacing.lg))
            Box(
                Modifier.fillMaxWidth().height(180.dp)
                    .border(1.dp, colors.border, Radii.md)
                    .background(colors.codeBackground, Radii.md)
                    .padding(Spacing.md)
                    .verticalScroll(scroll),
            ) {
                Text(log.joinToString("\n"), style = AndmxCodeTextStyle, color = colors.textSecondary)
            }
        }
    }
}
