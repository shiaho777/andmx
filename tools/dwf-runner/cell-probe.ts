// cell 一致性对照：同一份 payload、同一个假父进程，只把 **realm 的构造方式**换掉。
//
//   A 参照：`deps.vm` = node 真正的 `node:vm`（上游生产路径）
//   B 候选：`deps.vm` = cell.ts 的 shim，绑到一个真 vm context 上（设备侧绑 iframe window）
//
// 两条路的通道都是**异步**的（setTimeout 0），因为上游是 stdio 管道，响应永远晚一拍。
// 差异因此只剩一处：谁负责 createContext / runInContext。这正是设备侧唯一要替换的东西。
//
// 四个用例覆盖 cell 的四条出口：
//   happy  —— 正常返回（create-actor / log / report / ask / complete）
//   throw  —— 脚本抛错（错误对象跨 realm 重建）
//   syntax —— lowered 体编译失败（childMain 的 SyntaxError 分支）
//   ban    —— 运行期禁令（Date.now），证明 realm 里跑的确实是 BOOTSTRAP
//
// 断言是**逐行相等**。唯一的归一化是把 `error.stack` 去掉：栈文本是环境的产物（帧名、路径、
// realm 内部实现），不属于协议；把它算进对照只会制造假红。栈本身仍有断言——两条路都必须有。
//
// 失败即非零退出，可以直接挂进 CI。
import vm from "node:vm";
import { setTimeout as delay } from "node:timers/promises";
import { childMain, type ChildPayload } from "@zcode/dynamic-workflow-runtime";
import { CELL_STREAMS_BOOTSTRAP } from "../../ZCode-main/apps/zcode-cli/packages/dynamic-workflow-runtime/src/child-cell-streams.js";
import { startCell, type CellPort } from "./src/cell.js";
import { nodeRealm } from "./src/node-realm.js";
import { applyRealmSanitizer } from "./src/realm-sanitize.js";

interface Case {
  name: string;
  lowered: string;
  /** 期望的终结形态。 */
  expect: { ok: boolean; errorName?: string; messageIncludes?: string };
}

const CASES: Case[] = [
  {
    expect: { ok: true },
    lowered: String.raw`const reviewer = __host.createActor("actor#1", "评审甲", void 0);
__host.log("开始评审");
__host.report("report#1", { n: 1 }, void 0);
const notes = await __host.ask("ask#1", reviewer, "挑错");
return notes;`,
    name: "happy",
  },
  {
    expect: { errorName: "Error", messageIncludes: "boom", ok: false },
    lowered: String.raw`const a = __host.createActor("actor#1", "甲", void 0);
await __host.ask("ask#1", a, "先问一句");
throw new Error("boom");`,
    name: "throw",
  },
  {
    expect: { errorName: "SyntaxError", ok: false },
    lowered: "this is not javascript",
    name: "syntax",
  },
  {
    expect: { messageIncludes: "disabled in workflows", ok: false },
    lowered: "return Date.now();",
    name: "ban",
  },
];

const failures: string[] = [];

function check(label: string, condition: boolean, detail?: unknown): void {
  if (!condition) failures.push(`${label}${detail === undefined ? "" : ` — ${JSON.stringify(detail)}`}`);
}

interface Trace {
  lines: Array<Record<string, unknown>>;
  completed:
    | { ok: boolean; value?: unknown; errorName?: string; message?: string; hasStack: boolean }
    | undefined;
}

/** 造一对异步端口。`later` 一律排到下一轮宏任务，模拟管道与 postMessage 的迟到。 */
function channel(): { cell: CellPort; parent: CellPort } {
  let toParent: (line: string) => void = () => {};
  let toCell: (line: string) => void = () => {};
  const later = (fn: () => void): void => {
    setTimeout(fn, 0);
  };
  return {
    cell: {
      onLine: (handler) => {
        toCell = handler;
      },
      post: (line) => later(() => toParent(line)),
    },
    parent: {
      onLine: (handler) => {
        toParent = handler;
      },
      post: (line) => later(() => toCell(line)),
    },
  };
}

