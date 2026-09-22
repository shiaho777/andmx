# ZCode 3.14.0 源码级对齐分析（2026-09-21）

对象：`ZCode-main/`（官方开源源码，pnpm monorepo，版本 3.14.0）。
与此前逆向文档（`zcode_alignment_2026.md` / `zcode_alignment_3_11_2.md` /
`zcode_re_settings_2026-09.md`）的关系：那些文档基于桌面 bundle 逆向，
本文基于**源码**，修正/细化其中过时或推测的部分，并按「UI / Agent 工具链 /
上下文工程 / 提示词 / 模式 / 模型切换 / 交互 / 输入框 / 输出渲染 / 工具渲染 /
会话管理」分类给出差距清单。

不抄范围（用户已确认）：官方登录/套餐/配额体系（zai·bigmodel·zapi provider、
coding plan、OAuth 连接）、桌面 Electron 壳本身、远程协作 relay。dwf 工作流
引擎已按 Android 原生形态落地（见 P3-25）。AndMX 定位 = ZCode 能力面的
Android 超集，不是复刻。

---

## 一、源码纠正/细化逆向结论的关键差异

### 1. 系统提示词是「三段 system 消息」，不是一条 ✅ 已修

`core/src/context/builder.ts`：system 被拆成三个独立 message，**每段各打
`cache_control: ephemeral`**：

| 块 | 内容 | cacheHint |
|---|---|---|
| ① cli_prefix | `You are ZCode, an interactive coding agent` | stable |
| ② stable body | identity（含 SECURITY_NOTICE + # Harness）或 customSystemPrompt / workflowActor | stable |
| ③ dynamic | dynamic_behavior + session_guidance + memory + env_info + output_style + context_management + system_context(git) | dynamic |

meta_user 附件两条（skills_listing、context_prefix=agentsMd+memory index），
拼进首条 user 消息——AndMX 已对齐此通道。

**AndMX 已落地**：`ZCodePrompts.assembleBlocks` 出三块，`AgentEngine`
以多块 system 前缀持有（`setSystemBlocks` 重写前缀段）；
`AnthropicMessagesAdapter` 对前 ≤3 个连续 system 块逐块打
`cache_control: ephemeral`——改 dynamic 段不再失效 stable 段缓存；
历史中的 reminder 不打（breakpoint 预算：3 system + 1 消息锚点）。

### 2. 模式状态不在 system prompt 里——走 runtime_mode reminder ✅ 已修

源码里 system prompt **没有任何 mode 段**。Plan 模式靠：

- `EnterPlanMode`/`ExitPlanMode` 工具（需用户批准进出）
- `runtime_mode` reminder（`runtime/helpers/runtime-reminders.ts`）：
  plan 激活时每 ≥5 个人类回合注入一次；每第 5 次注入**完整版**
  （含 Plan Workflow 四阶段全文），其余注入**稀疏版**一句话；
  退出时发 `## Exited Plan Mode` reminder。
- reminder 包装为 `<system-reminder>`，挂在 current_turn 通道。

**AndMX 已落地**：`assemble` 不再携带 mode 段；
`AgentEngine.injectPlanModeReminder` 在人类回合后按上游节奏注入
`<system-reminder>` 包装的 plan reminder（每 ≥5 回合；第 1 次与每第
5 次 full、其余 sparse），plan→build 边沿发 `## Exited Plan Mode`；
`PlanModeState.active` 经 `setPlanModeProvider` 挂为唯一事实源，
`rescanPlanReminderState` 在会话恢复后重建计数。采用「持久化进历史」
而非上游 per_request 通道——更贴近 rewind/compact 后的可恢复语义。

### 3. Plan Workflow 数字变了：3 个 Explore，不是 4 ✅ 已修

`runtime-reminders.ts:23` `planResearchAgentCount = 3`。
AndMX `ZCodePrompts.PLAN_WORKFLOW` 已改为「up to 3」「3 agents maximum」。

### 4. Session-specific guidance 实际只剩一条 ✅ 已修

源码 `buildSessionGuidanceSection`：Agent 引导段、AskUserQuestion 引导段
全部注释掉了，**只剩**「`/<skill-name>` 经 Skill 调用」一条，且仅在 Skill
工具注册且有技能时输出。

AndMX 已收敛为 `ZCodePrompts.sessionGuidance(hasSkills)`：仅保留 Skill 一条，
且只在有已安装/插件技能时输出；其余引导随工具描述对齐（见第 6 节）。

### 5. auto-compact 公式：AndMX 把 output reserve 扣了两次 ✅ 已修

ZCode（`compact/policy.ts`）：
```
effectiveWindow = ctx − min(maxOutputTokens, 21_000)
threshold       = effectiveWindow − 13_000          # 无 95% 项
```
AndMX 原实现：
```
effective = ctx − reserve           # reserve = min(maxOut, ctx) 默认 32K
threshold = min(95%×effective, effective − reserve − 13K)
```
`byBuffer` 在已扣过 reserve 的 effective 上又扣了一次 reserve。
ctx=200K/maxOut=32K 时：ZCode 阈值 166K，AndMX 123K——提前 43K 压缩。

修复后：`effectiveContextWindow(ctx, maxOut)` = `ctx − min(maxOut‖32K, 21K)`；
`autoCompactThresholdTokens(effective)` = `effective − 13K`，95% 项删除，
阈值 166K 与上游一致；`isContextWindowExceeded` 同步传 `maxOutputTokens`。

