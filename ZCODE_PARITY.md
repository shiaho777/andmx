# AndMX ↔ ZCode 1:1 对标报告

对标时间：2026-10-29
上游基线：`github.com/zai-org/ZCode`

## 一、上游同步结论：没有落后

| 项 | 值 |
|----|-----|
| 本地快照 | `ZCode-main/`，commit `3c5b227`（2026-10-20 `chore: sync ZCode source`） |
| 上游 `origin/main` | `3c5b227` —— **与本地完全一致** |
| 根版本号 | `package.json` → `3.16.2` |
| 最新 tag | `v3.15.1`（3.16.2 尚未打 tag） |
| 旁支 | `feat/ui-plugin` @ `662c30b`，未合入 main |

结论：无需重新爬取，`ZCode-main/` 就是当前上游 main。后续对标以此为准。

## 二、判定口径

- **缺口**：上游存在、AndMX 无对应实现或语义不等价。
- **超集**：AndMX 多出来的能力，不算缺口，但需确认是否刻意。
- **N/A**：上游能力依赖桌面/服务端形态，Android 端无对应场景。

## 三、工具面差异

上游 `builtInTools` 共 44 项（`apps/zcode-cli/packages/core/src/tool/handlers/index.ts:80`）。
AndMX `agent/` 下工具名去重 94 个（含插件工具）。

### 3.1 缺失（P0/P1）

| 工具 | 上游位置 | 作用 | 判定 |
|------|----------|------|------|
| `ToolSearch` | `handlers/tool-search.ts` | 按需检索并加载工具定义 | **已补（local 模式，2026-11-02）** |
| `DeferredToolCall` | `handlers/deferred-tool-call.ts` | 调用尚未加载定义的延迟工具 | **已补（local 模式，2026-11-02）** |
| `ReplyToChannel` | `handlers/reply-to-channel.ts` | 向 IM 会话原生回复（含 @ 提及） | N/A（Android 无 Bot 频道场景） |

`ToolSearch` + `DeferredToolCall` 不是孤立工具，而是**一整套上下文预算机制**：
上游约 20 个工具声明 `shouldDefer: true`（`Cron*`、`OffPeak*`、`TaskOutput`、`SendMessage`、
`CreateWorkflow` 系列、`js`、`EnterPlanMode` 等），主请求只投递摘要，命中后才展开 schema。

AndMX 现状（2026-11-02）：

- `agent/Tool.kt` 增加 `shouldDefer`，`ToolSearch` / `DeferredToolCall` 落在
  `agent/ToolSearch.kt`，语义对齐上游 **local** 模式（故意不实现 provider-native 的
  `tool_reference` / `deferLoading`）。
- `ToolSearchRegistry` 每次 `runTurn` 接上引擎的实时工具面与当轮 `hiddenTools`；deferred
  工具不随请求发送，只在请求内的 `<system-reminder>` 公告名字。命中后模型拿到完整
  `<functions>` schema，再经 `DeferredToolCall({name,input})` 调用。
- 引擎在审批 / hooks / timeout / 输出预算之前解包 `DeferredToolCall`，所以风险策略看到的是
  目标工具而不是控制壳；直接调 deferred 工具、或未加载就调 `DeferredToolCall`，都会得到
  带 `ToolSearch` 指引的错误结果。
- `ToolSearch` 的 schema 结果不受 8k 历史输出预算截断（`AgentEngine.trimToolOutput` 单点例外），
  否则「已加载」会变成残页。
- 已标记 deferred 的 AndMX 工具：`TodoRead/Write`、`WebFetch/WebSearch`、`Cron*`、
  `OffPeak*`、`Agent/Task/TaskOutput/TaskStop/SendMessage/RespondToCoordinator`、
  `ListModels`、`ReadSessionContext`、`Enter/ExitPlanMode`、`webview_js`、全部 MCP 工具、
  以及全部 `*Workflow*` 工具（`RespondToCoordinator` 只出现在子代理面）。
- 仍缺：`native` 模式；discovered 集合跨压缩/会话重建的持久化；子代理引擎尚未接
  registry；没有 runtime 开关（当前有 deferred 工具即启用）。

### 3.2 语义不等价

| 上游 | AndMX | 差异 |
|------|-------|------|
| `js`（persistent Node REPL / Code-Act，`sideEffectScope: system`、`riskLevel: high`） | `webview_js`（`agent/JsReplTool.kt:102`） | AndMX 自述 "This is a WebView evaluator, not a host REPL"。运行时、作用域、风险级别均不同 |
| `Agent`（canonical，providerVisible）+ `Task`（别名，`providerVisible: false`） | `spawn_agent` / `multi_agent` + `Task`（`agent/multi/SubAgentOrchestrator.kt:622/660/845`） | 上游的 canonical 名是 `Agent`，`Task` 只是 Claude Code 兼容别名；AndMX 反了过来，且多拆出一个 `multi_agent` |

