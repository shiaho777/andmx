# ZCode Agent 对齐说明

基于本机 ZCode 3.0.0 的 model-io 轨迹与 tool JSON 逆向（`~/.zcode/cli/debug/model-io-*.jsonl`、`/tmp/zcode_prompts/tool_*.json`）。

## 系统提示词栈

1. `You are ZCode, an interactive coding agent`
2. Core / Harness / 安全边界
3. Craft + 不可逆操作确认
4. Session Environment（cwd / git / model / skills）
5. Mode overlay：`confirm` / `build(auto_edit)` / `plan` / `yolo(full)`
6. 项目文档：`AGENTS.md` / `CLAUDE.md` / `CODEX.md`
7. 用户自定义指令 + persona + memory/MCP 扩展

实现：`com.andmx.agent.zcode.ZCodePrompts` + `ChatController.buildZCodeSystemPrompt`.

## 工具表面（ZCode 线名）

| ZCode | AndMX 实现 | 对齐要点 |
|-------|------------|----------|
| Read | ReadFileTool | `file_path` → path |
| Write | WriteFileTool | `file_path` + content |
| Edit | EditFileTool | old/new_string + replace_all |
| Bash | ShellTool | command / timeout / description |
| Grep | GrepTool | pattern/path/glob/output_mode/-i/-A/-B/-C/head_limit/multiline |
| Glob | GlobTool | pattern + path |
| WebFetch | BrowseTool | url + prompt 必填（schema）；正文 + prompt 摘录 |
| WebSearch | WebSearchTool | query + allowed/blocked_domains |
| TodoRead / TodoWrite | TodoState + UpdatePlanTool | status/priority enum |
| EnterPlanMode | PlanModeState | 需用户确认后进入 |
| ExitPlanMode | PlanModeState | **必填 plan**，用户审批后才退出 |
| AskUserQuestion | 结构化 UI | questions[] + options + Other + multiSelect + preview |
| ReadSessionContext | 会话消息检索 | sessionId/query/strategy |
| Skill | SkillInstaller | 注入 SKILL.md + command 标记；仅列表内技能 |
| Agent | ZCodeAgentTool | SubAgentOrchestrator |

同时保留 snake_case 别名兼容旧会话。

## AskUserQuestion

- Schema 对齐 ZCode：1–4 题，每题 header/question/options(2–4)/multiSelect
- UI：`AskUserQuestionPanel` 选项卡 + Other 自由文本 + 可选备注；有 preview 时左右分栏
- 回传 JSON：`{"answers":{question: value}, "annotations":{...}}`

## ExitPlanMode

- 必填 `plan`（1..20000）
- `ExitPlanApprovalPanel` 展示完整计划，批准 → AUTO_EDIT；拒绝 → 保持 plan mode

## Skill

- 系统提示列出可用技能（`skillsHint`）
- 调用后注入 `<command-name>` / SKILL.md 正文，本轮内按指令执行，不重复 invoke

## 运行循环

`AgentEngine.runTurn`：流式 → 并行工具 → 回填 → 压缩 → 最多 50+grace 步。  
Plan 模式：`isPlanModeAllowed` 拦截写/执行类工具。

## 模式映射

| UI | ZCode | 行为 |
|----|-------|------|
| 改前确认 | confirm | 写/执行询问 |
| 自动编辑 | build | 读写自动，shell 询问 |
| 计划模式 | plan | 只读 + todo/plan + Ask |
| 完全访问 | yolo | 全自动 |

## 环境差异（非假装 1:1 的部分）

- WebFetch 不做「小模型二次问答」，返回可读正文 + prompt 摘录
- Skill 不执行未知宿主侧脚本运行时，只注入技能上下文
- Computer Use / MCP 为 AndMX 扩展，不在 ZCode 14 工具基线内


## 2026-07-21 1:1 工具面补齐（model-io 对照）

基于 `~/.zcode/cli/debug|rollout/model-io-sess_*.jsonl`：

| 项 | 状态 |
|----|------|
| SendMessage | 已加（队列到本地子代理，不宣称已读） |
| TaskStop | 已加（停 Bash 后台 task_id / Agent id） |
| Bash.run_in_background | 已加 + BackgroundTasks |
| Bash.dangerouslyDisableSandbox | 参数已透传（Android proot 侧为标记） |
| Bash.timeout | 已加（max 600000ms） |
| Read 默认 2000 行 + cat -n | 已加 |
| Grep head_limit 默认 250 / 0=不限 | 已加；offset 已支持 |
| WebFetch 必填 prompt + 跨域重定向不跟 | 已加；答案为 extractive（设备端无独立小模型时） |
| Agent.model / agent_<uuid> | 已加 |
| 系统提示 IDENTITY | 对齐 live 开场句 |
| Plan 允许表 | 收紧为读类 + 协调工具 |

仍非 1:1 / 已知差：
- WebFetch 小模型答页：已接当前会话 provider/model 的轻量 chat；失败时回退 extractive
- ApplyPatch / Goal* / list_dir / Git / spawn_agent / multi_agent：AndMX 扩展，ZCode 主会话 tool list 通常不出现
- Glob/Grep 在 ZCode **主会话** 有时不挂（子代理 Explore 才挂）；AndMX 主会话始终挂载
- 插件 MCP 面按启用插件动态出现，与 ZCode 一致

