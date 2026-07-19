# AndMX

Android 原生的 AI 编程 agent。把「能真正改代码、跑命令、操作屏幕的 coding agent」装进手机——不是聊天壳，而是一个自带 Linux 沙盒、真实工具调用、Computer Use 能力的完整 agent。

- **包名**：`com.andmx`
- **SDK**：minSdk 26 / targetSdk 34（`proot` flavor 除外），仅 `arm64-v8a`
- **语言**：Kotlin + Jetpack Compose，外加少量 C（PTY、exec probe）
- **版本**：0.1.0

## 核心能力

| 能力 | 说明 |
|------|------|
| **Agent 循环** | provider 无关的多步循环：问模型 → 执行工具 → 喂回结果 → 直到模型用纯文本回答；支持并行工具调用、上下文压缩兜底、hooks |
| **工具系统** | Shell / File / Patch / Git / Browse / ComputerUse / Goal / MCP 等，按风险分级（READ/WRITE/EXECUTE/NETWORK）走批准策略 |
| **proot 沙盒** | App 内运行 Linux guest，Alpine rootfs 自动下载，PTY 交互终端，项目目录 bind-mount 到手机真实存储 |
| **Computer Use** | MediaProjection 截屏 + AccessibilityService 派发手势（tap/swipe/type），构成 screenshot→action→screenshot 纯视觉循环 |
| **多 Provider** | 统一抽象 OpenAI / Anthropic / GLM(BigModel) / DeepSeek / Ollama / vLLM…，三种 wire 协议（OpenAI Chat / OpenAI Responses / Anthropic Messages），reasoning 元数据驱动 |
| **MCP** | 内置 JSON-RPC client，可挂载外部 MCP 服务器作为额外工具 |

## 两个构建变体（flavor）

| flavor | targetSdk | 用途 |
|--------|-----------|------|
| `lite` | 34 | 现代 SDK，仅用 Kotlin/JGit 工具，不从 data 目录 exec 原生二进制。Play 友好 |
| `proot` | 28 | 故意降到 28，让 guest 二进制可以从可写 data 目录被 exec（proot userland）。Sideload 导向 |

差异源于 Android 对 `targetSdk 29+` 禁止从可写目录 exec 原生二进制（W^X）。`proot` flavor + `vendor/proot/*.deb` + `app/src/main/cpp` + `native/probe.c` 共同构成在 App 内运行 Linux guest 的能力。

## 架构总览

按包名分层（`app/src/main/java/com/andmx/`）：

```
agent/        agent 循环 + 工具系统（ShellTool/FileTools/PatchTool/GitTool/
              ComputerUseTool/BrowseTool/GoalTool/McpTool…）+ 批准策略、
              上下文压缩、hooks、automations、plugins、memory、多 agent
llm/          LLM 客户端、流式模型、token 跟踪
llm/provider/ ProviderDefinition / ModelDefinition / ReasoningConfig
llm/wire/     WireAdapter 三协议实现（OpenAiChat/OpenAiResponses/Anthropic）
exec/         执行环境抽象（LocalProcess/LocalProot）、PersistentShell、PTY
exec/proot/   proot runtime、rootfs 安装器
exec/policy/  ExecPolicy / NetworkPolicy
computeruse/  AccessibilityService + MediaProjection + ScreenCaptor
mcp/          JSON-RPC MCP client
data/         Room 持久化（会话、消息、provider）
data/rollout/ 会话回放 / 断点续接
diff/         Diff / Patch 引擎
term/         终端模拟器
workspace/    项目管理、change tracker、guest 路径
settings/     ProviderStore / ProviderSettings
ui2/          新版极简 UI（Material You + 底部导航），默认 launcher
ui/workbench/ 旧版三栏 workbench UI（保留）
```

两套 UI 并存：`ui2/`（`MainActivity2`，当前 launcher）是移动优先的极简实现；`ui/workbench/`（`MainActivity`，已降级为非导出）是桌面风格的三栏布局。在 `AndroidManifest.xml` 切换 launcher activity 即可在两者间切换。

## 构建与运行

```bash
# Debug 构建
./gradlew assembleLiteDebug      # lite flavor（默认）
./gradlew assembleProotDebug     # proot flavor（需 sideload）

# 运行单元测试（63 个测试文件）
./gradlew test

# 安装到已连接设备
./gradlew installLiteDebug
```

`local.properties` 需配置 Android SDK 路径。

## 技术栈

- Kotlin + Jetpack Compose（Material 3 / Material You 动态主题）
- Hilt（DI）、Room（DB）、DataStore（设置）、kotlinx.serialization、Coroutines
- NDK 27、CMake（PTY 共享库 `andmxpty`，16KB page-size 兼容）
- 纯 Kotlin 实现的 Markdown 引擎与 ANSI 终端解析（无第三方 UI 库）

## 目录结构

```
app/src/main/java/com/andmx/   Kotlin 源码
app/src/main/cpp/              pty.c + CMakeLists（PTY 共享库）
app/src/test/                  单元测试
native/probe.c                 exec/W^X 探测二进制
vendor/proot/                  proot .deb（proot flavor 打包用）
tools/mock_llm_server.py       离线测试用 mock LLM 服务
docs/                          设计分析与周报
```

## 许可

私有项目，未声明开源许可。

## 贡献

交付环：**Issue → PR（base=`main`，`Fixes #N`）→ CI（`CI` / job `build`）→ merge → Issue 关闭**。

详见 [CONTRIBUTING.md](CONTRIBUTING.md)；coding agent 见 [AGENTS.md](AGENTS.md)。