### 3.3 编译期关闭（已修：改为运行时开关）

`app/src/main/java/com/andmx/agent/zcode/DynamicWorkflowSchema.kt` 原为 `const val DYNAMIC_WORKFLOW_TOOLS_ENABLED = false`，
`ChatController` 的 `if` 被编译器折叠，11 个工具永远不可达。

现状（2026-10-29）：

- 常量改为 `DYNAMIC_WORKFLOW_TOOLS_DEFAULT`，只作默认值；
- 真实开关走 `ProviderSettings.dynamicWorkflowToolsEnabled`，由 `ChatController.settingsSnapshot` 在每次建工具面时求值；
- `applyDynamicWorkflowGate`、`agentProviderDescription`、`taskProviderDescription`、`WorkflowPrompt` 全部改为接收运行时值；
- 切换开关会重建空闲会话，使新工具面生效。

**尚未解决**：11 个工具里只有 3 个（`ListWorkflowRuns`/`GetWorkflowRun`/`ListSavedWorkflows`）接上了
`WorkflowService`，其余 7 个 `execute()` 仍返回 `*_UNAVAILABLE` 桩。因此在桩接完之前**不要把开关暴露到 UI**，
否则模型会被 `/workflow` 引导去调用必然失败的工具。

### 3.4 超集（需确认是否有意）

| AndMX 多出 | 上游对应 |
|------------|----------|
| `create_goal` / `update_goal` / `get_goal` | 上游仅有 `GoalRead`（`handlers/target.ts`），且当前**未**进入 `builtInTools` |
| `apply_patch` | 上游 `applyPatchToolEntry` 在 `builtInTools` 中被注释掉（`// applyPatchToolEntry,`） |
| `android_*`（21）、`storage_*`（10）、`forge_*`（7）、`html_video_*`（10）、`memory_*`（4）、`computer`、`browse`、`git`、`list_dir`、`update_plan`、`web_search`、`webview_js` | 上游同类能力在 `apps/zcode-cli/packages/*-plugin` 中，不属 core builtin |

### 3.5 脚本静态分析：照搬上游，不重写（2026-10-29）

动态工作流的 `CreateWorkflow` / `AmendWorkflow` / `EvalWorkflowSnippet` / `SaveWorkflow` 都要先
「编译并分析脚本」，上游这层是 `analyzeWorkflowScript`（`packages/dynamic-workflow/src/analysis`，
**13,626 行 / 57 文件**）。它建立在 TypeScript 编译器之上：

```
ts.createProgram({host, options, rootNames: [SCRIPT_FILE_NAME, FACADE_FILE_NAME]})
  -> program.getSyntacticDiagnostics() + program.getSemanticDiagnostics()
  -> collectSites（站点表）-> interpret（taint 不动点 + 时序走查）-> 三个纯投影
```

**决定：不写 Kotlin 复述，把上游那份代码自己跑起来。**

理由：用 Kotlin 重写它等于重写一个 TypeScript 类型检查器。写出来的一定不是同一个东西——
诊断码、文案、图节点都会漂，而「能存下来但跑不起来」正是这个特性最难解释的一种坏掉方式。

做法（`tools/dwf-analyzer/`）：把 `analyzeWorkflowScript` + TypeScript 编译器打成单个 IIFE
（4.0 MiB），放进 `app/src/main/assets/dwf/analyzer.js`，由 WebView 执行。实测数据：

| 指标 | 值 |
|------|-----|
| 载荷 | 4.00 MiB（`analyzer.js`，APK 内压缩后约 1 MiB） |
| 沙箱初始化 | 127 ms（无 `require` / `process` / `fs` / `Buffer`，等价 WebView 页面） |
| 13.8 KB 真实脚本分析 | 268 ms |
| 类型错误脚本 | 45 ms，吐出上游原码 `1184` / `2322` / `2345` / `2304` |
| `analyze` 结果 | 4,005 B |
| `analyzeFull`（含 `core`） | 356,900 B |

桥接取舍：`core` 是内部铸造物（taint 事实 + 时序轨迹），唯一用途是被投影；投影在 bundle
里已经做完，所以 `analyze` 摘掉它，`analyzeFull` 才给全集。其余字段名、类型、嵌套形状逐字不动。

Kotlin 侧（`agent/workflow/script/`）：