- Agent 默认 subagent_type=general-purpose（与 ZCode 描述一致）
- 系统提示双段 IDENTITY：`You are ZCode...` + CORE 开场
- EnterPlanMode/ExitPlanMode/Agent 描述对齐 live tool JSON


## 主会话工具面（MAIN vs WORKER）

对照 live model-io：

**MAIN（主会话）** 常见集合：
`Agent, AskUserQuestion, Bash, Edit, EnterPlanMode, ExitPlanMode, Read, ReadSessionContext, Skill, TodoRead, TodoWrite, WebFetch, WebSearch, Write`
(+ 有多代理时 `SendMessage, TaskStop`)

**不含**：Glob / Grep / ApplyPatch / Goal* / list_dir / git / spawn_agent / multi_agent / snake_case 别名

**WORKER（Explore 子代理）**：
`Bash, Glob, Grep, Read, TodoWrite, WebFetch, WebSearch`

实现：`ZCodeToolSurface.MAIN|WORKER`；主会话 MAIN；`toolsFactory` WORKER。


## MCP 线名

ZCode live 形如：`mcp__plugin_android-emulator_android-emulator__android_preflight`

AndMX：
- 外部 MCP：`mcp__{server}__{tool}`
- 内置插件原生工具：`mcp__plugin_{plugin}_{server}__{tool}`


## Skill / harness 注入

ZCode 在 user 消息里注入：
```
<system-reminder>
The following skills are available for use with the Skill tool:
- name: desc (file: path)
</system-reminder>
```
以及 currentDate 的 system-reminder。

AndMX：`buildHarnessSystemReminders()` 在每次 `runTurn` 前拼入 model 输入；Skill 工具描述对齐 live JSON。
插件脚本工具线名：`mcp__plugin_{plugin}_tools__{script}`。
旧 workbench `ConversationController` 主工具面改为 `ZCodeToolSurface.MAIN`。

## 2026-07-21 真·1:1 收口（live model-io）

已按 `~/.zcode/cli/*/model-io-sess_*.jsonl` 对齐：

1. 系统提示三段原文：IDENTITY / CORE(Harness) / CRAFT+Session guidance；Environment+Context management 与 live 同结构
2. harness 以 **独立 user 消息** 注入（skills + currentDate），不再拼进用户正文；会话首轮注入
3. currentDate reminder 含 live 的 IMPORTANT 免答注记
4. 主工具面 MAIN：无 Glob/Grep；协调工具 Agent/SendMessage/TaskStop
5. Android 插件工具线名 **1:1**：`mcp__plugin_android-emulator_android-emulator__{tool}`
6. 主工具 description 从 live tool JSON 原文灌入（Bash/Read/Write/Edit/WebFetch/WebSearch/TodoWrite/EnterPlanMode/ExitPlanMode/AskUserQuestion/Skill/…）
7. Agent 目录 Explore 工具列表文案与 live 一致：`Read, Bash, WebFetch, WebSearch, TodoWrite`

## 2026-07-21 WebFetch + system wire（zcode.cjs + model-io）

已按 `/Applications/ZCode.app/Contents/Resources/glm/zcode.cjs` 与 live `body.system` 对齐：

1. WebFetch：15 分钟 URL 缓存；跨域重定向返回固定 REDIRECT 文案；私网/local 拒绝；HTML→markdown；处理前 100k 截断；prompt 处理 user 消息格式与 live `buildProcessingPrompt` 一致；`maxOutputTokens=4096`；工具结果仅返回答案文本（非整页摘录）
2. WebFetch 回答模型：`role=lite` 语义——独立 chat、无 system、仅 user；模型解析与 ZCode 一致为 `lite ?? main`（AndMX 当前无独立 lite 配置时用会话主模型）；失败 extractive 回退
3. Anthropic 线协议：`system` 为 `[{type:text,text,cache_control:{type:ephemeral}}, …]` 多分片；Agent 历史多条 system 消息（IDENTITY / CORE / CRAFT+Session…）
4. ChatRequest 支持 `max_tokens` 供 WebFetch lite 调用
5. 子代理 system 两段：`IDENTITY` + Explore/general-purpose 角色正文（Explore 文案对齐 live model-io）；工具面 WORKER 仍含 Glob/Grep


仍可能差异：
- 未单独配置 lite 模型时，与 ZCode 默认 `lite??main` 相同走主模型
- OpenAI 兼容线仍为 messages 内多 system（非 Anthropic system 数组）
- 插件市场其它插件 MCP 集合随启用集变化（与 ZCode 一致）

## 2026-07-21 写文件 UI（renderer reverse）

逆向 `index-iBkFHeQO.js` + i18n：

- 文案：`chat.toolCall.edit.writing/wrote/editing/edited` → 写入中/已写入/编辑中/已编辑
- 行变更：`Hn(old,new)` / `computeLineChangeStat` → `+N -M`（Write 用 empty→content）
- 工具 family=`file-write`：Write/Edit；运行中 autoOpen，完成后保持展开看 diff
- 预览卡：`chat.previewCards.htmlWebsite` =「网站 · HTML」；仅 `.html/.htm`
- 汇总条：`chat.changeSummary.filesChanged` + rewind「撤销」

AndMX：`ToolEditDiff`/`ToolArgs` 认 `Write`/`Edit`/`file_path`；`PreviewCards`/`ChangeSummaryBar` 挂在最后一条完成的 assistant 消息后。