其他 auto-compact 新增（未做，P1+）：
- **provider usage 覆盖**（`AutoCompactTokenOverride`）：流式拿到真实
  usage 后替代字符估算做判定，含 cacheRead/Write/incremental 拆分。
- **熔断**：连续 3 次压缩失败停止再试（`MAX_CONSECUTIVE_AUTOCOMPACT_FAILURES`）。
- **门槛**：`hasEnoughMessagesToCompact` = ≥2 个 assistant 起始回合 +
  ≥1 条 assistant 消息。
- token 估算计入 assistant `toolCalls` 的 name+input JSON，且 reasoning
  block 有独立投影（不再按 0 计）。

### 6. compact 摘要提示词的小幅更新 ✅ 已修（prompt 部分）

`compact/prompt.ts` 九节结构不变。已落地：
- `COMPACTION_SYSTEM_PROMPT` 补 `NO_TOOLS_PREAMBLE` 前置 +
  `<example>` 输出结构块 + 自定义指令示例；security 逐字保留条款原已具备；
- `extractSummary` 对齐 `formatCompactSummary`（剥 analysis、
  `Summary:` 前缀、折叠空行）；
- 压缩后 user 消息改走 `buildCompactSummaryMessage` 原文
  （continued-session 文案 + `recentMessagesPreserved` + `suppressFollowup`）。

`replStateCleared` 无 REPL 对应物不做；`customInstructions` 附加段（AndMX
`customCompactPrompt` 是整段替换语义，属扩展，保留）。

### 7. microcompact：语义已对齐，两处细节 ✅ 已修

- ZCode 触发阈值 = `0.9 × autoCompactThreshold`（0.9×166K=149K）——
  `microcompactThresholdTokens` 已改为基于 auto-compact 阈值计算。
- ZCode 媒体保护含 `image|video|file` 三类 block；AndMX `ApiMessage`
  只有 `imageUrls`，无 video/file 内容类型，无需补。

### 8. 工具并发不是「全并行」——有调度器 ✅ 已修

`tool/scheduler.ts`：依赖拓扑排序 + 分层并行分组，
`canRunInParallel` = readOnly || concurrentSafe || sideEffectScope==none；
**destructive 必串行**；maxConcurrency=10。

AndMX 已落地：`Tool.concurrentSafe`（默认 `risk==READ`，状态型工具显式
关闭、纯读网络工具与子代理发射显式打开）；`AgentEngine` 把同批
tool_calls 分成「连续 safe 段并行（≤10 分波）+ unsafe 串行屏障」，
结果按请求顺序回写 history。未实现上游的依赖拓扑排序（AndMX 工具间
无依赖声明，当前无此需求）。

### 9. 权限判定链比「模式映射」复杂得多

`permission/service.ts` 的完整顺序：
```
plan 进出转换 → requiresUserInteraction→ask → alwaysAsk 专属通道
（auto→deny、disallowed→deny、project deny→deny、session allow→allow、
  owned-workflow-amend→allow、否则 ask）
→ yolo→allow → auto→deny（未实现占位）→ disallowedTools→deny
→ project deny→deny → project ask→ask → plan→只读/非破坏MCP放行其余 deny
→ project allow→allow → WebFetch 预批 URL→allow → workflow 草稿写→allow
→ allowedTools→allow → edit 模式(编辑放行) → build 模式
（readOnly 放行；critical/high 问；session-low 放行；有副作用问；其余放行）
```

要点 AndMX 缺的（✅ 已修，差异注明）：
- ~~**项目级 ruleset**~~：`ApprovalRuleStore` 已扩为 `{allow,ask,deny}` 三桶
  持久化 + `ruleContent` 匹配（`prefix:*` 前缀、`*` 通配、精确）；
  旧 JSON 数组格式自动迁移为 allow 桶。subject 提取按工具：
  shell/git→command，其余→filePath/url/path/pattern。
- ~~**alwaysAsk 声明**~~：`Tool.alwaysAsk` 通道已加（`storage_clean` 首个
  接入）；项目 deny→deny → 项目 ask→ask → plan 闸 → 会话规则 →
  alwaysAsk→prompt → Bash grants → 项目 allow → 模式兜底。
  **刻意分歧**：上游 alwaysAsk 允许项目 allow 规则免确认，AndMX 只允许
  会话级「允许本会话」免确认（破坏性工具更保守）。
- ~~**Edit↔Write 规则互通**~~：`ruleAppliesTo` 内 write 继承 edit 规则。
- sideEffectScope 六值：未做（AndMX 用 risk 枚举近似，暂无需求）。
- 规则 UI 入口：审批 banner 五作用域（允许本会话/允许本项目/每次询问/
  始终拒绝/拒绝本项目），设置页按行为分桶展示可移除。

### 10. Reminder 体系：26 种 source、5 种投递通道

`system-reminder/source.ts`：每种 reminder 有
`channel(request_prefix|current_turn|tool_result|history_continuity|
mid_turn_event|real_user)`、`lifecycle`、`isMeta`、providerVisibility。
持久化（写进历史）的 15 种 vs 每轮重建不落盘的 10 种——
AndMX 目前所有提醒都是裸 system 消息（TodoReminder、RepeatCallGuard、
步数 nudge），**没有 `<system-reminder>` 包装约定，也没有通道/持久化区分**。
建议落一个 `ReminderKind` 枚举 + 统一 wrap 函数起步。

