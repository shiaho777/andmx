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
- ui2 总计约 7,460 行 Kotlin（早期文档曾给出更低的估算值，与实际实现规模不符，以代码为准）。
- ui2 与旧版 `ui/workbench/`（~10,000 行）并存，通过 `AndroidManifest.xml` 的 launcher activity 配置切换。
- 设置页（`ui2/settings/`，~3,695 行）规模超出早期预估，包含 provider/model/mcp/plugin/skill/sub-agent/usage 等多页。

## 早期文档中的不可验证声明

早期版本曾列出「启动提升 50%+」「内存减少 60%+」「APK 20MB」等性能数字。这些数字没有测量来源，已移除。如需性能基线，应使用 Android Studio Profiler 实测。

## 待完善

- 与真实 agent 引擎端到端对接
- Computer Use 集成
- 图片消息支持
- 会话搜索
