// 生成 Kotlin 单测用的金标样本：用真 bundle 跑真脚本，把 AnalyzeResult 原样落盘。
//
// 这样 Kotlin 解码层是被**上游真实输出**钉住的，而不是被我自己写的期望值钉住的——
// 上游改了字段形状，重跑本脚本就会让单测失败。
//
// 重跑：node tools/dwf-analyzer/emit-fixtures.mjs
import { readFileSync, mkdirSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import vm from "node:vm";

const here = dirname(fileURLToPath(import.meta.url));
const repo = resolve(here, "../..");
const bundle = readFileSync(resolve(repo, "app/src/main/assets/dwf/analyzer.js"), "utf8");
const outDir = resolve(repo, "app/src/test/resources/dwf");

const sandbox = { clearTimeout, console, performance, setTimeout, TextDecoder, TextEncoder, URL };
sandbox.globalThis = sandbox;
const context = vm.createContext(sandbox);
new vm.Script(bundle, { filename: "analyzer.js" }).runInContext(context);

mkdirSync(outDir, { recursive: true });

for (const name of ["ok-xiangqi", "type-errors", "models"]) {
  const script = readFileSync(resolve(here, "fixtures", `${name}.dwf.ts`), "utf8");
  const result = sandbox.__DWF.analyze(script);
  const target = resolve(outDir, `${name}.json`);
  writeFileSync(target, `${JSON.stringify(result, null, 2)}\n`);
  process.stdout.write(
    `emit ${name}.json  ok=${result.ok}  diags=${result.diagnostics.length}  `
    + `nodes=${result.graph?.nodes?.length ?? 0}  models=${result.modelReferences.length}\n`,
  );
}
