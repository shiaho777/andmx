// 端到端探针：**用 products 自己的 bundle 准备输入，再用上游真实引擎执行**。
//
// 链条：
//   app/src/main/assets/dwf/analyzer.js   （Kotlin 侧同一个文件）
//     → __DWF.prepare(script)             （lowered 函数体 + ask 规格 + 站点阶段 + 产物/模型清单）
//     → runWorkflowScript({lowered, …})   （上游 harness：沙箱子进程 → 引擎 → journal）
//     → AutoDriver 扮演模型
//
// 它回答两件事，都不是「上游有没有 bug」：
//   1. 执行 dwf 脚本是否必须有一个 JS 运行时？（是——lowering 用 TypeScript transform 把脚本
//      转成 JS 文本，cell 再把它当函数体求值。Kotlin 重写这条路不存在。）
//   2. bundle 交出的执行前输入，引擎能不能直接吃？（这就是本文件在断言的事。）
//
// 与 Android 的唯一差别是传输：这里 harness 用 node 子进程，设备上用 WebView。
// 引擎、lowering、协议、cell 是同一份代码。
//
// 失败即非零退出，可以直接挂进 CI。
import { readFileSync } from "node:fs";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import vm from "node:vm";
import { InMemoryJournalStore, validate } from "@zcode/dynamic-workflow";
import { runWorkflowScript } from "@zcode/dynamic-workflow-runtime";
import { AutoDriver } from "../../ZCode-main/apps/zcode-cli/packages/dynamic-workflow-runtime/tests/auto-driver.js";

const SCRIPT = String.raw`/* zcode-workflow
description: 执行管线探针
*/
const reviewer = agent('评审甲', { system: '你负责挑错' });
log('开始评审');
const notes = await reviewer.ask('挑出这段代码的问题');
return notes;
`;

const EXPECTED_EVENTS = [
  "run-started",
  "actor-created",
  "log",
  "node-queued",
  "node-dispatched",
  "node-settled",
  "run-settled",
];

const failures: string[] = [];

function check(label: string, condition: boolean, detail?: unknown): void {
  if (!condition) failures.push(`${label}${detail === undefined ? "" : ` — ${JSON.stringify(detail)}`}`);
}

/** 在无 require / process / fs 的上下文里跑 bundle——与 WebView 页面的条件等价。 */
function loadBundle(): Record<string, unknown> {
  const path = resolve(__dirname, "../../../app/src/main/assets/dwf/analyzer.js");
  const source = readFileSync(path, "utf8");
  const sandbox = { clearTimeout, console, performance, setTimeout, TextDecoder, TextEncoder, URL };
  sandbox.globalThis = sandbox;
  const context = vm.createContext(sandbox);
  new vm.Script(source, { filename: "analyzer.js" }).runInContext(context);
  const host = sandbox.__DWF;
  if (host === undefined) throw new Error("bundle 未挂载 __DWF");
  return host;
}

interface Prepared {
  askSpecs: Array<[string, { typed: boolean; schema?: unknown }]>;
  declaredArtifacts: Array<{ id: string; kind: string }>;
  diagnostics: Array<{ code: number; line: number; column: number; message: string }>;
  lowered?: { code: string; siteIds: string[]; holeBodies: Record<string, string> };
  modelReferences: Array<{ name: string; line: number; column: number }>;
  ok: boolean;
  sitePhases: Array<[string, string]>;
}

async function main(): Promise<void> {
  const bundleStartedAt = Date.now();
  const host = loadBundle();
  const bundleLoadMs = Date.now() - bundleStartedAt;

  const prepareStartedAt = Date.now();
  const prepared = (host.prepare as (script: string) => Prepared)(SCRIPT);
  const prepareMs = Date.now() - prepareStartedAt;

  check("bundle 能为这份脚本准备好执行输入", prepared.ok === true, prepared.diagnostics);
  check("bundle 给了 lowered 函数体", typeof prepared.lowered?.code === "string");
  check("bundle 给了 ask 规格", prepared.askSpecs.length === 1, prepared.askSpecs);

  if (!prepared.ok || prepared.lowered === undefined) {
    process.stderr.write(`\n无法继续：prepare 失败\n`);
    process.exit(1);
  }

  const cwd = mkdtempSync(join(tmpdir(), "andmx-dwf-runner-"));
  const journal = new InMemoryJournalStore();
  const driver = new AutoDriver(journal, {});

  const runStartedAt = Date.now();
  const settlement = await runWorkflowScript({
    lowered: prepared.lowered.code,
    scriptText: SCRIPT,
    cwd,
    runId: "probe",
    caps: { maxConcurrency: 4 },
    askSpecs: new Map(prepared.askSpecs),
    sitePhases: new Map(prepared.sitePhases),
    // 引擎不 import schema 实现，校验器由宿主注入。本脚本的 ask 是 untyped，不会走到它。
    validate: (schema, value) => validate(schema as never, value),
    makeDriver: (sink) => {
      driver.attach(sink);
      return driver;
    },
    timeoutMs: 30_000,
  });
  const runMs = Date.now() - runStartedAt;

  const events = driver.events.map((event) => event.type);
  const nodes = journal.listNodes("probe", { kinds: "all", withResult: false });
  const asks = driver.startAsks.map((call) => ({
    instructions: call.message.instructions,
    siteId: call.instance.siteId,
    typed: call.message.typed,
  }));

  check("run 结算为 completed", settlement.status === "completed", settlement.status);
  check("脚本的返回值就是 run 的产物", settlement.artifact === "ok", settlement.artifact);
  check("ask 按 bundle 给的规格派发到正确站点",
    asks.length === 1 && asks[0]?.siteId === "ask#1",
    asks);
  check("指令逐字到达",
    asks[0]?.instructions === "挑出这段代码的问题",
    asks[0]?.instructions);
  check("事件序列与上游一致",
    EXPECTED_EVENTS.every((type) => events.includes(type)),
    events);
  check("journal 落了一行 ask 节点",
    nodes.length === 1 && nodes[0]?.kind === "ask",
    nodes.map((node) => node.kind));

  process.stdout.write(
    `dwf-runner: bundle ${bundleLoadMs}ms, prepare ${prepareMs}ms, 执行 ${runMs}ms, `
    + `事件 ${events.length} 条, 节点 ${nodes.length} 行\n`,
  );

  if (failures.length > 0) {
    process.stderr.write(`\n校验失败 ${failures.length} 项:\n${failures.map((f) => `  - ${f}`).join("\n")}\n`);
    process.exit(1);
  }
  process.stdout.write("dwf-runner: bundle → 引擎 全链路校验通过\n");
}

void main();