---

## 二、Agent 工具链差距（按价值排）

| ZCode 3.14 | AndMX | 差距/行动 |
|---|---|---|
| Read/Write/Edit/Bash/Grep/Glob/WebFetch/WebSearch | ✅ 同名对齐 | 描述文本需按源码刷新（AndMX 是缩写版） |
| TodoRead/TodoWrite | ✅ | ZCode TodoWrite 描述含「渲染为工作计划」等完整文案，可刷新 |
| EnterPlanMode/ExitPlanMode | ✅ | EnterPlanMode 描述有完整 when-to-use 7 条+反例（源码 `plan-mode-prompts.ts`，比逆向版全）；ExitPlanMode 有 model instructions 全文 |
| AskUserQuestion | ✅ | — |
| Agent + Task | ✅（经 createZCodeAgentTools） | 源码 Agent 描述含 run_in_background/output_file/SendMessage 续聊/并行发射约定；缺 `Task` 别名入口确认 |
| TaskOutput / TaskStop | ✅ | orchestrator wait/close + Bash run_in_background 投影（输出落访客文件，TaskOutput 同管 agent 与后台 bash） |
| SendMessage / RespondToCoordinator | ✅ | SendMessage 工具面续聊运行中子代理 + resumeAsync 后台续跑终态通知 |
| Skill | ✅ | — |
| ReadSessionContext | ✅ | — |
| CronCreate/List/Update/Delete | ✅ | `CronStore`+`CronTools`+应用内 15s ticker；delayMinutes/5 段 cron/intervalUnit 规则、stale one-shot 拒绝、automation 回合禁写 |
| ListModels | ✅ | `ListModelsTool` 读 live provider 目录，`providerId/modelId` 规范形、`$level` 推理档后缀、`[current]`/`[disabled]` 标记 |
| js (node_repl) | ❌ | MCP host 的 JS REPL，配 browser-use 插件；Android 无对应物，可后置 |
| submit_result / escalate | ✅ | `WorkflowActorTools`：submit_result 存结构化结果，escalate 经 ask_user 审批路径回到 run 所属会话用户；无会话时 deferred 兜底 |
| CreateWorkflow/AmendWorkflow/SaveWorkflow/List*/Get*/Resume*/Cancel*/EvalWorkflowSnippet | ✅ | `WorkflowTools` 十工具面；差异：authoring 用 spec JSON（无 JS runtime，上游 TS DSL 不落地）；ResolveWorkflowQuestion 由 escalate/ask_user 路径覆盖 |
| ApplyPatch | ✅ AndMX 独有保留 | ZCode 源码里注释掉了（`// applyPatchToolEntry`） |
| Bash readonly-policy argv 引擎 | ✅ | `BashReadonlyPolicy`：shell tokenize + 管道/命令替换/复合命令递归检查、wrapper（env 等）识别、写操作/重定向/后台/畸形语法拒绝；仅用于 plan 模式 Bash 授权 |
| WebFetch egress-guard + cache + 模型摘要 | ✅ | `WebFetchGuard`（私网/本地 IP+主机名拦截、HTTP→HTTPS、有界重定向校验）+ TTL 缓存（15min/50MB）+ 命中 prompt 时模型摘要；10MB 响应上限/100K 输入上限 |

## 三、上下文工程差距

| 项 | 状态 |
|---|---|
| meta_user 首条注入 | ✅ |
| 三段 system + 分块 cache_control | ✅ |
| microcompact | ✅（阈值分母已改；media 三类无对应消息字段，无需补） |
| auto-compact 公式 | ✅（双扣修复 + provider usage 覆盖 + 3 连败熔断 + hasEnoughMessagesToCompact 门槛） |
| compact 提示词 | ✅（NO_TOOLS 前后置、example、security 保留、suppressFollowup + transcriptPath——TurnContext 携带 rolloutPath，压缩摘要附「read the full transcript at:」指引行） |
| token 估算 | ✅（toolCalls 入参已计；reasoning 无消息字段，无可补） |
| todo reminder | ✅（10 轮节流对齐） |
| runtime_mode / plan_mode_exit reminder | ✅ |
| 其余 reminder kinds（24 种） | ✅ 已接：queued_system_notification（子代理/后台终态）、runtime_mode、todo、date_change、task_status、shell_environment_change、model_anomaly、conversation_fork、rewind_notice、goal_state_change/resume_goal_state、tool_result_warning、prompt_attachment、plan_file_reference、selection_side_chat；上游 request-local 的部分源 AndMX 持久化进历史（刻意差异） |
| ReadFileState + 压缩后读文件回放 | ✅ `ReadFileState`（path/content/offset/limit/时间戳记录+seed hydrate）+ 压缩后 `RESUME_REFERENCED_SESSION_CONTEXT` 回放（近期优先、大文件引用式、数量/token 双上限、已保留 Read 条目去重、成功后清空） |
| Memory | ✅ `MemoryAgentRunner` 子代理回合：受限工具面（读 Read/Grep/Glob，写删限 memory 目录 canonical 校验）、有界 prompt+manifest、coalescing、`ModelCallTrace.Source.MEMORY`（已覆盖上游 memory-agent-loop 全部语义） |
| Goal verifier 循环 | ✅ 已对齐 |