/** 假父进程：应答 ask、记录一切，收到 complete 即结束。 */
function drive(start: (port: CellPort) => Promise<void>): Promise<Trace> {
  const trace: Trace = { completed: undefined, lines: [] };
  const { cell, parent } = channel();

  return new Promise<Trace>((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("cell 未在 10s 内完成")), 10_000);

    parent.onLine((line) => {
      const message = JSON.parse(line) as Record<string, unknown>;
      trace.lines.push(message);

      switch (message.kind) {
        case "create-actor":
        case "event":
          return;
        case "request": {
          parent.post(JSON.stringify({ id: message.id, kind: "response", ok: true, value: "pong" }));
          return;
        }
        case "complete": {
          const error = message.error as Record<string, unknown> | undefined;
          trace.completed = {
            errorName: typeof error?.name === "string" ? error.name : undefined,
            hasStack: typeof error?.stack === "string" && error.stack.length > 0,
            message: typeof error?.message === "string" ? error.message : undefined,
            ok: message.ok === true,
            value: message.value,
          };
          clearTimeout(timer);
          resolve(trace);
          return;
        }
        default:
          reject(new Error(`unexpected message: ${String(message.kind)}`));
      }
    });

    start(cell).catch(reject);
  });
}

function reference(payload: ChildPayload): Promise<Trace> {
  return drive((port) =>
    childMain({
      createInterface: () => {
        let closed = false;
        return {
          close: () => {
            closed = true;
          },
          on: (_event, listener) => {
            port.onLine((line) => {
              if (!closed && line.trim() !== "") listener(line);
            });
            return undefined;
          },
        };
      },
      payload,
      stdin: {} as never,
      stdout: { write: (chunk: string) => port.post(chunk.replace(/\n$/, "")) },
      streamsBootstrap: CELL_STREAMS_BOOTSTRAP,
      vm,
    }),
  );
}

function candidate(payload: ChildPayload): Promise<Trace> {
  return drive((port) =>
    startCell({
      payload,
      port,
      realm: nodeRealm(),
      streamsBootstrap: CELL_STREAMS_BOOTSTRAP,
    }),
  );
}

/** 协议层面的可比形态：`error.stack` 是环境产物，不进对照。 */
function wireForm(line: Record<string, unknown>): string {
  if (line.kind !== "complete") return JSON.stringify(line);
  const error = line.error as Record<string, unknown> | undefined;
  if (error === undefined) return JSON.stringify(line);
  const { stack: _stack, ...rest } = error;
  return JSON.stringify({ ...line, error: rest });
}

