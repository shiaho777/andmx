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

## 入口关系

对话是唯一主屏，其余表面从浮层唤起（见 `nav/AppNav.kt`）：

- 终端：对话头部 TopAppBar 右上角终端图标
- 设置：侧边栏底部入口
- 文件：侧边栏工作区入口 / `@` 引用

## 待完善

- ui2 的自动化测试（当前 `app/src/test/` 下没有覆盖 ui2 的用例）
- Computer Use 在 ui2 的完整接入（权限门目前在 `ui/computeruse/`）
- 图片消息、会话搜索