## 四、模式 / 模型切换

- ZCode 模式：`plan | build | edit | yolo | auto(保留未实现)`。
  AndMX：`CONFIRM | AUTO_EDIT | PLAN | FULL` ≈ build | edit | plan | yolo ——映射齐，
  **差异在判定语义**（见权限节）；ZCode 无「默认 mode」段。
- 子代理 permissionMode 7 值（default/plan/auto/acceptEdits/dontAsk/bypassPermissions…）
  AndMX `SubagentCatalog.PERMISSION_MODES` 已对齐 6 值。
- 模型切换：ZCode `/model` + composer 模型菜单 + 每模型 reasoning 档位目录 +
  Claude 槽位映射 + `ListModels` 工具 + 子代理 `model`/`thoughtLevel` 覆盖。
  AndMX：ModelPill + flattenModels + ReasoningLevel/ReasoningRulesApplier +
  ModelSwitchGuardDialog + SubagentModelCatalog + ListModels 工具面 ——**齐**。
  子代理 `model` 入参支持 `providerId/modelId`（内部沿用 `::`）与 `$level` 推理档后缀。
- 思考档默认「最高」：AndMX `defaultEffortFor` 已对齐。
- **turn-output-token-continuation**：模型撞 max output tokens 时自动续跑——
  ✅ 三适配器 finish_reason 透出 + `length` 续跑（≤3 次，成功复位，耗尽报错）。

## 五、Agent 交互 / 输入框

ZCode 输入面（`packages/ui` LexicalChatInput + mentions + v4/composer，
TUI 侧对应 app-*.ts）：

| 特性 | AndMX 现状 |
|---|---|
| @ 提及 7 类：files/skills/commands/subagents/whiteboards/sessions/plugins | ✅ 有 @文件 / #会话 / /命令 / $技能 / subagents / plugins / sessions chip（whiteboards 无对应物，不做） |
| 粘贴长文本→自动转附件 | ✅ 长粘贴转 PASTE chip，有界预览注入上下文 |
| 图片粘贴/拖拽文件入输入框 | ⚠️ 有附件，无拖拽（手机端拖拽可豁免） |
| 中文输入法 `、`→`/` 归一 | ✅ `、`与 `／` 均归一为 `/` |
| 输入历史上/下键翻阅 | ✅ InputHistoryMenu：composer + 旁历史钮→近 20 条已发输入去重回填（移动端菜单替代方向键） |
| 快捷键：Ctrl+M 模型菜单 / 切模式 / 切思考档（可改绑） | ⚠️ AndMX 有 pill 点击循环；无键盘快捷键体系（手机端合理） |
| queue vs guide 双车道 | ✅ QueueStrip（排队+暂停原因）+ injectUserMessage(steer)；ZCode 的 guide 在工具边界消费、queue 等回合结束，语义一致 |
| AskUserQuestion 5 分钟无回答自动继续 | ✅ 60s 隐藏宽限→可见倒计时→300s 自动 accept(空答案)+snooze+全局开关 |
| 斜杠命令 | ✅ /fork /rewind /resume /workflows(/dwf) /init /effort(/variant) /mcp /plugins(→PLUGIN 设置页) /locale(system/zh-CN/en-US) /mode(查看+切换 ExecMode) /skill(列表+chip+任务) /expert(status/stop/resume/<task> 启动)；/login /logout 属官方登录协议不抄 |
| SteerBar（运行中插话） | ✅ |

## 六、输出渲染 / 工具渲染

| ZCode | AndMX |
|---|---|
| Markdown：streamdown + ai-elements 组件族（code-block/mermaid/diagram/table/attachment/sources/snippet/terminal/test-results/checkpoint/plan/task/queue/reasoning/persona…） | 自研 MarkdownEngine + IncrementalMarkdown + CodeHighlight + GFM table + MermaidBlock（assets/mermaid.min.js + WebView 离线真渲染，渲染失败回退源码；复制/缩放保留） |
| 工具渲染：`ToolCallBlocks/renderers/` 40+ 每工具专属（edit 内联 diff、changes-group 改动分组、execute-group 命令分组、agent 卡片、todo 卡片、webfetch/search 结果卡、cua 截图组、workflow 卡片…） | ⚠️ family 分组 + ToolEditDiff + ToolCallCard + todo 清单卡 + agent/task 卡 + CUA 截图条（imageUrls→缩略图横条+放大）+ StructuredToolCard（workflow/cron/webfetch/search 字段行）+ computer/cron/workflow/listmodels canonical 标签 + webfetch OpenUrl 动作；40+ 逐工具专属卡不逐一复刻 |
| 工具分组 ×3：Explore(连续读搜)/Terminal(连续非只读 shell)/Changes(连续写改) | ✅ `groupKind` 语义分组：read-search / execute / changes，异类交错不成组，标题带差异化摘要 |
| ModelTrajectory：完整模型 I/O 时间线 + 搜索栏 + 展开控制 | ✅ ModelTrajectoryPage 已有（finish reason 已补 length/content_filter 映射） |
| TurnGroup / TurnNavigator（回合分组+导航） | ✅ TurnNavigator：回合锚点（用户消息）间 ↑/↓ 跳转 + k/N 计数 |
| 选中消息文本→引用/侧聊（SelectionActionMenu、selection_side_chat） | ✅ MESSAGE chip 引用 + 「侧聊」action→SideChatDialog→新会话注入 SELECTION_SIDE_CHAT reminder（带选段）后发问题 |
| ConversationShare*（分享选区/权限/确认 dock） | ✅ 顶栏分享钮→markdown 落 cache+FileProvider→ACTION_SEND 分享表；SideChatDialog「分享选段」→EXTRA_TEXT 分享（上游权限/确认 dock 移动端简化为系统分享表） |
| FileRewindDialog / FileSummaryPanel（逐文件回滚+变更摘要） | ✅ FileRewindDialog：逐文件列表（新建/修改、+/-行数、时间戳、路径）+ 单文件还原（同一安全闸）+ 全部还原 |
| PendingCommandRecoveryBanner | ✅ busy 排队输入落 `pendingQueueJson`（v16 迁移），重启/切回后 banner 恢复或丢弃 |
| 思考过程显示开关 / 待办显示开关 / 性能模式（精简渲染） | ✅ showReasoning/showTodos 设置已接入 timeline 过滤（此前已存在，本批核实） |

