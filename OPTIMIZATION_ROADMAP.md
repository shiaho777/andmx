# ui2 开发路线图（历史文档）

早期为 ui2 移动端 UI 制定的三周规划。保留作为历史记录，文末附实际完成情况。

## 目标

构建移动优先的轻量 UI，与旧版 `ui/workbench/` 三栏桌面布局并存。重点：体验优先、Android 原生 Material You、自研渲染（Markdown/终端）避免引入重型第三方 UI 库。

---

## Week 1：Markdown 引擎 + 文件图标

- 单遍扫描 Markdown 解析器（heading/list/quote/code/paragraph）
- 内联解析（bold/italic/code/link）
- 多语言代码高亮（AnnotatedString，无第三方依赖）
- 文件扩展名 → Material Icon 映射

**产物**：`ui2/markdown/`（MarkdownEngine / CodeHighlight / InlineParser / MarkdownView / CodeTheme / CodePreviewConfig，共 ~460 行）、`ui2/icons/FileTypeIcons.kt`

## Week 2：对话流 + 工具卡片

- 流式对话管理（ChatController + ChatViewModel，MVVM）
- 事件系统（ChatEvent）
- 工具调用可视化卡片（ToolCallCard）
- 流式文本 + 光标动画（StreamingText）
- 消息进入/退出动画

**产物**：`ui2/chat/`（共 ~975 行）

## Week 3：终端 + 打磨

- PTY 会话管理（TerminalController）
- ANSI 解析 → AnnotatedString 直接渲染
- 手势缩放字体、文本选择复制、自动滚动
- 会话抽屉（`ui2/drawer/`，~797 行）

**产物**：`ui2/terminal/`（~348 行）、`ui2/drawer/`

---

## 实际完成情况（核对于代码）

- Markdown 引擎、文件图标、对话流、工具卡片、终端、会话抽屉均已落地，对应代码在 `ui2/` 各子目录。
- ui2 总计约 23,300 行 Kotlin。历次文档给出的 7,460 及更低估算均已过时，以 `find app/src/main/java/com/andmx/ui2 -name '*.kt' | xargs wc -l` 为准。
- ui2 与旧版 `ui/`（含 `ui/workbench/`，约 22,400 行）并存，通过 `AndroidManifest.xml` 的 launcher activity 配置切换。早期「~10,000 行」的估算偏低约一半。
- 对话（`ui2/chat/`，约 10,600 行）与设置页（`ui2/settings/`，约 7,400 行）是两个最大的子模块，均远超早期预估。

## 早期文档中的不可验证声明

早期版本曾列出「启动提升 50%+」「内存减少 60%+」「APK 20MB」等性能数字。这些数字没有测量来源，已移除。如需性能基线，应使用 Android Studio Profiler 实测。

## 待完善

- ui2 的自动化测试（`app/src/test/` 下目前没有 ui2 覆盖）
- Computer Use 与对话流的深度集成
- 图片消息支持
- 会话搜索

> 「与真实 agent 引擎端到端对接」一项已完成：`ui2/chat/ChatController.kt` 构造真实
> `AgentEngine` + `LlmClient` 并调用 `runTurn`。缺的是自动化回归覆盖，不是接线。
