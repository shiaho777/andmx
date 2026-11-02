// 宿主一致性对照：同一份脚本、同一个假 driver，跑两条**完全不同的执行路径**，比结果。
//
//   A 参照：上游 `runWorkflowScript`（写入口文件 → spawn node 子进程 → stdio NDJSON → bridge）
//   B 候选：`startRun` + `loopbackCell`（引擎留在本进程 → vm realm 当沙箱 → 异步端点）
//
// 三个用例覆盖三种 run 形态：
//   sequential —— 两个 actor、两次串行 ask（基线）
//   fanout     —— 三个 actor、三次并发 ask（并发准入、ordinal 分配）
//   failed     —— ask 被拒绝导致 run 失败（错误路径的结算形态）
//
// 比四样东西：结算状态与产物、run 行状态、journal 的节点行、ask 派发的站点集合。
// 这四样合起来就是「run 到底发生了什么」——如果两条路一致，说明换掉传输没有改变语义。
//
// 节点行按 (siteId, ordinal) **排序后**比对：并发下并行的 ask 谁先落库是调度时序的函数，
// 两次运行本来就可以不同（上游自己也一样）。比的是内容与 ordinal 分配，不是插入顺序；
// 插入顺序在 sequential 用例里另有一条断言。
//
// 顺带量一件事：一次 run 有多少次 journal 写入。这个数决定「journal 桥到 Kotlin/Room 时用
// 同步桥是否可接受」——同步桥的代价是每次写入阻塞 JS 线程，所以次数×单次耗时就是答案。
//
// 失败即非零退出，可以直接挂进 CI。
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { setTimeout as delay } from "node:timers/promises";
import {
  InMemoryJournalStore,
  WorkflowError,
  analyzeWorkflowScript,
  buildAskSpecs,
  collectSitePhases,
  collectSites,
  createWorkflowProgram,
  lowerWorkflowScript,
  synthesizeAskSchemas,
  validate,
  type AskSpec,
  type JournalStorePort,
  type JsonSchema,
  type ValidateFn,
  type WorkflowEngine,
} from "@zcode/dynamic-workflow";
import { runWorkflowScript } from "@zcode/dynamic-workflow-runtime";
import { CELL_STREAMS_BOOTSTRAP } from "../../ZCode-main/apps/zcode-cli/packages/dynamic-workflow-runtime/src/child-cell-streams.js";
import {
  AutoDriver,
  type AutoDriverConfig,
} from "../../ZCode-main/apps/zcode-cli/packages/dynamic-workflow-runtime/tests/auto-driver.js";
import { startRun } from "./src/host.js";
import { loopbackCell } from "./src/loopback.js";

const validateFn: ValidateFn = (schema, value) => validate(schema as JsonSchema, value);

interface Case {
  name: string;
  script: string;
  driver?: AutoDriverConfig;
  /** world-read 的应答：`files.read` 走 `read`，`files.glob` 走 `glob`。 */
  worldReads?: (op: string, args: unknown[]) => unknown;
  /** 留白的补全体（有效脚本的 `holeBodies[siteId]`）。给出即由 poller 在留白出现时补上。 */
  hole?: { code: string };
  /** 期望的 ask 指令（子串匹配）——用来证明实参 / 留白值真的流进了子代理。 */
  expectInstructions?: string[];
  /** `RunSettlement.status` 只有 completed / errored / stopped 三种。 */
  expect: { status: "completed" | "errored"; artifact?: string; errorIncludes?: string };
  /** sequential 用例额外断言 journal 的插入顺序（并发下这个顺序本就非确定）。 */
  assertInsertionOrder?: boolean;
}

