# ui2 迭代记录

移动优先 UI（`app/src/main/java/com/andmx/ui2/`）的开发记录，作为旧版三栏 `ui/workbench/` 的轻量替代。

## 目标

提供一个适合手机单手使用的 UI：Material You 动态主题、自研 Markdown 引擎、流式对话。旧版 `ui/workbench/` 的桌面风格三栏布局保留，通过 `AndroidManifest.xml` 切换 launcher activity。

对齐 ZCode 后**对话是唯一主屏，没有底部 TAB**（见 commit 7c3e738）。终端从对话头部图标唤起，设置与文件从侧边栏唤起，均为浮层。`nav/` 现仅保留路由常量与 `NavBus`，不再承载 `NavigationBar`。

## 模块结构（实测行数）

```
ui2/（共 23,312 行 Kotlin）
├── MainActivity2.kt      (55)    入口
├── nav/                  (42)    路由常量 + NavBus（不再是底部导航）
├── theme/               (150)    Material You 动态主题
├── chat/             (10,624)    对话：ChatController/ViewModel/Screen/Composer/MessageBubble/ToolCallCard/…
├── drawer/            (2,094)    会话抽屉
├── files/             (1,605)    文件浏览
├── markdown/            (533)    自研 Markdown 引擎：解析/内联/代码高亮/主题
├── terminal/            (549)    终端视图（ANSI 渲染、手势缩放）
├── settings/          (7,374)    设置页：provider/model/mcp/plugin/skill/sub-agent/usage 等
├── usage/               (232)    用量统计
├── icons/                (54)    文件类型图标
└── computeruse/                  Computer Use 权限门
```

整个 `app/src/main/java/` 约 69,800 行 Kotlin（267 个文件），ui2 是其中一个模块。

行数会随开发变化，以实测为准：
`find app/src/main/java/com/andmx/ui2 -name '*.kt' | xargs wc -l | tail -1`

## 已实现

- **对话系统**：流式实时更新、工具调用卡片、Markdown 渲染、流式光标、错误处理、Material 进出动画
- **Markdown 引擎**：单遍扫描解析器（heading/list/quote/code/paragraph）、内联元素（bold/italic/code/link）、多语言代码高亮、深浅主题适配
- **终端系统**：PTY 会话管理、ANSI 颜色渲染、手势缩放字体、文本选择复制、自动滚动
- **文件系统**：目录导航、文件类型图标
- **基础设施**：Material You 动态主题、会话管理抽屉、设置界面
- **Agent 接入**：`ui2/chat/ChatController.kt` 构造真实 `AgentEngine` + `LlmClient(provider, tracker)`，注册完整工具集、`SubAgentOrchestrator`、MCP 与插件工具集，并通过 `engine.runTurn(...).collect{}` 驱动流式事件

## 待完善

- **ChatController 的可测性重构**：ui2 纯逻辑（markdown/时间线/工具卡片状态机/代码高亮等）与 LLM 传输层（MockWebServer e2e）已有测试覆盖；剩余的 `ChatController` 构造期耦合 Context/Room，需注入化后才能 JVM 单测
- Computer Use 与 ui2 的集成收尾
- 图片消息支持
- 会话搜索

## 文档

- `OPTIMIZATION_ROADMAP.md` — 早期三周规划（历史文档）
- `docs/zcode_analysis.md` — ZCode UI 设计分析
- `docs/week{1,2,3}_summary.md` — 周报
- `README.md` — 项目总览
