# Week 1 — Markdown 引擎 + 文件图标

## 完成内容

### ZCode UI 分析
输出 `docs/zcode_analysis.md`：记录 ZCode 的 Markdown 渲染、代码高亮、文件图标映射、交互动画等设计点，作为 ui2 实现参考。

### Markdown 引擎
`ui2/markdown/`（实测约 460 行）：
- `MarkdownEngine.kt` — 单遍扫描解析器（heading / paragraph / code / list / quote）
- `InlineParser.kt` — 内联元素（bold / italic / inline-code）
- `CodeHighlight.kt` + `CodeTheme.kt` + `CodePreviewConfig.kt` — 代码语法高亮（Kotlin/Python/Java/JS 等），基于 AnnotatedString，无第三方依赖
- `MarkdownView.kt` — Compose 渲染组件，深色/浅色主题适配

### 文件图标
`ui2/icons/FileTypeIcons.kt`（54 行）：扩展名 → Material Icon 映射，覆盖代码/配置/文档/图片等常见类型，未知类型有 fallback。已集成进 `FilesScreen`。

### UI 集成
`MessageBubble.kt` 使用 MarkdownView 渲染消息；`FilesScreen.kt` 使用 FileTypeIcons。

## 备注
- 行数为代码实测值（早期版本曾给出偏低的估算，以代码为准）。
- 性能（渲染耗时）未做定量测量。
