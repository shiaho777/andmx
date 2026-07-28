# ui2 移动端 UI

`app/src/main/java/com/andmx/ui2/`，移动优先的轻量 UI，作为旧版三栏 `ui/workbench/` 的替代。当前的默认 launcher（`MainActivity2`）。

## 设计目标

- 单手友好：对话是唯一主屏，终端 / 文件 / 设置以浮层唤起，不占底部 TAB
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
| `nav/` | 路由常量 + 进程级导航总线 NavBus（底部 TAB 结构已移除） |
| `theme/` | Material You 动态主题 |
| `icons/` | 文件类型图标映射 |

## 与旧版 UI 的关系

旧版 `ui/workbench/`（桌面风格三栏）保留。在 `AndroidManifest.xml` 中：

- `MainActivity2`（ui2）= 当前 launcher（`exported="true"`）
- `MainActivity`（workbench）= `exported="false"`

交换两者的 `exported` 即可切换默认 UI。详见顶层 `README.md`。

## 待完善

- ui2 的自动化测试（`app/src/test/` 下目前没有 ui2 覆盖；`tools/mock_llm_server.py` 可用于离线端到端验证）
- Computer Use 与对话流的深度集成
- 会话搜索

> 对话已通过 `ChatController` 接入真实 `AgentEngine` + `LlmClient`（含 MCP、插件工具集、SubAgent 编排）。
> 早期文档中「与真实 agent 引擎端到端对接」一项已完成，缺的是自动化回归覆盖。
