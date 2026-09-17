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

## 当前状态

- Markdown 引擎、文件图标、对话流、工具卡片、终端、会话抽屉均已落地，对应代码在 `ui2/` 各子目录。
- `MainActivity2` 是唯一界面入口，旧版 workbench 已删除；上文双 UI 并存仅描述早期目标。
- 图片附件和会话搜索已有实现；搜索匹配标题、项目和首条用户消息，不等同于全部消息全文检索。
- `app/src/test/java/com/andmx/ui2/` 已有逻辑测试；不能把它与真机 Compose 交互测试混为一谈。
- 上文行数均为历史记录，不作为当前规模或性能依据。

## 早期文档中的不可验证声明

早期版本曾列出「启动提升 50%+」「内存减少 60%+」「APK 20MB」等性能数字。这些数字没有测量来源，已移除。如需性能基线，应使用 Android Studio Profiler 实测。

## 后续验证重点

- 持续扩展设备级回归测试，覆盖生命周期、权限拒绝和长会话。
- Computer Use 已注册默认工具表，仍需用户授权屏幕录制及无障碍，计划模式不允许屏幕操作。
- 性能优化先建立启动、内存和长列表滚动基线，不使用未经测量的提升比例。
- Goal 会话内续跑不是后台定时调度，当前没有独立的定时任务系统。

`ChatController` 已构造真实 `AgentEngine` + `LlmClient` 并调用 `runTurn`，不是待接线的原型。
