# ui2 移动端 UI

`app/src/main/java/com/andmx/ui2/`，移动优先的轻量 UI，作为旧版三栏 `ui/workbench/` 的替代。当前的默认 launcher（`MainActivity2`）。

## 设计目标

- 单手友好的底部导航（对话 / 文件 / 终端 / 设置）
- Material You 动态主题（跟随系统壁纸）
- 自研 Markdown 引擎与 ANSI 终端渲染，不引入重型第三方 UI 库
- 流式对话 + 工具调用可视化

## 模块

| 模块 | 说明 |
|------|------|
| `chat/` | 对话：流式 ChatController/ViewModel、消息气泡、工具卡片、流式光标 |
| `markdown/` | 单遍扫描解析器 + 内联元素 + 代码高亮 |
| `terminal/` | PTY 会话、ANSI 颜色渲染、手势缩放 |
| `files/` | 目录导航、文件查看 |
| `drawer/` | 会话列表抽屉 |
| `settings/` | provider/model/mcp/plugin/skill/sub-agent/usage 多页设置 |
| `nav/` | 底部导航 |
| `theme/` | Material You 动态主题 |
| `icons/` | 文件类型图标映射 |

## 与旧版 UI 的关系

旧版 `ui/workbench/`（桌面风格三栏）保留。在 `AndroidManifest.xml` 中：

- `MainActivity2`（ui2）= 当前 launcher（`exported="true"`）
- `MainActivity`（workbench）= `exported="false"`

交换两者的 `exported` 即可切换默认 UI。详见顶层 `README.md`。

## 待完善

- 与真实 agent 引擎端到端对接
- Computer Use 集成
- 图片消息、会话搜索