**P4 续轮补齐**
- turn_complete 兜底：Done 时本回合无 Assistant 事件且 history 末尾有新回答 → 补写
  `AssistantComplete`+落库（内容判等防双写），对齐上游 applyTurnCompleteFallbackResponse。
- 子代理转写：`SubAgentEvent.ToolActivity`（ToolStarted/Finished 穿透）→
  ChatEvent → SubAgentItem.activities（30 条封顶）→ 卡内展开显示工具活动行。
- 回合终态系统通知：`TurnNotifier`（channel=agent_turns、前台静默、Android 13+
  运行时权限请求、通知声开关生效）——任务完成/失败/工具审批/ask_user/工作流
  escalate 五处接入，复用既有 notification/notificationSound 死设置。
- 斜杠补齐上游全集：/mode、/skill、/expert(status/stop/resume/<task>)、/dwf→/workflows。
- Mermaid 真渲染：assets/mermaid.min.js(10.9.3, MIT)+WebView 离线渲染对话框，
  strict 安全级+JS 桥错误回退源码。
- StructuredToolCard：workflow/cron/webfetch/search 四族字段行专属卡。

**P5 移动端完善轮（ai-elements/TUI 残余面审计 + 移动端交互加固）**

- 审批面板对齐上游 approval-panel：标题带工具名+模式、reason 行、mono 输入
  预览块（previewPermissionInput 等价）、「长期授权规则」scope 预览行
  （approvalPermissionScopes 等价，显示将持久化的命令前缀/文件规则）、
  允许/拒绝触觉反馈（LongPress haptic）。
- AskUserQuestion 对齐 question-panel：新增复核步（提交→逐题答案+备注预览→
  返回修改/确认提交，上游 review 流程等价）、多问题时「已答 n/N」进度。
- sources 卡：webfetch/search 结果自动提取 URL→「来源」chip 行（域名标签，
  点击经 ChatActionBus.openUrl 系统浏览器打开，≤8 条去重）。
- test-results 卡：工具输出末尾检测 `N passed/failed/skipped` 摘要→
  ✓/✗/○ 三色徽标行（通用检测，非单一工具绑定）。
- terminal 等价：MetaBlock 显示层剥离 ANSI 转义序列（CSI/OSC/字符集序列），
  上游 terminal.tsx ansi-to-react 的移动端等价（单色渲染、去乱码）。
- 图片画廊：截图条点击→HorizontalPager 画廊对话框（从点击张起始、左右滑动
  翻页、n/N 计数），替代单张放大，对齐 image-thumbnail-gallery。
- 消息动作移动端加固：动作行横滚（窄屏不挤压溢出）、用户气泡长按复制手势。
- ai-elements 审计结论：persona（Rive WebGL 头像动画，无 Android 等价物，
  刻意差异）；sandbox/task/checkpoint/plan/artifact 均有等价物覆盖
  （工具卡折叠态/todo 卡/压缩标记/ExitPlan 审批板/工作流产物页）。

**P6 细粒度对账轮（逐文件过 upstream tui/src 85 个源文件 + tool handlers）**

- `RespondToCoordinator` 工具补齐：子代理→协调者回话通道（上游
  coordinatorResponsePort 等价）——子代理此前 SendMessage 主代理会
  "Agent not found"，现为 `<agent-message from>` 注入主会话 pending 队列。
- 斜杠参数补全面板（上游 mode/model/effort-suggestion-panel 对齐）：
  `/mode`（4 档+描述）`/model`（当前 provider 模型表）`/effort`（档位+current）
  `/locale` `/skill` `/expert`（workflow defs 惰性加载）——`/<cmd> <partial>`
  触发 ArgSuggestion 面板，点选回填。
- workflow 时间线活卡（上游 app-workflow-card 对齐）：workflow 工具卡内嵌
  实时运行行——runId 从 args/output 提取，2s 轮询 workflowService.getRun，
  显示 `run <id> · status · 阶段 n/N`。
- 网络重试显示（上游 app-network-events 对齐）：`AgentEvent.Retrying` →
  `ChatEvent.Retrying` → WorkingIndicator 文本变为「请求失败，Ns 后重试
  （n/M）」，Done/Error 时清除。