- `WorkflowAnalysis.kt` —— 解码层。完整解码 `diagnostics` / `declaredArtifacts` /
  `modelReferences` / `graph`（这四个是工具层唯一消费的）；`causality` / `flow` / `handoff`
  原样留在 `raw` 里一个字节不丢，等看板真要做时再补解码器，不必重跑分析。
- `WorkflowScriptAnalyzer.kt` —— 接口、`WebViewWorkflowScriptAnalyzer`（页面只加载
  `file:///android_asset/dwf/analyzer.html`，关网络/跨源/ContentProvider，脚本经
  `JSONObject.quote` 转字面量）、以及 `MemoizedWorkflowScriptAnalyzer`——上游
  `workflow-script-analysis.ts` 的**单槽记忆**（审批 gate 与 handler 各分析一次，按脚本原文
  折叠成一次编译；刻意不是通用缓存）。

验证：`node tools/dwf-analyzer/verify.mjs` 是构建 + 真沙箱校验关口；
`emit-fixtures.mjs` 用真分析器跑真脚本产出 `app/src/test/resources/dwf/*.json`，
Kotlin 单测对着这些**上游真实输出**断言，而不是对着手写期望值。

### 3.6 `CreateWorkflow` 已接上：三源解析 + 分析闸门

上游 `create-workflow-source.ts`(338) 是 `CreateWorkflow` / `AmendWorkflow` / `EvalWorkflowSnippet`
共用的「两条来源合流成一份脚本」的合流点，也是全流程唯一一次读盘。已照搬：

| 新增 | 对齐的上游 | 行数 |
|------|-----------|------|
| `agent/workflow/CreateWorkflowSource.kt` | `create-workflow-source.ts` + `workflow-path-source.ts` | 417 |
| `agent/workflow/WorkflowArgs.kt` | `saved-workflows/args.ts` | 124 |
| `agent/workflow/WorkflowScriptNotes.kt` | `workflow-script-notes.ts` + `workflow-script-path.ts` | 124 |

接进 `CreateWorkflow` 之后的执行链：

```
validateSource（三选一 XOR；args 只跟 path 走）
  → resolve（script 恒等 / saved 走 SavedWorkflowStore / path 读盘并剥元数据块）
  → analyzer.analyze（上游 analyzeWorkflowScript）
      ├─ !ok → 「The workflow script has errors:」+ 逐条 `L:C message`（有文件则按文件行）
      └─ ok  → 分析事实（产物清单、脚本点名的模型、站点数）+ EXECUTION_UNAVAILABLE
```

失败关闭的三道口：**来源缺失**、**没有分析器**、**分析器不可用**——三者都拒绝执行，绝不默认通过。
旧的 `WORKFLOW_NOT_EXECUTED_NOTE`（「Nothing was typechecked」）已删除：它现在是假话。

一个照搬中发现的上游细节：`describeValue` 里那条 `null` 分支说明**显式 `null` 必须走到类型检查**
（`json` 收它，`string` 拒它并报 `got null`），不能被当作「没给」。参数校验据此实现。

仍未接：`AmendWorkflow` / `EvalWorkflowSnippet` / `ResumeWorkflowRun` /
`ResolveWorkflowQuestion` / `FillWorkflowHole`。真正的拦路虎见 3.7。

### 3.7 执行引擎：形态由证据定，不是选择题（2026-10-29）

「把 `engine/` 也照搬，还是按 Android 宿主重写」这个问题我先去证伪了，因为猜错了要赔上几万行。

**决定性事实**：`lowering/lower.ts` 用 **TypeScript 的 transform API** 把脚本站点改写成
`__host.*` 调用并转译成 ES2022 文本；沙箱 cell 再把这段文本当 **async 函数体求值**。
也就是说，执行一份 dwf 脚本**必然是在执行生成的 JavaScript**。任何「用 Kotlin 重写引擎」的方案
本身也得内嵌一个 JS 引擎——只是中间多绕了一层。这条路不存在。

于是只剩一个形态：**WebView 当沙箱，Kotlin 只实现 driver port。**

已实测（`tools/dwf-runner`，`node dist/probe.cjs`）：

| 环节 | 实测 |
|------|------|
| bundle 载入（无 `require`/`fs`/`process`） | 127 ms |
| `prepare`（lowered + ask 规格 + 站点阶段 + 产物/模型清单） | 86–173 ms |
| 真实引擎执行（含 spawn 沙箱子进程） | 53 ms |
| 结果 | `completed`，产物正确，7 条事件、1 行 journal 节点 |

探针跑的是**产品自己那份 bundle**（`app/src/main/assets/dwf/analyzer.js`）——同一份文件既喂
Kotlin 也喂探针，所以它不是「另写一个 demo」，而是那条链路的两端都在被验证。

