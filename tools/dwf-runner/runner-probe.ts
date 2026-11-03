// Device-neutral runner probe: real engine + real cell + fake Kotlin bridge.
// The only difference from Android is the realm factory: node vm here, iframe
// in the WebView entry point.
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import vm from "node:vm";
import { WorkflowRunner, type RunnerBridge } from "./src/android-runner.js";
import { nodeRealm } from "./src/node-realm.js";

const failures: string[] = [];
function check(label: string, condition: boolean, detail?: unknown): void {
  if (!condition) failures.push(`${label}${detail === undefined ? "" : ` — ${JSON.stringify(detail)}`}`);
}

function loadAnalyzer(): any {
  const path = resolve(__dirname, "../../../app/src/main/assets/dwf/analyzer.js");
  const source = readFileSync(path, "utf8");
  const sandbox: any = { clearTimeout, console, performance, setTimeout, TextDecoder, TextEncoder, URL };
  sandbox.globalThis = sandbox;
  const context = vm.createContext(sandbox);
  new vm.Script(source, { filename: "analyzer.js" }).runInContext(context);
  return sandbox.__DWF;
}

interface RunResult {
  settlement: any;
  journal: any;
  asks: Array<{ siteId: string; instructions: string; typed: boolean }>;
  events: string[];
  actors: number;
}

async function runScript(script: string, worldValue?: unknown, fillCode?: string): Promise<RunResult> {
  const analyzer = loadAnalyzer();
  const prepared = analyzer.prepare(script);
  if (!prepared.ok) throw new Error(`prepare failed: ${JSON.stringify(prepared.diagnostics)}`);
  let runner!: WorkflowRunner;
  const asks: Array<{ siteId: string; instructions: string; typed: boolean }> = [];
  const events: string[] = [];
  let journal: any = undefined;
  let actors = 0;
  let settle!: (value: any) => void;
  const settled = new Promise<any>((resolve) => { settle = resolve; });
  const bridge: RunnerBridge = {
    saveJournal(_runId, json) { journal = JSON.parse(json); },
    emit(_runId, eventJson) { events.push(JSON.parse(eventJson).type); },
    createActor() { actors += 1; },
    startAsk(_runId, requestId, requestJson) {
      const request = JSON.parse(requestJson);
      asks.push({
        siteId: request.siteId,
        instructions: request.instructions,
        typed: request.typed,
      });
      queueMicrotask(() => {
        runner.askResult(requestId, JSON.stringify({ kind: "text", value: "actor-done" }));
      });
    },
    continueAsk() {},
    cancelAsk() {},
    worldRead(requestId) {
      queueMicrotask(() => {
        runner.worldResult(requestId, true, JSON.stringify(worldValue ?? "world-content"));
      });
    },
    publishArtifact(requestId) {
      queueMicrotask(() => {
        runner.artifactResult(
          requestId,
          true,
          JSON.stringify({
            id: "note",
            kind: "markdown",
            version: 1,
            contentType: "text/markdown",
            bytes: 5,
            uri: ".andmx/workflow-runs/probe/note-v1.md",
            publishedAt: 1,
          }),
        );
      });
    },
    settled(_runId, json) { settle(JSON.parse(json)); },
    log() {},
  };
  runner = new WorkflowRunner({ createRealm: async () => nodeRealm(), bridge });
  const runId = `probe-${Date.now()}`;
  await runner.start(JSON.stringify({
    runId,
    lowered: prepared.lowered.code,
    scriptText: script,
    cwd: "/tmp/andmx-dwf-runner",
    caps: { maxConcurrency: 4 },
    askSpecs: prepared.askSpecs,
    sitePhases: prepared.sitePhases,
    timeoutMs: 30_000,
  }));
  if (fillCode !== undefined) {
    for (let attempt = 0; attempt < 500; attempt++) {
      const holes = runner.openHoles(runId) as Array<{ siteId: string }>;
      if (holes.length > 0) {
        for (const hole of holes) {
          runner.fillHole(runId, JSON.stringify({
            siteId: hole.siteId,
            code: fillCode,
            script: { text: script, hash: "probe-hash" },
            askSpecs: prepared.askSpecs,
            sitePhases: prepared.sitePhases,
            phaseNames: [],
          }));
        }
        break;
      }
      await new Promise<void>((resolve) => setTimeout(resolve, 10));
    }
  }
  const settlement = await settled;
  return { settlement, journal, asks, events, actors };
}

async function main(): Promise<void> {
  const sequential = await runScript(String.raw`
    const a = agent('one');
    log('start');
    const first = await a.ask('first');
    const second = await a.ask('second');
    return first + '|' + second;
  `);
  check("sequential completed", sequential.settlement.status === "completed", sequential.settlement);
  check("sequential artifact", sequential.settlement.artifact === "actor-done|actor-done", sequential.settlement.artifact);
  check("sequential asks", sequential.asks.length === 2, sequential.asks);
  check("sequential instructions", sequential.asks[0]?.instructions === "first", sequential.asks);
  check("sequential journal persisted", Array.isArray(sequential.journal?.runs) && sequential.journal.runs.length === 1, sequential.journal);
  check("sequential events", sequential.events.includes("run-started") && sequential.events.includes("run-settled"), sequential.events);

  const world = await runScript(String.raw`
    const body = await files.read('a.txt');
    return body;
  `, "hello-world");
  check("worldread completed", world.settlement.status === "completed", world.settlement);
  check("worldread artifact", world.settlement.artifact === "hello-world", world.settlement.artifact);

  const errored = await runScript(String.raw`
    throw new Error('boom');
  `);
  check("errored run is errored", errored.settlement.status === "errored", errored.settlement);

  const fanout = await runScript(String.raw`
    const a = agent('a');
    const b = agent('b');
    const [first, second] = await Promise.all([a.ask('A'), b.ask('B')]);
    return first + '|' + second;
  `);
  check("fanout completed", fanout.settlement.status === "completed", fanout.settlement);
  check("fanout asked twice", fanout.asks.length === 2, fanout.asks);
  check("fanout artifact", fanout.settlement.artifact === "actor-done|actor-done", fanout.settlement.artifact);

  const artifact = await runScript(String.raw`
    const ref = await artifact.markdown('note', 'hello');
    return ref.id + ':' + ref.version;
  `);
  check("artifact completed", artifact.settlement.status === "completed", artifact.settlement);
  check("artifact ref", artifact.settlement.artifact === "note:1", artifact.settlement.artifact);

  const hole = await runScript(
    String.raw`
      const extra = await hole<string>('补充规则', '需要补一段规则');
      const a = agent('a');
      const answer = await a.ask('结论：' + extra);
      return extra + '|' + answer;
    `,
    undefined,
    String.raw`(async () => '补好的值')`,
  );
  check("hole completed", hole.settlement.status === "completed", hole.settlement);
  check("hole artifact", hole.settlement.artifact === "补好的值|actor-done", hole.settlement.artifact);
  check("hole instructions", hole.asks[0]?.instructions === "结论：补好的值", hole.asks);

  if (failures.length > 0) {
    process.stderr.write(`runner-probe failures:\n${failures.map((f) => `  - ${f}`).join("\n")}\n`);
    process.exit(1);
  }
  process.stdout.write("runner-probe: sequential/worldread/errored/fanout/artifact/hole passed\n");
}

void main();