- 工具清单终态核对：上游 builtInTools 35 项全部有对应物或刻意差异——
  provider-visible-order 里的 EnterWorktree/ExitWorktree/LSP/NotebookEdit/
  TaskCreate/TaskGet/TaskList/TaskUpdate/ScheduleWakeup 上游仅有排序声明
  无 handler 实现（Claude-compat 占位），不视为缺口；offPeakCreate/
  offPeakList 依赖上游套餐额度端口 offPeakPort（无对应基建，刻意差异）；
  js/node_repl 无 JS runtime（既有刻意差异）。

## 七、会话管理

| ZCode | AndMX |
|---|---|
| sqlite 会话库 + 事件溯源（SessionEvent 流） | Room + rollout jsonl（RolloutWriter/Reader/SessionResumer）✅ 等价物在 |
| /rewind：对话回滚 **+ workspace 文件检查点双路**（selectCheckpointForRewind、restoreWorkspaceCheckpointFiles） | ✅ RewindPickerDialog（用户消息=检查点）→ truncateFromUserMessage + revertFileChanges(sinceMs) 双路；差异：文件侧按时间戳近似而非快照 |
| /fork：会话分叉（对话 + workspace 副本） | ✅ repo.forkConversation 复制会话+消息、记 spawn 边；workspace 不复制（共享工作区，移动端合理差异） |
| /resume + plan-file continuity（恢复时把 plan 文件引用注回） | ✅ /resume 开会话抽屉 + `PlanFiles`：批准 plan 落 `.andmx/plans/plan-<id>.md`，恢复/压缩后注入 PLAN_FILE_REFERENCE reminder（上游文案一致） |
| 标题生成 sidecar（首轮后自动起名） | ✅ `TitleGenerator` 异步旁路（60s 超时、1.2K 输入截断、JSON/fence/plain 解析+清洗+兜底）；仅首条用户消息且标题仍为默认时落名 |
| 会话归档/分组/pin/时间线分组 | ✅ 抽屉已支持 pinned 区/归档视图/task_groups 自定义分组（本批核实既有实现完整） |
| PendingCommandRecoveryBanner（中断命令恢复提示） | ✅（见渲染节） |
| selection_side_chat / ConversationShareSelection | ✅ 侧聊已做（见渲染节）；ConversationShare* 不做 |
| CommandInbox admission（busy/running 输入串行准入） | ✅ pending-injection 队列：运行期 system/user 注入经队列在步边界消费（不再直接改 history 竞态）；stop 暂停时不再误排空 |

## 八、明确不做（与 ZCode 解耦点）

- 官方 provider/登录/套餐/配额：`zai*`、`bigmodel*`、`zapi`、OAuth、coding
  plan usage meter、quota_exceeded 错误码——AndMX 只做三方自定义 provider。
- Remote control / 手机远控桌面、relay、attachment 调度——AndMX 自身即移动端。
- Electron 桌面层、`::code-comment` 渲染指令、mock-cdn 远程资源。
- node_repl/browser-use 官方插件链——Android 上可用自有 ComputerUse 替代。
- OTel 遥测体系（AndMX 的 ModelCallTrace 埋点已够用，可按需补指标名对齐）。

---

## 九、建议落地顺序

**P0 — 纯文本/逻辑对齐，收益即现（1 个 PR 内可分多个 commit）** ✅ 已全部落地

1. ✅ `autoCompactThresholdTokens` 修双扣 reserve → `effective−13K`；reserve 上限 21K；
   阈值公式去掉 95% 项。
2. ✅ microcompact 阈值分母改 autoCompactThreshold；media 保护补 video/file
   ——AndMX `ApiMessage` 无 video/file 内容类型，无需补。
3. ✅ token 估算计入 toolCalls 入参（原有）；reasoning —— `ApiMessage`
   无持久化 reasoning 字段，无需补。
4. ✅ compact 提示词补 `NO_TOOLS_PREAMBLE`/`<example>`/自定义指令示例；
   摘要消息补 `buildCompactSummaryMessage`（含 suppressFollowup 文案）。
5. ✅ Plan Workflow 4→3。
6. ✅ EnterPlanMode/ExitPlanMode 工具描述按 `plan-mode-prompts.ts` 源码全文刷新；
   顺带对齐了两个工具的成功结果文案与 ExitPlanMode 非 plan 态守卫。
7. ✅ SESSION_GUIDANCE 收敛为 `sessionGuidance(hasSkills)` 仅 Skill 一条。

**P1 — 机制级（各独立 Issue/PR）**
8. ✅ system 三分块 + Anthropic 适配器逐块 cache_control：
   `ZCodePrompts.assembleBlocks` 出 [IDENTITY / CORE / dynamic] 三块，
   `AgentEngine.systemPromptBlocks` + `setSystemBlocks` 只重写前缀；
   适配器对前 ≤3 个连续 system 块打 `cache_control: ephemeral`，
   历史中的 reminder 块不打（4-breakpoint 上限：3 system + 1 消息锚点）。
9. ✅ 模式从 system prompt 迁到 runtime_mode reminder：`assemble` 不再带
   mode；`injectPlanModeReminder` 在每个人类回合后检查，每 ≥5 回合重挂
   （第 1 次与每第 5 次为 full，其余 sparse），plan→build 边沿发
   `## Exited Plan Mode`；`rescanPlanReminderState` 在 seed 后重建计数。