const CASES: Case[] = [
  {
    assertInsertionOrder: true,
    expect: { artifact: "ok/ok", status: "completed" },
    name: "sequential",
    script: String.raw`/* zcode-workflow
description: 串行两 ask
*/
const red = agent('赤霄', { system: '你是红方' });
const black = agent('玄弈', { system: '你是黑方' });
log('开局');
const move = await red.ask('给出红方走法');
const reply = await black.ask('给出黑方走法');
return move + '/' + reply;
`,
  },
  {
    expect: { artifact: "ok|ok|ok", status: "completed" },
    name: "fanout",
    script: String.raw`/* zcode-workflow
description: 并发扇出
*/
const workers = [agent('甲'), agent('乙'), agent('丙')];
log('并发开始');
const answers = await Promise.all(workers.map((worker, index) => worker.ask('第 ' + index + ' 题')));
return answers.join('|');
`,
  },
  {
    driver: {
      asks: {
        "ask#1": () => ({
          error: new WorkflowError("ProviderError", "模型挂了"),
          type: "fail",
        }),
      },
    },
    expect: { errorIncludes: "模型挂了", status: "errored" },
    name: "errored",
    script: String.raw`/* zcode-workflow
description: ask 被拒绝
*/
const solo = agent('甲');
const answer = await solo.ask('这一问会失败');
return answer;
`,
  },
  {
    expect: { artifact: "ok", status: "completed" },
    expectInstructions: ["内容:A|2"],
    name: "worldread",
    worldReads: (op) => (op === "read" ? "内容:A" : ["a.ts", "b.ts"]),
    script: String.raw`/* zcode-workflow
description: 世界读取
*/
const a = agent('甲');
const text = await files.read('notes.md');
const hits = await files.glob('src/*.ts');
log('读完');
const answer = await a.ask('汇总 ' + text + '|' + hits.length);
return answer;
`,
  },
  {
    expect: { artifact: "ok", status: "completed" },
    expectInstructions: ["补好的值"],
    hole: { code: String.raw`(async () => { return "补好的值"; })` },
    name: "hole",
    script: String.raw`/* zcode-workflow
description: 留白补全
*/
const a = agent('甲');
const extra = await hole<string>('补充规则', '需要补一段规则');
const answer = await a.ask('结论：' + extra);
return answer;
`,
  },
];

const failures: string[] = [];

function check(label: string, condition: boolean, detail?: unknown): void {
  if (!condition) failures.push(`${label}${detail === undefined ? "" : ` — ${JSON.stringify(detail)}`}`);
}

function askSpecsFor(script: string): Map<string, AskSpec> {
  const workflow = createWorkflowProgram(script);
  const table = collectSites(workflow);
  const { schemas } = synthesizeAskSchemas(workflow, table);
  return buildAskSpecs(table, schemas);
}

function sitePhasesFor(script: string): ReadonlyMap<string, string> {
  const analysis = analyzeWorkflowScript(script);
  if (analysis.core === undefined) throw new Error("analysis 未产出 core，无法取站点阶段");
  return collectSitePhases(analysis.core);
}

/** 记下每次 journal 调用的次数——同步桥的代价就是次数 × 单次耗时。 */
function countingJournal(): { journal: JournalStorePort; counts: Map<string, number> } {
  const inner = new InMemoryJournalStore();
  const counts = new Map<string, number>();
  const journal = new Proxy(inner, {
    get(target, property) {
      const value = Reflect.get(target, property, target) as unknown;
      if (typeof value !== "function") return value;
      const name = String(property);
      return (...args: unknown[]): unknown => {
        counts.set(name, (counts.get(name) ?? 0) + 1);
        return (value as (...inner: unknown[]) => unknown).apply(target, args);
      };
    },
  }) as JournalStorePort;
  return { counts, journal };
}

interface NodeRow {
  kind: unknown;
  siteId: unknown;
  ordinal: unknown;
  status: unknown;
}

interface Outcome {
  settlement: { status: string; artifact?: unknown; error?: unknown };
  runStatus: string | undefined;
  nodes: NodeRow[];
  askedSites: string[];
  askedInstructions: string[];
  writeCalls: number;
  journalCalls: number;
}

function errorMessage(settlement: { error?: unknown }): string | undefined {
  const error = settlement.error as { message?: unknown } | undefined;
  return typeof error?.message === "string" ? error.message : undefined;
}

function project(
  journal: JournalStorePort,
  settlement: { status: string; artifact?: unknown; error?: unknown },
  driver: AutoDriver,
  counts: Map<string, number>,
): Outcome {
  const nodes = journal.listNodes("run", { kinds: "all", withResult: false }).map((node) => {
    const row = node as Record<string, unknown>;
    return { kind: row.kind, ordinal: row.ordinal, siteId: row.siteId, status: row.status };
  });
  let total = 0;
  let writes = 0;
  for (const [name, count] of counts) {
    total += count;
    if (/^(put|create|update)/.test(name)) writes += count;
  }
  return {
    askedInstructions: driver.startAsks.map((call) => call.message.instructions),
    askedSites: driver.startAsks.map((call) => call.instance.siteId),
    journalCalls: total,
    nodes,
    runStatus: journal.getRun("run")?.status,
    settlement,
    writeCalls: writes,
  };
}

