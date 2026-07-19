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