上游执行层的真实分工（`dynamic-workflow-runtime` 只有 1,570 行）：

| 件 | 归属 | Android 侧怎么办 |
|----|------|------------------|
| `engine/`（32 文件 / 6,300 行） | 纯，**零外部依赖** | 原样进 bundle |
| `child-source.ts` 的 BOOTSTRAP（`__host`/`__deliver`/`__execute` + 运行期禁令） | 自包含纯 JS 字符串 | 原样进 bundle，作为 cell 引导 |
| `protocol.ts`（4 种下行 + 1 种上行） | 纯类型 | 照搬成 Kotlin 侧的线协议 |
| `harness.ts` / `child-entry-file.ts` / stdio | node 专属传输 | **被 WebView + iframe 取代**，不移植 |
| `WorkflowDriver` / `WorkflowHostApi` / `JournalStorePort` | 宿主所有 | Kotlin 实现 |

三条必须在实现时守住的约束（都是读代码读出来的，不是猜的）：

1. **cell 要另一个 realm。** 上游的 cell 是 `vm.createContext` 出来的独立上下文，隔离原型污染，
   而它跨 realm 只放两种值：一个 `__send(string)` 与入站行字符串。WebView 里用 **iframe** 复现；
   同页同 realm 会丢掉这层保护。
2. **cell 里不能有别的唤醒源。** 上游的停滞检测成立的前提正是「裸 vm context 没有定时器、
   没有 fetch」。WebView 页面默认有 `setTimeout`/`fetch`/`XMLHttpRequest`——cell 引导必须先
   把它们抹掉，否则停滞检测会失真。
3. **不需要同步 round-trip。** `createActor` 的同步语义由「本地句柄 + 即发即忘的 create-actor」
   解决，`report`/`enterPhase`/`declareArtifact` 都是同步 void 走事件通道。所以整条桥可以是
   纯异步的——这让 iframe 的 `postMessage` 与 Android 的 JS 桥都够用。

**已做（node 侧全部验证过）**：bundle 补上 `lower` 与 `prepare`；cell 复用上游 `childMain`
做 realm 替换（3.7.1，四用例 + 净化 realm，协议逐行一致）；realm 净化器（3.7.1.1）；与传输
无关的引擎宿主 `src/host.ts`（3.7.3，串行/并发/失败/世界读取/留白补全五种形态，两条执行路径
的 run 事实全等）。

**设备侧 v1 已接线**（2026-11-03）：`assets/dwf/runner.js` 由 `tools/dwf-runner/src/android-entry.ts`
打包；WebView 主 realm + iframe cell、`PortableJournal` 同步桥、`DynamicWorkflowRunner`（Room
`workflow_journals`，DB v21）、actor ask/world read/artifact publish 回调、`CreateWorkflow` 启动与
`ResumeWorkflowRun`/cancel 均已在设备上跑通一条 sequential + ask + files.read 脚本。
尚未接线：`AmendWorkflow`、`FillWorkflowHole`、typed ask 的 reject/nudge 修复回路、动态 run 的
产物卡片/完整看板，以及 fanout/hole 的设备侧回归。

#### 3.7.1 cell 的 realm 替换已验证（2026-10-29）

`tools/dwf-runner/src/cell.ts` 没有重写 cell，而是**复用上游 `childMain`**，只替换它依赖的
三样平台设施（`vm.createContext`/`runInContext`、`readline`、`stdout`），把它们接到
「一个 realm + 一条异步消息通道」上。cell 的语义——`__host` 的每个 shim、`__deliver` 的应答
投递、`__execute` 的终结、运行期禁令——一行都没有重写。

`node dist/cell-probe.cjs` 的一致性对照：同一份 payload、同一个假父进程，只换 realm 的构造方式
（参照 = 真 `node:vm`；候选 = 替换后的 shim 绑到另一个真 vm context），**逐行比对 cell 发出的
NDJSON**。四个用例覆盖 cell 的四条出口：

| 用例 | 覆盖 | 结果 |
|------|------|------|
| `happy` | create-actor / log / report / ask / complete | 5 行，逐字节相同 |
| `throw` | 错误对象跨 realm 重建 | 3 行，逐字节相同 |
| `syntax` | lowered 体编译失败（`SyntaxError` 分支） | 1 行，逐字节相同 |
| `ban` | 运行期禁令（`Date.now()`） | 1 行，逐字节相同 |

`ban` 那条是关键的**真实性检查**：它证明替换后的 realm 里跑的确实是 BOOTSTRAP 本身——否则
`Date.now()` 不会被拦。