10. ✅ 并发调度：Tool 接口加 `concurrentSafe`（默认 `risk==READ` 推导，
    会话状态型工具显式关、WebFetch/WebSearch/子代理发射显式开）；
    `timeoutMs` 原有保留。AgentEngine 把同一批 tool_calls 分组——
    连续 safe 段并行（`MAX_PARALLEL_TOOL_CALLS=10` 分波），
    unsafe 调用成串行屏障；结果仍按请求顺序落 history。
    （实现以 risk 枚举涵盖 readOnly/destructive/sideEffectScope 语义。）
11. ✅ 权限层：`ApprovalRuleStore` 三桶 ruleset（allow/ask/deny）+ ruleContent
    匹配（prefix:* / 通配 / 精确，write 继承 edit）+ `Tool.alwaysAsk` 通道；
    判定序 deny→ask→plan→session→alwaysAsk→grants→allow→mode；
    banner 五作用域 + 设置页分桶管理。
12. ✅ queue|guide + AskUserQuestion：三态文案（空/部分/全部答案）、
    60s 隐藏宽限→可见倒计时→300s 自动 accept(空答案)、snooze、全局开关。
13. ✅ TaskOutput/TaskStop + Bash run_in_background（输出落访客可见文件）
    + SendMessage 子代理续聊 + resumeAsync 后台续跑终态通知。
14. ✅ turn-output-token-continuation：三适配器 finish_reason 透出 +
    length→续跑提示（≤3 次，成功复位，耗尽报错）。
15. ✅ reminder 骨架：`SystemReminder` 27 source + channel/lifecycle/isMeta
    描述符 + 统一包装 + 嵌套拒绝；queued_system_notification 已接
    子代理终态（`<task-notification>` XML）。

**P2 — 体验级**
16. ✅ @ 提及面板补 subagents/plugins/sessions：`MentionSuggestion` 分类
    + `AGENT`/`PLUGIN`/`SESSION` chip 类型 + 面板分组展示与插入。
17. ✅ 粘贴长文本转 `PASTE` chip（有界预览注入消息上下文，不直接撑爆输入框）；
    `、`与全角 `／` 归一为 `/` 触发斜杠联想。
18. ✅ 工具渲染：`ToolPresentation.groupKind` 语义分组——changes（写改，
    标题数 distinct 文件）/ execute（shell/git，标题数命令+预览）/ read-search
    保留；todo 工具专属清单卡（默认展开）；agent/task 家族卡已有保留。
19. ✅ ModelTrajectory 页已有（source/model/时间戳/token+cache/finish/耗时/
    输入输出预览）；补 `TracedLlm.finishOf` 把 length/content_filter 映射到
    `Finish.LENGTH/CONTENT_FILTER`。
20. ✅ /fork（`repo.forkConversation` 复制会话行+全部消息，新 rollout/session
    id，记 spawn 边）/rewind（RewindPickerDialog 选检查点=用户消息，对话截断
    + `revertFileChanges(sinceMs)` 截点后文件改动双路回滚，外部改动跳过）
    /resume（打开会话抽屉挑历史会话，rollout 恢复走既有 SessionResumer）。
    差异：文件回滚按 FileChange.timestamp 截点近似，无上游 workspace
    checkpoint 快照；文件先改于截点前又改于截点后的极端情形会回滚过头。
21. ✅ deny 原因展示：`ApprovalRuleStore.matches` 透出命中规则，
    `approveTool` 拒绝文案带 rule key/display；
    `ToolPresentation.deniedOrStopped` 识别「rejected execution」为 DENIED。

**P3 — 大特性，单独评估**
22. ✅ CronCreate/List/Update/Delete：`CronSchedule`（5 段 cron 解析 +
    delayMinutes 分钟锚定 + intervalUnit/interval 间隔规则 + stale 一次性
    30min 窗口拒绝）、`cron_automations` Room 表（v13→v14 迁移）、
    `CronStore` CRUD/推进、`CronTools` 四工具（上游 schema/描述移植，
    automation 回合内禁写）、ChatController 15s ticker 调度——到期经
    `sendMessage` 复用主循环，会话忙顺延 60s。ScheduleWakeup 不做：上游仅在
    provider-visible-order 中列名、无 handler 实现（占位声明）。
23. ✅ 记忆抽取子代理：`MemoryAgentRunner` 以受限工具面跑独立
    AgentEngine 回合（读 Read/Grep/Glob、写删 canonical 限制在 memory
    目录内），prompt 有界 + 现有 manifest 注入，coalescing 快照，
    Done 后触发，trace 源 `MEMORY`。
24. ✅ ListModels 工具 + 子代理模型目录工具化：`ListModelsTool` 只读
    live 目录输出 `providerId/modelId`、`[current]`、`[disabled: reason]`、
    reasoning 档位与默认；子代理 model 入参新增 `$level` 后缀解析到
    `TurnContext.reasoningOverride`。