/**
 * 留白补全：等它出现，然后按有效脚本的 `holeBodies[siteId]` 补上。
 *
 * 上游把这套动作交给主代理（`FillWorkflowHole` 工具 → 控制面 `fillHole`）。对照里由一个
 * poller 扮演它——必须真的走控制面，否则验的不是留白那条路。
 */
async function fillOpenHoles(
  engine: Pick<WorkflowEngine, "fillHole" | "openHoles"> | undefined,
  fill: { code: string },
  scriptText: string,
  askSpecs: ReadonlyMap<string, AskSpec>,
  sitePhases: ReadonlyMap<string, string>,
): Promise<void> {
  if (engine === undefined) throw new Error("控制面未绑定，无法补全留白");
  for (let attempt = 0; attempt < 500; attempt++) {
    const open = engine.openHoles();
    if (open.length > 0) {
      for (const hole of open) {
        engine.fillHole({
          askSpecs,
          code: fill.code,
          phaseNames: [],
          script: { hash: "probe-hash", text: scriptText },
          siteId: hole.siteId,
          sitePhases,
        });
      }
      return;
    }
    await delay(10);
  }
  throw new Error("留白始终没有出现");
}

/** 内容比对用的规范序：siteId 升序、同站点按 ordinal。 */
function canonical(nodes: NodeRow[]): NodeRow[] {
  return [...nodes].sort((a, b) => {
    const left = `${String(a.siteId)}#${String(a.ordinal).padStart(6, "0")}`;
    const right = `${String(b.siteId)}#${String(b.ordinal).padStart(6, "0")}`;
    return left.localeCompare(right);
  });
}

function sortedUnique(values: string[]): string {
  return [...values].sort().join(",");
}

function describe(nodes: NodeRow[]): string {
  return canonical(nodes)
    .map((n) => `${String(n.kind)}@${String(n.siteId)}#${String(n.ordinal)}:${String(n.status)}`)
    .join(" ");
}

