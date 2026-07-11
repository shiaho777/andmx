package com.andmx.ui2.nav

/**
 * ZCode 对齐：对话是唯一主屏，没有底部 TAB。
 * 此前 AppNav 承载的 Scaffold + NavigationBar + NavHost 四目的地结构已移除。
 *
 * 终端、设置、文件现在分别从以下入口唤起（均为浮层，非独占 TAB）：
 * - 终端：对话头部 TopAppBar 右上角终端图标
 * - 设置：侧边栏底部入口
 * - 文件：侧边栏工作区入口 / @ 引用
 *
 * NavBus 保留为进程级导航总线，供跨页面通信（如文件页回注 @ 引用）。
 * Screen 枚举的 route 常量保留，仅作为浮层唤起的标识，不再是 TAB 目的地。
 */

object AppRoutes {
    const val CHAT = "chat"
    const val FILES = "files"
    const val TERMINAL = "terminal"
    const val SETTINGS = "settings"
}

/** 旧 Screen 枚举的兼容别名，供现有 NavBus.navigateTo 调用方平滑过渡。 */
object Screen {
    const val CHAT = AppRoutes.CHAT
    const val FILES = AppRoutes.FILES
    const val TERMINAL = AppRoutes.TERMINAL
    const val SETTINGS = AppRoutes.SETTINGS
}
