# ZCode 桌面版深度逆向记录：设置面与能力差距（2026-09）

对象：ZCode.app 3.11.2（buildCommitId 89817f5b，2026-09-04 构建）。
证据来源：`~/.zcode/v2/setting.json`（运行时快照）、`/Applications/ZCode.app/Contents/Resources/glm/zcode.cjs`（引擎 bundle，设置 zod schema 位于字节偏移 ~405K）、`app.asar → out/renderer`（React 渲染层，i18n 文案 1720 个 `settings.*` 键）。
逆向产物已解包至 `/tmp/zcode-re/`（会话级，不入库）。

## 一、ZCode 设置页全貌（侧栏 15 区，三组）

侧栏结构（`_Gt`/`vGt`，styles chunk）：

```
基础设置      常规 / 外观 / 模型设置 / 浏览器 / 电脑控制
Agent 能力    记忆 / 子智能体 / 插件 / MCP 服务器 / 技能 / 命令 / 自动化(Beta) / 钩子
数据与统计    索引库 / 使用统计
```

### 1. 常规（general）
- 语言（locale / localePreference system|zh-CN|en-US）
- 任务通知 + 通知声音
- 关闭到托盘（Win）/ 保持电脑运行（keepAwake）
- Chrome 硬件加速；预览版更新；自动下载并安装更新
- **数据存储路径**（dataBaseDir，可迁移数据目录，禁选安装目录）
- **网络代理**：httpProxy / noProxy / 自定义 CA 证书路径（NODE_EXTRA_CA_CERTS 注入模型、MCP 与命令工具）
- 终端：字体 / 继承系统 Profile / 集成终端 Shell（Win cmd/git-bash）
- **交互行为**：queue | guide
- **显示思考过程 / 显示待办**
- **工具分组 ×3**：Explore（连续读/搜）、Terminal（连续非只读 shell）、Changes（连续 Write/Edit/ApplyPatch）
- **提问自动继续**（AskUserQuestion 5 分钟无回答自动继续，可关）
- **完整保留模型 I/O**（modelIoFullRetention：不做自动压缩/限制/删除）
- **优化体验**（对话内容用于优化，隐私声明）
- **性能模式**（精简渲染输出）
- 任务自动归档 + 保留时长（1/3/7/14/30 天档）

### 2. 外观（appearance）
- 界面主题（system / zai-light / zai-dark）；界面字号
- 代码设置：代码字号、浅色/深色代码主题（各自选择）、行号、长行换行、同时预览浅深两套主题

### 3. 模型设置（modelProvider，526 个 i18n 键）
- 添加供应商：**自定义端点** 或 **从供应商目录添加**（内置 catalog：moonshot-kimi 等）
- API 格式三选：Anthropic Messages / Chat Completions / Responses
- Base URL、API Key、每 provider 模型列表（上下文窗口/输出上限/模态/推理档位）
- **Claude 模型映射**（为 Claude 各槽位选模型）
- **连接方式**：OAuth / 个人套餐 / 体验套餐 / 团队套餐 / API Key（zai、bigmodel 两族，modelProviderFamilyModes/SelectedKeys）
- **套餐购买/升级页**（Lite/Pro/Max 权益对比、企业支付、150% 配额活动）
- 内置 provider id：`zai / zai-coding-plan / zai-start-plan / bigmodel / bigmodel-coding-plan / bigmodel-start-plan / zapi`

### 4. 浏览器（browser，44 键）
- 内置浏览器控制开关（Browser Use 官方插件）
- 数据管理：清缓存 / 清全部（Cookie+站点数据）/ **导入 Chrome 数据**（钥匙串授权流程）

### 5. 电脑控制（computerUse，12 键）
- 启用电脑控制（连带 MCP + 技能）；输入框显示电脑操作按钮入口；环境不支持降级（Linux/远程）

### 6. 记忆（memory，38 键）
- **memoryEnabled 总开关**（工作区记忆，新会话生效，token 成本提示）
- 记忆查看器：工作区选择 + 搜索、文件树（MEMORY.md/raw_memories.md/memory_summary.md）、5MiB 预览上限、相对时间

### 7. 子智能体（subagents，85 键）
- 新建/编辑/删除；**permissionMode ×7**（default/plan/auto/acceptEdits/dontAsk/bypassPermissions…）
- 工具范围：全部权限 / 自定义可用工具（含 disallowedTools）

### 8. 插件（plugins，247 键）
- 插件市场、安装/启用/禁用/配置、更新
- **导入外部 Agent 插件**（copy / symlink 两种模式）
- 内置 7 包：android-emulator、browser-use、document-skills、ios-simulator、restore-legacy-sessions、skill-creator、zcode-cua、zcode-guide

### 9. MCP（102 + 29 键）
- 服务器管理（表单/JSON 双模式）、启动/工具授权
- **同步 MCP 服务器到远端目标**
- **导入外部 Agent MCP 服务器**（扫描 Claude Code/Codex 等配置，全局/项目范围，copy/symlink）
- 官方 MCP 鉴权（X-Bigmodel-Authorization 等保留头、信任源校验）

### 10. 技能（125 键）/ 11. 命令（66 键）
- 管理 + 导入外部 Agent 技能/命令（copy / symlink）