async function main(): Promise<void> {
  const cwd = mkdtempSync(join(tmpdir(), "andmx-dwf-host-"));

  for (const testCase of CASES) {
    const askSpecs = askSpecsFor(testCase.script);
    const sitePhases = sitePhasesFor(testCase.script);
    const lowered = lowerWorkflowScript(testCase.script).lowered?.code;
    if (lowered === undefined) throw new Error(`[${testCase.name}] 脚本未通过 lowering`);

    // —— A 参照：上游子进程路径 ——
    const driverConfig: AutoDriverConfig = {
      ...(testCase.driver ?? {}),
      ...(testCase.worldReads === undefined ? {} : { worldReads: testCase.worldReads }),
    };
    const refJournal = countingJournal();
    const refDriver = new AutoDriver(refJournal.journal, driverConfig);
    let refEngine: WorkflowEngine | undefined;
    const refPending = runWorkflowScript({
      askSpecs,
      caps: { maxConcurrency: 4 },
      control: {
        bind: (engine) => {
          refEngine = engine as WorkflowEngine;
        },
      },
      cwd,
      makeDriver: (sink) => {
        refDriver.attach(sink);
        return refDriver;
      },
      runId: "run",
      scriptText: testCase.script,
      sitePhases,
      timeoutMs: 30_000,
      validate: validateFn,
    });
    // bind 在 runWorkflowScript 的同步段里就触发了，所以此刻 refEngine 已经在手。
    const refFill = testCase.hole === undefined
      ? undefined
      : fillOpenHoles(refEngine, testCase.hole, testCase.script, askSpecs, sitePhases);
    const refSettlement = await refPending;
    await refFill;
    const reference = project(refJournal.journal, refSettlement, refDriver, refJournal.counts);

    // —— B 候选：本进程引擎 + vm realm 沙箱 ——
    const candJournal = countingJournal();
    const candDriver = new AutoDriver(candJournal.journal, driverConfig);
    let candEngine: WorkflowEngine | undefined;
    const handle = startRun({
      askSpecs,
      bindControl: (engine) => {
        candEngine = engine;
      },
      caps: { maxConcurrency: 4 },
      cwd,
      endpoint: loopbackCell({
        payload: { args: {}, lowered },
        streamsBootstrap: CELL_STREAMS_BOOTSTRAP,
      }),
      makeDriver: (sink) => {
        candDriver.attach(sink);
        return candDriver;
      },
      runId: "run",
      scriptText: testCase.script,
      sitePhases,
      timeoutMs: 30_000,
      validate: validateFn,
    });
    const candFill = testCase.hole === undefined
      ? undefined
      : fillOpenHoles(candEngine, testCase.hole, testCase.script, askSpecs, sitePhases);
    const candSettlement = await handle.settled;
    await candFill;
    const candidate = project(candJournal.journal, candSettlement, candDriver, candJournal.counts);

    // —— 各自符合预期 ——
    for (const [label, outcome] of [["参照", reference], ["候选", candidate]] as const) {
      check(`[${testCase.name}] ${label} 结算为 ${testCase.expect.status}`,
        outcome.settlement.status === testCase.expect.status, outcome.settlement.status);
      if (testCase.expect.artifact !== undefined) {
        check(`[${testCase.name}] ${label} 产物正确`,
          outcome.settlement.artifact === testCase.expect.artifact, outcome.settlement.artifact);
      }
      if (testCase.expect.errorIncludes !== undefined) {
        const message = errorMessage(outcome.settlement);
        check(`[${testCase.name}] ${label} 错误含「${testCase.expect.errorIncludes}」`,
          message?.includes(testCase.expect.errorIncludes) === true, message);
      }
      for (const needle of testCase.expectInstructions ?? []) {
        check(`[${testCase.name}] ${label} 的 ask 指令含「${needle}」`,
          outcome.askedInstructions.some((text) => text.includes(needle)), outcome.askedInstructions);
      }
    }

    // —— 两条路一致 ——
    check(`[${testCase.name}] ask 指令逐字相同`,
      JSON.stringify(reference.askedInstructions) === JSON.stringify(candidate.askedInstructions),
      { candidate: candidate.askedInstructions, reference: reference.askedInstructions });
    check(`[${testCase.name}] 错误文案相同`,
      errorMessage(reference.settlement) === errorMessage(candidate.settlement),
      { candidate: errorMessage(candidate.settlement), reference: errorMessage(reference.settlement) });
    check(`[${testCase.name}] run 行状态相同`,
      reference.runStatus === candidate.runStatus,
      { candidate: candidate.runStatus, reference: reference.runStatus });
    check(`[${testCase.name}] ask 派发站点集合相同`,
      sortedUnique(reference.askedSites) === sortedUnique(candidate.askedSites),
      { candidate: sortedUnique(candidate.askedSites), reference: sortedUnique(reference.askedSites) });
    check(`[${testCase.name}] journal 节点内容相同`,
      JSON.stringify(canonical(reference.nodes)) === JSON.stringify(canonical(candidate.nodes)),
      { candidate: canonical(candidate.nodes), reference: canonical(reference.nodes) });
    check(`[${testCase.name}] journal 写入次数相同`,
      reference.writeCalls === candidate.writeCalls,
      { candidate: candidate.writeCalls, reference: reference.writeCalls });

    if (testCase.assertInsertionOrder === true) {
      check(`[${testCase.name}] journal 插入顺序相同`,
        JSON.stringify(reference.nodes) === JSON.stringify(candidate.nodes),
        { candidate: candidate.nodes, reference: reference.nodes });
    }

    // 真实性检查：一致不能是因为两边都空。
    check(`[${testCase.name}] journal 有节点行`, candidate.nodes.length > 0, candidate.nodes.length);

    process.stdout.write(`  ${testCase.name.padEnd(11)} ${describe(candidate.nodes)}\n`);
    process.stdout.write(
      `  ${"".padEnd(11)} 写入 ${candidate.writeCalls} 次 / 调用 ${candidate.journalCalls} 次`
      + `（参照${reference.writeCalls === candidate.writeCalls ? "相同" : "不同"}）\n`,
    );
  }

  if (failures.length > 0) {
    process.stderr.write(`\n对照失败 ${failures.length} 项:\n${failures.map((f) => `  - ${f}`).join("\n")}\n`);
    process.exit(1);
  }
  process.stdout.write(`dwf-runner: ${CASES.length} 个用例，子进程路径与进程内路径的 run 事实一致\n`);
}

void main();
