# ui2 迭代记录

移动优先 UI（`app/src/main/java/com/andmx/ui2/`）的开发记录，作为旧版三栏 `ui/workbench/` 的轻量替代。

## 目标

提供一个适合手机单手使用的 UI：底部导航、Material You 动态主题、自研 Markdown 引擎、流式对话。旧版 `ui/workbench/` 的桌面风格三栏布局保留，通过 `AndroidManifest.xml` 切换 launcher activity。

## 模块结构（实测行数）

```
ui2/（共 ~7,460 行 Kotlin）
├── MainActivity2.kt              入口
├── nav/        (100)             底部导航
├── theme/      (38)              Material You 动态主题
├── chat/       (975)             对话：ChatController/ViewModel/Event/Screen/Composer/MessageBubble/ToolCallCard/StreamingText
├── drawer/     (797)             会话抽屉
├── files/      (812)             文件浏览
├── markdown/   (460)             自研 Markdown 引擎：解析/内联/代码高亮/主题
├── terminal/   (348)             终端视图（ANSI 渲染、手势缩放）
├── settings/   (3,695)           设置页：provider/model/mcp/plugin/skill/sub-agent/usage 等
├── usage/      (134)             用量统计
├── icons/      (54)              文件类型图标
└── computeruse/                  Computer Use 权限门
```

整个 `app/src/main/java/` 约 43,700 行 Kotlin，ui2 是其中一个模块。

## 已实现

- **对话系统**：流式实时更新、工具调用卡片、Markdown 渲染、流式光标、错误处理、Material 进出动画
- **Markdown 引擎**：单遍扫描解析器（heading/list/quote/code/paragraph）、内联元素（bold/italic/code/link）、多语言代码高亮、深浅主题适配
- **终端系统**：PTY 会话管理、ANSI 颜色渲染、手势缩放字体、文本选择复制、自动滚动
- **文件系统**：目录导航、文件类型图标
- **基础设施**：Material You 动态主题、底部导航、会话管理抽屉、设置界面

## 待完善

- 连接真实 LLM 进行端到端测试
- 工具集成（FileTools/ShellTool 与真实 agent 引擎对接）
- Computer Use 功能集成
- 图片消息支持
- 会话搜索

## 文档

- `OPTIMIZATION_ROADMAP.md` — 早期三周规划（历史文档）
- `docs/zcode_analysis.md` — ZCode UI 设计分析
- `docs/week{1,2,3}_summary.md` — 周报
- `README.md` — 项目总览