25. ✅ dwf 工作流引擎（Android 原生落地）：
    - **契约层** `WorkflowModel`：definition/run snapshot/graph/collection/
      activity/sessionLink/event/criticResult/plannerResult，序列化字段名与
      上游 schema 一致；`deriveWorkflowSchedulerState`/`deriveWorkflowSessionLinks`
      派生函数移植。
    - **生命周期** `WorkflowLifecycle`：reconcileForResume（active→pending 重置）、
      cancel、reopenNode（maxReopens）、applyGraphSeed（重复/未知引用/自环/环
      校验）、applyNodePromptUpdates（跨 phase 拒绝）。
    - **调度器** `WorkflowGraphScheduler`：Mutex 保护快照、依赖就绪排序
      （工程节点先、探索集合轮转）、maxConcurrentLoops 并发上限、失败回
      pending 重试至 maxConsecutiveErrors、deadlock/error_threshold→paused、
      collection planner 触发（explorable+frontier 不足+unseen 完成）、
      结构化并发（取消随作用域收敛）。
    - **运行时** `WorkflowRuntime`：ExpertWorkflow 8 相定义（clarify→
      task_analysis→arch_decompose(seedGraphFromArtifact)→env_setup→
      meta_prompt(nodePromptsFromArtifact)→exec(scheduled_graph)→
      final_critic→complete）；phase→artifact→seed/prompts 管线、critic
      verdict/reopen 循环、失败→paused+recoveryActions、cancel→cancelled。
    - **actor 工具** `submit_result`（结构化结果槽）/`escalate`（经
      ask_user 审批面板回 run 所属会话；无会话时 deferred 文案兜底）。
    - **持久化** `WorkflowStore` + v15 迁移（workflow_definitions/runs/
      events 三表）+ GuestFs `.andmx/workflow-runs/<runId>/` 产物/报告。
    - **工具面** `WorkflowTools` 10 工具：Create/Amend/Save/ListSaved/
      ListRuns/GetRun/GetRoster/Resume/Cancel/EvalWorkflowSnippet；
      authoring 差异——上游 TS DSL（agent()/parallel()/pipeline()）无 JS
      runtime 不落地，改 spec JSON（WorkflowDefinition schema，Eval 干跑校验）。
    - **接线**：`runWorkflowActor` 建隔离 AgentEngine（全量工具面-变更类
      workflow 工具+actor 工具，trace 源 WORKFLOW）；`/workflows` 打开
      WorkflowsDialog（定义+运行列表）→ WorkflowRunDetailDialog（阶段/
      图节点/产物/事件/取消）；只读五工具进 plan 白名单。

**P4 — 收尾批（源码全量审计剩余项）**

26. ✅ auto-compact 三补：provider usage 覆盖（`ApiMessage.tokenUsage` 引擎侧
    字段，白名单序列化不上行）+ 3 连败熔断 + `hasEnoughMessagesToCompact` 门槛；
    `isContextWindowExceeded` 同走 usage 覆盖。
27. ✅ ReadFileState + 压缩后回放：`RESUME_REFERENCED_SESSION_CONTEXT` reminder，
    近期文件限量回放、大文件引用式、preserved Read 去重、成功后清空；seed 时
    从持久化 toolArgs hydrate。
28. ✅ WebFetch：`WebFetchGuard` egress 防护 + `WebFetchCache` TTL 缓存 +
    prompt 命中时 `summarizeFetchedContent` 模型摘要（trace 源 WEB_FETCH）。
29. ✅ reminder 源补齐：task_status、model_anomaly、conversation_fork、
    rewind_notice、goal_state_change/resume_goal_state、shell_environment_change、
    tool_result_warning、prompt_attachment、plan_file_reference、
    selection_side_chat 触发点全部接线。
30. ✅ CommandInbox 准入：运行期注入（系统通知/steer）经 pending 队列在步边界
    消费；stop 暂停态不再被 finally drainQueue 误排空。
31. ✅ `BashReadonlyPolicy` argv 级只读判定接入 plan 模式 Bash 授权。
32. ✅ 标题 sidecar（P4-B1）：`TitleGenerator` + `SESSION_TITLE` trace 源。
33. ✅ 会话抽屉分组（P4-B2）：pinned/归档/task_groups 既有实现核实完整。
34. ✅ plan-file continuity（P4-B3）：批准 plan 落盘 `.andmx/plans/`，
    恢复与压缩后注入 `PLAN_FILE_REFERENCE` reminder。
35. ✅ 斜杠补齐（P4-B4）：`/init`（上游 builtin prompt 移植，AndMX 命名）、
    `/effort`（list/档位/ off/on，ReasoningConfig levels→effortLevels→style 回退）、
    `/mcp`（配置+连接态+工具数）。
36. ✅ FileRewindDialog（P4-B5）：逐文件还原 `revertFileChange`（同一安全闸）+
    变更摘要列表。
37. ✅ PendingCommandRecoveryBanner + selection 侧聊（P4-B6）：
    `pendingQueueJson` 持久化（v16）+ banner 恢复/丢弃；「侧聊」action →
    SideChatDialog → 新会话 `SELECTION_SIDE_CHAT` 注入 + 问题发送。
38. ✅ 渲染层（P4-C）：`imageUrls` 全链路透传（event→ToolCall→卡片→持久化
    回放）+ CUA 截图条+放大；MermaidBlock 图源卡；TurnNavigator 回合跳转；
    computer/cron/workflow/listmodels canonical 标签；showReasoning/showTodos
    与 GFM table 此前已就绪（本批核实）。

## 验证方式

- 文本对齐项用「逐字 diff」验收：把 ZCode 源文件里的常量与 AndMX 常量对拷比较。
- 机制项先写单测（`./gradlew :app:testLiteDebugUnitTest`），
  编译用 `./gradlew :app:compileLiteDebugKotlin --offline --no-daemon`。
