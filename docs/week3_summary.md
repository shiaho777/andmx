# Week 3 — 终端

## 完成内容

`ui2/terminal/`（实测约 348 行）：
- `TerminalScreen.kt`（255 行）— PTY 会话管理 + ANSI 解析 + 状态显示 + 命令输入栏，DisposableEffect 自动清理。ANSI 解析直接产出 AnnotatedString（16 色 OneDark 风格调色板），含 `stripAnsi` 工具方法
- `TerminalView.kt`（93 行）— 手势缩放字体（双指 8–24sp）、自动滚动、SelectionContainer 文本选择复制、ANSI 着色渲染

## 与旧版终端的差异
旧版 `term/TerminalEmulator.kt` 维护完整屏幕缓冲区；ui2 版直接把 ANSI 解析为 AnnotatedString 渲染，代码更少并支持原生文本选择。此外新增手势缩放与输出缓冲限制（防 OOM）。

## 备注
- 行数为代码实测值。
- 60fps 滚动等性能指标未做定量测量。
