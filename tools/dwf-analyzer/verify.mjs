// 构建 + 校验关口。失败即非零退出，可以直接挂进 CI。
//
// 校验的核心不是「文件生成了」，而是「它在没有 require / process / fs / Buffer 的
// 上下文里真的跑得出上游那套结果」——WebView 页面就是这个条件。这里用 node:vm
// 造一个只有浏览器基础全局的对象当沙箱，故意不提供 node 的东西。
import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import vm from "node:vm";

const here = dirname(fileURLToPath(import.meta.url));

execFileSync(process.execPath, [resolve(here, "build.mjs")], { stdio: "inherit" });

const bundlePath = resolve(here, "../../app/src/main/assets/dwf/analyzer.js");
const bundle = readFileSync(bundlePath, "utf8");

/** 造一个等价 WebView 页面的上下文：没有任何 node 全局。 */
function createPage() {
  const sandbox = { clearTimeout, console, performance, setTimeout, TextDecoder, TextEncoder, URL };
  sandbox.globalThis = sandbox;
  const context = vm.createContext(sandbox);
  const startedAt = Date.now();
  new vm.Script(bundle, { filename: "analyzer.js" }).runInContext(context);
  return { host: sandbox.__DWF, loadMs: Date.now() - startedAt };
}

const failures = [];
function check(label, condition, detail) {
  if (!condition) failures.push(`${label}${detail === undefined ? "" : ` — ${detail}`}`);
}

const page = createPage();
check("bundle 挂载 __DWF", page.host !== undefined);
check("analyze / analyzeFull / compile / lower / prepare 五档齐备",
  typeof page.host?.analyze === "function"
  && typeof page.host?.analyzeFull === "function"
  && typeof page.host?.compile === "function"
  && typeof page.host?.lower === "function"
  && typeof page.host?.prepare === "function");

const fixture = (name) => readFileSync(resolve(here, "fixtures", name), "utf8");

function time(label, fn) {
  const startedAt = Date.now();
  const value = fn();
  process.stdout.write(`  ${label.padEnd(22)} ${String(Date.now() - startedAt).padStart(4)}ms\n`);
  return value;
}

process.stdout.write(`载荷 ${(bundle.length / 1024 / 1024).toFixed(2)} MiB, 初始化 ${page.loadMs}ms\n`);

// —— 干净的脚本：必须出图，且 core 已经按设计摘掉 ——
const ok = time("ok-xiangqi", () => page.host.analyze(fixture("ok-xiangqi.dwf.ts")));
check("ok-xiangqi 判为可提交", ok.ok === true, JSON.stringify(ok.diagnostics?.slice(0, 2)));
check("ok-xiangqi 无诊断", ok.diagnostics.length === 0);
check("ok-xiangqi 有站点图", (ok.graph?.nodes?.length ?? 0) > 0);
check("ok-xiangqi 有因果图", ok.causality !== undefined);
check("ok-xiangqi 有控制流图", ok.flow !== undefined);
check("ok-xiangqi 有交接图", ok.handoff !== undefined);
check("analyze 不含 core", ok.core === undefined);

// —— 类型错误：诊断码必须是上游真码，不是我们编的 ——
const bad = time("type-errors", () => page.host.analyze(fixture("type-errors.dwf.ts")));
const codes = bad.diagnostics.map((d) => d.code);
check("type-errors 判为不可提交", bad.ok === false);
check("type-errors 抓到 export 禁用 (1184)", codes.includes(1184), codes.join(","));
check("type-errors 抓到类型不匹配 (2322)", codes.includes(2322), codes.join(","));
check("type-errors 抓到未定义名字 (2304)", codes.includes(2304), codes.join(","));
check("type-errors 不给图", bad.graph === undefined && bad.causality === undefined);
check("type-errors 诊断带行号", bad.diagnostics.every((d) => d.line >= 1));

// —— 模型引用：脚本点名的模型名要被逐字交出来 ——
const models = time("models", () => page.host.analyze(fixture("models.dwf.ts")));
const names = (models.modelReferences ?? []).map((r) => r.name);
check("models 判为可提交", models.ok === true, JSON.stringify(models.diagnostics?.slice(0, 2)));
check("models 交出两个模型名", names.length === 2, names.join(","));
check("models 名字逐字保留", names.includes("claude-haiku-4") && names.includes("claude-sonnet-4"), names.join(","));

// —— compile 只回答能不能提交 ——
const compiled = page.host.compile(fixture("type-errors.dwf.ts"));
check("compile 只回 ok + diagnostics",
  compiled.ok === false && Object.keys(compiled).sort().join(",") === "diagnostics,ok");

// —— prepare：执行前的全部输入 ——
const prepared = time("prepare", () => page.host.prepare(fixture("models.dwf.ts")));
check("prepare 判为可执行", prepared.ok === true, JSON.stringify(prepared.diagnostics?.slice(0, 2)));
check("prepare 给出 lowered 函数体",
  typeof prepared.lowered?.code === "string" && prepared.lowered.code.length > 0);
check("lowered 的自由标识符只剩 __host",
  prepared.lowered.code.includes("__host.") && !prepared.lowered.code.includes("reviewer.ask("),
  prepared.lowered.code.slice(0, 120));
check("lowered 记录了被打桩的站点",
  Array.isArray(prepared.lowered.siteIds) && prepared.lowered.siteIds.length > 0,
  prepared.lowered.siteIds);
const askNodeCount = (prepared.graph?.nodes ?? []).filter((node) => node.kind === "ask").length;
check("prepare 给出每个 ask 站点的规格",
  Array.isArray(prepared.askSpecs) && prepared.askSpecs.length === askNodeCount,
  { askSpecs: prepared.askSpecs?.length, askNodes: askNodeCount });
// lowered 的 siteIds 是**全部**被打桩的站点（actor / world-read / ask / 留白…），
// ask 规格只覆盖其中 ask 那一族。
check("ask 规格只落在 lowered 的站点之内",
  prepared.askSpecs.every(([site]) => prepared.lowered.siteIds.includes(site)),
  { askSpecs: prepared.askSpecs.map(([site]) => site), sites: prepared.lowered.siteIds });
check("prepare 给出站点出生阶段", Array.isArray(prepared.sitePhases), prepared.sitePhases);
check("prepare 同时交出产物与模型清单",
  Array.isArray(prepared.declaredArtifacts) && Array.isArray(prepared.modelReferences));

const preparedBad = page.host.prepare(fixture("type-errors.dwf.ts"));
check("编不过的脚本不给 lowered", preparedBad.ok === false && preparedBad.lowered === undefined);
check("编不过的脚本仍然给诊断", (preparedBad.diagnostics ?? []).length > 0);

// —— 桥接体积：analyze 必须远小于 analyzeFull ——
const full = page.host.analyzeFull(fixture("ok-xiangqi.dwf.ts"));
const slim = JSON.stringify(ok).length;
const hefty = JSON.stringify(full).length;
check("analyze 比 analyzeFull 小一个量级", slim * 10 < hefty, `${slim}B vs ${hefty}B`);
process.stdout.write(`  analyze ${slim}B / analyzeFull ${hefty}B\n`);

if (failures.length > 0) {
  process.stderr.write(`\n校验失败 ${failures.length} 项:\n${failures.map((f) => `  - ${f}`).join("\n")}\n`);
  process.exit(1);
}
process.stdout.write("dwf-analyzer: 全部校验通过\n");