对照里唯一的归一化是去掉 `error.stack`：栈文本是环境的产物（帧名、路径、realm 内部实现），
不属于协议；「有没有栈」仍单独断言。

#### 3.7.1.1 realm 净化（2026-10-30）

上游的 cell 是裸 `vm.createContext`，**天然**没有定时器 / fetch / DOM / 父窗口，而停滞检测
成立的前提正是这个（`childMain` 的注释：「cell 内没有定时器与 I/O，所以『无在飞请求且未完成』
在这一刻成立就永远成立」）。iframe 默认全都有，所以设备侧必须先抹掉。

`src/realm-sanitize.ts` 做两件事：抹掉 32 个唤醒源（定时器 / fetch / XHR / WebSocket / Worker /
存储 / console…），以及**断开逃逸通道**（`parent` / `top` / `opener` / `frames` / `self` /
`postMessage`）——留着它们，脚本能绕过 `__send` 直接对宿主说话，而 `__send` 是唯一被落 journal
的出口。

已在 node 里验证（`cell-probe` 的 `sanitized` 段）：先往 realm 注入 8 个唤醒源造出「iframe 那种
什么都有」的环境，净化后逐个断言 `typeof` 变成 undefined、父窗口通道断开，**然后在这个净化过的
realm 上跑完整 cell**——产出 5 行，与参照逐字节相同。这证明 cell 不依赖它们，抹掉是安全的。

与 BOOTSTRAP 的分工：运行期禁令（`Date.now` / 无参 `new Date()` / `Math.random`）仍由
BOOTSTRAP 自己做（它要在同一 realm 里替换 `Date` 与 `Math.random`），净化只管内核之外的东西。

#### 3.7.2 下一步的真实规模

`harness.ts` 637 行按职责拆开：

| 段 | 行 | Android 侧 |
|----|----|-----------|
| `runWorkflowScript`（建引擎 + 写入口 + spawn） | ~100 | spawn 那半被 WebView 取代 |
| `bridge`（结算 / 超时 / signal / 子进程退出） | ~130 | 语义要保留，载体换成 WebView 生命周期 |
| `handleChildMessage` + `handleRequest`（**与传输无关**） | ~145 | 原样搬（这是核心） |
| 类型与工具函数 | ~50 | 搬 |

也就是说，真正要重写的只有生命周期那一段；消息分派那 145 行是机械工作。

还有一个**必须显式决定**的架构问题：引擎是 JS，所以 `handleChildMessage` 也在 JS 里，
Kotlin 只实现 driver port。那么 journal 落在哪一侧？

- 落在 JS（`InMemoryJournalStore`）：零桥接开销，但进程被杀就全没了，而上游整套 resume
  机制都建立在 journal 耐久之上。
- 落在 Kotlin（Room）：`JournalStorePort` 是**同步**接口（`createRun`/`putNode` 都是 void），
  过桥要么用 `@JavascriptInterface` 同步调用阻塞 JS 线程，要么把接口改成异步——后者等于改上游。

这一条我倾向「同步桥 + Kotlin/Room」，理由是 resume 的耐久性是硬需求；但在写下第一行之前
应当先做一次量测（一次典型 run 的 `putNode` 次数与单次耗时），而不是凭直觉定。

**这条已经被量掉了**（见 3.7.3）：一次两 ask 的 run 是 **10 次 journal 写入 / 29 次调用**。
按一次同步桥 0.1–1 ms 估，整条 run 的桥接代价在十几毫秒量级，而它换来的每次 ask 都是秒级的
模型调用——同步桥的代价可以忽略。**决定：journal 落 Kotlin/Room，走同步桥。**

#### 3.7.3 宿主对照：两条执行路径的 run 事实一致（2026-10-30）

`tools/dwf-runner/src/host.ts` 是与传输无关的引擎宿主：它只对一条 NDJSON 端点说话，不知道
对面是子进程还是 iframe。它搬的是上游 `bridge` / `handleChildMessage` / `handleRequest`
那约 145 行（与进程无关的那一半），并**刻意保留**这些语义：

- single-writer finalize（引擎结算 / 超时 / abort / 沙箱死亡，第一个到达者胜出）
- `create-actor` 同步处理（FIFO 保证句柄映射先于引用它的 ask）
- 事件同步分派（`report` 与 `declare-artifact` 的到达顺序就是 journal 顺序）
- 宿主侧故障 → `stopped(interrupted)` 可 resume；脚本抛错才是 `failed`
- 协议损坏大声拒绝，不猜不跳过

`node dist/host-probe.cjs` 把两条路径跑同一组脚本，三个用例覆盖三种 run 形态：

