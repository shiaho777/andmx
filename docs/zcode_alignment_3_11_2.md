# ZCode 3.11.2 对齐记录（2026-09-12）

对桌面 ZCode 3.11.2.6792（`Contents/Resources/glm/zcode.cjs`，12.6MB，
bundle 日期 2026-09-04）的增量逆向。与 8 月首份对齐文档（`zcode_alignment_2026.md`）
互补：本文聚焦 3.9→3.11 新增面与此前未覆盖的引擎机制。

## 逆向要点（新增/此前未记录）

### 1. Goal 自主交付循环（本次落地）

ZCode 的 Goal 不是状态标签而是**运行时循环**：

- 目标模型：`{objective(≤4000 chars), status: active|paused|budget_limited|complete,
  tokenBudget: int|null, tokensUsed, timeUsedSeconds}`；引擎侧相位
  `draft/prewarming/running/completedSuccess/completedInterrupted/error`。
- **每个回合终答后跑一次独立 verifier 调用**（querySource=`goalVerifier`，
  event=`target_completion_verification`）。Verifier 提示词要求只回
  `{"passed", "reason", "nextAction"}` JSON，先区分「寒暄类非任务」直接放行，
  并对 todo 列表、证据覆盖做 artifact-checklist 审计；解析链 = 原文 →
  字符串解包 → ```json 围栏 → 花括号切片，**解析失败 fail-open 为 passed**
  （防验证器故障死循环）。
- 未通过 → 注入 `goalContinuation` user 消息续跑：含 verifier 的 reason/
  nextAction、`<untrusted_objective>` 包裹的目标（防 prompt 注入）、预算
  面板（用时/tokens 已用/预算/剩余）、completion-audit 清单，并明确
  **「不许自己标完成，runtime 的 verifier 判定」**。
- `goalIteration` 逐轮 +1；验证条目锚定到 assistant 消息与 turn，
  时间线保留 20 条；`goal_summary_title_generation` 内部调用生成标题。
- 输入路由状态机：goal 运行/验证/压缩中新输入按
  `enqueue | guide(turnSteer) | startNow | choice` 路由，reasonCode 如
  `compactingAcceptsFutureInput`、`goalVerifierAcceptsFutureInput`、
  `heldQueueInputRequiresChoice`。

**AndMX 落地**：`agent/GoalVerifier.kt`（verifier + continuation 提示词逐字
移植、同款解析链与 fail-open）、`AgentEngine` 外层 goalLoop（终答→验证→
续跑，fresh step budget；`maxGoalIterations=20` 安全上限）、`GoalVerifying/
GoalVerified` 事件、`get_goal` 输出权威态、模型侧 `update_goal` 不再允许
self-complete（由 verifier 判定）、token/耗时由引擎随流记账、UI 时间线
`GoalVerifyRow` + StatusCapsule 迭代/下一步。

### 2. 系统提示词装配

9 个 section，均带 `{name, source, injectionTarget: system|meta_user,
cacheHint: stable|dynamic, chars, tokens, preview}`：

| Section | source | target | cache |
|---|---|---|---|
| CLI Prefix | cli_prefix | system | stable |
| Agent Identity | identity | system | stable |
| Environment Info | env_info | system | dynamic |
| System Context | system_context | system | dynamic |
| Custom System Prompt | custom_system_prompt | system | stable |
| Memory | memory | system | dynamic |
| Skills | skills | meta_user | dynamic |
| Request User Context | request_user_context | meta_user | dynamic |
| Current Date | current_date | meta_user | dynamic |

Skills 节：按 qualifiedName 排序，20K 字符总预算、单条 250 截断，
`also loadable as` 别名提示。AndMX 的 `ZCodePrompts` 已是同款结构，对齐度保持。

### 3. 工具注册表元数据

每个工具带 `{readOnly, destructive, concurrentSafe, timeoutMs,
maxOutputBytes, sideEffectScope}`，sideEffectScope 取值
`none|session|workspace|system|network|userInteraction`。
已见：Read/Glob/Grep/TodoRead/CronList=none·readonly，Write/Edit/
CronDelete=workspace，Bash/js=system，WebFetch/WebSearch=network，
Agent/TodoWrite=session。AndMX 的 `ToolRisk` 分级对应，但缺
`concurrentSafe`/`timeoutMs`/`sideEffectScope` 细分 —— 留作独立 Issue。

### 4. system-reminder 注入分类（25 种 kind）

`context_prefix, skills_listing, todo_reminder, task_status,
tool_result_warning, resume_referenced_session_context, plan_file_reference,
resume_goal_state, goal_state_change, plugin_reference, target_continuation,
goal_completion_verification, rewind_notice, queued_system_notification,
shell_environment_change, hook_context, memory_update, relevant_memory,
runtime_mode, plan_mode_exit, output_style, date_change,
referenced_session_context, model_anomaly, prompt_attachment, diagnostics`。

### 5. 子代理 frontmatter v2

Markdown agent 定义字段：`name, description, model, thoughtLevel, color,
permissionMode, maxTurns, memory(user|project|local), tools, disallowedTools,
skills, background, injectAgentsMd, mcpServers`。
较 8 月新增 `thoughtLevel / memory 作用域 / background / skills /
injectAgentsMd / mcpServers`。子代理可 `backgrounded:true` 挂 `workId`
后台运行，状态 `running/success/failed/cancelled`。

### 6. 内部模型调用算子（`actorKind: main|subagent|workflow_child|system`）

`agent_step, context_compaction, goal_title_generation,
goal_completion_verification, workspace_git_commit_message,
project_memory_extract, project_memory_dream, project_memory_recall,
read_session_context_extract/synthesize, session_title_generation,
tool_internal_model_call, web_fetch_processing, web_search,
workspace_generate_text`。
**project_memory_dream** = 记忆"做梦"式后台整理；**workflow_child** =
WorkflowGraphScheduler 的工作流子体。AndMX `ModelCallTrace.Source`
本次补 `GOAL_VERIFY`；memory dream/recall、workflow 未实现。

### 7. 其余新机制（未落地，供后续 Issue）

- **Cron 定时自动化**：`CronCreate/List/Update/Delete` + `ScheduleWakeup`，
  持久化、跨重启；prompt 禁止嵌套再建 automation（对应"闲时任务"）。
- **MCP Tasks 能力**：`TaskCreate/Get/List/Update/Stop` +
  `TaskStatusNotification` schema（后台任务 + 状态推送）。
- **附件管线**：分块上传（512KB/chunk、≤64 块、16 并发、staged ≤64MB、
  附件 ≤20MB、预览 ≤30MB、读缓存 30MB/30s、孤儿 24h 清理）——3.11.2 的
  PDF/媒体预览底座。
- **Rewind**：turn kind `regular|compact|rewind`，conversation/workspace
  双路回滚（AndMX 已有 RewindBar）。
- **selection_side_chat**：选中内容侧聊。
- **Remote Control**：`ZCodeProtocolAgentServer` + mailbox 适配（远程驱动）。
- **OTel 遥测**：`zcode.agent.step/turn.duration`、`zcode.command_execution.*`、
  `zcode.context_compaction.*` 等成体系指标。
- **限额与限制**：toolOutput head/tail 各 32KB、eventRetention 2000/会话、
  goalVerifications 20 条、commandPendingTtl 24h、
  `quota_exceeded`/`coding_plan_required` 官方 MCP 错误码。

## 3.9→3.11 changelog 功能 ↔ AndMX 现状

| ZCode 功能 | AndMX 现状 |
|---|---|
| Goal 自主交付（验证+续跑+预算） | **本次落地** |
| 思考轨迹搜索/默认展开/实时耗时 | ReasoningCard 已有耗时与展开；搜索未做 |
| 回合结束执行摘要+耗时 | TurnMetrics 已有 TTFT/TPS；摘要行可加 |
| 编辑后立即发送 | 已有编辑重发链路（truncateFromUserMessage） |
| MCP 协议版本配置+协商引导 | 未做 |
| PDF/媒体预览 | 未做（附件管线先行） |
| 插件按工作区安装/更新提醒 | 插件市场已有；workspace 级安装未做 |
| 拦截原因显示 | 审批已有 modeLabel；可补 deny 原因 |
| 侧边栏中键关闭/草稿任务/会话显示态 | drawer 已有列表；细节未做 |
| 输入框技能快捷入口/提示词模板引用插件 | StarterSuggestions 已有；技能入口可加 |
| 用量统计 V3/团队套餐 | usage 页已有个人统计 |

## 验证

- `./gradlew :app:compileLiteDebugKotlin --offline` 通过
- `./gradlew :app:testLiteDebugUnitTest --offline` 79 个测试类全绿，
  新增 `AgentEngineGoalLoopTest` 7 例（通过/续跑/预算/迭代上限/无目标/
  验证失败/解析回退）
