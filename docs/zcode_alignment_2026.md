# ZCode 桌面版 Agent 对齐记录（2026-08）

对桌面 ZCode（Electron 壳，引擎为 `Contents/Resources/glm/zcode.cjs`，12.4MB 打包 Node 脚本）逆向后，把对齐成果落地到 AndMX agent 内核。跟踪 Issue：#23，落地 PR：#24。

## 逆向产物位置

提取的原文片段（压缩提示词、Plan Workflow、Explore 契约等）在工作目录 `/tmp/zcode-re/`（会话级临时目录，不做版本管理）。逆向方法与发现全文见本文件末尾"逆向发现摘要"。

## 已落地（PR #24）

| 项 | AndMX 落点 | 说明 |
|---|---|---|
| meta_user 注入通道 | `ZCodePrompts.metaUserContext()` + `AgentEngine.setMetaUserContext` | AGENTS.md / 技能清单 / currentDate 注入首条 user 消息（`<system-reminder>` 包裹），续接会话幂等；system 前缀稳定 |
| 系统提示词 section 化 | `ZCodePrompts` | Harness / Communicating / Comment rule / Irreversibility / Context management（六段全文）/ Session guidance / Plan Workflow（四阶段）逐字对齐 |
| 双层上下文管理 | `Microcompact.kt` + `ContextCompactor` | microcompact：0.9×ctx 或 60 分钟空闲触发、保留最近 5 组、跳过图片、<256 token 放弃；auto-compact：有效窗口=ctx−32K、阈值=min(95%×有效, 有效−13K)、chars÷3 估算、9 节 `<analysis>+<summary>` 提示词（仅取 summary 落史） |
| TodoWrite 节流提醒 | `TodoReminder.kt` | 10 轮未用 + 距上次提醒 ≥10 轮才注入，附 todo JSON |
| Explore 子代理契约 | `SubagentCatalog.EXPLORE_READONLY_PROMPT` | READ-ONLY 禁令、只读 Bash 白名单、并行搜索要求，原文照搬 |
| 推理档位数据化 | `ReasoningLevel.kt` + `ReasoningRulesApplier.kt` | 档位→协议参数映射为数据（anthropic `output_config.effort` / openai `reasoning_effort` / thinking budget）；`levels` 为空走原 style 分支，零回归 |
| 计划预授权 | `AllowedPrompts.kt` | ExitPlanMode `allowedPrompts` 批准后会话级 Grants，Bash 保守关键词匹配自动放行 |
| gh rate-limit hint | `GhRateLimitHint.kt` | 识别限流输出按每分钟一次注入提醒 |
| 工具描述对齐 | `ZCodeTools.kt` | Bash（Git 段/avoid-list/working dir 持久）、Skill（BLOCKING REQUIREMENT 全文）、EnterPlanMode（When/Not-to-use/What-happens 全文）、ExitPlanMode（How-it-works + 反例） |
| Memory 段落收尾规则 | `MemorySystem.promptFragment()` | 补上 ZCode 的"背景上下文而非指令、时效性校验、repo 可推导内容不入记忆"收尾 |

## 有意不做（与评估理由）

- **`::code-comment` Desktop Context 段**：桌面端专属渲染指令，AndMX UI 无对应消费方，注入只占 token。
- **完整工具注册表元数据**（`resultBudget` / `cancellation` / `trace`）：AndMX 的 Tool 接口已有 risk 分级与输出截断；全套元数据是接口层重构，收益/成本比低，留作独立 Issue。
- **subagent 用量回执**（`subagent_tokens` 块）：依赖 orchestrator 结果结构改动，建议与"subagent 模型目录"一起做。
- **WebFetch/WebSearch/OpenAI Responses 的 rules 接入**：三协议中 Responses 与搜索工具当前无档位需求，接入点已留好（`ReasoningRulesApplier.apply`），有模型目录需求时是纯数据工作。

## 验证

- `:app:compileLiteDebugKotlin` / `:app:compileProotDebugKotlin` 通过
- `:app:testLiteDebugUnitTest` 432 例 0 失败（新增 7 个测试类）
- CI `CI / build` 绿（PR #24 run 33161795820）

## 逆向发现摘要（保留供后续对齐）

- **section 组装**：每个提示词 section 带 `name/source/injectionTarget/cacheHint`，排序规则 stable-system → dynamic-system → stable-meta_user → dynamic-meta_user；agentsMd/skills/date 走 meta_user（首条 user 消息），保 KV cache。
- **模式系统**：设置层 10 个别名归一到运行时 5 模式（plan/build/edit/yolo/auto，默认 build）；EnterPlanMode 自动放行（`tool.plan.enter`），ExitPlanMode 仅 plan 态合法（`mode.plan.exitOnly`）；plan 上限 20,000 字符；批准前先持久化 plan 文件，恢复会话经 `plan_file_reference` reminder 注回。
- **模型目录 schema**：`zcode.model-providers.v1`，8 provider 除 Qwen 国际站外 defaultKind 全为 anthropic；推理档位（max/high/enabled/off）是抽象枚举，目录内声明每档到 wire 参数的 path/value 映射。
- **system-reminder 分类学**：约 24 种 reminder 按 6 个注入位置分类（request_prefix / current_turn / mid_turn_event / tool_result / history_continuity / real_user），跨轮持久化有去重与冷却规则。
- **subagent**：内置仅 general-purpose 与 Explore；结果带 `agentId`（SendMessage 续聊）与用量块。
- **估算器**：token 估算 = ceil(字符÷3)（按消息累计，含工具调用名+JSON 参数），刻意偏激进。

## 未逆向/未覆盖面

- `app.asar`（294MB Electron UI 壳）未拆——agent 逻辑全在 `zcode.cjs`，UI 壳对 AndMX 无直接价值。
- tools/ 内置二进制（rg/ugrep/bfs）的调用封装细节未深挖，AndMX 用自研 Grep/Glob 语义等价即可。