| 路径 | 沙箱 | 传输 |
|------|------|------|
| A 参照 | node 子进程 | stdio NDJSON |
| B 候选 | 本进程 vm realm（设备上换 iframe） | 异步端点（设备上换 postMessage） |

| 用例 | 形态 | 结果（两条路全等） |
|------|------|------------------|
| `sequential` | 两个 actor、两次串行 ask | `ask#1#1` `ask#2#1` 皆 completed；10 次 journal 写入 |
| `fanout` | 三个 actor、三次**并发** ask | 同站点 `ask#1#1..3` 皆 completed；14 次写入 |
| `errored` | ask 被拒 → run 失败 | 节点 `ask#1#1:failed`，run 行 errored，错误文案相同；6 次写入 |
| `worldread` | `files.read` + `files.glob` | 两个 `world-read` 节点皆 completed；10 次写入 |
| `hole` | 留白经控制面 `fillHole` 补全 | completed，补全值确实流进了 ask 指令；7 次写入 |

比对项：结算状态与产物、run 行状态、ask 派发站点集合、ask 指令**逐字**、journal 节点内容、
journal 写入次数、错误文案。`sequential` 还额外断言 journal 的**插入顺序**（并发下这个顺序本就
非确定，故其余用例按 `(siteId, ordinal)` 排序后比内容——比的是内容与 ordinal 分配，不是落库时序）。

`hole` 那一格值得单独说：补全不是测试直接塞给引擎的，而是由一个 poller 扮演主代理——轮询
`engine.openHoles()`，再调 `engine.fillHole(...)`。走的就是 `FillWorkflowHole` 将来的那条路。

journal 写入的次数就是「同步桥可行性」的答案：**每次 ask 约 4 次写入**，`world-read` 同量级，
加上少量固定开销。按一次同步桥 0.1–1 ms 估，百级 ask 的 run 全程累计不过数毫秒到数百毫秒，
且分散在多次秒级模型调用之间。

与上游的一处**必然差异**已记在 `host.ts` 文件头：上游靠子进程 `close` 事件判「沙箱没了但引擎
未结算」，而 `close` 在 stdio 排空之后才触发；设备上没有进程可等，所以改成端点 `done` 之后再
等一个宏任务，否则 `complete` 那一行还在路上就会被误判成崩溃。

**仍未覆盖**：内容产物发布（`artifact.file` / `artifact.markdown` → `publishArtifact`）、
`resume`、`amend`、以及控制面的 `setMaxConcurrency`。这些路径在 `host.ts` 里都接了，
但还没有对照用例——不要把这个绿灯读成「全路径都验过了」。

## 四、子系统深度差异

以上游 `packages/core/src/` 对应目录文件数为口径：

| 子系统 | 上游 | AndMX | 缺口 |
|--------|------|-------|------|
| `subagent/` | 22 文件 | 4 文件（`SubAgentOrchestrator` 1198 行 + Catalog/ModelCatalog/Storage） | 缺 `message-steering`、`computer-use-policy`、`persistent-memory`、`tool-policy`、`tool-event-mirror`、`coordinator-response`、`mcp-config` 等独立分层 |
| `hooks/` | 19 文件 | 1 文件 / 229 行（`HookSystem.kt`） | 缺 `workspace-hook-policy`、`workspace-hook-review-flow`、`runtime-admission`、`telemetry`、`session-mailbox` |
| `memory/` | 10 文件 | 3 文件 / 795 行 | 缺 `dream`、`recall/`、`extraction`、`project-memory-retrieval-branch` |
| `runtime/` | 17 文件 | 逻辑散落在 `ChatController`（2812 行） | 缺 `command-queue`、`execution-state`、`permission-grant-recovery`、`model-selection` 的独立分层 |
| `compact/` | 6 文件 | `ContextCompactor` + `Microcompact` + `RapidRefill` + `ContextBreakdown` | 结构基本对齐 |
| `permission/` | 6 文件 | `PermissionService` + `PermissionModes` + `BashCommandParser`(723 行) + `CollaborationMode` + `guarded/` | 结构基本对齐，粒度更细 |

`hooks` 事件集对比：

- 一致：`SessionStart`、`UserPromptSubmit`、`PreToolUse`、`PostToolUse`、`PostToolUseFailure`、`Stop`、`PermissionRequest`
- AndMX 多出：`PreCompact`、`PostCompact`

## 五、刻意差异（不计入缺口）