async function main(): Promise<void> {
  const referenceTraces = new Map<string, Trace>();

  for (const testCase of CASES) {
    const payload: ChildPayload = { args: { topic: "象棋" }, lowered: testCase.lowered };
    const left = await reference(payload);
    const right = await candidate(payload);
    referenceTraces.set(testCase.name, left);

    // 先验两条路各自符合预期，再比它们是否一致。
    for (const [label, trace] of [["参照", left], ["候选", right]] as const) {
      const completed = trace.completed;
      check(`[${testCase.name}] ${label} 有终结消息`, completed !== undefined);
      check(`[${testCase.name}] ${label} ok=${testCase.expect.ok}`, completed?.ok === testCase.expect.ok, completed);
      if (testCase.expect.ok) {
        check(`[${testCase.name}] ${label} 返回值正确`, completed?.value === "pong", completed?.value);
      } else {
        if (testCase.expect.errorName !== undefined) {
          check(`[${testCase.name}] ${label} error.name=${testCase.expect.errorName}`,
            completed?.errorName === testCase.expect.errorName, completed?.errorName);
        }
        if (testCase.expect.messageIncludes !== undefined) {
          check(`[${testCase.name}] ${label} 错误文案含「${testCase.expect.messageIncludes}」`,
            completed?.message?.includes(testCase.expect.messageIncludes) === true, completed?.message);
        }
        // 栈不参与逐行对照，但「有没有栈」仍要断言——否则一条丢栈的错误会被当成正常。
        check(`[${testCase.name}] ${label} 错误带栈`, completed?.hasStack === true);
      }
    }

    const a = left.lines.map(wireForm);
    const b = right.lines.map(wireForm);
    check(`[${testCase.name}] 协议行数一致`, a.length === b.length, { candidate: b.length, reference: a.length });
    const mismatch = a.findIndex((line, index) => line !== b[index]);
    check(`[${testCase.name}] 协议行逐字节相同`, mismatch === -1, mismatch === -1
      ? undefined
      : { at: mismatch, candidate: b[mismatch], reference: a[mismatch] });

    process.stdout.write(
      `  ${testCase.name.padEnd(7)} ${a.length} 行 · ${a.map((l) => {
        const parsed = JSON.parse(l) as Record<string, unknown>;
        return parsed.kind === "event" ? `event:${String(parsed.type)}` : String(parsed.kind);
      }).join(" → ")}\n`,
    );
  }

  // —— 净化后的 realm：抹掉唤醒源之后，cell 必须照常工作 ——
  //
  // 这是设备侧的形态（iframe 默认有定时器与 fetch）。断言两件事：
  //   1. 净化确实把那些东西抹掉了；
  //   2. 抹掉之后 cell 仍然能跑完，且协议与参照逐行相同——证明 cell 不依赖它们。
  {
    const realm = nodeRealm();
    // 先注入，制造出 iframe 那种「什么都有」的环境。
    const wakeSources: Array<[string, unknown]> = [
      ["setTimeout", setTimeout],
      ["setInterval", setInterval],
      ["setImmediate", setImmediate],
      ["fetch", () => Promise.resolve()],
      ["XMLHttpRequest", function XMLHttpRequest() {}],
      ["postMessage", () => undefined],
      ["addEventListener", () => undefined],
      ["console", console],
    ];
    for (const [name, value] of wakeSources) realm.global[name] = value;

    const present = wakeSources
      .map(([name]) => name)
      .filter((name) => realm.evaluate(`typeof ${name}`, "probe.js") !== "undefined");
    check("[sanitized] 注入的唤醒源都在", present.length === wakeSources.length, present);

    applyRealmSanitizer(realm);

    const remaining = wakeSources
      .map(([name]) => name)
      .filter((name) => realm.evaluate(`typeof ${name}`, "probe.js") !== "undefined");
    check("[sanitized] 净化后唤醒源全部消失", remaining.length === 0, remaining);
    check("[sanitized] 父窗口通道已断开",
      realm.evaluate("typeof parent", "probe.js") === "undefined"
      && realm.evaluate("typeof top", "probe.js") === "undefined",
      { parent: realm.evaluate("typeof parent", "probe.js"), top: realm.evaluate("typeof top", "probe.js") });

    const happy = CASES.find((item) => item.name === "happy");
    if (happy === undefined) throw new Error("缺少 happy 用例");
    const payload: ChildPayload = { args: { topic: "象棋" }, lowered: happy.lowered };
    const sanitized = await drive((port) =>
      startCell({ payload, port, realm, streamsBootstrap: CELL_STREAMS_BOOTSTRAP }),
    );

    check("[sanitized] cell 仍然跑完", sanitized.completed?.ok === true, sanitized.completed);
    check("[sanitized] 返回值正确", sanitized.completed?.value === "pong", sanitized.completed?.value);

    const expected = (referenceTraces.get("happy")?.lines ?? []).map(wireForm);
    const actual = sanitized.lines.map(wireForm);
    check("[sanitized] 协议与参照逐字节相同",
      JSON.stringify(expected) === JSON.stringify(actual),
      { actual, expected });

    process.stdout.write(
      `  sanitized 抹掉 ${wakeSources.length} 个唤醒源，cell 仍产出 ${actual.length} 行且与参照一致\n`,
    );
  }

  if (failures.length > 0) {
    process.stderr.write(`\n对照失败 ${failures.length} 项:\n${failures.map((f) => `  - ${f}`).join("\n")}\n`);
    process.exit(1);
  }
  process.stdout.write(`dwf-runner: ${CASES.length} 个用例 + 净化 realm，协议逐行一致\n`);

  // 让挂起的宏任务彻底安静，避免 CI 里偶发不退出。
  await delay(10);
}

void main();