### 12. 自动化（automations，Beta）／13. 钩子（hooks，60 键）
- Hook：事件、匹配器（Write,Edit,Bash）、命令/参数（argv）、Shell、超时(秒)、后台运行、状态消息、自定义 JSON 字段
- **Hook 信任审核**（沙盒外运行需 Trust，10 种失败原因枚举）
- 导入外部 Hook；插件 Hook / 兼容分组

### 14. 索引库（6 键）
- 索引新文件夹（<50,000 文件）；Instant Grep 索引（本地存储）

### 15. 使用统计（143 键）
- 会话活跃度、模型用量（饼图/日趋势）、编程套餐用量、缓存命中

## 二、引擎侧其它关键发现

- **权限模式**：`["default","yolo","plan","edit","acceptEdits","auto","dontAsk","bypassPermissions","autoEdit","build"]` 十别名；chat 工具栏模式切换器带 5 套方言标签映射（claude/codex/gemini/glm/opencode），GLM 原生五档：默认/变更前确认(build)/自动编辑(edit)/计划(plan)/完全访问(yolo)
- **远程工作区目标**：ssh / wsl / docker / server 四种（凭证走 credentialKey，SSH 支持 password/private-key/agent）
- **Web 远程控制**：外部中继设备（deviceSid）+ 上下文记录
- **设置同步**（settingsSync）+ 引导迁移（从外部 Agent 导入设置的 onboarding，46 键）
- 模型目录 schema `zcode.model-providers.v1`：per-kind endpoints.paths、reasoning.levels per-kind set/unset 路径映射

## 三、AndMX 现状（ui2/settings 13 页 + 零散项）

已对齐：主题/语言、显示思考/待办、交互行为(queue/guide)、通知+声音、终端字体、任务归档、代码预览（字号/主题/行号/换行）、自定义 Provider（三协议 + /models 目录拉取 + ClaudeMapping）、MCP 管理、插件市场+管理、技能管理、子智能体（含 permissionMode + 工具范围）、命令管理、索引（新文件夹/InstantGrep）、使用统计、模型轨迹、记忆查看器、审批规则管理、额外指令/persona、状态面板。

## 四、差距清单（按优先级）

### P0 —— 设置页整区缺失
| # | 缺失 | ZCode 对应 | 说明 |
|---|------|-----------|------|
| 1 | **Hooks 设置页** | settings.hooks（60 键） | 引擎已有 `agent/hooks/HookSystem.kt`，纯缺 UI：事件/匹配器/命令/超时/后台/状态消息；信任审核可后置 |
| 2 | **记忆开关** | memoryEnabled | `MemoryConfig` 有 generate/use 但无用户开关；MemoryViewerPage 只读 |
| 3 | **ComputerUse 设置页** | settings.computerUse | computeruse/ 引擎齐全，缺启用开关 + composer 入口开关 |
| 4 | **Automations 设置页** | Beta | 完全空白，可最后做 |

### P1 —— 常规页缺失项（引擎改动小，UI+DataStore 为主）
| # | 缺失 | 移动端形态 |
|---|------|-----------|
| 5 | 提问自动继续（5min） | AskUserQuestion 超时自动继续 |
| 6 | 工具分组 ×3 开关 | TurnProcessFolding 已有折叠，补用户开关 |
| 7 | 完整保留模型 I/O | 关闭轨迹记录的自动清理/截断 |
| 8 | 性能模式 | 精简渲染（Markdown 降级） |
| 9 | 保持唤醒 | Android WakeLock |
| 10 | 网络代理（httpProxy/noProxy/CA） | OkHttp 代理 + 证书注入 |

### P2 —— 权限模式体系
- AndMX 三档（FULL/ASK/READ_ONLY，Codex 风格）vs ZCode GLM 五档（default/build/edit/plan/yolo）
- 缺 edit（自动编辑）/plan 的独立 UI 档位；子智能体 permissionMode 已对齐
- 建议：模式枚举扩为五档，保留别名映射

### P3 —— 模型设置差距
- OAuth/套餐连接方式（zai、bigmodel coding plan：体验/个人/团队、购买页）——Android 可先做 OAuth + 套餐展示
- 内置供应商目录（当前只有 /models 拉取，无内置 catalog）
- 外部 Agent 配置导入（MCP/插件/技能/命令/Hook 从 Claude Code 等）——移动端价值存疑，Android 无文件系统互访便利

### P4 —— 结构性差距（评估后再定）
- 远程工作区：AndMX 仅 SSH；ZCode 有 wsl/docker/server + MCP 同步到远端（wsl 无移动端意义）
- 浏览器设置页：BrowseTool 为 URL 抓取，无内置浏览器会话，ZCode 的浏览器数据管理不适用；可做 WebView 相关设置
- 设置同步 / 引导迁移 / Chrome 数据导入：桌面专属，不做

## 五、建议落地顺序

1. P0-1 Hooks 设置页（引擎已在，收益/成本比最高）
2. P0-2 记忆开关 + P0-3 ComputerUse 设置页（同批小改动）
3. P1 常规页补齐（一批 DataStore + UI 工作）
4. P2 权限模式五档化
5. P3 模型设置增强