| 项 | 上游 | AndMX | 原因 |
|----|------|-------|------|
| 执行环境 | 宿主 Node 进程 | `lite`（Kotlin/JGit）+ `proot`（Alpine guest，targetSdk 28） | Android W^X：targetSdk 29+ 禁止从可写目录 exec |
| 权限档位 | 3 档（`default` / `plan` / `yolo`） | 6 档（`BUILD` / `EDIT` / `AUTO` / `YOLO` / `PLAN` / `GUARDED`） | 移动端单手操作需要更细的默认档 |
| 斜杠命令 | `/compact`、`/init`、`/workflow` 等少量 | 50+ 个（含 `/rewind`、`/branch`、`/handoff`、`/mode` 等） | 移动端无键盘快捷键，需命令补位 |
| UI | 桌面 + Web + TUI 三端 | 仅 `ui2`（Compose） | 产品定位 |

## 六、建议补齐顺序

1. ~~**P0｜打开动态工作流**~~（已完成基础设施）：`DYNAMIC_WORKFLOW_TOOLS_ENABLED` 已改为运行时 flag。
   下一步是把它真正打开：先接完剩余 7 个工具的桩，再把设置开关放出来。
2. **P0｜工作流桩接线**（进行中）：已完成 `ListWorkflowRuns` / `GetWorkflowRun` / `ListSavedWorkflows` / `SaveWorkflow` /
   **`CreateWorkflow`**（见 3.6）；
   `AmendWorkflow` / `EvalWorkflowSnippet` / `ResumeWorkflowRun` / `ResolveWorkflowQuestion` / `FillWorkflowHole`
   仍返回 `*_UNAVAILABLE`。已完成的前置件：
   - `agent/workflow/WorkflowScriptFrontmatter.kt` —— 文件头编解码（对齐上游 `frontmatter.ts`）。
   - `agent/workflow/YamlSubset.kt` —— 该文件头所需的 YAML 子集解析器，超出子集即带行号报错。
   - `agent/workflow/SavedWorkflowStore.kt` —— 保存库（对齐上游 `store.ts`）：项目/全局双档、
     first-wins 遮蔽、坏文件单独列出、路径穿越防护、`save` 的覆盖标记。
   - `agent/workflow/CreateWorkflowSource.kt` + `WorkflowArgs.kt` + `WorkflowScriptNotes.kt` ——
     `create-workflow-source.ts` 的三源解析、参数校验与模型面文案。
   - **静态分析层已就位**（见 3.5）：上游分析器已在 assets 里跑通，Kotlin 解码层与单槽记忆到位。
   - 剩余：`create-workflow.ts` 的确认窗与 run 启动、`AmendWorkflow` 的三态模型继承、
     `FillWorkflowHole` 的留白拼接，以及**脚本执行引擎**——形态已由证据定下，见 3.7。
3. ~~**P0｜ToolSearch / DeferredToolCall**~~（已完成 local 模式，2026-11-02）：`shouldDefer` 已落到
   `Tool` 接口，两个控制工具与 `ToolSearchRegistry` 已接线到主引擎，deferred 工具覆盖见 3.1。
   下一步是把 discovered 集合写进压缩/恢复元数据，并评估子代理是否也接入同一机制。
4. **P1｜`js` 工具语义**：决定是「Android 不提供 host REPL（保持 `webview_js` 并改文档口径）」，还是补一个受限的 JS 执行面。当前是隐性偏离，模型侧描述已自认不同。
5. **P1｜subagent 分层**：至少补 `tool-policy`（子代理工具裁剪）与 `message-steering`（运行中插话，`AgentEngineSteerTest` 已覆盖引擎侧，需核对编排侧是否等价）。
6. ~~**P2｜hooks 工作区审核流**~~（已完成，2026-11-02）：`.zcode/hooks.json` 发现、SHA-256 信任固定、HooksPage 审查/撤销、远程 SSH 执行。
   ~~**memory recall/dream**~~（已完成，2026-11-02）：query-aware recall 与 Dream 去重/限流。

## 七、待核对项

- `Agent` 工具名缺失对第三方插件指令的影响：若插件 prompt 里写 "use the Agent tool"，AndMX 只有 `Task` 别名可命中。
- `multi_agent` 的上游对应物：上游似无此工具名，需确认是 AndMX 自造还是多代理并发入口的替代名。
- ~~`memory` 的 `recall/` 检索路径是否被 `MemorySystem.kt` 以另一种形式覆盖~~（已确认并补齐：`MemoryRecall` + `recallFragment`）。

## 八、2026-11-02 移动端闭环进度

已合入：

- **入站分享/外链/通知**：`ACTION_SEND`（文本/图片）、`ACTION_VIEW`、通知 `conversationId` 都能进入 Composer 或切换会话。
- **图片消息**：用户图片持久化到 `MessageEntity.imageUrlsJson`，并在气泡中显示。
- **终端/Bash**：终端会话改为进程级 registry，关闭 sheet 不再销毁 PTY；`ShellTool` 增量输出进入工具卡。
- **自定义命令/AUTO**：自定义命令进入斜杠补全并支持 `$ARGUMENTS`；AUTO 改为风险分级，不再是必拒绝占位。
- **设置/命令中心**：设置首页搜索 + 关于页；命令中心可搜命令、最近任务、索引文件。
- **后台任务/草稿**：后台 shell 任务面板（状态/输出/停止）；Composer 文本按 conversationId 持久化。
- **文件预览**：图片、PDF（前 10 页）、音频/视频、docx/pptx/xlsx 文本提取；Office 另可交给系统应用打开。
- **Git**：fetch、`pull --ff-only`、仓库级提交身份、提交前逐文件 diff。
- **审批**：子代理与工作流 actor 接入会话审批；`permissionMode` 真正映射为权限策略，父会话 Plan 时强制只读。
- **会话状态**：`workspace_changes`（DB v19）按会话持久化文件快照；fork 复制、rewind/edit 清空旧队列；
  每个 task 恢复队列与滚动位置；任意 assistant 轮可重试；子代理 `maxTurns` 硬限制。
- **审批恢复**：`permissionGrantsJson`（DB v20）持久化 SESSION_ALLOW/DENY，进程重启后仍生效。
- **Hooks 信任**：发现 `.zcode/hooks.json`；信任绑定文件 SHA-256，改动即失效；HooksPage 可审查/信任/撤销；
  远程工作区通过 SSH 执行。
- **记忆 recall/dream**：按请求打分注入相关记忆；Dream 去重并限流原始记忆，重建 MEMORY.md 与摘要；
  记忆页可巩固、整理与删除单条。
- **真实用量**：优先读取 rollout 的 `token_usage` 与 `turn_context.model`；旧会话回退到消息估算。
- **变更摘要**：`DiffEngine.lineStats` 先裁公共前后缀再算 LCS，大窗口回退多重集；文件改动弹窗显示真实 +A/-D，
  并支持逐行 diff 预览（超大变更有界降级）。
- **MCP resources/prompts**：4 个只读工具接入 agent；stdio/HTTP 都支持 list/read/subscribe 与 prompts list/get；
  设置页测试连接显示工具/资源/提示数量。
- **MCP OAuth**：HTTP 连接支持预置 access token、refresh_token 刷新与 client_credentials；
  手机端浏览器授权码 + PKCE 回调（`andmx://mcp-oauth`）已接通；token 按 server name + endpoint 隔离持久化。
- **工作区索引接入工具**：Grep/Glob 在索引就绪时走快速路径；写入后立即 dirty、回退文件扫描并延迟重建。
- **模型轨迹持久化**：`ModelCallTrace` 镜像到 `files/model-traces.json`，重启后仍可查看最近 50 次调用。
- **工作流恢复**：`ResumeWorkflowRun` 接入 `WorkflowService.resume`，模型侧与 `/dwf resume` 行为一致。
- **动态脚本 runner v1**：WebView + iframe cell、`runner.js`、Room journal（DB v21）、
  actor ask / world read / artifact publish 桥；`CreateWorkflow` 启动、`ResumeWorkflowRun`/cancel 已接线，
  sequential + ask + files.read 在设备上跑通并落 journal。
- **文档附件**：Composer 分开「添加图片」「添加文档」；可读文本/代码/JSON 正文真正内联进模型上下文。
- **定时任务**：`AndmxApp` 安装冷启动 `AutomationWake.onDue`，闹钟/BOOT_COMPLETED 拉起进程后能继续执行到期任务。
- **文件树变更**：宿主工作区路径映射到 guest 路径，changedOnly 与变更徽标不再按文件名误判。

仍缺（按优先级）：

1. **动态工作流 v2**：v1 已能跑 sequential/ask/files.read 并持久化/恢复 journal；仍缺
   `AmendWorkflow`、`FillWorkflowHole`、typed ask 修复回路，以及 fanout/hole 的设备侧回归与完整产物看板。
2. **MCP OAuth DCR**：浏览器授权码 + PKCE 已接；尚未实现动态客户端注册（Dynamic Client Registration），
   用户仍需自备 clientId/clientSecret。
3. **合并冲突处理**：逐文件 diff 已有，但 merge/rebase 冲突的交互式解决仍未做。
4. **i18n 完整覆盖**：设置与聊天主链路已覆盖，长尾页面仍有硬编码中文。

